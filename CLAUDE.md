# Shared Core Bench

Compares KMP, Rust/UniFFI and Lua as a shared business-logic core for an iOS and an Android app.

- `SPEC.md` — what gets built and measured
- `ISSUES.md` — delivery milestones and how work flows
- `CODING_STANDARDS.md` — what the PR review enforces; read it before writing tests or code
- `contract/README.md` and `android/README.md` — what fixtures and app tests need; read before changing either
- GitHub issues on `onato/shared-core-bench` — the work queue (board: users/onato/projects/2)

Each issue carries two checklists: "Contract cases" (the outer loop, below) and "Tests to write
first" (the test list). When I say "go", start with the contract cases if they aren't written yet;
otherwise find the next unchecked test, implement the test, then implement only enough code to make
that test pass. Tick an item only when it passes along with every other non-benchmark test.

# Issue and PR workflow

- Next issue: lowest-numbered open issue in the current milestone with status Todo and no open
  blockers. Move it to In progress on the project board when starting.
- One issue = one branch = one worktree (`swilliams/<issue#>-<slug>` from `origin/main`) = one PR
  whose body says `Closes #<n>`. Write the PR body with the `pr` skill.
- A hook denies any `gh` call that publishes text without the `🤖 … Claude Code` marker line,
  ticking an issue's checklist included, so keep that line in every body you write or edit. A
  denied Bash call runs none of its commands: give each `gh` publish its own call, and write a
  `--body-file` in an earlier one.
- Keep PRs under 400 changed lines, tests included; only the generated or vendored paths in
  `.github/pr-size-exclude` are left out. Reviews lose effectiveness past 200–400 lines (the
  Cisco/SmartBear study), and test code already gets less review than production code.
- If an issue won't fit, split it before writing code. Make each part a sub-issue of the same
  parent that merges on its own and has its own tests to write first, and link them with
  `Blocked by`. Doing less in a PR is almost always possible.
- An oversized PR is the exception, for work that can't be divided, usually because it is mostly
  boilerplate. Give a breakdown in the PR's Size section (tests, production code, build/config), and
  wait for the owner. `pr-size` passes it only once the owner adds the `size-approved` label.
- A PR touching a path in `.github/protected-paths` (the gates and what they read) passes
  `protected-paths` only once the owner adds the `gate-approved` label. Keep such changes in a PR
  of their own and say why in the body.
- `main` is protected: never push to it; every change merges through a PR that passes CI, the size
  check, the protected-paths check and the Claude review. Address review comments with new commits, not force pushes.

# TDD and Tidy First

Two loops, as in *Growing Object-Oriented Software, Guided by Tests*:

- **Outer loop: contract cases.** The issue's "Contract cases" are acceptance tests: case files in
  `contract/cases/` that every core must pass, Rust and Lua included. Write them all first, before
  any production code, and run them: they fail together because the feature doesn't exist yet.
  That one red run is their evidence. A case that turns green partway through the inner loop is
  expected. Never break code on purpose to make a case fail.
- **Inner loop: tests to write first.** Strict TDD, one test at a time, until the contract cases
  pass. An issue may have no inner tests when its contract cases drive the whole change. The rules
  below are for this loop.

- Red → Green → Refactor. Write the simplest failing test, run it, and see it fail for the
  expected reason before writing production code. A test that passes on first run proves
  nothing: drop it if it adds no behaviour, or rewrite it until it demands code that doesn't exist
  yet. Never break code on purpose to see it fail.
- Implement only enough to pass. Refactor only when green, in named steps (Extract Function,
  Inline Variable, …), running the tests after each. The refactor step reshapes only code this
  branch added. Extracting from, renaming or moving code already on `main` is a structural change:
  stop and open it as a `Structural:` PR first (next bullet), or leave the new code inline.
- One test at a time. Run all tests (except benchmarks) each time.
- Structural changes (rename, extract, move) and behavioural changes never share a PR. PRs are
  squash-merged, so a PR is the unit of history: when an issue needs a structural change first,
  open it as its own small PR (title starts `Structural:`), merge it, then branch the behavioural
  PR from the updated `main`. The structural PR closes an issue of its own: first open a sub-issue
  of the same parent, titled `Structural: …` and blocking the behavioural issue. Within a branch, commits are free-form; the PR title and description
  become the commit on `main`.
- Commit only when `make test`, `make lint` and `make coverage` pass with zero warnings.

# Devices

- iOS simulators and Android emulators are leased with `simpool` (the `/sim-pool` skill); never
  pick one by name or because it is booted. Make targets take `SIM_UDID` / `ANDROID_SERIAL` and
  refuse to run without one.
- Physical devices are passed explicitly: `IOS_DEVICE=<udid>`, `ANDROID_SERIAL=<serial>`.

# Project commands

All from the repo root. `make help` lists every target. Targets arrive with the issues that build
them; until then they fail with "not implemented".

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
