# Coding standards

The Claude PR review checks every diff against this file and the linked issue. Rules that can be
checked mechanically belong in a linter, a CI job or a coverage gate instead; this file holds the
judgement calls.

## Tests

- Tests verify behaviour through public interfaces — the `SharedCore` contract, a module's public
  API, a screen's visible state. A test should survive any refactor that keeps behaviour the same.
- Name tests for the behaviour (`pagingStopsWhenRemoteReportsNoMorePages`), not the method.
- Assert on values, not on booleans, so failures say what went wrong.
- Behaviour shared across cores is specified once, as a contract case in `contract/cases/`. A
  core-specific test is only for behaviour that exists in that core alone (bridge code, memory
  ownership, errors crossing the FFI).
- The contract adapters (`KmpContractCore.kt` in core-kmp's tests, `SharedCoreContract.kt` in the
  app's) mirror each other on purpose: each maps the contract's JSON onto its own build's types, and
  the two builds share no test source set. Repeated argument parsing and string-to-enum maps between
  them are not a finding. `contract/README.md` → "Changing a result or a required field" lists what a
  change must touch in both.
- Tests run against real SQLite (in-memory or a temp file), never a mocked database. Fakes are for
  the network and the clock only.
- When fixing a defect: an API-level failing test first, then the smallest test that reproduces
  the problem.

### Anti-patterns

Chasing 100% coverage pushes towards the first two; the review flags them even when coverage is
green.

- **Implementation-coupled**: mocks internal collaborators, tests private functions, or verifies
  through a side channel (querying the database instead of using the interface). The tell: it
  breaks on a refactor that didn't change behaviour.
- **Tautological**: the expected value is computed the way the code computes it, so the test
  passes by construction. Expected values come from an independent source: a literal, a worked
  example, the spec, a contract case.
- **Horizontal slicing**: all the tests first, then all the code. One test → one implementation →
  repeat. An issue's contract cases are the exception: they are the outer loop, written together
  before any code (`CLAUDE.md` → TDD), while its tests to write first still go one at a time.

## Coverage

100% lines for every core and both apps, 95% branches where the tool measures them, enforced by
`make coverage`.

- Untested code is deleted, not excluded. Exclusions live in each module's `coverage-exclusions`
  file, one line and one reason per entry, and only for: generated code (UniFFI bindings and
  scaffolding, SQLDelight output), process entry points (`@main`, `Application`), and Compose
  `@Preview` / SwiftUI `#Preview` blocks. A PR adding an entry says so in its description.
- Kotlin/Native has no coverage tool, so KMP common code is measured on the JVM target. `iosMain`
  stays thin `actual` declarations, exercised by the iOS app's contract tests.
- Lua: luacov measures lines only; the C bridge is measured with llvm-cov through its own host
  tests.
- App UI: SwiftUI through ViewInspector unit tests plus XCUITests; Compose through Robolectric
  Compose tests plus instrumented tests. Logic lives in view models, tested directly.

## Code

- Eliminate duplication; express intent through naming and structure.
- Make dependencies explicit — pass clocks, ports and paths in, don't reach for globals.
- Small, single-purpose functions; minimal state and side effects.
- The simplest solution that could possibly work; no abstraction the issue doesn't need.
- Errors that cross a core boundary are results, never exceptions or panics.
- The app knows cores only through its `SharedCore` interface / protocol; no core types leak past
  the adapter.

## Benchmarks

- A benchmark's harness is test-driven (correct results on a tiny input) before it measures
  anything.
- Benchmarks never run in `make test`; only in release builds via `make bench`.
- Every result records platform, device model, OS version, core, storage variant, build config and
  git SHA. Simulator and emulator results are labelled as such.

## Code smells

The review also flags these as judgement calls (Fowler, *Refactoring*, ch. 3); a rule above wins
where they disagree: Mysterious Name, Duplicated Code,
Feature Envy, Data Clumps, Primitive Obsession, Repeated Switches, Shotgun Surgery, Divergent
Change, Speculative Generality, Message Chains, Middle Man, Refused Bequest.
