from pathlib import Path

from spec_contract import missing_calls

ROOT = Path(__file__).resolve().parents[2]

README = """\
| Field | Meaning |
|---|---|
| `step` | The contract call: `ingest`, `page` or `stats` (see `SPEC.md`, "Shared core contract") |
| `args` | The call's parameters by name; `body` is a string. |
"""

SPEC = """\
### Shared core contract

```
ingest(requestId, status, body: bytes): IngestResult
stats(): CoreStats                     // page counts
```

### Storage port

```
page(query: Query, cursor: Cursor?, size: Int): [ProductRow]
```
"""


def test_a_step_call_missing_from_the_contract_block_is_reported_by_name():
    assert missing_calls(SPEC, README) == ["page"]


def test_the_spec_contract_block_names_every_call_in_the_contract_readme_step_row():
    spec = (ROOT / "SPEC.md").read_text()
    readme = (ROOT / "contract" / "README.md").read_text()

    assert missing_calls(spec, readme) == []
