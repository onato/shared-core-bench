import json
import sys
from pathlib import Path

VERDICTS = ("APPROVE", "REQUEST_CHANGES")


def run_result(path: Path) -> dict | None:
    try:
        messages = json.loads(path.read_text())
    except (OSError, ValueError):
        return None
    results = [m for m in messages if isinstance(m, dict) and m.get("type") == "result"]
    return results[-1] if results else None


def describe(denial: dict) -> str:
    tool_input = denial.get("tool_input") or {}
    detail = tool_input.get("command") or tool_input.get("file_path") or json.dumps(tool_input)
    return f"{denial.get('tool_name', '?')}: {detail}"


def main(argv: list[str]) -> int:
    path, execution = Path(argv[0]), Path(argv[1])
    result = run_result(execution)
    if result is None:
        print(f"no run result in {execution}")
        return 1
    denials = result.get("permission_denials") or []
    count = max(len(denials), result.get("permission_denials_count") or 0)
    if count:
        # A review that was refused a tool may not have read the diff or posted its findings,
        # whatever verdict it returned.
        print(f"{count} tool calls were denied during the review:")
        for denial in denials:
            print(f"  {describe(denial)}")
        return 1
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
