---
name: retro
description: Run a retrospective on the Ralph loop's recent work and turn what slowed it down into fixes to the repo's environment. Use for the improver session (.ralph/IMPROVE.md), at a milestone checkpoint, or when asked for a retro.
---

A retro improves the **environment** the loop works in: its docs, checks, review standards and
tools. It never changes the product. The aim is that the next session doesn't hit the same
problem.

## 1. Pick the scope

- **Checkpoint:** every PR merged in the milestone. Write `docs/checkpoints/<milestone>.md`.
- **Improver run:** the PRs merged since the last improver run. Its issue says which ones. There
  is no document; the output is the issues in step 5.

## 2. Read the sources

For each PR in scope:

- its `## Retro notes` comment, where the session wrote what slowed it, what review caught and
  what it guessed
- its review threads and the `claude-review` comments, especially findings that needed a second
  round
- failed `ci`, `pr-size` and `protected-paths` runs (`gh pr checks`, `gh run view --log-failed`)
- the session log, `.ralph/logs/<timestamp>-<issue>.log` in the checkout the loop ran from. It is
  stream-json. The final `result` event holds the session's answer, its turn count and duration,
  and `rate_limit_event` lines give weekly usage.

Also read the loop's own stop messages, and any issue the loop moved to In review.

## 3. Find patterns

- **A pattern** is the same friction seen twice in scope, or any gate that let something bad
  reach `main`.
- **One-off friction** stays in the notes. Don't fix it, but list it in the checkpoint document
  so the next retro can spot a repeat.
- **A broken gate** comes first, before anything else: the loop should not take another issue
  until it is fixed.

## 4. Choose the fix, most severe first

Go through the patterns in this order and pick the cheapest fix that would have prevented each:

1. **Navigation.** The session couldn't find a file or a fact. Add a pointer where it looked
   first: an issue template, `CLAUDE.md`'s file list, or a README in the directory.
2. **Automated checks.** A mistake a machine could catch gets a lint rule, a test or a CI job.
   First check whether one exists but isn't wired into `make lint` or CI; wiring it is the fix.
3. **Coding standards.** The review missed something, or flagged something it shouldn't have. A
   mechanical rule (a banned call, an import shape, a file location) gets a check, as in step 2.
   Only judgement calls go into `CODING_STANDARDS.md`.
4. **`CLAUDE.md` size.** Every session loads it. Move rules out of it into
   `CODING_STANDARDS.md` (read at review time) or into a check. Keep it to pointers and the
   workflow.
5. **No-ops.** Delete instructions that change nothing a session does.
6. **Tool economy.** Calls that were slow or flooded the context, such as whole Gradle logs or
   repeated `gh` queries, get a quieter command or a `make` target.
7. **Information access.** A fact the session needed but couldn't get, such as a device log or a
   CI artifact, gets a way to fetch it.

## 5. File the fixes

Each fix is its own issue in the current milestone, with tests to write first where it is
code. Each fix becomes a small PR of its own, never part of a feature PR.

- **Tightening or clarifying**, such as a new check, a clearer doc or a stricter rule: file it
  as an issue that blocks the next Todo issue, so the loop takes it first. The improver may
  build it.
- **Loosening a gate, changing `SPEC.md`, or a judgement call**: file it with the
  `retro-proposal` label and don't build it. The owner decides. Lowering a coverage gate,
  raising the size limit, adding a coverage exclusion, weakening a review rule and changing the
  loop's stop conditions all count as loosening.

At a checkpoint:

- `SPEC.md` may record decisions that merged PRs already made, such as a library choice, a
  result shape or an SDK level. Any change to what is still to be built or measured stays a
  `retro-proposal`.
- The milestone is ending, so its fix issues go under the next milestone's parent as `draft`
  sub-issues, ahead of its first task.

Changes to paths in `.github/protected-paths`, including this skill, need the owner's
`gate-approved` label to merge, whichever way they go. Say in the PR why the change is needed.

## 6. Write it up

At a checkpoint, `docs/checkpoints/<milestone>.md` covers:

- **What was hard:** the patterns, with the PRs that show them.
- **What the gates caught:** CI, `pr-size`, `protected-paths` and review. Note anything that got
  past them.
- **PR sizes:** changed lines per PR, and which issues were split.
- **Coverage blind spots:** exclusions added, and code that is covered but not really tested.
- **Results:** any benchmark numbers the milestone produced.
- **Fixes:** each issue filed in step 5, and which ones are `retro-proposal`.
- **One-off friction:** the notes kept for the next retro.
