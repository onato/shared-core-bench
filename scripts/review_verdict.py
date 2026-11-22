import sys
from pathlib import Path

VERDICTS = ("APPROVE", "REQUEST_CHANGES")


def main(argv: list[str]) -> int:
    path = Path(argv[0])
    if not path.exists():
        print(f"no verdict: {path} was not written")
        return 1
    verdict, _, summary = path.read_text().partition("\n")
    verdict = verdict.strip()
    if verdict not in VERDICTS:
        print(f"no verdict: expected {' or '.join(VERDICTS)} in {path}")
        return 1
    if verdict == "APPROVE":
        return 0
    print(summary.strip())
    return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
