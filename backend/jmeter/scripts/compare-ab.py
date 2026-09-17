"""Compare the DB path against the cache path across concurrency levels.

Reads the statistics.json copies written by run-ab-staircase.ps1
(backend/jmeter/statistics-ab/<path_label>-t<level>.json) and prints two tables:

  1. per concurrency level: DB path vs cache path, each against the network
     baseline measured in the SAME run;
  2. per path: throughput across levels, i.e. the staircase - if throughput
     stops rising while p95 climbs, that level is the inflection point.

Why the baseline matters: the target is usually reached over the public
internet, so a large share of every response time is RTT + HTTPS and has
nothing to do with the server. The "net" columns subtract the baseline measured
in the same run, which is the only defensible way to compare two server-side
paths. It is still an approximation: the baseline group runs at low concurrency
while the measured path runs at N threads, so contention is not identical.

Usage:
    python compare-ab.py [statistics-ab 目录]

Console output is ASCII-only on purpose: a Windows console defaults to GBK and
would mangle non-ASCII text.
"""
import glob
import json
import os
import re
import sys

# label-t<level>.json
TAG_RE = re.compile(r"^(?P<label>.+)-t(?P<level>\d+)$")
# Baseline samplers carry no server work: they measure the network floor.
# Matched by prefix (this plan) or by substring (legacy Chinese-named reports).
BASELINE_PREFIX = "BASELINE-"
BASELINE_HINTS = ("health", "tag_category")


def is_baseline(name):
    if name.startswith(BASELINE_PREFIX):
        return True
    lowered = name.lower()
    return any(hint in lowered for hint in BASELINE_HINTS)


def load_runs(stats_dir):
    """Return {level: {label: sampler_stats}} plus the baseline per level."""
    runs = {}
    for path in sorted(glob.glob(os.path.join(stats_dir, "*.json"))):
        tag = os.path.splitext(os.path.basename(path))[0]
        match = TAG_RE.match(tag)
        if not match:
            print("! skipped (name does not match <label>-t<level>): {}".format(tag))
            continue
        with open(path, encoding="utf-8") as fh:
            data = json.load(fh)

        label = match.group("label")
        level = int(match.group("level"))

        measured = data.get(label)
        if measured is None:
            # Fallback: the measured sampler is the one carrying the load, so it
            # has far more samples than any single baseline. This keeps the
            # comparison working even if the label placeholder in the plan did
            # not resolve to the expected name.
            candidates = [
                (name, item) for name, item in data.items()
                if name != "Total" and not is_baseline(name)
            ]
            if not candidates:
                print("! skipped (no usable sampler): {}".format(tag))
                continue
            name, measured = max(candidates, key=lambda pair: int(pair[1].get("sampleCount", 0)))
            print("! {}: label '{}' not found, using '{}' instead".format(tag, label, name))

        baselines = [item for name, item in data.items() if is_baseline(name)]
        runs.setdefault(level, {})[label] = {
            "measured": measured,
            "baselines": baselines,
            "tag": tag,
        }
    return runs


def p95(item):
    return float(item.get("pct2ResTime", 0))


def p99(item):
    return float(item.get("pct3ResTime", 0))


def worst_baseline_p95(baselines):
    """The slowest baseline of the run: the most conservative RTT floor."""
    if not baselines:
        return None
    return max(p95(item) for item in baselines)


def net(value, baseline):
    """Latency with the network floor removed, floored at 0."""
    if baseline is None:
        return None
    return max(value - baseline, 0.0)


def fmt(value, digits=1):
    return "-" if value is None else round(value, digits)


def main():
    default_dir = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "statistics-ab")
    stats_dir = sys.argv[1] if len(sys.argv) > 1 else default_dir

    runs = load_runs(stats_dir)
    if not runs:
        print("no usable runs found in {}".format(stats_dir))
        print("run scripts/run-ab-staircase.ps1 first")
        return 1

    levels = sorted(runs)
    labels = sorted({label for level in runs for label in runs[level]})

    print("### Per-level comparison (measured path vs baseline of the same run)")
    print()
    print("| level | path | samples | err% | QPS | mean(ms) | p95(ms) | "
          "baseline p95(ms) | net p95(ms) | net p99(ms) |")
    print("| ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |")

    for level in levels:
        for label in labels:
            entry = runs[level].get(label)
            if entry is None:
                continue
            item = entry["measured"]
            base = worst_baseline_p95(entry["baselines"])
            print("| {} | {} | {} | {} | {} | {} | {} | {} | {} | {} |".format(
                level,
                label,
                "{:,}".format(int(item.get("sampleCount", 0))),
                item.get("errorPct", 0),
                fmt(item.get("throughput")),
                fmt(item.get("meanResTime")),
                fmt(p95(item)),
                fmt(base),
                fmt(net(p95(item), base)),
                fmt(net(p99(item), base)),
            ))

    print()
    print("### Staircase (throughput and p95 per level)")
    print()
    print("| path | level | QPS | mean(ms) | p95(ms) | p99(ms) | QPS vs prev | p95 vs prev |")
    print("| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |")

    for label in labels:
        prev_qps = None
        prev_p95 = None
        for level in levels:
            entry = runs[level].get(label)
            if entry is None:
                continue
            item = entry["measured"]
            qps = float(item.get("throughput", 0))
            cur_p95 = p95(item)

            qps_delta = "-" if prev_qps is None else "{:+.1f}%".format((qps / prev_qps - 1) * 100 if prev_qps else 0)
            p95_delta = "-" if prev_p95 is None else "{:+.1f}%".format((cur_p95 / prev_p95 - 1) * 100 if prev_p95 else 0)

            print("| {} | {} | {} | {} | {} | {} | {} | {} |".format(
                label, level, fmt(qps), fmt(item.get("meanResTime")),
                fmt(cur_p95), fmt(p99(item)), qps_delta, p95_delta,
            ))
            prev_qps, prev_p95 = qps, cur_p95
        print()

    print("Notes")
    print()
    print("- Each row is a SEPARATE JMeter run (fresh JVM): the DB path and the")
    print("  cache path never share a JVM, so they do not compete for threads or")
    print("  connection pool, and neither carries the other's queuing cost.")
    print("- 'net' columns subtract the slowest baseline of the same run; they are")
    print("  the closest available proxy for server-side cost over a public")
    print("  network. For a real server-side number, rerun against localhost or an")
    print("  inner-network address.")
    print("- A rising QPS with a flat p95 means the level is still below the")
    print("  inflection point; QPS flattening while p95 climbs marks the knee.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
