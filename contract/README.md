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

Every case starts from a fresh core with an empty database, configured with the base URL
`https://dummyjson.com/products` and a page size of 20. Steps run in order against that core.

| Field | Meaning |
|---|---|
| `step` | The contract call: `ingest`, `page`, `detail`, `nextFetch`, `refresh` or `stats` (see `SPEC.md`, "Shared core contract") |
| `args` | The call's parameters by name; omit when it takes none. Byte arguments such as `ingest`'s `body` are UTF-8 strings. |
| `expect` | The exact JSON the call must return; `null` for "nothing". |

The result shapes in the example are illustrative: each step's result is fixed by the issue that
adds its first fixture.

## Step results

| Step | Result |
|---|---|
| `ingest` | `{"accepted": n, "rejected": m}` — a product that isn't an object, has no integer `id` or `stock`, no string `title`, `description`, `category` or `thumbnail`, a `price`, `discountPercentage` or `rating` that isn't a JSON number, or `tags` that aren't an array of strings, is rejected and counted; a missing `brand` is stored as `null`; unknown fields are ignored. Accepted products are upserted by `id` in one transaction, replacing their tags. `{"error": "invalid-json"}` when the body isn't JSON; `{"error": "not-a-product-page"}` when it is JSON but not an object with a `products` array. `{"error": "unknown-request"}` when `requestId` isn't the pending request's id; `{"error": "http-status", "status": n}` when `status` isn't 2xx. Only a stored page completes the pending request; after any error it stays pending. |
| `nextFetch` | `{"requestId": n, "url": s}` — the pending request: `<baseUrl>?limit=<page size>&skip=<skip>`, starting at id 1 and skip 0. Each stored page moves to the next id and `skip` + page size. `null` once that reaches the `total` the API last reported (a page without `total` leaves it unknown). |
| `refresh` | `null`. Starts a new walk: the pending request moves to the next id at skip 0. When the page that brings a walk to its end is stored, every product that walk did not return is soft-deleted; a walk that stops before its end deletes nothing. A soft-deleted product stays stored but drops out of `page` and `detail` until a later page returns it again. |
| `page` | `{"items": [{"id": n, "title": s, "price": x, "discountedPrice": x, "rating": x, "thumbnail": s}, …], "nextCursor": n}` — summaries of up to `args.size` stored products that aren't soft-deleted (`discountedPrice` as in `detail`) in `args.query.sort` order, starting after `args.cursor`, the `id` of the previous page's last item (from the first product when it is `null`). With the `id` sort any cursor works; with the others it must be a stored product's `id`. `args.query` is optional, and so is each of its fields; the products kept are those every given field keeps. `text: s` keeps products whose `title` or `brand` contains `s`, ignoring ASCII case, with `%` and `_` taken literally; an empty `text` keeps every product. `category: s` keeps products whose `category` is exactly `s`. `minPrice: x` and `maxPrice: x` keep products whose `discountedPrice` is at least / at most `x`. `sort` is `"id"` (the default, ascending), `"price-asc"` or `"price-desc"` (by `discountedPrice`) or `"rating-desc"`; ties order by ascending `id`. `nextCursor` is the last item's `id` when more products follow, `null` otherwise. `{"error": "invalid-page-size"}` when `size` ≤ 0. |
| `detail` | `{"id": n, "title": s, "description": s, "category": s, "brand": s\|null, "price": x, "discountPercentage": x, "discountedPrice": x, "rating": x, "ratingBucket": s, "stock": n, "stockStatus": s, "thumbnail": s, "tags": [s, …]}` for the stored product with `args.id`, tags in the order ingested; `null` when there is none or it is soft-deleted. Three fields are derived: `discountedPrice` is `price × (1 − discountPercentage / 100)` rounded half up to cents, computed so binary floating point cannot shift a half cent (9.99 at 7.17% → 9.27, 0.05 at 50% → 0.03); `ratingBucket` is `"excellent"` for a rating ≥ 4.5, `"good"` ≥ 4.0, `"fair"` ≥ 3.0, else `"poor"`; `stockStatus` is `"out-of-stock"` for 0, `"low-stock"` for 1–9, `"in-stock"` for 10 and more. |
| `stats` | `{"productCount": n, "deletedCount": m}` — the number of distinct products stored, soft-deleted ones included, and how many of them are soft-deleted. |

## Changing a result or a required field

Runners compare whole results, and every product body must pass `ingest`, so a shape change
reaches well past the new fixture. Count these into the issue's size before starting:

- **A field added to a step's result:** every case with that step (`grep -l '"step": "detail"'
  contract/cases/*`), the step's row above, and both contract adapters:
  `core-kmp/src/jvmTest/kotlin/bench/core/KmpContractCore.kt` and the app's
  `android/app/src/test/kotlin/bench/android/core/SharedCoreContract.kt`. The app runs these cases
  through its adapter, so its own type in `android/app/src/main/kotlin/bench/android/core/`
  (`SharedCore.kt`, mapped in `KmpSharedCore.kt`) gains the field too.
- **A new required input field:** every product body in `contract/cases`, the core's tests that
  ingest products (`IngestStorageTest`, `AndroidDriversTest`), the app's adapter tests
  (`KmpSharedCore*Test`, `FakeSharedCoreTest`, `Summaries.kt`) and the benchmark's
  `android/fixtures/src/main/kotlin/bench/fixtures/ProductFixtures.kt`.
- **A new argument to a step** (a `page` query field, say): the core's own type for it in
  `core-kmp/src/commonMain/kotlin/bench/core/` (`Query.kt`), both contract adapters, and the app's
  `SharedCore.kt` with `KmpSharedCore.kt` and the test `FakeSharedCore.kt`, because the app runs
  every case too. Both adapters read an optional argument with `args["name"]` and a default, never
  `getValue`: existing cases omit it, and `getValue` fails them with `Key … is missing` before the
  core runs.

## Runners

A core's runner turns each case file into its own test, sends every step to the core and compares
the result with `expect` as JSON values, so key order and whitespace don't matter. Numbers are
compared as written, though: the cores return prices as doubles, so a whole-number price is
written `10.0` in `expect`, never `10`. It fails:

- on the first step whose result differs, naming the file, step index and step type, with the
  expected and actual JSON pretty-printed;
- on a step type it doesn't know, naming the file and step index.

| Core | Runner |
|---|---|
| KMP (JVM) | `core-kmp/contract-runner/src/main/kotlin/bench/contract/ContractRunner.kt`, run over `contract/cases` by `core-kmp/src/jvmTest/kotlin/bench/core/KmpContractTest.kt` |
