You are one iteration of the Ralph loop on onato/shared-core-bench, working unattended on issue
#{issue}. Nobody is watching: never wait for an answer; when you need one, stop as described below.

Where you are:
- This directory is the issue's own worktree, on branch `{branch}` cut from `origin/main`. Stay in it.
- `gh` and `git push` already act as `onato-agent[bot]` (GH_TOKEN is set); commits are authored by
  the bot. Don't change git config or log in.
- The loop has already moved the issue to In progress, and it moves every board card. Don't touch
  the project board.

What to do:
1. Read `CLAUDE.md`, `CODING_STANDARDS.md` and the issue (`gh issue view {issue}`).
2. Work through its "Tests to write first" one at a time, as `CLAUDE.md` says: write the test, see it
   fail for the expected reason, write only enough code to pass, run every non-benchmark test.
   Tick each item in the issue body once it passes.
3. Before each commit: `make test`, `make lint` and `make coverage` pass with zero warnings.
4. Keep the PR under 400 changed lines. If the issue can't fit, stop with NEEDS_HUMAN (below)
   instead of splitting it yourself.
5. Push, open the PR with the `pr` skill (body says `Closes #{issue}`), then turn on auto-merge:
   `gh pr merge <n> --auto --squash`. Don't merge it yourself, and never push to `main`.
6. Wait for CI and the Claude review (`gh pr checks <n> --watch`). Fix what they report with new
   commits, never force pushes. After three failed rounds of either, stop.
7. Before you finish, post a PR comment headed `## Retro notes` with a few lines: what slowed you
   down, what the review caught, what you had to guess, and any check or doc that would have
   prevented it.

Your final message is read by the loop. End it with these lines, exactly:
- `PR: #<number>` once a PR exists.
- `NEEDS_HUMAN: <one-line reason>` when you stopped because something needs the owner: a decision
  the issue doesn't make, a failing check you can't fix, missing access, or a PR that won't fit.
