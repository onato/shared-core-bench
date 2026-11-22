# Shared Core Bench

Compares Kotlin Multiplatform, Rust/UniFFI and Lua as a shared business-logic core for an iOS and
an Android app: same demo app, same contract, same benchmarks.

- `SPEC.md` — what is being built and measured
- `ISSUES.md` — delivery milestones and how work flows through PRs
- `CLAUDE.md` — development rules (TDD, coverage, commands)

## Protected `main`

The `protect-main` ruleset applies to the default branch, with no bypass actors (admins included):

- Changes arrive only through a pull request, squash merge only, so history stays linear.
- Every review thread must be resolved before merge. No approving review is required; the
  `claude-review` check is the reviewer.
- Required checks, run against a branch that is up to date with `main`:
  - `ci` — `make lint test coverage`
  - `pr-size` — at most 400 changed lines, excluding `.github/pr-size-exclude`
  - `claude-review` — the Claude review must return `APPROVE`
- No force pushes and no deletion of `main`.

`claude-review` reads its prompt and verdict check from `main`, so a PR that changes them is
reviewed by the version already merged.

### Changing the review gate

`claude-code-action` refuses to run when the PR changes `.github/workflows/claude-review.yml`
itself, so such a PR can never pass the required `claude-review` check. The owner merges it by:

1. Opening the PR on its own, with nothing else in it; `ci` and `pr-size` must pass.
2. Removing `claude-review` from the ruleset's required checks.
3. Squash-merging the PR.
4. Restoring `claude-review` immediately and checking the ruleset lists all three checks.

Say in the PR that it uses this procedure.
