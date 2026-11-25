# Issues

Get one thin slice working end to end, fully tested, behind a reviewed-PR gate — then grow it.
Only the next milestone is broken into tasks; later work stays at feature level until we reach it,
and the checkpoint at the end of each milestone writes the next milestone's tasks with what we
learned.

## How we work

- **One issue = one PR = one worktree.** PRs stay under **400 changed lines** (generated code,
  lockfiles and fixture data excluded). An issue that won't fit is split before work starts.
- **Squash merge only, linear history.** The PR title and description become the commit on
  `main`, so `main` has one green commit per PR. Structural changes an issue needs go in their own
  `Structural:` PR first.
- **Every PR goes through** CI (lint, tests, coverage gate), the PR-size check and the Claude
  review. `main` takes no direct pushes, no force pushes, and nobody can bypass the rules.
- **Board** (GitHub Projects): Todo → In progress → In review → Done. The agent moves Todo → In
  progress when it starts. GitHub's built-in project workflows move the rest, and these must be
  on (project → Workflows): **Item closed** → Done, **Pull request merged** → Done, **Pull
  request linked to issue** → In review, **Auto-add to project** (`is:issue`). The Ralph loop
  refuses to start while any is off, and moves closed issues the board missed to Done.
- **Next issue**: the lowest-numbered open Todo issue in the current milestone whose blockers are
  closed. Each issue lists its contract cases and its tests to write first; "go" means the next
  unchecked test in the current issue (Beck's `plan.md` loop, one file per issue instead of one per
  repo), after its contract cases are written.
- **Issue format** (see `CLAUDE.md` → TDD for the two loops):

  ```markdown
  ## Goal
  ## Contract cases            ← files in contract/cases/, all written first, red together
  - [ ] `<case>.json` — <the behaviour it pins>
  ## Tests to write first      ← unit, app and UI tests, one at a time, each red before its code
  - [ ] <behaviour>
  ## Done when
  - Contract cases seen failing together before any code, and each test seen failing before its
    own code (say so in the PR)
  ## Blocked by
  ```

  Either list may be `none` (say why). Order the tests to write first so each one needs code the
  earlier ones didn't: a test the previous step already satisfies belongs in no list.
- **Before filing an issue**, check these four things. Each one cost a session time when an issue
  left it out:
  - **Where the code goes.** Name the module, and check that the module can depend on what the
    code uses. An Android library can't depend on `:app`, so #123 had to be split partway
    through (#216, #217).
  - **What it waits for.** List under `Blocked by` every issue whose change one of these tests
    needs, including a doc line the test reads. #213 left out #214 and its session sat idle.
  - **How it can fail.** A failure path gets one test for each way it can fail (the block returns
    an error, the commit fails, the driver throws) in each port or core that has the path. #212
    listed no commit-failure test for the Android port, and only review caught the missing
    `ROLLBACK`.
  - **Who calls it.** When the issue changes a signature or a return shape, list the callers
    (`grep -rn '<call>(' core-kmp android`) and size the issue from them. #220 grouped four
    reads, and its session had to split it into three issues before writing code. For each
    caller that puts something on screen, add a test to write first for what the user sees when
    the call fails. #234 and #235 had to guess this.
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
2. **Contract format** — JSON case format, runner on the JVM, first contract case: decode a page.
3. **Decode products** — minimal fields; malformed items are skipped and counted, not fatal.
4. **Store products** — SQLDelight schema, upsert by id inside one transaction.
5. **Read a page** — `page(cursor, size)`, keyset paging by id.
6. **Remote paging** — `nextFetch()` / `ingest()`: offset paging against the API, end of data.
6b. **Ralph loop** (#20) — unattended runs from #7 on: fresh `claude -p` per issue, auto-merge on
    green checks, stops and pings on anything needing a human.
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
`make bench`, and every change since M0 has been reviewed. Done: see `docs/checkpoints/M1.md`.

---

## M2 — Android + KMP complete

Parent #101. Drafted at the M1 checkpoint as `draft` sub-issues; `plan-approved` on #101 releases
them to the loop. Each issue spells out its contract result shapes and rules, since M1's sessions
lost most of their guessing to shapes the issue left open.

1. **No core types past the adapter** (#102) — detekt `ForbiddenImport`, and the moves it needs.
2. **Full product** (#103) and **derived fields** (#104) — discounted price, rating bucket, stock
   status, all in the contract.
3. **Summaries** (#105) — page items become list-cell summaries; **cells** (#106) and the
   **detail screen** (#107) show them.
4. **Search** (#108), **filters** (#109), **sort** (#110) — keyset over `(sort_key, id)`; the
   **search box** (#111) and **filter and sort controls** (#112).
5. **Refresh with soft delete** (#113) and **pull to refresh** (#114).
6. **Delegated storage** — the core's port, batched (#115) and chatty (#116); the Android port on
   `sqlite-android` (#117); the **settings picker** (#118).
7. **Fixture mode** (#119) and **instrumented UI tests** (#120).
8. **Benchmarks** — boundary (#121), core workloads (#122), delegated-storage rows (#123),
   Macrobenchmark (#124).
9. **Checkpoint** (#125).

---

## Retros

Each Ralph iteration leaves a `## Retro notes` comment on its PR. The `retro` skill
(`.claude/skills/retro/SKILL.md`) reads them with the review threads and failed checks: the loop's
improver session after every 5 merged PRs, and the checkpoint at the end of each milestone, which
writes `docs/checkpoints/<milestone>.md`.

- **Default: fix at the checkpoint**, from patterns across the milestone.
- **Fix immediately** when a gate let something bad reach `main`, or the same problem shows up
  twice in a milestone — stop the loop and fix it first.
- **Don't fix** one-off friction or style preferences; leave them in the notes.
- A fix is its own small PR, filed as an issue in the current milestone that blocks the next task.
  Tightening fixes are built by the improver or the checkpoint; anything that loosens a gate or
  changes `SPEC.md` becomes a `retro-proposal` issue for the owner.

---

## Later milestones (tasks written at each checkpoint)

- **M3 — iOS + KMP**: iOS app with the same features, XCFramework + SKIE, Swift storage port,
  XCTest benches.
- **M4 — Android + Rust/UniFFI**: Rust core passing the contract suite, cargo-ndk, Kotlin bindings.
- **M5 — iOS + Rust/UniFFI**: XCFramework, Swift bindings. UniFFI verdict.
- **M6 — iOS + Lua**: Lua core, C bridge, Swift wrapper.
- **M7 — Android + Lua**: JNI bridge.
- **M8 — Report**: HTML report, physical-device runs, verdict per hypothesis.
