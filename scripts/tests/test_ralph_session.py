import json
import subprocess
from datetime import datetime

from ralph import Issue, Outcome
from ralph_session import ClaudeSession


class FakeTokens:
    def session_env(self, environ):
        return {**environ, "GH_TOKEN": "ghs_bot"}


class FakeProcesses:
    """Records each command; `claude` streams `events` to its stdout and exits with `exit`."""

    def __init__(self, *events, exit=0):
        self.events = events
        self.exit = exit
        self.calls = []

    def __call__(self, args, **kwargs):
        self.calls.append((args, kwargs))
        if args[0] == "claude":
            for event in self.events:
                kwargs["stdout"].write(json.dumps(event) + "\n")
        return subprocess.CompletedProcess(args, self.exit if args[0] == "claude" else 0)

    def claude(self):
        return next((args, kwargs) for args, kwargs in self.calls if args[0] == "claude")


def result(text):
    return {"type": "result", "result": text}


def session(tmp_path, processes):
    repo = tmp_path / "checkout"
    (repo / ".ralph").mkdir(parents=True)
    (repo / ".ralph" / "PROMPT.md").write_text("Work on issue #{issue} on branch {branch}.\n")
    return repo, ClaudeSession(
        repo=repo,
        tokens=FakeTokens(),
        run=processes,
        environ={"PATH": "/usr/bin"},
        now=lambda: datetime(2026, 11, 23, 1, 2, 3),
    )


def test_a_session_runs_claude_on_the_issue_in_its_own_worktree_and_logs_the_stream(tmp_path):
    processes = FakeProcesses({"type": "system"}, result("Opened it.\nPR: #52"))
    repo, claude = session(tmp_path, processes)

    outcome = claude.work(Issue(7, "In progress"))

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


def test_the_session_commits_and_pushes_as_the_bot_not_the_owner(tmp_path):
    processes = FakeProcesses(result("PR: #52"))
    _, claude = session(tmp_path, processes)

    claude.work(Issue(7, "In progress"))

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

    claude.work(Issue(7, "In progress"))

    env = processes.claude()[1]["env"]
    assert [k for k in env if k.startswith("CLAUDE")] == []
    assert env["PATH"] == "/usr/bin"


def test_a_needs_human_marker_is_reported_to_the_loop_as_its_reason(tmp_path):
    text = "Stopped.\nPR: #53\nNEEDS_HUMAN: the spec doesn't say which SQLite version to pin\n"
    _, claude = session(tmp_path, FakeProcesses(result(text)))

    outcome = claude.work(Issue(8, "In progress"))

    assert outcome == Outcome(pr=53, needs_human="the spec doesn't say which SQLite version to pin")


def test_a_session_that_ends_without_a_result_needs_a_human_and_points_at_its_log(tmp_path):
    repo, claude = session(tmp_path, FakeProcesses({"type": "system"}, exit=1))

    outcome = claude.work(Issue(9, "In progress"))

    log = repo / ".ralph" / "logs" / "20261123-010203-9.log"
    assert outcome == Outcome(pr=None, needs_human=f"the session ended without a result; see {log}")
