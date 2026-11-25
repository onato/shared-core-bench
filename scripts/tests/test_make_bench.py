"""`make bench`'s recipe, read with `make -n` so nothing runs."""

import re
import subprocess
from pathlib import Path

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
