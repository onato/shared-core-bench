import json

import bench_collect

PIXEL = {
    "brand": "google",
    "model": "Pixel 8 Pro",
    "fingerprint": "google/husky/husky:16/BP2A.250605.031/13581935:user/release-keys",
    "version": {"sdk": 36},
}
TIMINGS = {"median": 300, "runs": [500, 100, 300, 200, 400]}
INGEST = {
    "name": "ingest1000Products",
    "className": "bench.benchmark.IngestBenchmark",
    "metrics": {"timeNs": TIMINGS},
}


def androidx_report(build=PIXEL, benchmark=INGEST):
    """The parts of androidx.benchmark's `*-benchmarkData.json` the collector reads."""
    return {"context": {"build": build}, "benchmarks": [benchmark]}


def collect(report=None):
    labels = {"core": "kmp", "storage": "embedded", "build_type": "release", "git_sha": "abc1234"}
    return bench_collect.collect(report or androidx_report(), **labels)


def test_each_benchmark_becomes_one_row_with_its_timings():
    [row] = collect()

    assert [row[k] for k in ("suite", "workload", "n", "median_ns", "p95_ns", "runs")] == [
        "core", "ingest1000Products", 1000, 300, 500, 5,
    ]  # fmt: skip


def test_boundary_benchmarks_are_the_boundary_suite_with_their_size_as_n():
    boundary = {"name": "bytesIn1024Bytes", "className": "bench.benchmark.BoundaryBenchmark"}
    [row] = collect(androidx_report(benchmark=INGEST | boundary))

    assert [row[k] for k in ("suite", "workload", "n")] == ["boundary", "bytesIn1024Bytes", 1024]


def test_rows_record_device_os_core_storage_build_type_and_git_sha():
    [row] = collect()

    keys = ("platform", "device", "os", "core", "storage", "build_type", "git_sha")
    assert [row[k] for k in keys] == [
        "android", "google Pixel 8 Pro", "Android API 36", "kmp", "embedded", "release", "abc1234",
    ]  # fmt: skip


def test_emulator_results_are_labelled_as_emulator():
    fingerprint = (
        "google/sdk_gphone64_arm64/emu64a:16/BE2A.250530.026.F3/13894323:userdebug/dev-keys"
    )
    emulator = PIXEL | {"model": "sdk_gphone64_arm64", "fingerprint": fingerprint}

    [device] = collect()
    [emulated] = collect(androidx_report(build=emulator))

    assert (device["emulator"], emulated["emulator"]) == (False, True)


def test_workload_drops_the_prefix_androidx_adds_for_suppressed_errors():
    [row] = collect(androidx_report(benchmark=INGEST | {"name": "EMULATOR_ingest1000Products"}))

    assert row["workload"] == "ingest1000Products"


def test_a_cold_start_is_an_app_suite_row_of_its_time_to_initial_display_in_ns():
    startup = {
        "name": "coldStartFromCache1000Products",
        "className": "bench.macrobenchmark.StartupBenchmark",
        "metrics": {
            "timeToInitialDisplayMs": {"median": 300.5, "runs": [500.0, 100.0, 300.5, 200.0, 400.0]}
        },
        "sampledMetrics": {},
    }
    [row] = collect(androidx_report(benchmark=startup))

    assert [row[k] for k in ("suite", "workload", "n", "median_ns", "p95_ns", "runs")] == [
        "app", "coldStartFromCache1000Products timeToInitialDisplayMs", 1000, 300_500_000,
        500_000_000, 5,
    ]  # fmt: skip


def test_a_scroll_is_one_row_per_frame_timing_with_p50_as_median_and_p95_as_p95():
    def sampled(p50, p95):
        return {"P50": p50, "P90": 9.0, "P95": p95, "P99": 30.0, "runs": [[4.0, 5.0], [6.0]]}

    scroll = {
        "name": "scroll1000Products",
        "className": "bench.macrobenchmark.ScrollBenchmark",
        "metrics": {"frameCount": {"median": 240.0, "runs": [238.0, 242.0]}},
        "sampledMetrics": {
            "frameDurationCpuMs": sampled(4.5, 12.0),
            "frameOverrunMs": sampled(-6.0, 2.5),
        },
    }
    rows = collect(androidx_report(benchmark=scroll))

    keys = ("suite", "workload", "n", "median_ns", "p95_ns", "runs")
    assert [[row[k] for k in keys] for row in rows] == [
        ["app", "scroll1000Products frameDurationCpuMs", 1000, 4_500_000, 12_000_000, 2],
        ["app", "scroll1000Products frameOverrunMs", 1000, -6_000_000, 2_500_000, 2],
    ]


def test_table_groups_by_workload_with_cores_as_columns():
    where = {"platform": "android", "storage": "embedded"}
    rows = [
        where | {"workload": "ingest1000Products", "core": "rust", "median_ns": 8_000_000},
        where | {"workload": "decode100Products", "core": "kmp", "median_ns": 1_000_000},
        where | {"workload": "ingest1000Products", "core": "kmp", "median_ns": 12_500_000},
    ]

    assert bench_collect.table(rows) == (
        "| workload | platform | storage | kmp (median ms) | rust (median ms) |\n"
        "| --- | --- | --- | --- | --- |\n"
        "| decode100Products | android | embedded | 1.00 | - |\n"
        "| ingest1000Products | android | embedded | 12.50 | 8.00 |\n"
    )


def test_main_saves_the_rows_it_finds_under_the_output_directory_and_prints_them(tmp_path, capsys):
    device = tmp_path / "connected" / "releaseAndroidTest" / "connected" / "DOC_API36(AVD) - 16"
    device.mkdir(parents=True)
    (device / "bench.benchmark.test-benchmarkData.json").write_text(json.dumps(androidx_report()))
    (device / "logcat.txt").write_text("not a report")
    out = tmp_path / "results" / "20261123T0100"
    labels = [
        "--core",
        "kmp",
        "--storage",
        "embedded",
        "--build-type",
        "release",
        "--git-sha",
        "a1",
    ]

    status = bench_collect.main([*labels, "--out", str(out), str(tmp_path / "connected")])

    saved = json.loads((out / "results.json").read_text())
    assert (status, [row["workload"] for row in saved]) == (0, ["ingest1000Products"])
    assert (
        capsys.readouterr().out.splitlines()[-1]
        == "| ingest1000Products | android | embedded | 0.00 |"
    )


def test_table_shows_a_delegated_rows_overhead_over_its_embedded_row():
    where = {"workload": "ingest1000Products", "platform": "android", "core": "kmp"}
    rows = [
        where | {"storage": "embedded", "median_ns": 12_500_000},
        where | {"storage": "batched", "median_ns": 15_000_000},
    ]

    assert bench_collect.table(rows) == (
        "| workload | platform | storage | kmp (median ms) | kmp overhead |\n"
        "| --- | --- | --- | --- | --- |\n"
        "| ingest1000Products | android | batched | 15.00 | +2.50 ms (+20%) |\n"
        "| ingest1000Products | android | embedded | 12.50 |  |\n"
    )


def test_a_delegated_row_without_an_embedded_row_shows_a_dash_for_its_overhead():
    row = {"workload": "search10000Rows", "platform": "android", "core": "kmp"}

    assert bench_collect.table([row | {"storage": "chatty", "median_ns": 3_000_000}]) == (
        "| workload | platform | storage | kmp (median ms) | kmp overhead |\n"
        "| --- | --- | --- | --- | --- |\n"
        "| search10000Rows | android | chatty | 3.00 | — |\n"
    )


def test_main_adds_a_variants_rows_to_the_results_already_saved_and_prints_them_all(
    tmp_path, capsys
):
    output = tmp_path / "connected"
    output.mkdir()
    (output / "bench.benchmark.test-benchmarkData.json").write_text(json.dumps(androidx_report()))
    out = tmp_path / "results" / "20261125T0100"
    labels = ["--core", "kmp", "--build-type", "release", "--git-sha", "a1", "--out", str(out)]

    for storage in ("embedded", "chatty"):
        bench_collect.main([*labels, "--storage", storage, str(output)])

    saved = json.loads((out / "results.json").read_text())
    assert [row["storage"] for row in saved] == ["embedded", "chatty"]
    assert capsys.readouterr().out.splitlines()[-2:] == [
        "| ingest1000Products | android | chatty | 0.00 | +0.00 ms (+0%) |",
        "| ingest1000Products | android | embedded | 0.00 |  |",
    ]
