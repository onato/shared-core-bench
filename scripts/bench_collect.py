"""Turn androidx.benchmark JSON into result rows and print them as a table."""

import argparse
import json
import math
import re
import sys
from pathlib import Path

# Fingerprints of the SDK's emulator images; a physical device never carries these.
EMULATOR_MARKERS = ("sdk_gphone", "generic", "emulator")
NS_PER_MS = 1_000_000


def collect(report: dict, *, core: str, storage: str, build_type: str, git_sha: str) -> list[dict]:
    build = report["context"]["build"]
    where = {
        "platform": "android",
        "device": f"{build['brand']} {build['model']}",
        "os": f"Android API {build['version']['sdk']}",
        "emulator": any(marker in build["fingerprint"] for marker in EMULATOR_MARKERS),
        "core": core,
        "storage": storage,
        "build_type": build_type,
        "git_sha": git_sha,
    }
    return [where | row for benchmark in report["benchmarks"] for row in _timings(benchmark)]


def _timings(benchmark: dict) -> list[dict]:
    # androidx.benchmark prefixes each suppressed error, e.g. `EMULATOR_` or `DEBUGGABLE_`.
    name = re.sub(r"^([A-Z]+_)+", "", benchmark["name"])
    labels = {"suite": _suite(benchmark["className"]), "n": int(re.search(r"\d+", name).group())}
    if "timeNs" in benchmark["metrics"]:
        return [labels | {"workload": name} | _spread(benchmark["metrics"]["timeNs"], 1)]
    # Macrobenchmark names times `<what>Ms`; its other metrics, such as `frameCount`, are counts.
    timed = [
        labels | {"workload": f"{name} {metric}"} | _spread(timings, NS_PER_MS)
        for metric, timings in benchmark["metrics"].items()
        if metric.endswith("Ms")
    ]
    # Per-frame samples, pooled across runs into percentiles.
    sampled = [
        labels
        | {"workload": f"{name} {metric}"}
        | {
            "median_ns": samples["P50"] * NS_PER_MS,
            "p95_ns": samples["P95"] * NS_PER_MS,
            "runs": len(samples["runs"]),
        }
        for metric, samples in benchmark["sampledMetrics"].items()
    ]
    return timed + sampled


def _suite(class_name: str) -> str:
    if class_name.startswith("bench.macrobenchmark."):
        return "app"
    return "boundary" if class_name.endswith(".BoundaryBenchmark") else "core"


def _spread(timings: dict, to_ns: int) -> dict:
    runs = sorted(timings["runs"])
    return {
        "median_ns": timings["median"] * to_ns,
        "p95_ns": runs[math.ceil(0.95 * len(runs)) - 1] * to_ns,
        "runs": len(runs),
    }


def table(rows: list[dict]) -> str:
    """Markdown table: one line per workload, platform and storage, one column per core."""
    cores = sorted({row["core"] for row in rows})
    medians = {}
    for row in rows:
        key = (row["workload"], row["platform"], row["storage"])
        medians.setdefault(key, {})[row["core"]] = f"{row['median_ns'] / 1e6:.2f}"
    header = ["workload", "platform", "storage", *(f"{core} (median ms)" for core in cores)]
    lines = [header, ["---"] * len(header)]
    lines += [
        [*key, *(by_core.get(core, "-") for core in cores)]
        for key, by_core in sorted(medians.items())
    ]
    return "".join(f"| {' | '.join(line)} |\n" for line in lines)


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    for option in ("--core", "--storage", "--build-type", "--git-sha"):
        parser.add_argument(option, required=True)
    parser.add_argument("--out", type=Path, required=True, help="report/results/<timestamp>")
    parser.add_argument("output", type=Path, help="directory holding androidx.benchmark JSON")
    args = parser.parse_args(argv)

    labels = {"core": args.core, "storage": args.storage, "build_type": args.build_type}
    rows = [
        row
        for source in sorted(args.output.rglob("*benchmarkData.json"))
        for row in collect(json.loads(source.read_text()), git_sha=args.git_sha, **labels)
    ]
    args.out.mkdir(parents=True, exist_ok=True)
    (args.out / "results.json").write_text(json.dumps(rows, indent=2) + "\n")
    print(table(rows), end="")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
