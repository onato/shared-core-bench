import io
import json

from pr_size import check, main


def label_events(tmp_path, *events):
    path = tmp_path / "events.json"
    path.write_text(
        json.dumps(
            [{"event": e, "label": {"name": n}, "actor": {"login": a}} for e, n, a in events]
        )
    )
    return str(path)


def test_over_the_limit_passes_when_the_owner_added_the_size_approved_label(tmp_path, capsys):
    events = label_events(
        tmp_path, ("labeled", "bug", "onato-agent[bot]"), ("labeled", "size-approved", "onato")
    )
    numstat = io.StringIO("424\t0\tsrc/a.kt\n")

    code = main(["--approver", "onato", "--label-events", events], stdin=numstat)

    assert code == 0
    out = capsys.readouterr().out
    assert "424 changed lines, limit 400: approved by onato (size-approved)" in out


def test_a_size_approved_label_added_by_anyone_but_the_owner_does_not_approve(tmp_path):
    events = label_events(tmp_path, ("labeled", "size-approved", "onato-agent[bot]"))
    numstat = io.StringIO("424\t0\tsrc/a.kt\n")

    code = main(["--approver", "onato", "--label-events", events], stdin=numstat)

    assert code == 1


def test_a_size_approved_label_the_owner_later_removed_does_not_approve(tmp_path):
    events = label_events(
        tmp_path, ("labeled", "size-approved", "onato"), ("unlabeled", "size-approved", "onato")
    )
    numstat = io.StringIO("424\t0\tsrc/a.kt\n")

    code = main(["--approver", "onato", "--label-events", events], stdin=numstat)

    assert code == 1


def test_total_under_the_limit_passes():
    numstat = "10\t5\tsrc/a.kt\n20\t0\tsrc/b.kt\n"

    result = check(numstat, excludes=[], limit=400)

    assert result.passed
    assert result.total == 35


def test_total_over_the_limit_fails_and_lists_the_largest_files_first():
    numstat = "100\t0\tsmall.kt\n300\t50\tbig.kt\n150\t0\tmedium.kt\n"

    result = check(numstat, excludes=[], limit=400)

    assert not result.passed
    assert result.total == 600
    assert result.largest == [("big.kt", 350), ("medium.kt", 150), ("small.kt", 100)]


def test_excluded_globs_are_ignored_including_nested_paths():
    numstat = (
        "1\t0\tsrc/kept.kt\n"
        "500\t0\tgradlew\n"
        "500\t0\tandroid/gradlew\n"
        "500\t0\tgradle/wrapper/gradle-wrapper.properties\n"
        "500\t0\tuv.lock\n"
        "500\t0\tcore-rust/Cargo.lock\n"
        "500\t0\tcore-rust/src/generated/bindings.rs\n"
    )
    excludes = ["gradlew", "gradle/wrapper/", "*.lock", "**/generated/**"]

    result = check(numstat, excludes=excludes, limit=400)

    assert result.passed
    assert result.total == 1
    assert result.largest == [("src/kept.kt", 1)]


def test_a_pattern_with_a_slash_is_anchored_at_the_root():
    numstat = "500\t0\tgradle/wrapper/x.jar\n7\t0\tandroid/gradle/wrapper/x.jar\n"

    result = check(numstat, excludes=["/gradle/wrapper/"], limit=400)

    assert result.largest == [("android/gradle/wrapper/x.jar", 7)]


def test_binary_files_are_ignored():
    numstat = "-\t-\tgradle/wrapper/gradle-wrapper.jar\n3\t1\tREADME.md\n"

    result = check(numstat, excludes=[], limit=400)

    assert result.total == 4
    assert result.largest == [("README.md", 4)]


def test_command_line_reads_limit_and_exclude_file_and_reports_failure(tmp_path, capsys):
    exclude_file = tmp_path / "exclude"
    exclude_file.write_text("# generated\n\n*.lock\n")
    numstat = io.StringIO("8\t4\tsrc/a.kt\n900\t0\tuv.lock\n")

    code = main(["--limit", "10", "--exclude-file", str(exclude_file)], stdin=numstat)

    out = capsys.readouterr().out
    assert code == 1
    assert "12 changed lines, limit 10" in out
    assert "src/a.kt" in out
    assert "uv.lock" not in out


def test_command_line_defaults_to_400_and_the_repo_exclude_file(tmp_path, monkeypatch, capsys):
    (tmp_path / ".github").mkdir()
    (tmp_path / ".github" / "pr-size-exclude").write_text("*.lock\n")
    monkeypatch.chdir(tmp_path)
    numstat = io.StringIO("400\t0\tsrc/a.kt\n900\t0\tuv.lock\n")

    code = main([], stdin=numstat)

    assert code == 0
    assert "400 changed lines, limit 400" in capsys.readouterr().out


def test_command_line_without_an_exclude_file_counts_everything(tmp_path, monkeypatch, capsys):
    monkeypatch.chdir(tmp_path)

    code = main([], stdin=io.StringIO("401\t0\tuv.lock\n"))

    assert code == 1
    assert "401 changed lines, limit 400" in capsys.readouterr().out


def test_a_directory_pattern_matches_that_directory_anywhere_but_not_a_file_of_that_name():
    numstat = "500\t0\tandroid/app/build/out.txt\n3\t0\tdocs/build\n"

    result = check(numstat, excludes=["build/"], limit=400)

    assert result.largest == [("docs/build", 3)]
