"""The iOS line-coverage gate, read from `xccov view --report --json`."""

import json
from pathlib import Path

import coverage_ios

# Captured from `make coverage-ios` on the app shell; paths shortened to /repo and /home.
SAMPLE = Path(__file__).parent / "data" / "xccov_report.json"


def write_report(tmp_path, report):
    path = tmp_path / "report.json"
    path.write_text(json.dumps(report))
    return path


def write_exclusions(tmp_path, text):
    path = tmp_path / "coverage-exclusions"
    path.write_text(text)
    return path


def run(tmp_path, report, exclusions=""):
    args = [str(write_report(tmp_path, report)), "--root", "/repo/ios"]
    return coverage_ios.main([*args, "--exclusions", str(write_exclusions(tmp_path, exclusions))])


def sample():
    return json.loads(SAMPLE.read_text())


def test_a_report_with_every_app_line_covered_passes(tmp_path, capsys):
    status = run(tmp_path, sample())

    assert (status, capsys.readouterr().out) == (0, "coverage-ios: 12/12 lines (100.0%)\n")


def with_root_view_covered(report, covered_lines):
    (app,) = (target for target in report["targets"] if target["name"] == "SharedCoreBench.app")
    (root_view,) = (file for file in app["files"] if file["name"] == "RootView.swift")
    root_view["coveredLines"] = covered_lines
    return report


def test_a_report_with_an_uncovered_app_line_fails_and_names_the_file(tmp_path, capsys):
    status = run(tmp_path, with_root_view_covered(sample(), 3))

    assert (status, capsys.readouterr().out) == (
        1,
        "coverage-ios: 11/12 lines (91.7%)\n"
        "coverage-ios: SharedCoreBench/RootView.swift has 3/4 lines covered\n",
    )


def test_files_the_exclusions_list_are_left_out_of_the_gate(tmp_path, capsys):
    exclusions = (
        "# One file pattern per line, with the reason after '#'.\n*/RootView.swift # a reason\n"
    )

    status = run(tmp_path, with_root_view_covered(sample(), 3), exclusions)

    assert (status, capsys.readouterr().out) == (0, "coverage-ios: 8/8 lines (100.0%)\n")
