"""`make bench`'s recipe: read with `make -n` so nothing runs, or run against a fake adb."""

import re
import subprocess
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]


def bench_commands(*variables):
    dry_run = [
        "make",
        "--no-print-directory",
        "-n",
        "bench",
        "ANDROID_SERIAL=emulator-5554",
        *variables,
    ]
    return subprocess.run(dry_run, cwd=ROOT, capture_output=True, text=True, check=True).stdout


def test_every_storage_runs_the_benchmarks_on_that_variant_and_labels_its_rows_with_it():
    commands = bench_commands("STORAGE=all")

    runs = re.findall(r"connectedReleaseAndroidTest .*?storage=(\w+)", commands)
    labels = re.findall(r"bench_collect\.py .*?--storage (\w+)", commands)
    assert (runs, labels) == (["embedded", "batched", "chatty"], ["embedded", "batched", "chatty"])


@pytest.mark.parametrize("target", ["bench", "test-android-device"])
def test_an_unreachable_serial_stops_the_run_before_gradle(target, tmp_path):
    adb = tmp_path / "adb"
    adb.write_text("#!/bin/sh\necho \"adb: device '$2' not found\" >&2\nexit 1\n")
    adb.chmod(0o755)

    run = subprocess.run(
        [
            "make",
            "--no-print-directory",
            target,
            "ANDROID_SERIAL=emulator-9999",
            f"ADB={adb}",
            "ANDROID_GRADLE=echo gradle-ran",
        ],
        cwd=ROOT,
        capture_output=True,
        text=True,
    )

    assert (run.returncode, "gradle-ran" in run.stdout) == (2, False)
    assert "adb could not reach emulator-9999 (exit 1:" in run.stderr
