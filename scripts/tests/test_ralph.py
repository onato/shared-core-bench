from dataclasses import replace

import pytest
from ralph import (
    DONE,
    IN_PROGRESS,
    IN_REVIEW,
    REQUIRED_WORKFLOWS,
    TODO,
    Issue,
    Outcome,
    PullRequest,
    run,
)


class FakeGitHub:
    """Issues by number; each issue's open blockers are worked out from `blocked_by` on read."""

    def __init__(self, issues, blocked_by=None):
        self.issues = {issue.number: issue for issue in issues}
        self.blocked_by = blocked_by or {}
        self.prs = {}
        self.moves = []
        self.comments = []
        self.workflows = {name: True for name in REQUIRED_WORKFLOWS}

    def board_workflows(self):
        return self.workflows

    def milestone_issues(self, milestone):
        assert milestone == 2
        is_open = [n for n, issue in self.issues.items() if issue.is_open]
        return [
            replace(
                issue, open_blockers=tuple(b for b in self.blocked_by.get(n, ()) if b in is_open)
            )
            for n, issue in sorted(self.issues.items())
        ]

    def pull_request(self, number):
        return self.prs[number]

    def set_status(self, issue, status):
        self.moves.append((issue, status))
        self.issues[issue] = replace(self.issues[issue], status=status)

    def comment(self, issue, body):
        self.comments.append((issue, body))

    def merge(self, issue, pr):
        self.issues[issue] = replace(self.issues[issue], is_open=False, status="Done")
        self.prs[pr] = replace(self.prs[pr], merged=True)


class FakeSession:
    """Each issue's session opens `pr` and, unless `pending`, has it merged at once."""

    def __init__(self, github, runs):
        self.github = github
        self.runs = runs
        self.worked = []
        self.status_at_start = []

    def work(self, issue):
        self.worked.append(issue.number)
        self.status_at_start.append(self.github.issues[issue.number].status)
        outcome, pr, pending = self.runs[issue.number]
        if pr is not None:
            self.github.prs[pr.number] = pr
            if not pending:
                self.github.merge(issue.number, pr.number)
        return outcome


def merged_run(pr):
    return Outcome(pr=pr), PullRequest(number=pr), False


class Clock:
    def __init__(self):
        self.now = 0.0
        self.sleeps = []
        self.on_sleep = lambda: None

    def __call__(self):
        return self.now

    def sleep(self, seconds):
        self.sleeps.append(seconds)
        self.now += seconds
        self.on_sleep()


def loop(github, session, notes=None, clock=None, max_iterations=10):
    notes = [] if notes is None else notes
    clock = clock or Clock()
    return run(2, github, session, notes.append, clock, clock.sleep, max_iterations)


def test_takes_the_lowest_ready_todo_issue_and_marks_it_in_progress_first():
    github = FakeGitHub(
        [
            Issue(7, TODO),
            Issue(8, TODO),
            Issue(9, IN_PROGRESS),
            Issue(10, TODO),
        ],
        blocked_by={8: (7,), 10: (9,)},
    )
    session = FakeSession(github, {7: merged_run(40), 8: merged_run(41)})

    loop(github, session)

    assert session.worked == [7, 8]
    assert session.status_at_start == [IN_PROGRESS, IN_PROGRESS]
    assert github.moves[:2] == [(7, IN_PROGRESS), (8, IN_PROGRESS)]


@pytest.mark.parametrize(
    ("outcome", "pr", "reason"),
    [
        (
            Outcome(pr=40),
            PullRequest(number=40, changes_requested=3),
            "PR #40: the review requested changes 3 times",
        ),
        (
            Outcome(pr=40),
            PullRequest(number=40, ci_failure_streak=3),
            "PR #40: CI failed on 3 runs in a row",
        ),
        (
            Outcome(pr=None, needs_human="needs splitting: over 400 lines"),
            None,
            "needs a human: needs splitting: over 400 lines",
        ),
        (Outcome(pr=None), None, "no PR opened"),
    ],
    ids=["changes requested 3 times", "CI failed 3 runs in a row", "NEEDS_HUMAN", "no PR"],
)
def test_a_stuck_issue_goes_to_in_review_with_its_reason_and_the_loop_goes_on(outcome, pr, reason):
    github = FakeGitHub([Issue(7, TODO), Issue(8, TODO)])
    session = FakeSession(github, {7: (outcome, pr, True), 8: merged_run(41)})

    loop(github, session)

    assert session.worked == [7, 8]
    assert github.moves[:2] == [(7, IN_PROGRESS), (7, IN_REVIEW)]
    assert github.comments == [(7, f"Moved to In review by the Ralph loop: {reason}.")]


def test_stops_with_milestone_complete_when_no_issues_are_open():
    github = FakeGitHub([Issue(7, TODO)])
    session = FakeSession(github, {7: merged_run(40)})
    notes = []

    stop = loop(github, session, notes)

    assert stop == "Ralph loop stopped: milestone complete."
    assert notes == [stop]


def test_stops_listing_what_is_left_when_open_issues_remain_but_none_is_ready():
    github = FakeGitHub(
        [
            Issue(9, IN_PROGRESS),
            Issue(10, TODO),
            Issue(11, TODO),
            Issue(12, IN_REVIEW),
        ],
        blocked_by={10: (9,), 11: (9, 10)},
    )
    session = FakeSession(github, {})

    stop = loop(github, session)

    assert session.worked == []
    assert stop == (
        "Ralph loop stopped: nothing left to take.\n"
        "Open:\n"
        "- #9 In progress\n"
        "- #10 Todo, blocked by #9\n"
        "- #11 Todo, blocked by #9, #10\n"
        "- #12 In review"
    )


def test_stops_at_the_iteration_cap():
    github = FakeGitHub([Issue(7, TODO), Issue(8, TODO)])
    session = FakeSession(github, {7: merged_run(40), 8: merged_run(41)})

    stop = loop(github, session, max_iterations=1)

    assert session.worked == [7]
    assert stop.startswith("Ralph loop stopped: iteration cap (1) reached.\n")


def test_waits_for_a_pending_auto_merge_then_takes_the_issue_it_unblocks():
    github = FakeGitHub([Issue(7, TODO), Issue(8, TODO)], blocked_by={8: (7,)})
    session = FakeSession(
        github, {7: (Outcome(pr=40), PullRequest(number=40), True), 8: merged_run(41)}
    )
    clock = Clock()
    clock.on_sleep = lambda: github.merge(7, 40)

    stop = loop(github, session, clock=clock)

    assert session.worked == [7, 8]
    assert clock.sleeps == [60]
    assert stop == "Ralph loop stopped: milestone complete."


def test_gives_up_waiting_for_auto_merge_after_an_hour():
    github = FakeGitHub([Issue(7, TODO), Issue(8, TODO)], blocked_by={8: (7,)})
    session = FakeSession(github, {7: (Outcome(pr=40), PullRequest(number=40), True)})
    clock = Clock()

    stop = loop(github, session, clock=clock)

    assert session.worked == [7]
    assert sum(clock.sleeps) == 3600
    assert stop.startswith(
        "Ralph loop stopped: auto-merge still pending after 60 minutes: PR #40.\n"
    )


def test_the_stop_message_lists_stuck_issues_and_prs_without_retro_notes():
    github = FakeGitHub([Issue(7, TODO), Issue(8, TODO), Issue(9, TODO)])
    session = FakeSession(
        github,
        {
            7: (Outcome(pr=40), PullRequest(number=40, ci_failure_streak=3), True),
            8: (Outcome(pr=None, needs_human="a secret: the API key"), None, False),
            9: (Outcome(pr=42), PullRequest(number=42, has_retro_notes=False), False),
        },
    )
    notes = []

    stop = loop(github, session, notes)

    assert notes == [stop]
    assert stop == (
        "Ralph loop stopped: nothing left to take.\n"
        "Open:\n"
        "- #7 In review\n"
        "- #8 In review\n"
        "Moved to In review:\n"
        "- #7: PR #40: CI failed on 3 runs in a row\n"
        "- #8: needs a human: a secret: the API key\n"
        "Missing retro notes: PR #42"
    )


def test_stops_before_any_session_when_a_required_board_workflow_is_off():
    github = FakeGitHub([Issue(7, TODO)])
    github.workflows["Item closed"] = False
    del github.workflows["Pull request merged"]
    session = FakeSession(github, {7: merged_run(40)})
    notes = []

    stop = loop(github, session, notes)

    assert session.worked == []
    assert github.moves == []
    assert notes == [stop]
    assert stop == (
        "Ralph loop stopped: board workflows are off: Item closed, Pull request merged. "
        "Turn them on in the project's Workflows settings."
    )


def test_closed_issues_left_off_done_are_moved_to_done_and_named_in_the_stop_message():
    github = FakeGitHub([Issue(6, IN_PROGRESS, is_open=False), Issue(7, TODO)])
    session = FakeSession(github, {7: merged_run(40)})

    stop = loop(github, session)

    assert github.moves[:2] == [(6, DONE), (7, IN_PROGRESS)]
    assert stop == (
        "Ralph loop stopped: milestone complete.\n"
        "Moved to Done (closed, but the board had not moved them): #6"
    )
