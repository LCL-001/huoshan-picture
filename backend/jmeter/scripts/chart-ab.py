"""Render the A/B staircase as a PNG chart.

Reads the same statistics.json copies as compare-ab.py
(backend/jmeter/statistics-ab/<path_label>-t<level>.json) and draws one figure
with two panels: throughput (QPS) and p95 latency, each with one line per path.

Why this exists next to JMeter's own HTML dashboard: that dashboard is
per-run, so it can only ever show ONE path. The DB-vs-cache comparison across
runs only exists in this directory's JSON files.

Console output is ASCII-only on purpose: a Windows console defaults to GBK and
would mangle non-ASCII text.

Usage:
    python chart-ab.py [statistics-ab 目录] [输出目录]
"""

import glob
import json
import os
import re
import sys

try:
    from PIL import Image, ImageDraw, ImageFont
except ImportError:  # optional: the run itself must not depend on a chart
    Image = None

TAG_RE = re.compile(r"^(?P<label>.+)-t(?P<level>\d+)$")

# Series order matters: cache first so it reads as the "reference" line.
SERIES = (
    ("Cache", "#0b7285", "#66d9e8"),
    ("DB", "#c2255c", "#f783ac"),
)

WIDTH = 1080
HEIGHT = 500
PANEL_TOP = 78
PANEL_BOTTOM = HEIGHT - 86
PANEL_GAP = 46
MARGIN_X = 70


def load_runs(stats_dir):
    """Return {label: {level: stats}} from the runner's statistics.json copies."""
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
            candidates = [
                item for name, item in data.items()
                if name != "Total" and not name.startswith("BASELINE-")
                and "health" not in name.lower() and "tag_category" not in name.lower()
            ]
            if not candidates:
                print("! skipped (no usable sampler): {}".format(tag))
                continue
            measured = max(candidates, key=lambda item: int(item.get("sampleCount", 0)))

        runs.setdefault(label, {})[level] = measured
    return runs


def nice_top(value):
    """Round an axis maximum up to a readable value."""
    if value <= 0:
        return 1.0
    magnitude = 10 ** (len(str(int(value))) - 1)
    for step in (1, 1.5, 2, 2.5, 3, 4, 5, 7.5, 10):
        candidate = step * magnitude
        if value <= candidate:
            return candidate
    return 10 * magnitude


def font(size):
    try:
        return ImageFont.load_default(size=size)
    except TypeError:  # Pillow < 10.1 has no size argument
        return ImageFont.load_default()


def label_for(label, index):
    """Map a raw sampler label to a short series name."""
    for name, _, _ in SERIES:
        if name.lower() in label.lower():
            return name
    return "series{}".format(index + 1)


def color_for(name, index):
    for series_name, dark, _ in SERIES:
        if series_name == name:
            return dark
    return "#495057"


def draw_panel(draw, box, levels, runs, value_of, title, unit, fnt, fnt_small):
    left, top, right, bottom = box
    series = []
    for index, (label, by_level) in enumerate(sorted(runs.items())):
        name = label_for(label, index)
        points = [(level, value_of(by_level[level])) for level in levels if level in by_level]
        if points:
            series.append((name, color_for(name, index), points))

    y_max = nice_top(max((v for _, _, pts in series for _, v in pts), default=0) * 1.15)

    draw.rectangle([left, top, right, bottom], outline="#adb5bd")

    grid_steps = 4
    for step in range(grid_steps + 1):
        value = y_max * step / grid_steps
        y = bottom - (bottom - top) * step / grid_steps
        draw.line([left, y, right, y], fill="#e9ecef")
        draw.text((left - 8, y), "{:,.0f}".format(value), fill="#495057",
                  font=fnt_small, anchor="rm")

    x_step = (right - left) / (len(levels) + 1)
    for index, level in enumerate(levels):
        x = left + x_step * (index + 1)
        draw.line([x, bottom, x, bottom + 4], fill="#868e96")
        draw.text((x, bottom + 8), "t{}".format(level), fill="#495057",
                  font=fnt_small, anchor="mt")

    # Lines and markers first, value labels afterwards: where a label can go
    # depends on where every series' point landed at that level, not on a fixed
    # per-series side (the two lines cross, so a fixed side collides).
    markers = {index: [] for index in range(len(levels))}
    for name, color, points in series:
        coords = []
        for index, level in enumerate(levels):
            match = [v for lv, v in points if lv == level]
            if not match:
                continue
            x = left + x_step * (index + 1)
            y = bottom - (bottom - top) * min(match[0] / y_max, 1.0)
            coords.append((x, y))
            markers[index].append((y, match[0], color))
        for a, b in zip(coords, coords[1:]):
            draw.line([a, b], fill=color, width=3)
        for x, y in coords:
            draw.ellipse([x - 4, y - 4, x + 4, y + 4], fill=color)

    # Highest point of the level gets its label above, lowest gets it below.
    for index, items in markers.items():
        if not items:
            continue
        x = left + x_step * (index + 1)
        items.sort(key=lambda item: item[0])
        for rank, (y, value, color) in enumerate(items):
            text = "{:,.1f}".format(value)
            if rank == 0 and len(items) > 1:
                draw.text((x, y - 13), text, fill=color, font=fnt_small, anchor="mb")
            elif rank == len(items) - 1:
                draw.text((x, y + 13), text, fill=color, font=fnt_small, anchor="mt")
            else:
                draw.text((x + 10, y), text, fill=color, font=fnt_small, anchor="lm")

    draw.text(((left + right) / 2, top - 30), title, fill="#212529", font=fnt, anchor="mm")
    draw.text(((left + right) / 2, bottom + 34), unit, fill="#868e96",
              font=fnt_small, anchor="mt")

    legend_x = left + 6
    for name, color, _ in series:
        draw.line([legend_x, top + 12, legend_x + 22, top + 12], fill=color, width=3)
        draw.text((legend_x + 28, top + 12), name, fill="#343a40", font=fnt_small, anchor="lm")
        legend_x += 28 + 40


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    stats_dir = sys.argv[1] if len(sys.argv) > 1 else os.path.join(here, "..", "statistics-ab")
    out_dir = sys.argv[2] if len(sys.argv) > 2 else os.path.join(here, "..", "reports", "charts")

    if Image is None:
        print("Pillow not installed - skipping chart (pip install pillow)")
        return 0

    runs = load_runs(stats_dir)
    if not runs:
        print("no usable runs found in {}".format(stats_dir))
        print("run scripts/run-ab-staircase.ps1 first")
        return 1

    levels = sorted({level for by_level in runs.values() for level in by_level})
    os.makedirs(out_dir, exist_ok=True)

    image = Image.new("RGB", (WIDTH, HEIGHT), "white")
    draw = ImageDraw.Draw(image)
    fnt = font(15)
    fnt_small = font(12)

    panel_width = (WIDTH - 2 * MARGIN_X - PANEL_GAP) / 2
    left_box = (MARGIN_X, PANEL_TOP, MARGIN_X + panel_width, PANEL_BOTTOM)
    right_box = (MARGIN_X + panel_width + PANEL_GAP, PANEL_TOP,
                 WIDTH - MARGIN_X, PANEL_BOTTOM)

    panels = (
        (left_box, "Throughput", "concurrency (threads)  -  QPS",
         lambda item: float(item.get("throughput", 0))),
        (right_box, "p95 latency", "concurrency (threads)  -  ms",
         lambda item: float(item.get("pct2ResTime", 0))),
    )
    for box, title, unit, value_of in panels:
        draw_panel(draw, box, levels, runs, value_of, title, unit, fnt, fnt_small)

    samples = sum(
        int(item.get("sampleCount", 0))
        for by_level in runs.values() for item in by_level.values()
    )
    errors = max(
        (float(item.get("errorPct", 0))
         for by_level in runs.values() for item in by_level.values()),
        default=0.0,
    )
    caption = (
        "source: statistics-ab/*.json  -  separate JMeter process per path, "
        "one path per run  -  {} runs, {:,} samples, max err {:.1f}%".format(
            sum(len(by_level) for by_level in runs.values()), samples, errors)
    )
    draw.text((MARGIN_X, HEIGHT - 26), caption, fill="#868e96", font=fnt_small)
    draw.text((MARGIN_X, 22), "List query: DB path vs Caffeine+Redis cache path",
              fill="#212529", font=fnt)

    out_path = os.path.join(out_dir, "ab-staircase.png")
    image.save(out_path)
    print("wrote {}".format(os.path.abspath(out_path)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
