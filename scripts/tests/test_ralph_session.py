import contextlib
import json
import subprocess
from datetime import datetime, timedelta
from pathlib import Path

import pytest
from ralph import POLL_SECONDS, Improvement, Issue, Outcome
from ralph_session import ClaudeSession


class FakeTokens:
    """Mints ghs_1, ghs_2, … in turn."""

    def __init__(self):
        self.minted = 0

    def session_env(self, environ):
        return {**environ, "GH_TOKEN": "ghs_bot"}

    def current(self):
        self.minted += 1
        return f"ghs_{self.minted}"


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
        self.outputs = {}  # git subcommand, or another command's first two words → its stdout
        self.on_wait = nothing  # called with claude's start kwargs at each wait

    def start(self, args, **kwargs):
        self.calls.append((args, kwargs))
        for event in self.events:
            kwargs["stdout"].write(json.dumps(event) + "\n")
        return FakeProcess(self, kwargs)

    def __call__(self, args, **kwargs):
        self.calls.append((args, kwargs))
        keys = (" ".join(args[3:5]), args[3]) if args[0] == "git" else (" ".join(args[:2]),)
        for key in keys:
            if key in self.fails:
                return self.fails[key]
        stdout = next((self.outputs[key] for key in keys if key in self.outputs), "")
        return subprocess.CompletedProcess(args, 0, stdout, "")

    def claude(self):
        return next((args, kwargs) for args, kwargs in self.calls if args[0] == "claude")


class FakeProcess:
    def __init__(self, processes, kwargs):
        self.processes = processes
        self.kwargs = kwargs

    def wait(self, timeout):
        self.processes.on_wait(self.kwargs)
        self.processes.waits.append(timeout)
        if len(self.processes.waits) <= self.processes.polls:
            raise subprocess.TimeoutExpired("claude", timeout)
        return self.processes.exit


def nothing(*_):
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
    assert [args for args, _ in processes.calls[:3]] == [
        ["git", "-C", str(repo), "fetch", "origin"],
        [
            "git",
            "-C",
            str(repo),
            "for-each-ref",
            "--format=%(refname)",
            "refs/heads/swilliams/7-ralph",
            "refs/remotes/origin/swilliams/7-ralph",
        ],
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
    log = repo / ".ralph" / "logs" / "20261123-010203-7.log"
    assert log.read_text().splitlines()[-1] == json.dumps(result("Opened it.\nPR: #52"))


def token_file(kwargs):
    return Path(kwargs["env"]["RALPH_TOKEN_FILE"])


def test_a_session_starts_with_a_freshly_minted_token_in_a_file_only_it_can_read(tmp_path):
    processes = FakeProcesses(result("PR: #52"))
    _, claude = session(tmp_path, processes)
    seen = {}

    def start(args, **kwargs):
        seen.update(token=token_file(kwargs).read_text(), mode=token_file(kwargs).stat().st_mode)
        return processes.start(args, **kwargs)

    claude.start = start
    claude.work(Issue(7, "In progress"), nothing)

    assert (seen["token"], oct(seen["mode"] & 0o777)) == ("ghs_1", "0o600")


def test_the_token_file_gets_a_new_token_once_45_minutes_pass_and_not_before(tmp_path):
    processes = FakeProcesses(result("PR: #52"), polls=3)
    _, claude = session(tmp_path, processes)
    clock = [datetime(2026, 11, 23, 1, 0, 0)]
    claude.now = lambda: clock[0]
    seen = []

    def on_wait(kwargs):
        seen.append(token_file(kwargs).read_text())
        clock[0] += timedelta(minutes=30) if len(seen) == 1 else timedelta(minutes=15)

    processes.on_wait = on_wait
    claude.work(Issue(7, "In progress"), nothing)

    # Waits at 0, 30, 45 and 60 minutes: renewed at 45, and next due at 90.
    assert seen == ["ghs_1", "ghs_1", "ghs_2", "ghs_2"]


def test_the_session_is_told_where_its_token_file_is_and_given_no_token_itself(tmp_path):
    processes = FakeProcesses(result("PR: #52"))
    _, claude = session(tmp_path, processes)
    claude.environ = {"PATH": "/usr/bin", "GH_TOKEN": "owner-token"}

    claude.work(Issue(7, "In progress"), nothing)

    env = processes.claude()[1]["env"]
    assert [k for k in env if not k.startswith("GIT_")] == ["PATH", "RALPH_TOKEN_FILE"]
    assert [v for v in env.values() if "ghs_" in v or "owner-token" in v] == []


def behind_session_bin(path):
    """The PATH the session would have had without its own `bin` in front."""
    first, rest = path.split(":", 1)
    assert first.endswith("/bin")
    return rest


def in_session(claude, processes, command):
    """What `command` prints when run in the claude session's environment, as it starts."""
    printed = []

    def start(args, **kwargs):
        shell = ["/bin/sh", "-c", command]
        printed.append(subprocess.run(shell, env=kwargs["env"], capture_output=True, text=True))
        return processes.start(args, **kwargs)

    claude.start = start
    claude.work(Issue(7, "In progress"), nothing)
    return printed[0].stdout


def test_gh_in_the_session_runs_the_real_gh_with_the_token_from_the_file(tmp_path):
    processes = FakeProcesses(result("PR: #52"))
    _, claude = session(tmp_path, processes)
    real = tmp_path / "real-bin"
    real.mkdir()
    (real / "gh").write_text('#!/bin/sh\necho "$GH_TOKEN $*"\n')
    (real / "gh").chmod(0o755)
    claude.environ = {"PATH": f"{real}:/usr/bin:/bin"}

    assert in_session(claude, processes, "gh pr view 52") == "ghs_1 pr view 52\n"


def test_git_in_the_session_pushes_to_github_with_the_token_from_the_file(tmp_path):
    processes = FakeProcesses(result("PR: #52"))
    _, claude = session(tmp_path, processes)
    # The owner's own git config stays out of it.
    claude.environ = {"PATH": "/usr/bin:/bin", "HOME": str(tmp_path), "GIT_CONFIG_NOSYSTEM": "1"}

    printed = in_session(
        claude, processes, "printf 'protocol=https\\nhost=github.com\\n\\n' | git credential fill"
    )

    assert printed == "protocol=https\nhost=github.com\nusername=x-access-token\npassword=ghs_1\n"


def interrupted():
    raise KeyboardInterrupt


@pytest.mark.parametrize("while_running", [nothing, interrupted])
def test_the_token_file_is_deleted_when_the_session_ends_however_it_ends(tmp_path, while_running):
    processes = FakeProcesses(result("PR: #52"), polls=1)
    _, claude = session(tmp_path, processes)

    with contextlib.suppress(KeyboardInterrupt):
        claude.work(Issue(7, "In progress"), while_running)

    private = token_file(processes.claude()[1]).parent
    assert [path for path in [private] if path.exists()] == []


def test_an_issue_whose_worktree_is_still_there_is_resumed_in_it_without_adding_one(tmp_path):
    processes = FakeProcesses(result("PR: #52"))
    _, claude = session(tmp_path, processes)
    worktree = tmp_path / "shared-core-bench-7"
    worktree.mkdir()

    outcome = claude.work(Issue(7, "In progress"), nothing)

    assert outcome == Outcome(pr=52)
    adds = [args for args, _ in processes.calls if args[3:5] == ["worktree", "add"]]
    assert adds == []
    assert processes.claude()[1]["cwd"] == worktree


def test_a_session_on_a_branch_still_there_is_told_it_resumes_and_where_the_ruling_is(tmp_path):
    processes = FakeProcesses(result("PR: #52"))
    processes.outputs = {"for-each-ref": "refs/heads/swilliams/7-ralph\n", "gh pr": "52\n"}
    _, claude = session(tmp_path, processes)

    claude.work(Issue(7, "In progress"), nothing)

    assert processes.claude()[0][2] == (
        "Work on issue #7 on branch swilliams/7-ralph.\n"
        "\nResuming: an earlier session left branch `swilliams/7-ralph` with open PR #52. Before"
        " anything else, read the latest comments on the issue and on the PR: the owner's"
        " ruling is there.\n"
    )


def test_a_session_resuming_a_branch_whose_pr_was_never_opened_is_told_there_is_none(tmp_path):
    processes = FakeProcesses(result("PR: #52"))
    processes.outputs = {"for-each-ref": "refs/heads/swilliams/7-ralph\n"}
    _, claude = session(tmp_path, processes)

    claude.work(Issue(7, "In progress"), nothing)

    assert processes.claude()[0][2].splitlines()[2] == (
        "Resuming: an earlier session left branch `swilliams/7-ralph` with no open PR. Before"
        " anything else, read the latest comments on the issue and on the PR: the owner's"
        " ruling is there."
    )


def test_an_issue_whose_branch_is_still_there_gets_a_worktree_on_that_branch(tmp_path):
    processes = FakeProcesses(result("PR: #52"))
    processes.outputs = {"for-each-ref": "refs/remotes/origin/swilliams/7-ralph\n"}
    repo, claude = session(tmp_path, processes)

    claude.work(Issue(7, "In progress"), nothing)

    worktree = tmp_path / "shared-core-bench-7"
    adds = [args for args, _ in processes.calls if args[3:5] == ["worktree", "add"]]
    assert adds == [["git", "-C", str(repo), "worktree", "add", str(worktree), "swilliams/7-ralph"]]


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
        # The remote is SSH, which would push with the owner's key: go over HTTPS with the
        # token file's token, through git-credential-ralph.
        "GIT_CONFIG_COUNT": "3",
        "GIT_CONFIG_KEY_0": "url.https://github.com/.insteadOf",
        "GIT_CONFIG_VALUE_0": "git@github.com:",
        "GIT_CONFIG_KEY_1": "credential.https://github.com.helper",
        "GIT_CONFIG_VALUE_1": "",
        "GIT_CONFIG_KEY_2": "credential.https://github.com.helper",
        "GIT_CONFIG_VALUE_2": "ralph",
    }
    assert behind_session_bin(env["PATH"]) == "/usr/bin"


def test_each_session_starts_fresh_even_when_the_loop_runs_inside_claude_code(tmp_path):
    processes = FakeProcesses(result("PR: #52"))
    _, claude = session(tmp_path, processes)
    claude.environ = {"PATH": "/usr/bin", "CLAUDECODE": "1", "CLAUDE_CODE_SESSION_ID": "parent"}

    claude.work(Issue(7, "In progress"), nothing)

    env = processes.claude()[1]["env"]
    assert [k for k in env if k.startswith("CLAUDE")] == []
    assert behind_session_bin(env["PATH"]) == "/usr/bin"


def test_a_session_finds_simpool_on_its_path(tmp_path):
    processes = FakeProcesses(result("PR: #52"))
    _, claude = session(tmp_path, processes)
    claude.environ = {"PATH": "/usr/bin", "HOME": "/Users/owner"}

    claude.work(Issue(7, "In progress"), nothing)

    env = processes.claude()[1]["env"]
    assert (
        behind_session_bin(env["PATH"]) == "/Users/owner/.claude/skills/sim-pool/scripts:/usr/bin"
    )


def test_a_worktree_that_cannot_be_made_needs_a_human_and_starts_no_claude(tmp_path):
    processes = FakeProcesses(result("PR: #52"))
    error = "fatal: invalid reference: origin/main\n"
    processes.fails = {"worktree": subprocess.CompletedProcess([], 128, "", error)}
    _, claude = session(tmp_path, processes)

    outcome = claude.work(Issue(7, "In progress"), nothing)

    assert outcome == Outcome(
        pr=None,
        needs_human="git worktree add failed: fatal: invalid reference: origin/main",
    )
    assert [args[0] for args, _ in processes.calls] == ["git", "git", "git"]


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
