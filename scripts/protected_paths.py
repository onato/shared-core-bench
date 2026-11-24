import argparse
import json
import sys
from pathlib import Path
from typing import TextIO

from gates import approved_by, load_patterns, matches_any

APPROVAL_LABEL = "gate-approved"


def main(argv: list[str], stdin: TextIO = sys.stdin) -> int:
    parser = argparse.ArgumentParser(description="Fail when a PR changes a protected path.")
    parser.add_argument("--patterns-file", type=Path, default=Path(".github/protected-paths"))
    parser.add_argument("--approver", help="the only login whose gate-approved label counts")
    parser.add_argument("--label-events", type=Path, help="the PR's issue events, as JSON")
    args = parser.parse_args(argv)

    patterns = load_patterns(args.patterns_file.read_text())
    touched = [path for path in stdin.read().splitlines() if matches_any(path, patterns)]
    if not touched:
        print("No protected paths changed.")
        return 0
    events = json.loads(args.label_events.read_text()) if args.label_events else []
    approved = approved_by(events, APPROVAL_LABEL, args.approver)
    if approved:
        print(f"Protected paths changed: approved by {args.approver} ({APPROVAL_LABEL})")
    else:
        print(f"Protected paths changed; the owner's {APPROVAL_LABEL} label is needed:")
    for path in touched:
        print(f"  {path}")
    return 0 if approved else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
