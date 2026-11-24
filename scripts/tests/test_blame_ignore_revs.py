import subprocess
from pathlib import Path

from blame_ignore_revs import missing

ROOT = Path(__file__).resolve().parents[2]

A = "a" * 40
B = "b" * 40


def test_a_structural_squash_commit_not_in_the_file_is_missing():
    log = f"{A} Structural: move the runner (#63)\n{B} Ralph loop: stuck issues (#41)\n"

    assert missing(log, ignore_revs="# comment\n") == [f"{A} Structural: move the runner (#63)"]


def test_listed_commits_and_branch_commits_without_a_pr_number_are_not_missing():
    log = f"{A} Structural: move the runner (#63)\n{B} Structural: extract a helper\n"

    assert missing(log, ignore_revs=f"# Structural: move the runner (#63)\n{A}\n") == []


def test_every_structural_commit_before_this_one_is_in_git_blame_ignore_revs():
    # A structural PR can't list its own squash commit, so the next PR adds it: HEAD is left out.
    # CI needs the full history (fetch-depth: 0) for HEAD's parents to exist.
    log = subprocess.run(
        ["git", "log", "--format=%H %s", "HEAD^@"],
        cwd=ROOT,
        capture_output=True,
        text=True,
        check=True,
    ).stdout

    assert missing(log, (ROOT / ".git-blame-ignore-revs").read_text()) == []
