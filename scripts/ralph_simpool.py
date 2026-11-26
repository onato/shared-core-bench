"""The Ralph loop's issue emulators, through the sim-pool skill's `simpool`."""

import re
import subprocess
from collections.abc import Callable
from dataclasses import dataclass
from pathlib import Path

ISSUE_EMULATOR = re.compile(r"^issue\s+scb-(\d+)\s", re.MULTILINE)


@dataclass
class SimPool:
    environ: dict[str, str]
    run: Callable[..., subprocess.CompletedProcess]

    def issues(self) -> list[int]:
        """The issues with an emulator of their own; none while simpool isn't installed."""
        if not self._simpool().exists():
            return []
        status = self.run([str(self._simpool()), "status"], capture_output=True, text=True)
        return [int(n) for n in ISSUE_EMULATOR.findall(status.stdout)]

    def prune(self, issue: int) -> str | None:
        """Why the issue's emulator stays, if it does: simpool keeps a leased one and exits 0."""
        name = f"scb-{issue}"
        done = self.run([str(self._simpool()), "prune", name], capture_output=True, text=True)
        if f"simpool: deleted {name} " in done.stderr:
            return None
        return done.stderr.strip() or f"simpool prune {name} exited {done.returncode}"

    def _simpool(self) -> Path:
        default = f"{self.environ['HOME']}/.claude/skills/sim-pool"
        return Path(self.environ.get("SIM_POOL_SKILL", default)) / "scripts" / "simpool"
