import argparse
import sys
from dataclasses import dataclass
from fnmatch import fnmatchcase
from pathlib import Path
from typing import TextIO


@dataclass
class Result:
    passed: bool
    total: int
    largest: list[tuple[str, int]]


def is_excluded(path: str, patterns: list[str]) -> bool:
    return any(_matches(path, pattern) for pattern in patterns)


def _matches(path: str, pattern: str) -> bool:
    # gitignore rules: a slash at the start or in the middle anchors the pattern at the root;
    # a trailing slash matches directories only. fnmatch's `*` crosses `/`, which gives `**`.
    directory_only = pattern.endswith("/")
    body = pattern.strip("/")
    if pattern.startswith("/") or "/" in body:
        return fnmatchcase(path, body + "/*" if directory_only else body)
    parts = path.split("/")
    candidates = parts[:-1] if directory_only else parts
    return any(fnmatchcase(part, body) for part in candidates)


def check(numstat: str, excludes: list[str], limit: int) -> Result:
    sizes: dict[str, int] = {}
    for line in numstat.splitlines():
        added, deleted, path = line.split("\t", 2)
        binary = added == "-"
        if not binary and not is_excluded(path, excludes):
            sizes[path] = int(added) + int(deleted)
    total = sum(sizes.values())
    largest = sorted(sizes.items(), key=lambda item: item[1], reverse=True)
    return Result(passed=total <= limit, total=total, largest=largest)


def load_excludes(text: str) -> list[str]:
    lines = (line.strip() for line in text.splitlines())
    return [line for line in lines if line and not line.startswith("#")]


def main(argv: list[str], stdin: TextIO = sys.stdin) -> int:
    parser = argparse.ArgumentParser(description="Fail when a PR changes too many lines.")
    parser.add_argument("--limit", type=int, default=400)
    parser.add_argument("--exclude-file", type=Path, default=Path(".github/pr-size-exclude"))
    args = parser.parse_args(argv)

    excludes = load_excludes(args.exclude_file.read_text()) if args.exclude_file.exists() else []
    result = check(stdin.read(), excludes=excludes, limit=args.limit)

    print(f"{result.total} changed lines, limit {args.limit}")
    for path, size in result.largest[:10]:
        print(f"  {size:6d}  {path}")
    return 0 if result.passed else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
