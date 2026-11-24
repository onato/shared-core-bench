import json
import subprocess
from datetime import datetime

from ralph import POLL_SECONDS, Improvement, Issue, Outcome
from ralph_session import ClaudeSession


class FakeTokens:
    def session_env(self, environ):
        return {**environ, "GH_TOKEN": "ghs_bot"}


class FakeProcesses:
    """Records each command; `claude` streams `events` to its stdout and exits with `exit`
    after `polls` waits have timed out."""

    def __init__(self, *events, exit=0, polls=0):
        self.events = events
        self.exit = exit
        self.polls = polls
        self.waits = []  # each wait's timeout
        self.calls = []
        self.fails = {}  # git subcommand, one word or two → the CompletedProcess it fails with

    def start(self, args, **kwargs):
        self.calls.append((args, kwargs))
        for event in self.events:
            kwargs["stdout"].write(json.dumps(event) + "\n")
        return FakeProcess(self)

    def __call__(self, args, **kwargs):
        self.calls.append((args, kwargs))
        for subcommand in (" ".join(args[3:5]), args[3]) if args[0] == "git" else ():
            if subcommand in self.fails:
                return self.fails[subcommand]
        return subprocess.CompletedProcess(args, 0)

    def claude(self):
        return next((args, kwargs) for args, kwargs in self.calls if args[0] == "claude")


class FakeProcess:
    def __init__(self, processes):
        self.processes = processes

    def wait(self, timeout):
        self.processes.waits.append(timeout)
        if len(self.processes.waits) <= self.processes.polls:
            raise subprocess.TimeoutExpired("claude", timeout)
        return self.processes.exit


def nothing():
    pass


def result(text):
    return {"type": "result", "result": text}


def session(tmp_path, processes):
    repo = tmp_path / "checkout"
    (repo / ".ralph").mkdir(parents=True)
    (repo / ".ralph" / "PROMPT.md").write_text("Work on issue #{issue} on branch {branch}.\n")
    (repo / ".ralph" / "IMPROVE.md").write_text("Review {prs}.\n")
    return repo, ClaudeSession(
        repo=repo,
        tokens=FakeTokens(),
        run=processes,
        start=processes.start,
        environ={"PATH": "/usr/bin"},
        now=lambda: datetime(2026, 11, 23, 1, 2, 3),
    )


def test_a_session_runs_claude_on_the_issue_in_its_own_worktree_and_logs_the_stream(tmp_path):
    processes = FakeProcesses({"type": "system"}, result("Opened it.\nPR: #52"))
    repo, claude = session(tmp_path, processes)

    outcome = claude.work(Issue(7, "In progress"), nothing)

    worktree = tmp_path / "shared-core-bench-7"
    assert outcome == Outcome(pr=52)
    assert [args for args, _ in processes.calls[:2]] == [
        ["git", "-C", str(repo), "fetch", "origin"],
        [
            "git",
            "-C",
            str(repo),
            "worktree",
            "add",
            str(worktree),
            "-b",
            "swilliams/7-ralph",
            "origin/main",
        ],
    ]
    args, kwargs = processes.claude()
    assert args == [
        "claude",
        "-p",
        "Work on issue #7 on branch swilliams/7-ralph.\n",
        "--permission-mode",
        "auto",
        "--output-format",
        "stream-json",
        "--verbose",
    ]
    assert kwargs["cwd"] == worktree
    assert kwargs["env"]["GH_TOKEN"] == "ghs_bot"
    log = repo / ".ralph" / "logs" / "20261123-010203-7.log"
    assert log.read_text().splitlines()[-1] == json.dumps(result("Opened it.\nPR: #52"))


def test_while_claude_runs_the_session_calls_while_running_every_poll(tmp_path):
    processes = FakeProcesses(result("PR: #52"), polls=2)
    _, claude = session(tmp_path, processes)
    calls = []

    outcome = claude.work(Issue(7, "In progress"), lambda: calls.append(len(processes.waits)))

    assert calls == [1, 2]
    assert processes.waits == [POLL_SECONDS] * 3
    assert outcome == Outcome(pr=52)


def test_the_session_commits_and_pushes_as_the_bot_not_the_owner(tmp_path):
    processes = FakeProcesses(result("PR: #52"))
    _, claude = session(tmp_path, processes)

    claude.work(Issue(7, "In progress"), nothing)

    env = processes.claude()[1]["env"]
    bot = "onato-agent[bot]"
    email = "359795497+onato-agent[bot]@users.noreply.github.com"
    assert {k: env[k] for k in env if k.startswith("GIT_")} == {
        "GIT_AUTHOR_NAME": bot,
        "GIT_AUTHOR_EMAIL": email,
        "GIT_COMMITTER_NAME": bot,
        "GIT_COMMITTER_EMAIL": email,
        # The remote is SSH, which would push with the owner's key: go over HTTPS with GH_TOKEN.
        "GIT_CONFIG_COUNT": "3",
        "GIT_CONFIG_KEY_0": "url.https://github.com/.insteadOf",
        "GIT_CONFIG_VALUE_0": "git@github.com:",
        "GIT_CONFIG_KEY_1": "credential.https://github.com.helper",
        "GIT_CONFIG_VALUE_1": "",
        "GIT_CONFIG_KEY_2": "credential.https://github.com.helper",
        "GIT_CONFIG_VALUE_2": "!gh auth git-credential",
    }
    assert env["PATH"] == "/usr/bin"


def test_each_session_starts_fresh_even_when_the_loop_runs_inside_claude_code(tmp_path):
    processes = FakeProcesses(result("PR: #52"))
    _, claude = session(tmp_path, processes)
    claude.environ = {"PATH": "/usr/bin", "CLAUDECODE": "1", "CLAUDE_CODE_SESSION_ID": "parent"}

    claude.work(Issue(7, "In progress"), nothing)

    env = processes.claude()[1]["env"]
    assert [k for k in env if k.startswith("CLAUDE")] == []
    assert env["PATH"] == "/usr/bin"


def test_a_worktree_that_cannot_be_made_needs_a_human_and_starts_no_claude(tmp_path):
    processes = FakeProcesses(result("PR: #52"))
    error = "fatal: a branch named 'swilliams/7-ralph' already exists\n"
    processes.fails = {"worktree": subprocess.CompletedProcess([], 128, "", error)}
    _, claude = session(tmp_path, processes)

    outcome = claude.work(Issue(7, "In progress"), nothing)

    assert outcome == Outcome(
        pr=None,
        needs_human="git worktree add failed: fatal: a branch named 'swilliams/7-ralph' "
        "already exists",
    )
    assert [args[0] for args, _ in processes.calls] == ["git", "git"]


def test_a_needs_human_marker_is_reported_to_the_loop_as_its_reason(tmp_path):
    text = "Stopped.\nPR: #53\nNEEDS_HUMAN: the spec doesn't say which SQLite version to pin\n"
    _, claude = session(tmp_path, FakeProcesses(result(text)))

    outcome = claude.work(Issue(8, "In progress"), nothing)

    assert outcome == Outcome(pr=53, needs_human="the spec doesn't say which SQLite version to pin")


def test_the_first_pr_line_is_the_issues_pr_and_the_rest_are_other_prs_it_opened(tmp_path):
    text = "Done.\nPR: #130\nPR: #126\nPR: #127\n"
    _, claude = session(tmp_path, FakeProcesses(result(text)))

    outcome = claude.work(Issue(18, "In progress"), nothing)

    assert outcome == Outcome(pr=130, also=(126, 127))


def test_a_session_that_ends_without_a_result_needs_a_human_and_points_at_its_log(tmp_path):
    repo, claude = session(tmp_path, FakeProcesses({"type": "system"}, exit=1))

    outcome = claude.work(Issue(9, "In progress"), nothing)

    log = repo / ".ralph" / "logs" / "20261123-010203-9.log"
    assert outcome == Outcome(pr=None, needs_human=f"the session ended without a result; see {log}")


def test_the_improver_reviews_the_merged_prs_in_a_detached_worktree_of_main(tmp_path):
    processes = FakeProcesses(result("Done.\nPR: #63\nPR: #64\nPROPOSED: #65\n"))
    repo, claude = session(tmp_path, processes)

    improvement = claude.improve((60, 61, 62), nothing)

    worktree = tmp_path / "shared-core-bench-improve-20261123-010203"
    assert improvement == Improvement(prs=(63, 64), proposals=(65,))
    assert [args for args, _ in processes.calls[:2]] == [
        ["git", "-C", str(repo), "fetch", "origin"],
        ["git", "-C", str(repo), "worktree", "add", str(worktree), "--detach", "origin/main"],
    ]
    args, kwargs = processes.claude()
    assert args[2] == "Review PRs #60, #61, #62.\n"
    assert kwargs["cwd"] == worktree
    assert (repo / ".ralph" / "logs" / "20261123-010203-improve.log").exists()


def test_an_improver_without_a_worktree_needs_a_human_and_starts_no_claude(tmp_path):
    processes = FakeProcesses(result("PR: #63"))
    processes.fails = {"fetch": subprocess.CompletedProcess([], 128, "", "fatal: offline\n")}
    _, claude = session(tmp_path, processes)

    improvement = claude.improve((60,), nothing)

    assert improvement == Improvement(needs_human="git fetch origin failed: fatal: offline")
    assert [args[0] for args, _ in processes.calls] == ["git"]


def test_an_improver_that_ends_without_a_result_needs_a_human_and_points_at_its_log(tmp_path):
    repo, claude = session(tmp_path, FakeProcesses({"type": "system"}, exit=1))

    improvement = claude.improve((60,), nothing)

    log = repo / ".ralph" / "logs" / "20261123-010203-improve.log"
    assert improvement == Improvement(needs_human=f"the session ended without a result; see {log}")


def test_removing_an_issues_worktree_keeps_uncommitted_work_and_deletes_its_branch(tmp_path):
    processes = FakeProcesses()
    repo, claude = session(tmp_path, processes)
    (tmp_path / "shared-core-bench-7").mkdir()

    failed = claude.remove(7)

    assert failed is None
    assert [args for args, _ in processes.calls] == [
        ["git", "-C", str(repo), "worktree", "remove", str(tmp_path / "shared-core-bench-7")],
        ["git", "-C", str(repo), "show-ref", "--verify", "--quiet", "refs/heads/swilliams/7-ralph"],
        ["git", "-C", str(repo), "branch", "-D", "swilliams/7-ralph"],
    ]


def test_a_worktree_with_uncommitted_work_is_kept_with_its_branch_and_the_failure_reported(
    tmp_path,
):
    processes = FakeProcesses()
    error = "fatal: contains modified or untracked files, use --force to delete it\n"
    processes.fails = {"worktree remove": subprocess.CompletedProcess([], 128, "", error)}
    _, claude = session(tmp_path, processes)
    (tmp_path / "shared-core-bench-7").mkdir()

    failed = claude.remove(7)

    assert failed == (
        "git worktree remove failed: fatal: contains modified or untracked files, "
        "use --force to delete it"
    )
    assert [args[3] for args, _ in processes.calls] == ["worktree"]


def test_removing_an_issue_with_no_worktree_or_branch_left_over_succeeds(tmp_path):
    processes = FakeProcesses()
    processes.fails = {"show-ref --verify": subprocess.CompletedProcess([], 1, "", "")}
    repo, claude = session(tmp_path, processes)

    failed = claude.remove(7)

    assert failed is None
    assert [args for args, _ in processes.calls] == [
        ["git", "-C", str(repo), "show-ref", "--verify", "--quiet", "refs/heads/swilliams/7-ralph"],
    ]


def test_the_improvers_worktree_is_removed_once_its_session_ends(tmp_path):
    processes = FakeProcesses(result("PR: #63"))
    repo, claude = session(tmp_path, processes)

    claude.improve((60,), nothing)

    worktree = tmp_path / "shared-core-bench-improve-20261123-010203"
    assert processes.calls[-1][0] == ["git", "-C", str(repo), "worktree", "remove", str(worktree)]


def test_an_improver_worktree_that_cannot_be_removed_is_reported_with_its_prs(tmp_path):
    processes = FakeProcesses(result("PR: #63"))
    error = "fatal: contains modified or untracked files, use --force to delete it\n"
    processes.fails = {"worktree remove": subprocess.CompletedProcess([], 128, "", error)}
    _, claude = session(tmp_path, processes)

    improvement = claude.improve((60,), nothing)

    assert improvement == Improvement(
        prs=(63,),
        left_in_place="git worktree remove failed: fatal: contains modified or untracked files, "
        "use --force to delete it",
    )
