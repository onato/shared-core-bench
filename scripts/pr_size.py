import argparse
import json
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import TextIO

from gates import approved_by, load_patterns, matches_any

APPROVAL_LABEL = "size-approved"


@dataclass
class Result:
    passed: bool
    total: int
    largest: list[tuple[str, int]]


def check(numstat: str, excludes: list[str], limit: int) -> Result:
    sizes: dict[str, int] = {}
    for line in numstat.splitlines():
        added, deleted, path = line.split("\t", 2)
        binary = added == "-"
        if not binary and not matches_any(path, excludes):
            sizes[path] = int(added) + int(deleted)
    total = sum(sizes.values())
    largest = sorted(sizes.items(), key=lambda item: item[1], reverse=True)
    return Result(passed=total <= limit, total=total, largest=largest)


def main(argv: list[str], stdin: TextIO = sys.stdin) -> int:
    parser = argparse.ArgumentParser(description="Fail when a PR changes too many lines.")
    parser.add_argument("--limit", type=int, default=400)
    parser.add_argument("--exclude-file", type=Path, default=Path(".github/pr-size-exclude"))
    parser.add_argument("--approver", help="the only login whose size-approved label counts")
    parser.add_argument("--label-events", type=Path, help="the PR's issue events, as JSON")
    args = parser.parse_args(argv)

    excludes = load_patterns(args.exclude_file.read_text()) if args.exclude_file.exists() else []
    result = check(stdin.read(), excludes=excludes, limit=args.limit)
    events = json.loads(args.label_events.read_text()) if args.label_events else []
    approved = not result.passed and approved_by(events, APPROVAL_LABEL, args.approver)

    approval = f": approved by {args.approver} ({APPROVAL_LABEL})" if approved else ""
    print(f"{result.total} changed lines, limit {args.limit}{approval}")
    for path, size in result.largest[:10]:
        print(f"  {size:6d}  {path}")
    return 0 if result.passed or approved else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
