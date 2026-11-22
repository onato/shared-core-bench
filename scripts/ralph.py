"""Ralph loop: works through a milestone's issues, one fresh session per issue."""

from collections.abc import Callable
from dataclasses import dataclass, field
from typing import Protocol

TODO, IN_PROGRESS, IN_REVIEW = "Todo", "In progress", "In review"
STUCK_AFTER = 3
POLL_SECONDS = 60
MERGE_TIMEOUT = 3600


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


@dataclass(frozen=True)
class Outcome:
    pr: int | None
    needs_human: str | None = None


class GitHub(Protocol):
    def milestone_issues(self, milestone: int) -> list[Issue]: ...
    def pull_request(self, number: int) -> PullRequest: ...
    def set_status(self, issue: int, status: str) -> None: ...
    def comment(self, issue: int, body: str) -> None: ...


class Session(Protocol):
    def work(self, issue: Issue) -> Outcome: ...


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
    waiting_since: float | None = None

    def run(self) -> str:
        while True:
            issue = next_issue(self.github.milestone_issues(self.milestone))
            if issue is None:
                headline, done = self.wait_for_merges()
            elif self.iterations == self.max_iterations:
                headline, done = f"iteration cap ({self.max_iterations}) reached", True
            else:
                headline, done = None, False
                self.work(issue)
            if done:
                return self.stop_message(headline)

    def wait_for_merges(self) -> tuple[str | None, bool]:
        """Nothing to take: stop, unless a PR from this run may still merge and unblock one."""
        self.pending = [n for n in self.pending if not self.github.pull_request(n).merged]
        if not self.pending:
            return None, True
        if self.waiting_since is None:
            self.waiting_since = self.clock()
        if self.clock() - self.waiting_since >= MERGE_TIMEOUT:
            prs = ", ".join(f"PR #{n}" for n in self.pending)
            return f"auto-merge still pending after {MERGE_TIMEOUT // 60} minutes: {prs}", True
        self.sleep(POLL_SECONDS)
        return None, False

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
        return "\n".join(lines)


def describe(issue: Issue) -> str:
    blocked = ", ".join(f"#{b}" for b in issue.open_blockers)
    return f"- #{issue.number} {issue.status}" + (f", blocked by {blocked}" if blocked else "")
