"""`ClaudeSession.update` against real repos: an origin, and the checkout the loop runs from."""

import subprocess
from datetime import datetime

from ralph import PullRequest
from ralph_session import ClaudeSession

IGNORE_REVS, HEADER = ".git-blame-ignore-revs", "# Structural commits\n"
IDENTITY = ["-c", "user.name=Owner", "-c", "user.email=owner@example.com"]


class FakeTokens:
    def session_env(self, environ):
        return {**environ, "GH_TOKEN": "ghs_bot"}


def git(where, *args):
    done = subprocess.run(
        ["git", "-C", str(where), *IDENTITY, *args], capture_output=True, text=True, check=True
    )
    return done.stdout


def commit(where, message, **files):
    for name, text in files.items():
        (where / name).write_text(text)
    git(where, "add", "-A")
    git(where, "commit", "-q", "-m", message)


def repos(tmp_path):
    """An origin whose `main` has a PR branch `pr` cut from it, and a clone as the checkout."""
    origin = tmp_path / "origin.git"
    git(tmp_path, "init", "-q", "--bare", "-b", "main", str(origin))
    seed = tmp_path / "seed"
    git(tmp_path, "clone", "-q", str(origin), str(seed))
    commit(seed, "Start", **{".git-blame-ignore-revs": "# Structural commits\n", "a.txt": "a\n"})
    git(seed, "push", "-q", "origin", "HEAD:main")
    git(seed, "checkout", "-q", "-b", "pr")
    commit(seed, "The PR's work", **{"b.txt": "b\n"})
    git(seed, "push", "-q", "origin", "pr")
    git(seed, "checkout", "-q", "main")
    checkout = tmp_path / "checkout"
    git(tmp_path, "clone", "-q", str(origin), str(checkout))
    return seed, ClaudeSession(
        repo=checkout,
        tokens=FakeTokens(),
        run=subprocess.run,
        start=subprocess.Popen,
        environ={"PATH": "/usr/bin:/bin:/opt/homebrew/bin"},
        now=lambda: datetime(2026, 11, 25, 1, 2, 3),
    )


def on_origin(seed, path, branch="pr"):
    git(seed, "fetch", "-q", "origin")
    return git(seed, "show", f"origin/{branch}:{path}")


def test_update_merges_main_into_the_prs_branch_and_pushes_it(tmp_path):
    seed, session = repos(tmp_path)
    commit(seed, "Main moves on", **{"a.txt": "a, later\n"})
    git(seed, "push", "-q", "origin", "HEAD:main")

    failed = session.update(PullRequest(40, behind=True, branch="pr"))

    assert failed is None
    assert (on_origin(seed, "a.txt"), on_origin(seed, "b.txt")) == ("a, later\n", "b\n")


def test_update_lists_the_structural_commits_the_merge_brings_in(tmp_path):
    seed, session = repos(tmp_path)
    commit(seed, "Structural: rename a (#41)", **{"a.txt": "renamed\n"})
    git(seed, "push", "-q", "origin", "HEAD:main")
    sha = git(seed, "rev-parse", "HEAD").strip()

    session.update(PullRequest(40, behind=True, branch="pr"))

    assert on_origin(seed, ".git-blame-ignore-revs") == (
        f"# Structural commits\n\n# Structural: rename a (#41)\n{sha}\n"
    )


def structural(where, number):
    commit(where, f"Structural: step {number} (#{number})", **{"a.txt": f"step {number}\n"})
    return f"\n# Structural: step {number} (#{number})\n" + git(where, "rev-parse", "HEAD")


def test_a_conflict_only_in_the_ignore_list_takes_mains_side_then_adds_what_is_missing(tmp_path):
    seed, session = repos(tmp_path)
    entry_41 = structural(seed, 41)
    git(seed, "push", "-q", "origin", "HEAD:main")
    git(seed, "checkout", "-q", "pr")
    git(seed, "merge", "-q", "--no-edit", "origin/main")
    commit(seed, "The PR lists #41", **{IGNORE_REVS: HEADER + entry_41})
    git(seed, "push", "-q", "origin", "pr")
    git(seed, "checkout", "-q", "main")
    entry_42 = structural(seed, 42)
    commit(seed, "Main lists #41 and #42", **{IGNORE_REVS: HEADER + entry_41 + entry_42})
    entry_43 = structural(seed, 43)
    git(seed, "push", "-q", "origin", "HEAD:main")

    failed = session.update(PullRequest(40, behind=True, branch="pr"))

    assert failed is None
    assert on_origin(seed, IGNORE_REVS) == HEADER + entry_41 + entry_42 + entry_43


def test_any_other_conflict_fails_the_update_pushes_nothing_and_leaves_no_worktree(tmp_path):
    seed, session = repos(tmp_path)
    git(seed, "checkout", "-q", "pr")
    commit(seed, "The PR changes b", **{"b.txt": "the PR's b\n"})
    git(seed, "push", "-q", "origin", "pr")
    before = git(seed, "rev-parse", "HEAD")
    git(seed, "checkout", "-q", "main")
    commit(seed, "Main changes b", **{"b.txt": "main's b\n"})
    git(seed, "push", "-q", "origin", "HEAD:main")

    failed = session.update(PullRequest(40, behind=True, branch="pr"))

    assert failed == "merging main conflicts in b.txt"
    git(seed, "fetch", "-q", "origin")
    assert git(seed, "rev-parse", "origin/pr") == before
    assert not (tmp_path / "shared-core-bench-update-40").exists()


def test_a_pr_whose_branch_is_gone_reports_the_worktree_that_could_not_be_made(tmp_path):
    _, session = repos(tmp_path)

    failed = session.update(PullRequest(40, behind=True, branch="deleted"))

    assert failed.startswith("git worktree add failed: fatal: invalid reference: origin/deleted")
