import sys

from device_check import Result, check, main

SERIAL = "emulator-5554"


def adb_that_runs(code):
    """A fake adb: Python running `code`, with adb's own arguments left in sys.argv."""
    return [sys.executable, "-c", code]


def adb_script(tmp_path, body):
    """A fake adb as an executable, the way the command line takes it."""
    adb = tmp_path / "adb"
    adb.write_text(f"#!/bin/sh\n{body}\n")
    adb.chmod(0o755)
    return adb


def clock_reading(*seconds):
    readings = iter(seconds)
    return lambda: next(readings)


def test_passes_when_adb_prints_the_model_within_the_limit():
    adb = adb_that_runs("print('Pixel 7')")

    result = check(SERIAL, adb=adb, clock=clock_reading(10.0, 10.4), limit=5.0)

    assert result == Result(passed=True, message="emulator-5554 answered in 0.4 s: Pixel 7")


def test_fails_with_the_serial_and_the_seconds_taken_when_adb_answers_after_the_limit():
    adb = adb_that_runs("import time; time.sleep(5); print('Pixel 7')")

    result = check(SERIAL, adb=adb, clock=clock_reading(3.0, 3.3), limit=0.2)

    assert result == Result(
        passed=False,
        message="emulator-5554 did not answer adb within 0.2 s (gave up after 0.3 s). "
        "Lease another emulator: android/README.md → Running the benchmarks.",
    )


def test_fails_naming_the_serial_when_adb_exits_non_zero():
    adb = adb_that_runs(
        "import sys; sys.stderr.write(\"adb: device 'emulator-5554' not found\\n\"); sys.exit(1)"
    )

    result = check(SERIAL, adb=adb, clock=clock_reading(0.0, 0.1), limit=5.0)

    assert result == Result(
        passed=False,
        message="adb could not reach emulator-5554 (exit 1: adb: device 'emulator-5554' "
        "not found). Lease another emulator: android/README.md → Running the benchmarks.",
    )


def test_fails_naming_the_serial_when_adb_prints_no_model():
    adb = adb_that_runs("print()")

    result = check(SERIAL, adb=adb, clock=clock_reading(0.0, 0.1), limit=5.0)

    assert result == Result(
        passed=False,
        message="adb could not reach emulator-5554 (no model printed). "
        "Lease another emulator: android/README.md → Running the benchmarks.",
    )


def test_the_command_line_prints_the_failure_and_exits_non_zero(tmp_path, capsys):
    adb = adb_script(tmp_path, "echo 'error: no devices' >&2; exit 1")

    code = main(["--serial", SERIAL, "--adb", str(adb)])

    assert (code, capsys.readouterr().err) == (
        1,
        "adb could not reach emulator-5554 (exit 1: error: no devices). "
        "Lease another emulator: android/README.md → Running the benchmarks.\n",
    )


def test_the_command_line_prints_the_model_and_exits_zero(tmp_path, capsys):
    adb = adb_script(tmp_path, "echo Pixel 7")

    code = main(["--serial", SERIAL, "--adb", str(adb)])

    assert (code, capsys.readouterr().out.startswith("emulator-5554 answered in ")) == (0, True)
