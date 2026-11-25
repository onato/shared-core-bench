import os
import subprocess
import sys
from dataclasses import replace
from pathlib import Path

import pytest
from ralph import (
    DONE,
    IN_PROGRESS,
    IN_REVIEW,
    REQUIRED_WORKFLOWS,
    TODO,
    GitHubError,
    Improvement,
    Issue,
    Live,
    Outcome,
    PullRequest,
    main,
    run,
)
from ralph_token import TokenError


class FakeGitHub:
    """Issues by number; each issue's open blockers are worked out from `blocked_by` on read."""

    def __init__(self, issues, blocked_by=None):
        self.issues = {issue.number: issue for issue in issues}
        self.blocked_by = blocked_by or {}
        self.prs = {}
        self.moves = []
        self.comments = []
        self.updates = []  # PRs brought up to date with main, in order
        self.pr_fails = []  # errors the next PR reads raise, one each
        self.workflows = {name: True for name in REQUIRED_WORKFLOWS}
        self.approved = False  # the owner's plan-approved label on the milestone's parent
        self.undrafted = []
        self.open_prs = set()  # issues an open PR closes or works on

    def plan_approved(self, parent):
        assert parent == 2
        return self.approved

    def undraft(self, issue):
        self.undrafted.append(issue)
        self.issues[issue] = replace(self.issues[issue], draft=False)

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
        if self.pr_fails:
            raise GitHubError(self.pr_fails.pop(0))
        return self.prs[number]

    def has_open_pr(self, issue):
        return issue in self.open_prs

    def set_status(self, issue, status):
        self.moves.append((issue, status))
        self.issues[issue] = replace(self.issues[issue], status=status)

    def comment(self, issue, body):
        self.comments.append((issue, body))
        return f"https://github.com/onato/shared-core-bench/issues/{issue}#issuecomment-1"

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
        self.improved = []  # (the PRs it was given, how many issues had been worked by then)
        self.improvement = Improvement()
        self.removed = []
        self.removal_fails = {}  # issue → what failed
        self.update_fails = {}  # PR → why it couldn't be brought up to date
        self.while_running = {}  # issue → what happens on GitHub while its session runs
        # issue → the PRs updated by the time its session ended; "improve" → one list per run
        self.updates_at_end = {}

    def remove(self, issue):
        self.removed.append(issue)
        return self.removal_fails.get(issue)

    def update(self, pr):
        if failed := self.update_fails.get(pr.number):
            return failed
        self.github.updates.append(pr.number)
        self.github.prs[pr.number] = replace(pr, behind=False)
        return None

    def improve(self, prs, while_running):
        self.improved.append((prs, len(self.worked)))
        self.while_running.pop("improve", lambda: None)()
        while_running()
        self.updates_at_end.setdefault("improve", []).append(list(self.github.updates))
        for number in self.improvement.prs:
            self.github.prs[number] = PullRequest(number=number, merged=True)
        return self.improvement

    def work(self, issue, while_running):
        self.worked.append(issue.number)
        self.status_at_start.append(self.github.issues[issue.number].status)
        self.while_running.get(issue.number, lambda: None)()
        while_running()
        self.updates_at_end[issue.number] = list(self.github.updates)
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


def test_the_owner_is_messaged_as_soon_as_an_issue_needs_them():
    github = FakeGitHub([Issue(7, TODO), Issue(8, TODO)])
    stuck = (Outcome(pr=None, needs_human="choose a database"), None, True)
    session = FakeSession(github, {7: stuck, 8: merged_run(41)})
    notes = []

    stop = loop(github, session, notes)

    assert notes == [
        "Ralph loop: #7 needs you (moved to In review): needs a human: choose a database.\n"
        "https://github.com/onato/shared-core-bench/issues/7#issuecomment-1",
        stop,
    ]


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
    github.open_prs = {12}
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


def pending_run(pr, behind=False):
    return Outcome(pr=pr), PullRequest(number=pr, behind=behind), True


def test_pending_prs_left_behind_main_are_brought_up_to_date_while_the_loop_works():
    github = FakeGitHub([Issue(7, TODO), Issue(8, TODO), Issue(9, TODO)])
    session = FakeSession(
        github, {7: pending_run(40), 8: pending_run(41, behind=True), 9: merged_run(42)}
    )
    clock = Clock()
    clock.on_sleep = lambda: [github.merge(7, 40), github.merge(8, 41)]

    loop(github, session, clock=clock)

    assert session.worked == [7, 8, 9]
    assert github.updates == [41]


def test_a_pending_pr_that_falls_behind_while_a_session_runs_is_updated_before_it_ends():
    github = FakeGitHub([Issue(7, TODO), Issue(8, TODO)])
    session = FakeSession(github, {7: pending_run(40), 8: merged_run(41)})
    session.while_running = {8: lambda: github.prs.update({40: PullRequest(40, behind=True)})}
    clock = Clock()
    clock.on_sleep = lambda: github.merge(7, 40)

    loop(github, session, clock=clock)

    assert (session.updates_at_end[7], session.updates_at_end[8]) == ([], [40])


def test_a_github_error_while_a_session_runs_is_listed_in_the_stop_message_and_it_goes_on():
    github = FakeGitHub([Issue(7, TODO), Issue(8, TODO), Issue(9, TODO)])
    session = FakeSession(github, {7: pending_run(40), 8: merged_run(41), 9: merged_run(42)})

    session.while_running = {8: lambda: github.pr_fails.append("gh api graphql failed: HTTP 502")}
    clock = Clock()
    clock.on_sleep = lambda: github.merge(7, 40)

    stop = loop(github, session, clock=clock)

    assert session.worked == [7, 8, 9]
    assert stop == (
        "Ralph loop stopped: milestone complete.\n"
        "GitHub errors while a session ran:\n"
        "- gh api graphql failed: HTTP 502"
    )


def test_at_the_iteration_cap_the_loop_waits_for_its_open_prs_before_stopping():
    github = FakeGitHub([Issue(7, TODO), Issue(8, TODO)])
    session = FakeSession(github, {7: pending_run(40, behind=True), 8: merged_run(41)})
    clock = Clock()
    clock.on_sleep = lambda: github.merge(7, 40)

    stop = loop(github, session, clock=clock, max_iterations=1)

    assert session.worked == [7]
    assert github.updates == [40]
    assert clock.sleeps == [60]
    assert stop.startswith("Ralph loop stopped: iteration cap (1) reached.\n")


def test_a_pr_that_cannot_be_brought_up_to_date_puts_its_issue_in_review_and_is_not_waited_for():
    github = FakeGitHub([Issue(7, TODO), Issue(8, TODO)])
    session = FakeSession(github, {7: pending_run(40, behind=True), 8: merged_run(41)})
    session.update_fails = {40: "merging main conflicts in b.txt"}
    clock = Clock()
    notes = []

    stop = loop(github, session, notes=notes, clock=clock)

    reason = "PR #40 can't be brought up to date with main: merging main conflicts in b.txt"
    assert (7, IN_REVIEW) in github.moves
    assert github.comments == [(7, f"Moved to In review by the Ralph loop: {reason}.")]
    assert f"- #7: {reason}" in stop.splitlines()
    assert clock.sleeps == []


def test_a_pr_with_no_issue_of_its_own_that_cannot_be_updated_is_reported_and_not_waited_for():
    github = FakeGitHub([Issue(18, TODO)])
    github.prs[127] = PullRequest(number=127, behind=True)
    session = FakeSession(github, {18: (Outcome(pr=130, also=(127,)), PullRequest(130), False)})
    session.update_fails = {127: "merging main conflicts in b.txt"}
    clock = Clock()
    notes = []

    stop = loop(github, session, notes=notes, clock=clock)

    reason = "PR #127 can't be brought up to date with main: merging main conflicts in b.txt"
    assert f"Ralph loop: {reason}." in notes
    assert stop.splitlines()[-2:] == ["Not brought up to date:", f"- {reason}"]
    assert clock.sleeps == []


def test_other_prs_a_session_opened_are_brought_up_to_date_and_waited_for():
    github = FakeGitHub([Issue(18, TODO)])
    github.prs[127] = PullRequest(number=127, behind=True)
    session = FakeSession(github, {18: (Outcome(pr=130, also=(127,)), PullRequest(130), False)})
    clock = Clock()
    clock.on_sleep = lambda: github.prs.update({127: PullRequest(number=127, merged=True)})

    loop(github, session, clock=clock)

    assert github.updates == [127]
    assert clock.sleeps == [60]


def test_other_prs_a_session_merged_are_given_to_the_improver():
    github = FakeGitHub([Issue(18, TODO)])
    github.prs[126] = PullRequest(number=126, merged=True)
    session = FakeSession(github, {18: (Outcome(pr=130, also=(126,)), PullRequest(130), False)})

    loop(github, session)

    assert session.improved == [((130, 126), 1)]


def test_after_a_session_whose_pr_merged_its_worktree_and_branch_are_removed():
    github = FakeGitHub([Issue(7, TODO)])
    session = FakeSession(github, {7: merged_run(40)})

    loop(github, session)

    assert session.removed == [7]


def test_a_pending_prs_worktree_is_removed_once_it_merges():
    github = FakeGitHub([Issue(7, TODO)])
    session = FakeSession(github, {7: pending_run(40)})
    clock = Clock()
    clock.on_sleep = lambda: github.merge(7, 40)

    loop(github, session, clock=clock)

    assert session.removed == [7]


def test_a_stuck_issues_worktree_is_kept_for_the_owner_even_if_its_pr_merged():
    github = FakeGitHub([Issue(7, TODO), Issue(8, TODO)])
    stuck = (Outcome(pr=40, needs_human="pick a licence"), PullRequest(number=40), False)
    session = FakeSession(github, {7: stuck, 8: (Outcome(pr=None), None, False)})

    loop(github, session)

    assert session.removed == []


def test_a_failed_removal_is_listed_in_the_stop_message_and_the_loop_goes_on():
    github = FakeGitHub([Issue(7, TODO), Issue(8, TODO)])
    session = FakeSession(github, {7: merged_run(40), 8: merged_run(41)})
    session.removal_fails = {7: "git worktree remove failed: fatal: contains modified files"}

    stop = loop(github, session)

    assert session.worked == [7, 8]
    assert stop.endswith(
        "Worktrees left in place:\n- #7: git worktree remove failed: fatal: contains modified files"
    )


def test_an_in_review_issue_nothing_holds_goes_back_to_todo_and_is_taken_in_the_same_run():
    github = FakeGitHub([Issue(7, IN_REVIEW)])
    session = FakeSession(github, {7: merged_run(40)})

    loop(github, session)

    assert session.worked == [7]
    assert github.moves[:2] == [(7, TODO), (7, IN_PROGRESS)]
    assert github.comments == [
        (7, "Moved back to Todo by the Ralph loop: nothing holds it any more.")
    ]


def test_an_in_review_issue_whose_pr_is_still_open_stays_in_review():
    github = FakeGitHub([Issue(7, IN_REVIEW)])
    github.open_prs = {7}
    session = FakeSession(github, {})

    loop(github, session)

    assert (session.worked, github.moves, github.comments) == ([], [], [])


def test_an_in_review_issue_with_an_open_blocker_stays_in_review():
    github = FakeGitHub([Issue(7, IN_REVIEW), Issue(8, IN_PROGRESS)], blocked_by={7: (8,)})
    session = FakeSession(github, {})

    loop(github, session)

    assert (session.worked, github.moves, github.comments) == ([], [], [])


def test_a_released_issues_leftover_worktree_and_branch_are_removed_before_its_session():
    github = FakeGitHub([Issue(7, IN_REVIEW)])
    session = FakeSession(github, {7: merged_run(40)})
    removed_by_start = []
    session.while_running[7] = lambda: removed_by_start.extend(session.removed)

    loop(github, session)

    assert removed_by_start == [7]


def test_a_leftover_that_cannot_be_removed_is_reported_and_its_issue_stays_in_review():
    github = FakeGitHub([Issue(7, IN_REVIEW)])
    session = FakeSession(github, {})
    session.removal_fails = {7: "git worktree remove failed: fatal: contains modified files"}

    stop = loop(github, session)

    assert (session.worked, github.moves) == ([], [])
    assert stop.endswith(
        "Worktrees left in place:\n- #7: git worktree remove failed: fatal: contains modified files"
    )


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

    assert notes[-1] == stop
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


def test_after_five_merged_prs_an_improver_session_runs_before_the_next_issue():
    github = FakeGitHub([Issue(n, TODO) for n in range(1, 8)])
    session = FakeSession(github, {n: merged_run(40 + n) for n in range(1, 8)})

    loop(github, session)

    assert session.worked == [1, 2, 3, 4, 5, 6, 7]
    assert session.improved[0] == ((41, 42, 43, 44, 45), 5)


def test_a_pending_pr_that_falls_behind_while_the_improver_runs_is_updated_before_it_ends():
    github = FakeGitHub([Issue(n, TODO) for n in range(1, 8)])
    session = FakeSession(
        github, {1: pending_run(41), **{n: merged_run(40 + n) for n in range(2, 8)}}
    )
    session.while_running = {
        "improve": lambda: github.prs.update({41: PullRequest(41, behind=True)})
    }
    clock = Clock()
    clock.on_sleep = lambda: github.merge(1, 41)

    loop(github, session, clock=clock)

    assert session.updates_at_end["improve"][0] == [41]


def test_with_nothing_left_to_take_an_improver_reviews_the_rest_before_the_loop_stops():
    github = FakeGitHub([Issue(7, TODO), Issue(8, TODO)], blocked_by={8: (7,)})
    session = FakeSession(github, {7: pending_run(40), 8: merged_run(41)})
    clock = Clock()
    clock.on_sleep = lambda: github.merge(7, 40)

    stop = loop(github, session, clock=clock)

    assert session.improved == [((40, 41), 2)]
    assert stop == "Ralph loop stopped: milestone complete."


def test_the_improvers_prs_and_proposals_are_reported_and_each_proposal_messages_the_owner():
    github = FakeGitHub([Issue(7, TODO)])
    session = FakeSession(github, {7: merged_run(40)})
    session.improvement = Improvement(prs=(50,), proposals=(51, 52))
    notes = []

    stop = loop(github, session, notes)

    assert notes == [
        "Ralph loop: the improver proposes #51; it needs your decision.",
        "Ralph loop: the improver proposes #52; it needs your decision.",
        stop,
    ]
    assert stop == (
        "Ralph loop stopped: milestone complete.\n"
        "Improver PRs: PR #50\n"
        "Improver proposals: #51, #52"
    )


def test_an_improver_that_needs_the_owner_messages_them_and_the_loop_goes_on():
    github = FakeGitHub([Issue(7, TODO)])
    session = FakeSession(github, {7: merged_run(40)})
    session.improvement = Improvement(needs_human="git fetch origin failed: offline")
    notes = []

    stop = loop(github, session, notes)

    assert notes[0] == "Ralph loop: the improver needs you: git fetch origin failed: offline."
    assert stop.endswith("\nImprover needs you: git fetch origin failed: offline")


def test_an_improver_worktree_left_in_place_is_listed_in_the_stop_message():
    github = FakeGitHub([Issue(7, TODO)])
    session = FakeSession(github, {7: merged_run(40)})
    session.improvement = Improvement(left_in_place="git worktree remove failed: modified files")

    stop = loop(github, session)

    assert stop.endswith(
        "\nImprover worktree left in place: git worktree remove failed: modified files"
    )


def test_the_loop_does_not_take_a_draft_issue():
    github = FakeGitHub([Issue(7, TODO, draft=True)])
    session = FakeSession(github, {})

    loop(github, session)

    assert session.worked == []


def test_once_the_owner_approves_the_plan_the_drafts_move_to_todo_and_are_taken():
    github = FakeGitHub([Issue(7, "No status", draft=True), Issue(8, TODO, draft=True)])
    github.approved = True
    session = FakeSession(github, {7: merged_run(40), 8: merged_run(41)})

    loop(github, session)

    assert github.undrafted == [7, 8]
    assert github.moves[:2] == [(7, TODO), (8, TODO)]
    assert session.worked == [7, 8]


def test_with_only_drafts_left_the_stop_message_asks_for_plan_approved_on_the_parent():
    github = FakeGitHub([Issue(7, TODO, draft=True), Issue(8, TODO, draft=True)])
    session = FakeSession(github, {})

    stop = loop(github, session)

    assert stop == (
        "Ralph loop stopped: drafts wait for the owner's plan-approved label on #2.\n"
        "Open:\n"
        "- #7 Todo, draft\n"
        "- #8 Todo, draft"
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


def no_sessions():
    raise AssertionError("a dry run must not start a session")


def dry_run(github, *args):
    return main(["--dry-run", "--milestone", "2", *args], connect=lambda: github, live=no_sessions)


def test_dry_run_prints_the_next_issue_and_any_workflows_off_without_moving_a_card(capsys):
    github = FakeGitHub([Issue(6, IN_PROGRESS, is_open=False), Issue(7, TODO), Issue(8, TODO)])
    github.workflows["Item closed"] = False

    code = dry_run(github)

    assert code == 0
    assert capsys.readouterr().out == (
        "Board workflows are off: Item closed. Turn them on in the project's Workflows settings.\n"
        "Next: #7\n"
    )
    assert github.moves == []
    assert github.comments == []


def test_dry_run_with_nothing_ready_prints_why_and_what_is_open(capsys):
    github = FakeGitHub([Issue(7, IN_REVIEW), Issue(8, TODO)], blocked_by={8: (7,)})

    code = dry_run(github)

    assert code == 0
    assert capsys.readouterr().out == (
        "Nothing to take. Open:\n- #7 In review\n- #8 Todo, blocked by #7\n"
    )


class FailingGitHub(FakeGitHub):
    def milestone_issues(self, milestone):
        raise GitHubError("gh api graphql: HTTP 401: Bad credentials")


def test_a_failing_github_call_stops_with_the_command_and_its_error(capsys):
    code = dry_run(FailingGitHub([]))

    assert code == 1
    assert capsys.readouterr().err == (
        "Ralph loop stopped: gh api graphql: HTTP 401: Bad credentials\n"
    )


def test_without_dry_run_the_loop_works_the_milestone_and_sends_its_stop_message(capsys):
    github = FakeGitHub([Issue(7, TODO), Issue(8, TODO)], blocked_by={8: (7,)})
    session = FakeSession(github, {7: merged_run(50), 8: merged_run(51)})
    notes, clock = [], Clock()

    code = main(
        ["--milestone", "2", "--max-iterations", "1"],
        connect=lambda: github,
        live=lambda: Live(session, notes.append, clock, clock.sleep),
    )

    assert code == 0
    assert session.worked == [7]
    assert notes == ["Ralph loop stopped: iteration cap (1) reached.\nOpen:\n- #8 Todo"]
    assert capsys.readouterr().out == notes[0] + "\n"


def test_a_loop_that_cannot_start_says_why_and_starts_no_session(capsys):
    def live():
        raise TokenError("Ralph loop can't start: missing installation_id in .ralph/config")

    code = main(["--milestone", "2"], connect=lambda: FakeGitHub([]), live=live)

    assert code == 1
    message = "Ralph loop can't start: missing installation_id in .ralph/config"
    assert capsys.readouterr().err == message + "\n"


def test_the_script_stops_cleanly_when_gh_fails(tmp_path):
    fake_gh = tmp_path / "gh"
    fake_gh.write_text("#!/bin/sh\necho 'HTTP 401: Bad credentials' >&2\nexit 1\n")
    fake_gh.chmod(0o755)
    script = Path(__file__).parents[1] / "ralph.py"

    result = subprocess.run(
        [sys.executable, str(script), "--dry-run", "--milestone", "2"],
        capture_output=True,
        text=True,
        env={**os.environ, "PATH": f"{tmp_path}{os.pathsep}{os.environ['PATH']}"},
    )

    assert (result.returncode, result.stderr.splitlines()[-1]) == (
        1,
        "Ralph loop stopped: gh api graphql -f query=… -F owner=onato -F project=2: "
        "HTTP 401: Bad credentials",
    )
