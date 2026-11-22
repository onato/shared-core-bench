---
name: pr
description: "Use when writing a PR body for this repo."
---

PRs are squash-merged with the PR title and body as the commit on `main`, so both are written to
be read in `git log`: an imperative title under 72 characters, prefixed `Structural:` when the PR
only rearranges code. Write the body with this template. Skip preambles and keep prose brief.

```markdown
Closes #<issue>

## Summary

<diagram, diff-sketch, or tree>

## Evidence

- **Before:** <the new tests failing — test names and the failure line>
  **After:** <the same tests passing; `make coverage` result per module>

## Merge Danger

**Door:** <one-way or two-way>

<optional: description>

**Blast Radius:** <one word>

<optional: what could break>

## Size

<changed lines counted by the size check> / 400
<coverage-exclusions entries added, with reasons — or "none">
```

## Summary

Pick the smallest view that makes the key point clear:

- **Pseudocode** for logic or an algorithm.
- **Call tree** for runtime control flow (`ingest → decode → upsert`).
- **Component tree** for UI structure, including state and module boundaries that matter.
- **Shallow file tree** for file responsibility or a broad refactor.
- **Mermaid sequence diagram** for interaction across the native ↔ core boundary.
- **`diff`-shaped sketch** when the point is what changes and the surrounding shape already exists —
  match the diff to the topic (component tree, file tree, call tree, control flow).
- **The whole block** when most of it is new or the reader needs the exact target shape.

Place each visual next to the short text it supports. Keep only the calls, files, states and
boundaries needed to make the point; usually one or two visuals, rarely more.

## Evidence

Concrete proof the change works, before and after. For this repo the before is the red run: the
tests written first, failing for the expected reason. The after is the same tests green plus the
coverage gate. Screenshots for visible UI changes when a device is available.

## Merge Danger

A two-way door is cheap to roll back; a one-way door isn't (schema changes that ship, deleted
data, public contract changes other cores must follow). The blast radius is the scope of impact:
one core, every core (contract), one app, CI.
