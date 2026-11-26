"""Fail `make coverage-ios` below 100% lines for the app target of an `xccov` JSON report."""

import argparse
import json
import sys
from fnmatch import fnmatch
from pathlib import Path

APP_TARGET = "SharedCoreBench.app"


def read_exclusions(path: Path) -> list[str]:
    """One file pattern per line, relative to the root, with the reason after '#'."""
    patterns = (line.partition("#")[0].strip() for line in path.read_text().splitlines())
    return [pattern for pattern in patterns if pattern]


def gated_files(report: dict, root: Path, exclusions: list[str]) -> dict[Path, dict]:
    (app,) = (target for target in report["targets"] if target["name"] == APP_TARGET)
    files = {Path(file["path"]).relative_to(root): file for file in app["files"]}
    return {
        name: file
        for name, file in files.items()
        if not any(fnmatch(str(name), pattern) for pattern in exclusions)
    }


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("report", type=Path)
    parser.add_argument(
        "--root", type=Path, required=True, help="the directory files are named from"
    )
    parser.add_argument("--exclusions", type=Path, required=True)
    args = parser.parse_args(argv)
    files = gated_files(
        json.loads(args.report.read_text()), args.root, read_exclusions(args.exclusions)
    )
    covered = sum(file["coveredLines"] for file in files.values())
    total = sum(file["executableLines"] for file in files.values())
    print(f"coverage-ios: {covered}/{total} lines ({100 * covered / total:.1f}%)")
    under = {
        name: file for name, file in files.items() if file["coveredLines"] < file["executableLines"]
    }
    for name, file in under.items():
        lines = f"{file['coveredLines']}/{file['executableLines']}"
        print(f"coverage-ios: {name} has {lines} lines covered")
    return 1 if under else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
