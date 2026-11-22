# Shared Core Bench

Compares KMP, Rust/UniFFI and Lua as a shared business-logic core for an iOS and an Android app.
`SPEC.md` is the source of truth for what gets built; `ISSUES.md` is how it is delivered; the
GitHub issues on `onato/shared-core-bench` are the work queue.

Each issue carries a "Tests to write first" checklist — that is the test list. When I say "go",
find the next unchecked test in the current issue, implement the test, then implement only enough
code to make that test pass. Tick it only when it passes along with every other non-benchmark test.

# Issue and PR workflow

- Next issue: lowest-numbered open issue in the current milestone with status Todo and no open
  blockers. Move it to In progress on the project board when starting.
- One issue = one branch = one worktree (`swilliams/<issue#>-<slug>` from `origin/main`) = one PR
  whose body says `Closes #<n>`.
- Keep PRs under 400 changed lines, excluding paths in `.github/pr-size-exclude`. If an issue
  won't fit, stop and split it into new issues before writing code.
- `main` is protected: never push to it; every change merges through a PR that passes CI, the size
  check and the Claude review. Address review comments with new commits, not force pushes.

The TDD and Tidy First rules below are adapted from Kent Beck's CLAUDE.md for BPlusTree3
(github.com/KentBeck/BPlusTree3, commit 8c494da), extended with this project's coverage and
benchmark rules.

# Core development principles

- Always follow the TDD cycle: Red → Green → Refactor.
- Write the simplest failing test first, run it, and see it fail for the expected reason before
  writing any production code. A test that passes on first run proves nothing — fix the test.
- Implement the minimum code needed to make the test pass.
- Refactor only after tests are passing.
- Separate structural changes from behavioral changes (Tidy First).
- Write one test at a time, make it pass, then improve structure. Run all tests (except
  benchmarks) each time.

# TDD methodology

- Each test defines one small increment of behavior. Name tests for the behavior
  (`pagingStopsWhenRemoteReportsNoMorePages`), not the method.
- Make failures clear and informative: assert on values, not on booleans.
- When fixing a defect, first write an API-level failing test, then the smallest test that
  reproduces the problem, then get both to pass.
- Behavior that is shared across cores is specified once, as a golden fixture in `contract/`, and
  each core gets a failing contract test for it before any core implements it. A core-specific test
  is only for behavior that exists in that core alone (bridge code, memory ownership, errors
  crossing the FFI).
- Tests run against real SQLite (in-memory or a temp file), never a mocked database. Fakes are for
  the network and the clock only.

# Tidy First

- Two kinds of change:
  1. **Structural**: rearranging code without changing behavior (rename, extract, move).
  2. **Behavioral**: adding or changing functionality.
- Never mix them in one commit. When both are needed, make the structural change first.
- Run the tests before and after every structural change to show behavior did not change.

# Commit discipline

Commit only when all tests pass, coverage gates pass (`make coverage`), there are zero compiler or
linter warnings (`make lint`), and the change is one logical unit. Each commit message says whether
it is structural or behavioral. Small, frequent commits.

# Code quality

- Eliminate duplication; express intent through naming and structure.
- Make dependencies explicit — pass clocks, ports and paths in, don't reach for globals.
- Small, single-purpose functions; minimal state and side effects.
- The simplest solution that could possibly work.
- Refactor in named steps (Extract Function, Inline Variable, …), one at a time, tests after each.

# Coverage

Target is 100% line coverage for every core and both apps, enforced by `make coverage`, which fails
below the gate. Branch coverage gate is 95% wherever the tool measures branches.

- Untested code is deleted, not excluded. The only exclusions allowed are listed in each module's
  `coverage-exclusions` file with a one-line reason per entry, and limited to:
  generated code (UniFFI bindings and scaffolding, SQLDelight output), process entry points
  (`@main`, `Application`), Compose `@Preview` and SwiftUI `#Preview` blocks.
  Adding an entry requires saying so in the commit message.
- Known blind spot: Kotlin/Native has no coverage tool, so KMP common code is measured on the JVM
  target. Keep `iosMain` to thin `actual` declarations and test their behavior through the iOS
  app's contract tests.
- Lua core: luacov measures lines only; the C bridge is measured with llvm-cov through its own
  host tests.
- App UI: SwiftUI views through ViewInspector unit tests plus XCUITests; Compose through
  Robolectric Compose tests plus instrumented tests. Logic belongs in view models, which are
  unit-tested directly.

# Benchmarks

- Benchmarks are tests too: a benchmark's harness is test-driven (it produces correct results on a
  tiny input) before it is used to measure anything.
- Benchmarks never run as part of `make test`. They run only in release builds via `make bench`.
- Every result records platform, device model, OS version, core, storage variant, build config and
  git SHA. A number without that context is not reported.

# Devices

- iOS simulators and Android emulators are leased with `simpool` (the `/sim-pool` skill); never
  pick one by name or because it is booted. Make targets take `SIM_UDID` / `ANDROID_SERIAL` and
  refuse to run without one.
- Physical devices are passed explicitly: `IOS_DEVICE=<udid>`, `ANDROID_SERIAL=<serial>`.
  Benchmark numbers from simulators and emulators are labelled as such in the report.

# Project commands

All from the repo root. `make help` lists every target.

| Command | What it does |
|---|---|
| `make bootstrap` | Install toolchains and tools (rustup targets, cargo-ndk, cargo-llvm-cov, luarocks deps) |
| `make test` | All unit + contract tests for every core and both apps (no benchmarks) |
| `make test-core CORE=kmp\|rust\|lua` | One core's tests |
| `make test-ios` / `make test-android` | App unit, contract-through-bridge and UI tests |
| `make lint` | clippy, ktlint/detekt, SwiftLint, luacheck; warnings are errors |
| `make coverage` | Run tests with coverage and fail below the gates |
| `make bench` | Run the benchmark matrix and print the results table |
| `make bench-report` | Build the HTML report from saved results |

`make bench` options: `PLATFORM=ios|android|all`, `CORE=kmp|rust|lua|all`,
`STORAGE=embedded|batched|chatty|all`, `SUITE=boundary|core|app|all` (default `all` for each).
