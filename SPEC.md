# Shared Core Bench — KMP vs UniFFI (Rust) vs Lua

## Goal

Decide which technology to use for business logic shared between an iOS and an Android app, using
evidence instead of reputation. One demo app per platform, one shared core implemented three times
behind the same contract, and a benchmark screen that runs identical workloads against each core.

Questions to answer:

1. **Boundary cost.** What does crossing native ↔ core cost per call, and per KB of data? This is
   where UniFFI's reputation comes from, so it gets measured on its own, not just end to end.
2. **Work cost.** How fast is JSON decoding, business logic and SQLite work inside each core?
3. **End-to-end cost.** Does any of it matter at the scale of a real screen (load a page, scroll)?
4. **Everything else.** Binary size, cold-start/init time, memory, build time, debuggability, and how
   much hand-written glue each option needs.

## Hypotheses to test

| Claim | Why it might be true | Why it might not matter |
|---|---|---|
| UniFFI is slow | Every non-primitive argument is serialized into a `RustBuffer` and copied on each crossing. On Android, Kotlin bindings call through **JNA**, whose per-call overhead is far above JNI. Callback interfaces add a crossing per callback. | Overhead is per call, not per item. A coarse API (`ingest(bytes)`, `page(n)` → 20 rows) crosses a handful of times per screen. Swift bindings call C directly and are cheap. |
| Lua is slowest | Interpreted; LuaJIT's JIT is unavailable on iOS (no executable memory), so it's plain interpreter. Pure-Lua JSON is slow. Every bridge is hand-written C/JNI. | If JSON and SQLite are done by C libraries (lua-cjson, lsqlite3) Lua only orchestrates; the hot loops are native. |
| KMP is "free" | On Android it's plain JVM bytecode — no boundary at all. | On iOS, Kotlin/Native bridges through Objective-C: `String` and collections are converted/copied, `suspend`/`Flow` need wrappers (SKIE), and K/N's GC behaves differently from ARC. |

The benchmark must be able to prove each of these wrong.

## Demo app

A small offline-first **product catalogue**: a paged list, a detail screen, pull-to-refresh, and a
search/filter box. Native UI (SwiftUI, Jetpack Compose); everything below the UI lives in the core.

### Data source

Default: **`https://dummyjson.com/products?limit=&skip=`** — real offset paging, ~190 records with
nested objects (reviews, dimensions, tags), which gives the JSON decoder real work.

For benchmarks the network is taken out of the measurement: a **fixture mode** serves generated,
deterministic pages (seeded, configurable record count and payload size, up to e.g. 10 000 records)
from bundled files or a tiny local server, so runs are repeatable and comparable across cores.

### Networking stays native

All three cores use the same pattern, so networking is identical and the boundary is crossed in a
realistic place:

```
Core decides WHAT to fetch     →  FetchRequest { url, headers }
Native fetches it              →  URLSession / OkHttp
Core gets the raw bytes        ←  ingest(requestId, statusCode, body: bytes)
```

The core never links an HTTP stack. (A KMP-only variant using Ktor in common code can be added later
as an extra data point; it is not part of the fair comparison.)

### Shared core contract

The same logical API in all three, kept deliberately coarse:

```
init(dbPath, config)
nextFetch(): FetchRequest?             // paging/remote-mediator logic lives here
ingest(requestId, status, body: bytes): IngestResult   // decode → validate → transform → upsert
page(query: Query, cursor: Cursor?, size: Int): Page   // reads only from the DB
detail(id): Product?
refresh(): void                        // invalidates and resets remote paging state
stats(): CoreStats                     // row counts, last sync, timings
```

Each call's JSON result shape and error codes are in `contract/README.md`.

`Page` = `{ items: [ProductSummary], nextCursor? }`. Summaries are small (id, title, price,
discountedPrice, rating, thumbnail) — the shape a list cell needs — so pages stay cheap to cross.

### Business logic in the core (so there is something to share)

- Decode + validate the payload; reject/record malformed items rather than failing the page.
- Derived fields: discounted price (decimal-safe rounding), rating bucket, stock status.
- Upsert with dedupe by id; keep a `syncedAt` for staleness; soft-delete items missing after refresh.
- Query: free-text search on title/brand, filter by category and price range, sort by price/rating.
- **Paging**: keyset paging over the local DB (`WHERE (sort_key, id) > (?, ?) LIMIT ?`, not
  `OFFSET`), plus remote-mediator logic — when the local cursor reaches the end of cached data and
  the remote source has more, `nextFetch()` returns the next remote page.

### Per-technology stack

| | KMP | UniFFI (Rust) | Lua |
|---|---|---|---|
| Language | Kotlin 2.x, common code | Rust stable | Lua 5.4 |
| JSON | kotlinx.serialization (`JsonElement` tree, no generated serializers) | serde / serde_json | lua-cjson (C); pure-Lua dkjson as a second data point |
| SQLite | SQLDelight | rusqlite, `bundled` feature | lsqlite3 binding over SQLite |
| iOS bridge | K/N framework via ObjC (+ SKIE) | UniFFI Swift bindings (C ABI) | Hand-written C + Swift wrapper |
| Android bridge | none — JVM library | UniFFI Kotlin bindings (JNA) | Hand-written JNI + Kotlin wrapper |
| Paging on Android UI | AndroidX Paging 3 `PagingSource` wrapping `page()` | same | same |

To keep the SQLite comparison fair, all three use the same SQLite version bundled into the core
rather than the OS copy (noted as a deviation if a library forces otherwise).

## Benchmarks

Run from a Benchmark screen in each app (and from a headless instrumented test / XCTest for CI-style
runs). Release builds, physical devices where possible, warm-up iterations discarded, median and
p95 over N runs, results exported as CSV.

**Boundary micro-benchmarks** (isolate the claim about UniFFI):

1. No-op call × 100 000.
2. String round-trip: 16 B, 1 KB, 100 KB.
3. Bytes in: 1 KB, 100 KB, 1 MB (the `ingest` shape).
4. Return a list of N records (N = 20, 200, 2 000) — the `page` shape.
5. Callback from core to native × 10 000 (UniFFI callback interface, K/N lambda, Lua→C→host).

**Core workloads**:

6. Decode N products from JSON (N = 100, 1 000, 10 000).
7. Ingest N products: decode + transform + upsert in one transaction.
8. Page through 10 000 rows, 20 at a time, with and without a search filter.
9. Search query latency on a 10 000-row DB.

**App-level**:

10. Cold start to first page rendered (from cache), and with an empty cache in fixture mode.
11. Scroll the full list (frame drops via Android Macrobenchmark / iOS Instruments hitches).

**Static**: added binary size per ABI/arch, peak memory during workload 7, clean and incremental
build time, lines of hand-written glue.

Results go into a single report with a chart per workload and a short verdict per hypothesis.

### Side experiment: delegated storage

The main design keeps SQLite inside the core. This experiment measures the alternative — the app
owns the database and the core reaches it through a storage port — to put a number on how much each
core's boundary costs when storage crosses it. It is reported separately and does not change the
main comparison.

**Storage port** (implemented natively; the core calls out through a callback interface — a UniFFI
callback interface, a Kotlin interface on K/N, host functions registered into Lua):

```
upsert(items: [ProductRow])                 // batched: one call per ingested page
upsertOne(item: ProductRow)                 // chatty: one call per item
query(query: Query, cursor: Cursor?, size: Int): [ProductRow]
get(id): ProductRow?
markMissingDeleted(syncedBefore: Timestamp)
transaction(block)                          // begin/commit around an ingest
```

The core keeps all decision logic (validation, derived fields, dedupe, which rows to write, paging
cursors); only SQL execution moves to the app. The native implementations use the **same schema and
SQL** as the embedded cores, on raw SQLite (the SQLite3 C API from Swift; `sqlite-android` on
Android, so the SQLite version is also the bundled one), so the difference measured is the boundary
and not a different database layer.

**Variants**, each run against all three cores on both platforms:

| Variant | Storage | Crossings per ingest of N items |
|---|---|---|
| Embedded (baseline) | SQLite in core | 1 |
| Delegated, batched | App, `upsert(items)` | ~2 (+ transaction begin/commit) |
| Delegated, chatty | App, `upsertOne` | N + 2 |

**Workloads**: repeat benchmarks 7 (ingest N = 100, 1 000, 10 000), 8 (page through 10 000 rows) and 9
(search) for each variant. The page read crosses back with a list of rows, so it also exercises the
"return N records" cost from benchmark 4 in a real setting.

**Reported as** the overhead over the embedded baseline per core and platform (ms and %), plus the
added glue (lines of port code per platform). Expected outcome to confirm or refute: batched
delegation is close to embedded for all cores, while chatty delegation exposes the per-call cost —
worst for UniFFI on Android (JNA) and Lua, and zero-cost for KMP on Android.

## Repository layout

```
shared-core-bench/
  SPEC.md
  CLAUDE.md            # TDD rules, coverage gates, project commands
  ISSUES.md            # delivery milestones, PR gates, workflow
  Makefile             # test, lint, coverage, bench, bench-report
  scripts/             # coverage-gate, bench-collect, report builder
  contract/            # the API above, golden fixtures, expected outputs
  core-kmp/            # Kotlin Multiplatform library; its own Gradle build with its own wrapper
                       #   contract-runner/: the JVM fixture runner the app's tests reuse
  core-rust/           # Rust crate + uniffi.toml
  core-lua/            # Lua sources + C/JNI/Swift bridge
  fixtures/            # deterministic data generator + local fixture server (M1's generator is
                       #   android/fixtures' ProductFixtures, until another core needs it)
  ios/                 # SwiftUI app, backend picker, benchmark screen
  android/             # Compose app, backend picker, benchmark screen; its own Gradle build
  report/              # CSV results + generated report
  docs/checkpoints/    # one retro per milestone
```

Each core and app builds from its own directory, so the root holds only docs, the Makefile and
`scripts/`. The Android build pulls in the KMP core as a composite build
(`includeBuild("../core-kmp")`) rather than sharing one root Gradle build.

The apps pick a core at runtime from a settings toggle, so every core is benchmarked inside the same
host app on the same device.

## Running benchmarks

One command runs the full matrix — 2 platforms × 3 cores × 3 storage variants — or any slice of it:

```
make bench                                         # everything
make bench PLATFORM=android CORE=rust STORAGE=chatty
make bench SUITE=boundary                          # just the FFI micro-benchmarks
make bench-report                                  # HTML report from saved results
```

- **iOS**: a `BenchTests` XCTest target run with `xcodebuild test` in the Release configuration on
  `IOS_DEVICE` or a leased `SIM_UDID`. The harness emits one JSON line per measurement.
- **Android**: an `androidx.benchmark` microbenchmark module for the boundary and core suites and a
  Macrobenchmark module for startup and scrolling, run with `connectedReleaseAndroidTest` on
  `ANDROID_SERIAL`. Emulator runs need `androidx.benchmark.suppressErrors=EMULATOR` and are labelled
  as emulator results.
- `scripts/bench_collect.py` gathers both platforms' output into `report/results/<timestamp>/` in one
  schema (platform, device, OS, core, storage, suite, workload, N, median, p95, runs, git SHA), and
  prints a table grouped by workload with each core side by side, plus overhead over the embedded
  baseline for the delegated-storage rows.

## Testing

Developed test-first throughout; `CLAUDE.md` holds the rules and each issue's "Tests to write first" checklist the test list.

**Coverage gates** (`make coverage` fails below them): 100% lines for every core and both apps, 95%
branches where the tool measures them. Exclusions only for generated code, entry points and
previews, each listed with a reason.

| Module | Tool |
|---|---|
| core-kmp | Kover (`koverVerify`), JVM target — Kotlin/Native has no coverage tool, so `iosMain` stays thin |
| core-rust | cargo-llvm-cov `--fail-under-lines 100` |
| core-lua | busted + luacov; llvm-cov for the C bridge |
| ios | `xcodebuild -enableCodeCoverage YES` + `xccov`, checked by `scripts/coverage-gate` |
| android | Kover for unit/Robolectric tests, JaCoCo for instrumented tests, merged |

- **Contract tests**: one suite of golden fixtures (`contract/`) with expected outputs; each core runs
  them natively (Kotlin tests, `cargo test`, busted for Lua). The same fixtures run again through
  the Swift and Kotlin wrappers to prove the bridges, not just the cores.
- **Storage port**: the contract suite runs a second time with each core wired to the native
  storage ports, and must produce identical results to the embedded run.
- **App**: a few UI tests per platform (first page loads, scroll triggers next page, refresh).
- Built test-first, contract suite before any core implementation.

## Milestones

`ISSUES.md` turns this into delivery milestones: PR gates first, then a walking skeleton of
Android + KMP, then the rest of each combination below.

Built one app/core combination at a time, easiest first. The first combination builds the full test
suite, the benchmark harness and the app; each later one plugs a new core or platform into what
exists, so the remaining unknowns are the bridge and the core, not the app.

0. **Foundations**: `git init`, Makefile with `test`, `lint` and `coverage` gates,
   `contract/` golden fixtures, fixture generator and local fixture server.
1. **Android + KMP**: no boundary, the shortest path to a working app.
   - Full Android app: paged list (Paging 3), detail, refresh, search, backend picker.
   - KMP core passes the contract suite (JVM tests).
   - App tests at the coverage gate; benchmark harness and `make bench PLATFORM=android CORE=kmp`.
   - Delegated-storage port, Android side.
2. **iOS + KMP**: same core, adds the iOS app and the Kotlin/Native → ObjC bridge (SKIE).
   - Full iOS app, tests, benchmark harness, Swift storage port.
3. **Android + Rust/UniFFI**: first new core; adds cargo-ndk, JNA, UniFFI Kotlin bindings.
4. **iOS + Rust/UniFFI**: XCFramework packaging, UniFFI Swift bindings.
   With 3 and 4 done, the UniFFI performance question has its answer.
5. **iOS + Lua**: Swift calls the Lua C API directly; hand-written bridge.
6. **Android + Lua**: hardest — hand-written JNI on top of the C bridge.
7. **Report** across all six combinations and the delegated-storage experiment.

Rules for the sequence:

- **The app talks to an app-level core interface, never to a core's types.** On Android a Kotlin
  `SharedCore` interface, on iOS a Swift protocol; each core gets an adapter behind it. Otherwise the
  KMP-first build bakes KMP types into the app, and every later core is forced to mimic them.
- **The contract stays core-neutral.** Golden fixtures are JSON in/JSON out, not Kotlin types, so
  later cores can't fail a test because of how Kotlin happened to model something.
- **Every combination ends with a checkpoint**: run its benchmarks, write down what was learned,
  update `SPEC.md` and write the next milestone's issues, and decide whether to continue —
  e.g. stop the Lua track if it is clearly unviable after combination 5.

## Prerequisites on this Mac

Present: Xcode, Android SDK + NDK, Lua 5.4 (Homebrew). The newest Android platform installed is
SDK 36, so Android dependencies must accept `compileSdk` 36.
Missing: Rust toolchain (`rustup`, plus `aarch64-apple-ios`, `aarch64-apple-ios-sim`,
`aarch64-linux-android`, `x86_64-linux-android` targets and `cargo-ndk`), and a standalone Kotlin
toolchain is not needed (Gradle brings it).

## Open questions

- Physical devices available for benchmarking, and which models? Simulator numbers are only good
  for relative comparison.
- Is dummyjson acceptable as the live source, or should it mirror a real API from your apps?
- Should the KMP variant also try Swift export (experimental) alongside the ObjC bridge?
