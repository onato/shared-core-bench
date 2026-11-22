import os
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

USAGE = "usage: coverage_summary.py NAME=REPORT.xml ..."
NO_REPORT = "no report"


def ratio(covered: int, total: int) -> str:
    if total == 0:
        return "none"
    return f"{covered}/{total} ({100 * covered / total:.1f}%)"


def read_report(path: Path) -> tuple[str, str] | None:
    """Line and branch coverage from a Cobertura (coverage.py) or JaCoCo (Kover) XML report."""
    try:
        root = ET.parse(path).getroot()
    except (OSError, ET.ParseError):
        return None
    if root.tag == "coverage":
        lines = ratio(int(root.get("lines-covered")), int(root.get("lines-valid")))
        branches = ratio(int(root.get("branches-covered")), int(root.get("branches-valid")))
        return lines, branches
    # JaCoCo repeats counters per package and class; the report-level ones are the totals.
    counters = {c.get("type"): c for c in root.findall("counter")}

    def counter(kind: str) -> str:
        covered, missed = int(counters[kind].get("covered")), int(counters[kind].get("missed"))
        return ratio(covered, covered + missed)

    return counter("LINE"), counter("BRANCH")


def main(argv: list[str], summary_path: Path | None) -> int:
    modules = [arg.partition("=") for arg in argv]
    if not modules or any(not name or not sep for name, sep, _ in modules):
        print(USAGE, file=sys.stderr)
        return 2
    rows = []
    for name, _, report in modules:
        result = read_report(Path(report))
        if result is None:
            print(f"::warning title=Coverage {name}::no coverage report at {report}")
            result = (NO_REPORT, NO_REPORT)
        else:
            print(f"::notice title=Coverage {name}::lines {result[0]}, branches {result[1]}")
        rows.append(f"| {name} | {result[0]} | {result[1]} |\n")
    if summary_path is not None:
        with summary_path.open("a") as summary:
            summary.write("## Coverage\n\n| Module | Lines | Branches |\n|---|---|---|\n")
            summary.writelines(rows)
    return 0


if __name__ == "__main__":
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    sys.exit(main(sys.argv[1:], Path(summary) if summary else None))
