import io
import json
from pathlib import Path

import pytest
from protected_paths import main


def patterns(tmp_path, *lines):
    path = tmp_path / "protected-paths"
    path.write_text("\n".join(lines) + "\n")
    return str(path)


def test_a_pr_touching_no_protected_path_passes(tmp_path, capsys):
    changed = io.StringIO("core-kmp/src/Feed.kt\nREADME.md\n")

    code = main(["--patterns-file", patterns(tmp_path, ".github/", "CODING_STANDARDS.md")], changed)

    assert code == 0
    assert capsys.readouterr().out == "No protected paths changed.\n"


def test_a_pr_touching_protected_paths_fails_and_lists_them(tmp_path, capsys):
    changed = io.StringIO(".github/workflows/ci.yml\nsrc/a.kt\nCODING_STANDARDS.md\n")

    code = main(["--patterns-file", patterns(tmp_path, ".github/", "CODING_STANDARDS.md")], changed)

    assert code == 1
    assert capsys.readouterr().out == (
        "Protected paths changed; the owner's gate-approved label is needed:\n"
        "  .github/workflows/ci.yml\n"
        "  CODING_STANDARDS.md\n"
    )


REPO_PATTERNS = str(Path(__file__).parents[2] / ".github" / "protected-paths")


def test_the_repo_list_protects_the_gates_with_gitignore_rules(capsys):
    changed = io.StringIO(
        ".github/claude-review-prompt.md\n"
        ".claude/skills/pr/SKILL.md\n"
        ".ralph/PROMPT.md\n"
        "android/app/coverage-exclusions\n"
        "core-kmp/build.gradle.kts\n"
        "scripts/pr_size.py\n"
        "docs/.github/notes.md\n"
        "core-kmp/feed/build.gradle.kts\n"
        "scripts/ralph.py\n"
    )

    code = main(["--patterns-file", REPO_PATTERNS], changed)

    assert code == 1
    assert capsys.readouterr().out.splitlines()[1:] == [
        "  .github/claude-review-prompt.md",
        "  .claude/skills/pr/SKILL.md",
        "  .ralph/PROMPT.md",
        "  android/app/coverage-exclusions",
        "  core-kmp/build.gradle.kts",
        "  scripts/pr_size.py",
    ]


def label_events(tmp_path, *events):
    path = tmp_path / "events.json"
    path.write_text(
        json.dumps(
            [{"event": e, "label": {"name": n}, "actor": {"login": a}} for e, n, a in events]
        )
    )
    return str(path)


def test_protected_paths_pass_once_the_owner_adds_gate_approved(tmp_path, capsys):
    events = label_events(
        tmp_path, ("labeled", "size-approved", "onato"), ("labeled", "gate-approved", "onato")
    )
    args = ["--patterns-file", patterns(tmp_path, ".github/"), "--approver", "onato"]

    code = main([*args, "--label-events", events], io.StringIO(".github/pr-size-exclude\n"))

    assert code == 0
    assert capsys.readouterr().out == (
        "Protected paths changed: approved by onato (gate-approved)\n  .github/pr-size-exclude\n"
    )


@pytest.mark.parametrize(
    "events",
    [
        [("labeled", "gate-approved", "onato-agent[bot]")],
        [("labeled", "gate-approved", "onato"), ("unlabeled", "gate-approved", "onato")],
        [("labeled", "size-approved", "onato")],
    ],
    ids=["added by someone else", "removed later", "a different label"],
)
def test_gate_approved_counts_only_while_the_owner_added_it_last(tmp_path, events):
    args = ["--patterns-file", patterns(tmp_path, ".github/"), "--approver", "onato"]
    path = label_events(tmp_path, *events)

    code = main([*args, "--label-events", path], io.StringIO(".github/pr-size-exclude\n"))

    assert code == 1
