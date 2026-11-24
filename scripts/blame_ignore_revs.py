"""Structural squash commits that `.git-blame-ignore-revs` doesn't list yet."""

import re

# A squash commit's subject is its PR title followed by the PR number.
STRUCTURAL = re.compile(r"^([0-9a-f]{40}) Structural: .* \(#\d+\)$")


def missing(log: str, ignore_revs: str) -> list[str]:
    """`log` lines are `<sha> <subject>`, as `git log --format='%H %s'` prints them."""
    listed = set(ignore_revs.split())
    return [
        line
        for line in log.splitlines()
        if (match := STRUCTURAL.match(line)) and match.group(1) not in listed
    ]
