"""汇总 JMeter HTML 报告目录下的 statistics.json，输出 Markdown 表格。

用法：
    python summarize_reports.py [statistics 目录]

默认读取同级的 statistics/ 目录（仓库中保存的是各次压测报告里的
statistics.json）。JMeter 的 HTML 报告是生成物，仓库不保存，
需要时用同一份 .jmx 重跑并指定 -o 输出目录即可重新生成。
"""
import glob
import json
import os
import sys


def percentile_label(key):
    return {"pct1ResTime": "p90", "pct2ResTime": "p95", "pct3ResTime": "p99"}.get(key, key)


def main():
    default_dir = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "statistics")
    stats_dir = sys.argv[1] if len(sys.argv) > 1 else default_dir

    rows = []
    for path in sorted(glob.glob(os.path.join(stats_dir, "*.json"))):
        scenario = os.path.splitext(os.path.basename(path))[0]
        with open(path, encoding="utf-8") as fh:
            data = json.load(fh)
        total = data.get("Total")
        if not total:
            continue
        rows.append((scenario, total))

    print("| 场景 | 样本数 | 错误率 | 吞吐(QPS) | 平均响应(ms) | p95(ms) |")
    print("| --- | ---: | ---: | ---: | ---: | ---: |")
    total_samples = 0
    for scenario, total in rows:
        samples = int(total.get("sampleCount", 0))
        total_samples += samples
        print("| {} | {} | {}% | {} | {} | {} |".format(
            scenario,
            f"{samples:,}",
            total.get("errorPct", 0),
            round(total.get("throughput", 0), 2),
            round(total.get("meanResTime", 0), 2),
            round(total.get("pct2ResTime", 0), 2),
        ))
    print()
    print("累计样本数：{}".format(f"{total_samples:,}"))

    print()
    print("### 各场景采样器明细（p95）")
    print()
    print("| 场景 | 采样器 | 样本数 | 平均(ms) | p95(ms) |")
    print("| --- | --- | ---: | ---: | ---: |")
    for scenario, _ in rows:
        path = os.path.join(stats_dir, scenario + ".json")
        with open(path, encoding="utf-8") as fh:
            data = json.load(fh)
        for label, item in data.items():
            if label == "Total":
                continue
            print("| {} | {} | {} | {} | {} |".format(
                scenario,
                label,
                f"{int(item.get('sampleCount', 0)):,}",
                round(item.get("meanResTime", 0), 2),
                round(item.get("pct2ResTime", 0), 2),
            ))


if __name__ == "__main__":
    main()
