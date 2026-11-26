"""Ralph loop: works through a milestone's issues, one fresh session per issue."""

import argparse
import subprocess
import sys
from collections.abc import Callable
from dataclasses import dataclass, field, replace
from typing import Protocol

from ralph_token import TokenError

TODO, IN_PROGRESS, IN_REVIEW, DONE = "Todo", "In progress", "In review", "Done"
STUCK_AFTER = 3
POLL_SECONDS = 60
MERGE_TIMEOUT = 3600
# An improver session reviews this many merged PRs at a time, and whatever is left at the end.
IMPROVE_AFTER = 5
# The board only stays true while these GitHub Projects built-in workflows move the cards.
REQUIRED_WORKFLOWS = (
    "Item closed",
    "Pull request merged",
    "Pull request linked to issue",
    "Auto-add to project",
)


@dataclass(frozen=True)
class Issue:
    number: int
    status: str
    is_open: bool = True
    open_blockers: tuple[int, ...] = ()
    draft: bool = False  # proposed for the milestone; waits for the owner's plan-approved


@dataclass(frozen=True)
class PullRequest:
    number: int
    merged: bool = False
    changes_requested: int = 0
    ci_failure_streak: int = 0
    has_retro_notes: bool = True
    behind: bool = False
    branch: str = ""
    unresolved_threads: int = 0
    blocked_with_checks_passing: bool = False  # every check passed, yet GitHub won't merge it


@dataclass(frozen=True)
class Outcome:
    pr: int | None
    needs_human: str | None = None
    also: tuple[int, ...] = ()  # other PRs the session opened, such as a checkpoint's fixes


@dataclass(frozen=True)
class Improvement:
    """What an improver session did: fix PRs it opened, and what it left to the owner."""

    prs: tuple[int, ...] = ()
    proposals: tuple[int, ...] = ()
    needs_human: str | None = None
    left_in_place: str | None = None  # why its worktree could not be removed


class GitHubError(Exception):
    """A GitHub call failed; the message names the command and its error."""


class ReadsBoard(Protocol):
    def board_workflows(self) -> dict[str, bool]: ...
    def milestone_issues(self, milestone: int) -> list[Issue]: ...


class GitHub(ReadsBoard, Protocol):
    def pull_request(self, number: int) -> PullRequest: ...
    def has_open_pr(self, issue: int) -> bool: ...  # one closes it or is on its branch
    def set_status(self, issue: int, status: str) -> None: ...
    def comment(self, issue: int, body: str) -> str: ...  # the comment's URL
    def plan_approved(self, parent: int) -> bool: ...  # the owner added plan-approved last
    def undraft(self, issue: int) -> None: ...


class Session(Protocol):
    # `while_running` is called every POLL_SECONDS until the session ends.
    def work(self, issue: Issue, while_running: Callable[[], None]) -> Outcome: ...
    def remove(self, issue: int) -> str | None: ...  # what failed, if the worktree stays
    def update(self, pr: PullRequest) -> str | None: ...  # merges main in; what failed
    def improve(self, prs: tuple[int, ...], while_running: Callable[[], None]) -> Improvement: ...


def next_issue(issues: list[Issue]) -> Issue | None:
    ready = [
        i for i in issues if i.is_open and i.status == TODO and not i.open_blockers and not i.draft
    ]
    return min(ready, key=lambda i: i.number, default=None)


def stuck_reason(outcome: Outcome, pr: PullRequest | None) -> str | None:
    if outcome.needs_human:
        return f"needs a human: {outcome.needs_human}"
    if pr is None:
        return "no PR opened"
    if pr.changes_requested >= STUCK_AFTER:
        return f"PR #{pr.number}: the review requested changes {pr.changes_requested} times"
    if pr.ci_failure_streak >= STUCK_AFTER:
        return f"PR #{pr.number}: CI failed on {pr.ci_failure_streak} runs in a row"
    return None


def run(
    milestone: int,
    github: GitHub,
    session: Session,
    notify: Callable[[str], None],
    clock: Callable[[], float],
    sleep: Callable[[float], None],
    max_iterations: int,
) -> str:
    message = _Loop(milestone, github, session, notify, clock, sleep, max_iterations).run()
    notify(message)
    return message


@dataclass(frozen=True)
class Stop:
    """Why the loop stops; no headline means it ran out of issues it can take."""

    headline: str | None = None


@dataclass
class _Loop:
    milestone: int
    github: GitHub
    session: Session
    notify: Callable[[str], None]
    clock: Callable[[], float]
    sleep: Callable[[float], None]
    max_iterations: int
    iterations: int = 0
    opened: list[int] = field(default_factory=list)
    pending: list[int] = field(default_factory=list)
    stuck: dict[int, str] = field(default_factory=dict)
    fixed: list[int] = field(default_factory=list)
    merged: list[int] = field(default_factory=list)  # since the last improver session
    improvements: list[Improvement] = field(default_factory=list)
    worktrees: dict[int, int] = field(default_factory=dict)  # a pending PR → its issue
    left_in_place: dict[int, str] = field(default_factory=dict)  # issue → why its worktree stayed
    errors_while_running: list[str] = field(default_factory=list)
    not_brought_up_to_date: list[str] = field(default_factory=list)  # PRs no issue holds
    held_by_threads: list[str] = field(default_factory=list)  # PRs no issue holds, too
    waiting_since: float | None = None

    def run(self) -> str:
        workflows = self.github.board_workflows()
        off = [name for name in REQUIRED_WORKFLOWS if not workflows.get(name)]
        if off:
            return (
                f"Ralph loop stopped: board workflows are off: {', '.join(off)}. "
                "Turn them on in the project's Workflows settings."
            )
        self.approve_drafts()
        while True:
            issues = self.github.milestone_issues(self.milestone)
            self.move_closed_to_done(issues)
            issues = self.release_in_review(issues)
            stop = self.step(next_issue(issues))
            if stop is not None:
                return self.stop_message(stop.headline)

    def approve_drafts(self) -> None:
        drafts = [i for i in self.github.milestone_issues(self.milestone) if i.draft and i.is_open]
        if not drafts or not self.github.plan_approved(self.milestone):
            return
        for draft in drafts:
            self.github.undraft(draft.number)
            self.github.set_status(draft.number, TODO)

    def step(self, issue: Issue | None) -> Stop | None:
        self.refresh_pending()
        if issue is None:
            stop = self.wait_for_merges(Stop())
            if stop == Stop() and self.merged and self.halt() is None:
                self.improve()
                return None  # its PRs need merging before the loop stops
            return stop
        if halt := self.halt():
            # No more sessions, but the open PRs still need merging: a strict `main` leaves
            # them behind whenever another merges, and auto-merge won't update them itself.
            return self.wait_for_merges(Stop(halt))
        if len(self.merged) >= IMPROVE_AFTER:
            self.improve()
            return None
        self.work(issue)
        return None

    def improve(self) -> None:
        prs, self.merged = tuple(self.merged), []
        improvement = self.session.improve(prs, self.update_behind_pending)
        self.improvements.append(improvement)
        self.pending += improvement.prs
        for proposal in improvement.proposals:
            self.notify(f"Ralph loop: the improver proposes #{proposal}; it needs your decision.")
        if improvement.needs_human:
            self.notify(f"Ralph loop: the improver needs you: {improvement.needs_human}.")

    def halt(self) -> str | None:
        if self.iterations == self.max_iterations:
            return f"iteration cap ({self.max_iterations}) reached"
        return None

    def refresh_pending(self) -> None:
        prs = [self.github.pull_request(n) for n in self.pending]
        self.pending = [pr.number for pr in prs if not pr.merged]
        self.merged += [pr.number for pr in prs if pr.merged and pr.number in self.opened]
        for pr in prs:
            if pr.merged and pr.number in self.worktrees:
                self.remove_worktree(self.worktrees.pop(pr.number))
        self.update_behind(prs)
        self.drop_held(prs)

    def drop_held(self, prs: list[PullRequest]) -> None:
        """Stop waiting for a green PR an unresolved review thread blocks: it never merges."""
        for pr in prs:
            if pr.blocked_with_checks_passing and pr.unresolved_threads:
                count = pr.unresolved_threads
                threads = f"{count} unresolved review thread" + ("" if count == 1 else "s")
                reason = f"PR #{pr.number} is held by {threads}"
                self.stop_waiting(pr.number, reason, self.held_by_threads)

    def update_behind(self, prs: list[PullRequest]) -> None:
        for pr in prs:
            if not pr.merged and pr.behind and (failed := self.session.update(pr)):
                self.not_updated(pr.number, failed)

    def not_updated(self, pr: int, failed: str) -> None:
        """Stop waiting for a PR the loop can't bring up to date: it won't merge without help."""
        reason = f"PR #{pr} can't be brought up to date with main: {failed}"
        self.stop_waiting(pr, reason, self.not_brought_up_to_date)

    def stop_waiting(self, pr: int, reason: str, unowned: list[str]) -> None:
        """Drops a pending PR; its issue goes to In review, or `unowned` lists a PR without one."""
        self.pending.remove(pr)
        if pr not in self.worktrees:  # a session's other PR, or the improver's
            unowned.append(reason)
            self.notify(f"Ralph loop: {reason}.")
            return
        issue = self.worktrees.pop(pr)
        self.stuck[issue] = reason
        self.github.set_status(issue, IN_REVIEW)
        url = self.github.comment(issue, f"Moved to In review by the Ralph loop: {reason}.")
        self.notify(f"Ralph loop: #{issue} needs you (moved to In review): {reason}.\n{url}")

    def update_behind_pending(self) -> None:
        """While a session runs: a GitHub error is reported at the end, not raised into it."""
        try:
            self.update_behind([self.github.pull_request(n) for n in self.pending])
        except GitHubError as error:
            self.errors_while_running.append(str(error))

    def remove_worktree(self, issue: int) -> None:
        if failed := self.session.remove(issue):
            self.left_in_place[issue] = failed

    def move_closed_to_done(self, issues: list[Issue]) -> None:
        for closed in [i for i in issues if not i.is_open and i.status != DONE]:
            self.github.set_status(closed.number, DONE)
            self.fixed.append(closed.number)

    def release_in_review(self, issues: list[Issue]) -> list[Issue]:
        """Moves back to Todo each In-review issue nothing holds; the ones stuck this run stay."""
        released = []
        for issue in issues:
            if self.nothing_holds(issue) and self.cleared(issue.number):
                self.github.set_status(issue.number, TODO)
                self.github.comment(
                    issue.number, "Moved back to Todo by the Ralph loop: nothing holds it any more."
                )
                issue = replace(issue, status=TODO)
            released.append(issue)
        return released

    def cleared(self, issue: int) -> bool:
        """Whether the issue's leftover worktree and branch are gone, so a session can start."""
        if failed := self.session.remove(issue):
            self.left_in_place[issue] = failed
            return False
        return True

    def nothing_holds(self, issue: Issue) -> bool:
        return (
            issue.is_open
            and issue.status == IN_REVIEW
            and not issue.open_blockers
            and issue.number not in self.stuck
            and not self.github.has_open_pr(issue.number)
        )

    def wait_for_merges(self, stop: Stop) -> Stop | None:
        """`stop` once this run's PRs have merged; until then wait, as one may unblock an issue."""
        if not self.pending:
            return stop
        if self.waiting_since is None:
            self.waiting_since = self.clock()
        if self.clock() - self.waiting_since >= MERGE_TIMEOUT:
            prs = ", ".join(f"PR #{n}" for n in self.pending)
            return Stop(f"auto-merge still pending after {MERGE_TIMEOUT // 60} minutes: {prs}")
        self.sleep(POLL_SECONDS)
        return None

    def work(self, issue: Issue) -> None:
        self.waiting_since = None
        self.iterations += 1
        self.github.set_status(issue.number, IN_PROGRESS)
        outcome = self.session.work(issue, self.update_behind_pending)
        pr = self.github.pull_request(outcome.pr) if outcome.pr is not None else None
        if pr is not None:
            self.opened.append(pr.number)
        self.opened += outcome.also
        self.pending += outcome.also
        reason = stuck_reason(outcome, pr)
        if reason:
            self.stuck[issue.number] = reason
            self.github.set_status(issue.number, IN_REVIEW)
            url = self.github.comment(
                issue.number, f"Moved to In review by the Ralph loop: {reason}."
            )
            self.notify(
                f"Ralph loop: #{issue.number} needs you (moved to In review): {reason}.\n{url}"
            )
        elif pr.merged:  # without a PR, stuck_reason gave a reason
            self.merged.append(pr.number)
            self.remove_worktree(issue.number)
        else:
            self.pending.append(pr.number)
            self.worktrees[pr.number] = issue.number

    def stop_message(self, headline: str | None) -> str:
        still_open = [i for i in self.github.milestone_issues(self.milestone) if i.is_open]
        if headline is None and still_open and all(i.draft for i in still_open):
            headline = f"drafts wait for the owner's plan-approved label on #{self.milestone}"
        elif headline is None:
            headline = "nothing left to take" if still_open else "milestone complete"
        lines = [f"Ralph loop stopped: {headline}."]
        if still_open:
            lines += ["Open:", *map(describe, still_open)]
        if self.stuck:
            lines += ["Moved to In review:", *(f"- #{n}: {r}" for n, r in self.stuck.items())]
        no_notes = [n for n in self.opened if not self.github.pull_request(n).has_retro_notes]
        if no_notes:
            lines.append("Missing retro notes: " + ", ".join(f"PR #{n}" for n in no_notes))
        if self.fixed:
            moved = ", ".join(f"#{n}" for n in self.fixed)
            lines.append(f"Moved to Done (closed, but the board had not moved them): {moved}")
        if self.left_in_place:
            lines += [
                "Worktrees left in place:",
                *(f"- #{n}: {r}" for n, r in self.left_in_place.items()),
            ]
        if self.errors_while_running:
            lines += [
                "GitHub errors while a session ran:",
                *(f"- {e}" for e in self.errors_while_running),
            ]
        if self.not_brought_up_to_date:
            lines += ["Not brought up to date:", *(f"- {r}" for r in self.not_brought_up_to_date)]
        if self.held_by_threads:
            lines += ["Held by review threads:", *(f"- {r}" for r in self.held_by_threads)]
        return "\n".join(lines + self.improver_lines())

    def improver_lines(self) -> list[str]:
        prs = [n for i in self.improvements for n in i.prs]
        proposals = [n for i in self.improvements for n in i.proposals]
        lines = ["Improver PRs: " + ", ".join(f"PR #{n}" for n in prs)] if prs else []
        if proposals:
            lines.append("Improver proposals: " + ", ".join(f"#{n}" for n in proposals))
        lines += [
            f"Improver needs you: {i.needs_human}" for i in self.improvements if i.needs_human
        ]
        lines += [
            f"Improver worktree left in place: {i.left_in_place}"
            for i in self.improvements
            if i.left_in_place
        ]
        return lines


def describe(issue: Issue) -> str:
    blocked = ", ".join(f"#{b}" for b in issue.open_blockers)
    draft = ", draft" if issue.draft else ""
    return f"- #{issue.number} {issue.status}{draft}" + (
        f", blocked by {blocked}" if blocked else ""
    )


@dataclass(frozen=True)
class Live:
    """What a real run needs beyond GitHub: sessions, where the stop message goes, and time."""

    session: Session
    notify: Callable[[str], None]
    clock: Callable[[], float]
    sleep: Callable[[float], None]


def main(argv: list[str], connect: Callable[[], GitHub], live: Callable[[], Live]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--milestone", type=int, required=True, help="the parent issue")
    parser.add_argument("--dry-run", action="store_true", help="print the next issue and stop")
    parser.add_argument("--max-iterations", type=int, default=3, help="sessions to start at most")
    args = parser.parse_args(argv)
    try:
        if args.dry_run:
            print(dry_run(args.milestone, connect()))
            return 0
        github, real = connect(), live()
        print(
            run(
                args.milestone,
                github,
                real.session,
                real.notify,
                real.clock,
                real.sleep,
                args.max_iterations,
            )
        )
    except GitHubError as error:
        print(f"Ralph loop stopped: {error}", file=sys.stderr)
        return 1
    except TokenError as error:
        print(error, file=sys.stderr)
        return 1
    return 0


def dry_run(milestone: int, github: ReadsBoard) -> str:
    """What the loop would take next, read from GitHub without changing anything."""
    workflows = github.board_workflows()
    off = [name for name in REQUIRED_WORKFLOWS if not workflows.get(name)]
    lines = []
    if off:
        lines.append(
            f"Board workflows are off: {', '.join(off)}. "
            "Turn them on in the project's Workflows settings."
        )
    issues = github.milestone_issues(milestone)
    issue = next_issue(issues)
    if issue is None:
        lines += ["Nothing to take. Open:", *map(describe, [i for i in issues if i.is_open])]
    else:
        lines.append(f"Next: #{issue.number}")
    return "\n".join(lines)


if __name__ == "__main__":
    # Run as a script this module is __main__, and ralph_github imports a second copy as
    # `ralph`; go through that copy so its GitHubError is the one main catches.
    import os
    import time
    import urllib.request
    from datetime import datetime
    from pathlib import Path

    import ralph
    from ralph_github import GitHubAdapter, gh_runner
    from ralph_session import ClaudeSession
    from ralph_token import Tokens, github_post, keychain_key, load_app, openssl_signer

    repo = Path(__file__).resolve().parents[1]

    def connect() -> GitHub:
        # The owner's gh login: GitHub Apps can't move cards on a user-owned project.
        return GitHubAdapter("onato", "shared-core-bench", 2, gh_runner(subprocess.run))

    def live() -> Live:
        config = repo / ".ralph" / "config"
        app = load_app(
            config.read_text() if config.exists() else None,
            lambda: keychain_key(subprocess.run),
        )
        sign = openssl_signer(app.key, subprocess.run)
        tokens = Tokens(
            app.app_id, app.installation_id, sign, github_post(urllib.request.urlopen), time.time
        )
        session = ClaudeSession(
            repo, tokens, subprocess.run, subprocess.Popen, dict(os.environ), datetime.now
        )
        telegram = Path.home() / ".claude" / "skills" / "telegram" / "send.sh"

        def notify(message: str) -> None:
            subprocess.run([str(telegram), message], check=False)

        return Live(session, notify, time.time, time.sleep)

    sys.exit(ralph.main(sys.argv[1:], connect, live))
