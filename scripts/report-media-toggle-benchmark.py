"""Summarize completed, comparable media toggle CSVs using only Python's standard library.

Usage: python scripts/report-media-toggle-benchmark.py baseline.csv current.csv
Does not connect to databases or the network, or change source data.
"""
import argparse
import csv
import json
import math
from pathlib import Path
from statistics import median


def percentile(values, quantile):
    values = sorted(values)
    return values[max(0, math.ceil(len(values) * quantile) - 1)]


def load(path):
    with path.open(encoding="utf-8-sig", newline="") as source:
        rows = list(csv.DictReader(source))
    if not rows:
        raise ValueError(f"No samples in {path}")
    return rows


def summarize(path):
    rows = load(path)
    print(f"\nFile: {path}\n")
    print("| Media | Activation | n | FX dispatch median/P95 ms | Publish complete median/P95 ms | Remote data median/P95 ms |")
    print("|---|---|---:|---:|---:|---:|")
    for kind in ("VIDEO", "AUDIO"):
        for activation in ("cold", "warm"):
            group = [row for row in rows if row["kind"] == kind and row["activation"] == activation]
            if not group:
                continue
            cells = [kind, activation, str(len(group))]
            for name in ("fx_dispatch_ms", "publish_complete_ms", "remote_data_ms"):
                values = [float(row[name]) for row in group]
                cells.append(f"{median(values):.3f} / {percentile(values, .95):.3f}")
            print("| " + " | ".join(cells) + " |")
    print("\nCold = first activation per round, warm = repeated activation after 300 ms off.")
    print("Remote data = decoded frame for VIDEO; both PCM callback and fresh inbound RTP for AUDIO; not screen/speaker presentation.")
    print("Cold samples are too few for a meaningful tail-latency claim. Inspect the CSV for round-by-round variation.")
    print(f"Thread sample range: {min(int(r['threads']) for r in rows)} to {max(int(r['threads']) for r in rows)}.")
    print(f"Heap sample range MiB: {min(int(r['heap_bytes']) for r in rows)/1048576:.2f} to {max(int(r['heap_bytes']) for r in rows)/1048576:.2f}.")


def diagnostics(directory):
    events = {}
    timings = {}
    resources = {}
    for path in directory.glob("*.txt"):
        for line in path.read_text(encoding="utf-8-sig").splitlines():
            try:
                row = json.loads(line)
            except (ValueError, TypeError):
                continue
            event = row.get("event", "unknown")
            fields = row.get("fields", {})
            events[event] = events.get(event, 0) + 1
            if event == "runtime.sample":
                for metric in ("process_cpu_pct", "heap_used_bytes", "thread_count", "gc_time_ms_delta"):
                    if isinstance(fields.get(metric), (int, float)):
                        resources.setdefault("jvm/" + metric, []).append(fields[metric])
            if event == "video.engine.sample" and "kind" not in fields:
                for metric in ("active_peer_connections", "go_heap_bytes", "goroutine_count", "queue_packets", "queue_drops"):
                    if isinstance(fields.get(metric), (int, float)):
                        resources.setdefault("sfu/" + metric, []).append(fields[metric])
            if event in ("signaling.client.request", "signaling.request.completed"):
                action = fields.get("action", "unknown")
                for metric in ("signaling_round_trip_ms", "handler_ms", "queue_ms"):
                    key = f"{event}/{action}/{metric}"
                    if isinstance(fields.get(metric), (int, float)):
                        timings.setdefault(key, []).append(fields[metric])
    print(f"\nDiagnostics: {directory}\n")
    print("| Request | n | Median ms | P95 ms |")
    print("|---|---:|---:|---:|")
    for key, values in sorted(timings.items()):
        print(f"| {key} | {len(values)} | {median(values):.3f} | {percentile(values, .95):.3f} |")
    if resources:
        print("\n| Sampled resource | n | Median | P95 | Max observed |")
        print("|---|---:|---:|---:|---:|")
        for key, values in sorted(resources.items()):
            print(f"| {key} | {len(values)} | {median(values):.3f} | {percentile(values, .95):.3f} | {max(values):.3f} |")
    print("\nEvents: " + ", ".join(f"{key}={value}" for key, value in sorted(events.items())))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("csv", nargs="+", type=Path)
    parser.add_argument("--diagnostics", type=Path, action="append", default=[])
    arguments = parser.parse_args()
    for source in arguments.csv:
        summarize(source)
    for directory in arguments.diagnostics:
        diagnostics(directory)
