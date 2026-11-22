import json
import sys
from pathlib import Path

VERDICTS = ("APPROVE", "REQUEST_CHANGES")
REQUIRED_TOOLS = {"mcp__github_inline_comment__create_inline_comment"}
REQUIRED_COMMANDS = ("gh pr view", "gh pr diff", "gh issue view")


def run_result(path: Path) -> dict | None:
    try:
        messages = json.loads(path.read_text())
    except (OSError, ValueError):
        return None
    results = [m for m in messages if isinstance(m, dict) and m.get("type") == "result"]
    return results[-1] if results else None


def is_required(denial: dict) -> bool:
    if denial.get("tool_name") in REQUIRED_TOOLS:
        return True
    command = (denial.get("tool_input") or {}).get("command") or ""
    # Anywhere in the command: a chain that includes the diff still means the diff was refused.
    return any(required in command for required in REQUIRED_COMMANDS)


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
    # Unlisted denials can't be told apart, so they count as required.
    fatal = count > len(denials) or any(map(is_required, denials))
    if count:
        kind = "" if fatal else "optional "
        print(f"{count} {kind}tool calls were denied during the review:")
        for denial in denials:
            print(f"  {describe(denial)}")
    if fatal:
        # Refused a gather or report tool, the review may not have read the diff or left its
        # inline findings, whatever verdict it returned.
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
