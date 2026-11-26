"""Fail fast when adb can't reach the device a run is about to use."""

import argparse
import subprocess
import sys
import time
from collections.abc import Callable
from dataclasses import dataclass

ADVICE = "Lease another emulator: android/README.md → Running the benchmarks."


@dataclass(frozen=True)
class Result:
    passed: bool
    message: str


def check(serial: str, adb: list[str], clock: Callable[[], float], limit: float) -> Result:
    started = clock()
    try:
        answer = subprocess.run(
            [*adb, "-s", serial, "shell", "getprop", "ro.product.model"],
            capture_output=True,
            text=True,
            timeout=limit,
        )
    except subprocess.TimeoutExpired:
        seconds = clock() - started
        return Result(
            False,
            f"{serial} did not answer adb within {limit:g} s "
            f"(gave up after {seconds:.1f} s). {ADVICE}",
        )
    model = answer.stdout.strip()
    if answer.returncode != 0:
        return unreachable(serial, f"exit {answer.returncode}: {answer.stderr.strip()}")
    if not model:
        return unreachable(serial, "no model printed")
    seconds = clock() - started
    return Result(True, f"{serial} answered in {seconds:.1f} s: {model}")


def unreachable(serial: str, reason: str) -> Result:
    return Result(False, f"adb could not reach {serial} ({reason}). {ADVICE}")


def main(argv: list[str], clock: Callable[[], float] = time.monotonic) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--limit", type=float, default=5.0, help="seconds adb has to answer")
    args = parser.parse_args(argv)

    result = check(args.serial, adb=[args.adb], clock=clock, limit=args.limit)
    print(result.message, file=sys.stdout if result.passed else sys.stderr)
    return 0 if result.passed else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
