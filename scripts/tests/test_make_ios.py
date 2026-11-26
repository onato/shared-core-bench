"""`make test-ios`'s and `make coverage-ios`'s guards: they run only on a leased simulator."""

import subprocess
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]


@pytest.mark.parametrize("target", ["test-ios", "coverage-ios"])
def test_without_a_simulator_stops_and_names_simpool_acquire(target):
    run = subprocess.run(
        ["make", "--no-print-directory", target, "SIM_UDID="],
        cwd=ROOT,
        capture_output=True,
        text=True,
    )

    assert (run.returncode, run.stderr.splitlines()[0]) == (
        2,
        f"{target}: set SIM_UDID to a simulator from `simpool acquire`",
    )
