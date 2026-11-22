# Contract fixtures

Behaviour shared by every core is specified once, here, as JSON in / JSON out. Each core runs the
same cases natively, so no fixture may depend on how one language models a type.

## Layout

`contract/cases/*.json` — one file per case. The file name, without `.json`, is the test name, so
name it for the behaviour (`paging-stops-when-remote-has-no-more.json`). Feature fixtures arrive
with the issues that implement the feature.

## A case

```json
{
  "steps": [
    { "step": "ingest", "args": { "requestId": 1, "status": 200, "body": "{\"products\": []}" },
      "expect": { "accepted": 0, "rejected": 0 } },
    { "step": "page", "args": { "size": 20 }, "expect": { "items": [] } },
    { "step": "nextFetch", "expect": null },
    { "step": "stats", "expect": { "rows": 0 } }
  ]
}
```

Every case starts from a fresh core with an empty database. Steps run in order against that core.

| Field | Meaning |
|---|---|
| `step` | The contract call: `ingest`, `page`, `nextFetch` or `stats` (see `SPEC.md`, "Shared core contract") |
| `args` | The call's parameters by name; omit when it takes none. Byte arguments such as `ingest`'s `body` are UTF-8 strings. |
| `expect` | The exact JSON the call must return; `null` for "nothing". |

The result shapes in the example are illustrative: each step's result is fixed by the issue that
adds its first fixture.

## Step results

| Step | Result |
|---|---|
| `ingest` | `{"accepted": n, "rejected": m}` — a product without a numeric `price` is rejected and counted, unknown fields are ignored. `{"error": "invalid-json"}` when the body isn't JSON. `requestId` and `status` are passed but not used yet. |

## Runners

A core's runner turns each case file into its own test, sends every step to the core and compares
the result with `expect` as JSON values, so key order and whitespace don't matter. It fails:

- on the first step whose result differs, naming the file, step index and step type, with the
  expected and actual JSON pretty-printed;
- on a step type it doesn't know, naming the file and step index.

| Core | Runner |
|---|---|
| KMP (JVM) | `core-kmp/src/jvmTest/kotlin/bench/contract/ContractRunner.kt`, run over `contract/cases` by `bench/core/KmpContractTest.kt` |
