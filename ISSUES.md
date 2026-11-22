# Issues

Get one thin slice working end to end, fully tested, behind a reviewed-PR gate — then grow it.
Only the next milestone is broken into tasks; later work stays at feature level until we reach it,
and the checkpoint at the end of each milestone writes the next milestone's tasks with what we
learned.

## How we work

- **One issue = one PR = one worktree.** PRs stay under **400 changed lines** (generated code,
  lockfiles and fixture data excluded). An issue that won't fit is split before work starts.
- **Every PR goes through** CI (lint, tests, coverage gate), the PR-size check and the Claude
  review. `main` takes no direct pushes, no force pushes, and nobody can bypass the rules.
- **Board** (GitHub Projects): Todo → In progress → In review → Done. GitHub's built-in project
  workflows move items on PR open and merge; the agent moves Todo → In progress when it starts.
- **Next issue**: the lowest-numbered open Todo issue in the current milestone whose blockers are
  closed. Each issue lists its tests to write first; "go" means the next unchecked test in the
  current issue (Beck's `plan.md` loop, one file per issue instead of one per repo).
- Hierarchy uses GitHub sub-issues: one parent issue per milestone, tasks as its sub-issues, and
  GitHub issue dependencies ("blocked by") for order.
- Board: https://github.com/users/onato/projects/2

---

## M0 — Gatekeeping

Lands before any code, so the first line of product code is already reviewed.

1. **PR size check** — workflow fails a PR over 400 changed lines; exclusions in
   `.github/pr-size-exclude`. Repo scripts are Python 3 + pytest, held to the same 100% gate.
2. **Claude PR review** — `anthropics/claude-code-action` reviews every PR against `CLAUDE.md` and
   `SPEC.md`, posts inline comments, and fails its check when it requests changes.
3. **Protect `main`** — ruleset: PRs required, required checks (size, review, CI once it exists),
   no force push or deletion, no bypass. Updated as each required check lands.

## M1 — Walking skeleton: Android + KMP

The thinnest version of the real thing: the app fetches pages from an API, the KMP core decodes
and stores them in SQLite, and the list pages through them from the database. Products have only
`id`, `title` and `price`. 100% coverage from the first PR.

1. **KMP module and CI** — Gradle KMP (jvm, android; ios target added in M3), Kover 100% gate,
   ktlint, detekt, `make test/lint/coverage` for it, CI workflow; one trivial test to prove the gate
   fails when coverage drops.
2. **Contract format** — JSON fixture format, runner on the JVM, first fixture: decode a page.
3. **Decode products** — minimal fields; malformed items are skipped and counted, not fatal.
4. **Store products** — SQLDelight schema, upsert by id inside one transaction.
5. **Read a page** — `page(cursor, size)`, keyset paging by id.
6. **Remote paging** — `nextFetch()` / `ingest()`: offset paging against the API, end of data.
7. **Android app shell** — Compose app module, Kover gate, ktlint, Robolectric, empty screen test;
   joins `make` and CI.
8. **`SharedCore` interface + KMP adapter** — app-level interface, fake for app tests.
9. **Fetcher** — OkHttp executes `FetchRequest`s; tested with MockWebServer.
10. **Paging source and remote loading** — Paging 3 `PagingSource` over `SharedCore.page()`, plus
    fetch → ingest → invalidate when local data runs out.
11. **Product list screen** — Compose list with loading, error-with-retry and empty states;
    Robolectric Compose tests.
12. **First benchmark** — microbenchmark module, one workload (ingest 1 000 products), and
    `make bench PLATFORM=android CORE=kmp` printing the results table.
13. **Checkpoint** — what was hard, what the gates caught, PR sizes; write M2's tasks.

At the end of M1 there is a running app, a passing contract suite, the coverage gate, a working
`make bench`, and every change since M0 has been reviewed.

---

## Later milestones (tasks written at each checkpoint)

- **M2 — Android + KMP complete**: full product model, derived fields, search and filters, detail
  screen, refresh with soft delete, settings picker, delegated-storage port, instrumented UI tests,
  Macrobenchmark, full bench suite.
- **M3 — iOS + KMP**: iOS app with the same features, XCFramework + SKIE, Swift storage port,
  XCTest benches.
- **M4 — Android + Rust/UniFFI**: Rust core passing the contract suite, cargo-ndk, Kotlin bindings.
- **M5 — iOS + Rust/UniFFI**: XCFramework, Swift bindings. UniFFI verdict.
- **M6 — iOS + Lua**: Lua core, C bridge, Swift wrapper.
- **M7 — Android + Lua**: JNI bridge.
- **M8 — Report**: HTML report, physical-device runs, verdict per hypothesis.
