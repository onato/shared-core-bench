"""Ralph loop: works through a milestone's issues, one fresh session per issue."""

import argparse
import subprocess
import sys
from collections.abc import Callable
from dataclasses import dataclass, field
from typing import Protocol

from ralph_token import TokenError

TODO, IN_PROGRESS, IN_REVIEW, DONE = "Todo", "In progress", "In review", "Done"
STUCK_AFTER = 3
POLL_SECONDS = 60
MERGE_TIMEOUT = 3600
# The owner's budget for unattended runs: the share of the weekly usage limit used.
WEEKLY_LIMIT = 0.20
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


@dataclass(frozen=True)
class PullRequest:
    number: int
    merged: bool = False
    changes_requested: int = 0
    ci_failure_streak: int = 0
    has_retro_notes: bool = True
    behind: bool = False


@dataclass(frozen=True)
class Outcome:
    pr: int | None
    needs_human: str | None = None


class GitHubError(Exception):
    """A GitHub call failed; the message names the command and its error."""


class ReadsBoard(Protocol):
    def board_workflows(self) -> dict[str, bool]: ...
    def milestone_issues(self, milestone: int) -> list[Issue]: ...


class GitHub(ReadsBoard, Protocol):
    def pull_request(self, number: int) -> PullRequest: ...
    def set_status(self, issue: int, status: str) -> None: ...
    def update_branch(self, number: int) -> None: ...
    def comment(self, issue: int, body: str) -> None: ...


class Session(Protocol):
    def work(self, issue: Issue) -> Outcome: ...
    def weekly_usage(self) -> float | None: ...


def next_issue(issues: list[Issue]) -> Issue | None:
    ready = [i for i in issues if i.is_open and i.status == TODO and not i.open_blockers]
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
    message = _Loop(milestone, github, session, clock, sleep, max_iterations).run()
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
    clock: Callable[[], float]
    sleep: Callable[[float], None]
    max_iterations: int
    iterations: int = 0
    opened: list[int] = field(default_factory=list)
    pending: list[int] = field(default_factory=list)
    stuck: dict[int, str] = field(default_factory=dict)
    fixed: list[int] = field(default_factory=list)
    waiting_since: float | None = None

    def run(self) -> str:
        workflows = self.github.board_workflows()
        off = [name for name in REQUIRED_WORKFLOWS if not workflows.get(name)]
        if off:
            return (
                f"Ralph loop stopped: board workflows are off: {', '.join(off)}. "
                "Turn them on in the project's Workflows settings."
            )
        while True:
            issues = self.github.milestone_issues(self.milestone)
            self.move_closed_to_done(issues)
            stop = self.step(next_issue(issues))
            if stop is not None:
                return self.stop_message(stop.headline)

    def step(self, issue: Issue | None) -> Stop | None:
        self.refresh_pending()
        if issue is None:
            return self.wait_for_merges(Stop())
        if halt := self.halt():
            # No more sessions, but the open PRs still need merging: a strict `main` leaves
            # them behind whenever another merges, and auto-merge won't update them itself.
            return self.wait_for_merges(Stop(halt))
        self.work(issue)
        return None

    def halt(self) -> str | None:
        if self.iterations == self.max_iterations:
            return f"iteration cap ({self.max_iterations}) reached"
        used = self.session.weekly_usage()
        if used is not None and used >= WEEKLY_LIMIT:
            return f"weekly usage {used:.0%} ≥ {WEEKLY_LIMIT:.0%}"
        return None

    def refresh_pending(self) -> None:
        prs = [self.github.pull_request(n) for n in self.pending]
        self.pending = [pr.number for pr in prs if not pr.merged]
        for pr in prs:
            if not pr.merged and pr.behind:
                self.github.update_branch(pr.number)

    def move_closed_to_done(self, issues: list[Issue]) -> None:
        for closed in [i for i in issues if not i.is_open and i.status != DONE]:
            self.github.set_status(closed.number, DONE)
            self.fixed.append(closed.number)

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
        outcome = self.session.work(issue)
        pr = self.github.pull_request(outcome.pr) if outcome.pr is not None else None
        if pr is not None:
            self.opened.append(pr.number)
        reason = stuck_reason(outcome, pr)
        if reason:
            self.stuck[issue.number] = reason
            self.github.set_status(issue.number, IN_REVIEW)
            self.github.comment(issue.number, f"Moved to In review by the Ralph loop: {reason}.")
        elif pr is not None and not pr.merged:
            self.pending.append(pr.number)

    def stop_message(self, headline: str | None) -> str:
        still_open = [i for i in self.github.milestone_issues(self.milestone) if i.is_open]
        if headline is None:
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
        return "\n".join(lines)


def describe(issue: Issue) -> str:
    blocked = ", ".join(f"#{b}" for b in issue.open_blockers)
    return f"- #{issue.number} {issue.status}" + (f", blocked by {blocked}" if blocked else "")


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
        session = ClaudeSession(repo, tokens, subprocess.run, dict(os.environ), datetime.now)
        telegram = Path.home() / ".claude" / "skills" / "telegram" / "send.sh"

        def notify(message: str) -> None:
            subprocess.run([str(telegram), message], check=False)

        return Live(session, notify, time.time, time.sleep)

    sys.exit(ralph.main(sys.argv[1:], connect, live))
