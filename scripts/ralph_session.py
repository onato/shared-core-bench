"""One Ralph loop iteration: a fresh `claude -p` on an issue, in that issue's own worktree."""

import json
import re
import subprocess
from collections.abc import Callable
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Protocol

from ralph import POLL_SECONDS, Improvement, Issue, Outcome

BOT = "onato-agent[bot]"
BOT_EMAIL = "359795497+onato-agent[bot]@users.noreply.github.com"  # the bot's GitHub user id
BOT_GIT = {
    "GIT_AUTHOR_NAME": BOT,
    "GIT_AUTHOR_EMAIL": BOT_EMAIL,
    "GIT_COMMITTER_NAME": BOT,
    "GIT_COMMITTER_EMAIL": BOT_EMAIL,
    # The remote is SSH, which would push with the owner's key: go over HTTPS with GH_TOKEN,
    # emptying the helper list first so the keychain's owner credentials aren't offered.
    "GIT_CONFIG_COUNT": "3",
    "GIT_CONFIG_KEY_0": "url.https://github.com/.insteadOf",
    "GIT_CONFIG_VALUE_0": "git@github.com:",
    "GIT_CONFIG_KEY_1": "credential.https://github.com.helper",
    "GIT_CONFIG_VALUE_1": "",
    "GIT_CONFIG_KEY_2": "credential.https://github.com.helper",
    "GIT_CONFIG_VALUE_2": "!gh auth git-credential",
}


class SessionTokens(Protocol):
    def session_env(self, environ: dict[str, str]) -> dict[str, str]: ...


@dataclass
class ClaudeSession:
    repo: Path
    tokens: SessionTokens
    run: Callable[..., subprocess.CompletedProcess]
    start: Callable[..., subprocess.Popen]
    environ: dict[str, str]
    now: Callable[[], datetime]

    def work(self, issue: Issue, while_running: Callable[[], None]) -> Outcome:
        branch, worktree = self._branch(issue.number), self._worktree(issue.number)
        failed = self._add_worktree(worktree, ["-b", branch, "origin/main"])
        if failed:
            return Outcome(pr=None, needs_human=failed)
        prompt = (self.repo / ".ralph" / "PROMPT.md").read_text()
        prompt = prompt.replace("{issue}", str(issue.number)).replace("{branch}", branch)
        text, log = self._claude(prompt, worktree, str(issue.number), while_running)
        if text is None:
            return Outcome(pr=None, needs_human=f"the session ended without a result; see {log}")
        return outcome(text)

    def remove(self, issue: int) -> str | None:
        """What failed, or None once the issue's worktree and branch are gone."""
        return self._git(
            ["worktree", "remove", str(self._worktree(issue))],
            ["branch", "-D", self._branch(issue)],  # -D: a squash merge leaves it unmerged
        )

    def _branch(self, issue: int) -> str:
        return f"swilliams/{issue}-ralph"

    def _worktree(self, issue: int) -> Path:
        return self.repo.parent / f"shared-core-bench-{issue}"

    def improve(self, prs: tuple[int, ...], while_running: Callable[[], None]) -> Improvement:
        worktree = self.repo.parent / f"shared-core-bench-improve-{self.now():%Y%m%d-%H%M%S}"
        failed = self._add_worktree(worktree, ["--detach", "origin/main"])
        if failed:
            return Improvement(needs_human=failed)
        named = "PRs " + ", ".join(f"#{n}" for n in prs)
        prompt = (self.repo / ".ralph" / "IMPROVE.md").read_text().replace("{prs}", named)
        text, log = self._claude(prompt, worktree, "improve", while_running)
        left = self._git(["worktree", "remove", str(worktree)])
        if text is None:
            return Improvement(
                needs_human=f"the session ended without a result; see {log}", left_in_place=left
            )
        return Improvement(
            prs=numbers("PR", text), proposals=numbers("PROPOSED", text), left_in_place=left
        )

    def _add_worktree(self, worktree: Path, start: list[str]) -> str | None:
        """What failed, or None once the worktree exists."""
        return self._git(["fetch", "origin"], ["worktree", "add", str(worktree), *start])

    def _git(self, *steps: list[str]) -> str | None:
        """What failed, stopping at the first step to fail, or None once all have run."""
        for step in steps:
            done = self.run(["git", "-C", str(self.repo), *step], capture_output=True, text=True)
            if done.returncode != 0:
                return f"git {' '.join(step[:2])} failed: {done.stderr.strip()}"
        return None

    def _claude(
        self, prompt: str, worktree: Path, name: str, while_running: Callable[[], None]
    ) -> tuple[str | None, Path]:
        """The session's final result, if it reached one, and its log."""
        logs = self.repo / ".ralph" / "logs"
        logs.mkdir(exist_ok=True)
        log = logs / f"{self.now():%Y%m%d-%H%M%S}-{name}.log"
        with log.open("w") as out:
            process = self.start(
                [
                    "claude",
                    "-p",
                    prompt,
                    "--permission-mode",
                    "auto",
                    "--output-format",
                    "stream-json",
                    "--verbose",
                ],
                cwd=worktree,
                env=self.tokens.session_env({**own_environ(self.environ), **BOT_GIT}),
                stdout=out,
                stderr=subprocess.STDOUT,
                text=True,
            )
            while not exited(process):
                while_running()
        return final_result(log), log


def exited(process: subprocess.Popen) -> bool:
    try:
        process.wait(timeout=POLL_SECONDS)
    except subprocess.TimeoutExpired:
        return False
    return True


def events(log: Path) -> list[dict]:
    return [json.loads(line) for line in log.read_text().splitlines() if line.startswith("{")]


def own_environ(environ: dict[str, str]) -> dict[str, str]:
    """Without the CLAUDE* variables a Claude Code parent sets, which make `claude` a child."""
    return {k: v for k, v in environ.items() if not k.startswith("CLAUDE")}


def final_result(log: Path) -> str | None:
    return next((e["result"] for e in reversed(events(log)) if e.get("type") == "result"), None)


def outcome(text: str) -> Outcome:
    prs = numbers("PR", text)
    human = re.search(r"^NEEDS_HUMAN: (.+?)\s*$", text, re.MULTILINE)
    return Outcome(
        pr=prs[0] if prs else None,
        needs_human=human.group(1) if human else None,
        also=prs[1:],
    )


def numbers(marker: str, text: str) -> tuple[int, ...]:
    return tuple(int(n) for n in re.findall(rf"^{marker}: #(\d+)\s*$", text, re.MULTILINE))
