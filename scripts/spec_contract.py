"""Contract calls that `contract/README.md`'s `step` row names but `SPEC.md`'s block leaves out."""

import re

STEP_ROW = re.compile(r"^\| `step` \| (.*)$", re.MULTILINE)
CONTRACT_BLOCK = re.compile(
    r"^### Shared core contract\n.*?^```\n(.*?)^```", re.MULTILINE | re.DOTALL
)
CALL = re.compile(r"`(\w+)`")


def step_calls(contract_readme: str) -> list[str]:
    return CALL.findall(STEP_ROW.search(contract_readme).group(1))


def contract_block(spec: str) -> str:
    return CONTRACT_BLOCK.search(spec).group(1)


def missing_calls(spec: str, contract_readme: str) -> list[str]:
    block = contract_block(spec)
    return [call for call in step_calls(contract_readme) if f"{call}(" not in block]
