"""`make test-ios`'s guard: it runs only on a leased simulator."""

import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def test_test_ios_without_a_simulator_stops_and_names_simpool_acquire():
    run = subprocess.run(
        ["make", "--no-print-directory", "test-ios", "SIM_UDID="],
        cwd=ROOT,
        capture_output=True,
        text=True,
    )

    assert (run.returncode, run.stderr.splitlines()[0]) == (
        2,
        "test-ios: set SIM_UDID to a simulator from `simpool acquire`",
    )


def test_make_test_on_macos_checks_sim_udid_before_any_other_leg():
    run = subprocess.run(
        ["make", "--no-print-directory", "-n", "test", "SIM_UDID=", "IS_MACOS=Darwin"],
        cwd=ROOT,
        capture_output=True,
        text=True,
    )

    assert "SIM_UDID" in run.stdout.splitlines()[0]
