from coverage_summary import main

COBERTURA = (
    '<?xml version="1.0" ?>\n'
    '<coverage lines-valid="95" lines-covered="90" branches-valid="24" branches-covered="24"'
    ' line-rate="0.947" branch-rate="1"></coverage>\n'
)

JACOCO = (
    '<?xml version="1.0" encoding="UTF-8"?>\n'
    '<report name="core-kmp"><package name="bench/core">'
    '<counter type="LINE" missed="9" covered="1"/></package>'
    '<counter type="BRANCH" missed="0" covered="0"/>'
    '<counter type="LINE" missed="0" covered="5"/></report>\n'
)


def write(tmp_path, name, text):
    path = tmp_path / name
    path.write_text(text)
    return path


def test_each_module_gets_a_notice_with_line_and_branch_coverage(tmp_path, capsys):
    scripts = write(tmp_path, "coverage.xml", COBERTURA)
    kmp = write(tmp_path, "report.xml", JACOCO)

    code = main([f"scripts={scripts}", f"kmp={kmp}"], summary_path=None)

    out = capsys.readouterr().out.splitlines()
    assert code == 0
    assert out == [
        "::notice title=Coverage scripts::lines 90/95 (94.7%), branches 24/24 (100.0%)",
        "::notice title=Coverage kmp::lines 5/5 (100.0%), branches none",
    ]


def test_the_step_summary_gets_a_table_row_per_module(tmp_path):
    scripts = write(tmp_path, "coverage.xml", COBERTURA)
    kmp = write(tmp_path, "report.xml", JACOCO)
    summary = write(tmp_path, "summary.md", "earlier step output\n")

    main([f"scripts={scripts}", f"kmp={kmp}"], summary_path=summary)

    assert summary.read_text() == (
        "earlier step output\n"
        "## Coverage\n\n"
        "| Module | Lines | Branches |\n"
        "|---|---|---|\n"
        "| scripts | 90/95 (94.7%) | 24/24 (100.0%) |\n"
        "| kmp | 5/5 (100.0%) | none |\n"
    )


def test_a_missing_or_unreadable_report_is_a_warning_not_a_failure(tmp_path, capsys):
    garbled = write(tmp_path, "garbled.xml", "<not xml")
    absent = tmp_path / "absent.xml"
    summary = tmp_path / "summary.md"

    code = main([f"scripts={absent}", f"kmp={garbled}"], summary_path=summary)

    out = capsys.readouterr().out.splitlines()
    assert code == 0
    assert out == [
        f"::warning title=Coverage scripts::no coverage report at {absent}",
        f"::warning title=Coverage kmp::no coverage report at {garbled}",
    ]
    assert "| scripts | no report | no report |" in summary.read_text()


def test_a_well_formed_report_without_counters_is_a_warning(tmp_path, capsys):
    reports = [
        write(tmp_path, "cobertura.xml", '<coverage lines-valid="x"></coverage>'),
        write(tmp_path, "jacoco.xml", '<report><counter type="LINE" covered="1"/></report>'),
        write(tmp_path, "other.xml", "<html></html>"),
    ]

    code = main([f"m{i}={path}" for i, path in enumerate(reports)], summary_path=None)

    assert code == 0
    assert capsys.readouterr().out.splitlines() == [
        f"::warning title=Coverage m{i}::no coverage report at {path}"
        for i, path in enumerate(reports)
    ]


def test_an_argument_without_a_name_is_a_usage_error(tmp_path, capsys):
    code = main([str(tmp_path / "coverage.xml")], summary_path=None)

    assert code == 2
    assert "usage: coverage_summary.py NAME=REPORT.xml ..." in capsys.readouterr().err
