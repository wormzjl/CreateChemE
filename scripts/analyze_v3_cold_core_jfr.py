#!/usr/bin/env python3
"""Summarize the separate four-recording V3 JFR profile panel.

The result is descriptive profiling evidence only.  It does not read or alter
the primary benchmark timing journal.
"""
from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any


def jfr_json(jfr: Path, recording: Path, events: str) -> list[dict[str, Any]]:
    result = subprocess.run([str(jfr), "print", "--json", "--events", events, "--stack-depth", "32", str(recording)],
                            capture_output=True, text=True, encoding="utf-8", check=True)
    return json.loads(result.stdout).get("recording", {}).get("events", [])


def jfr_summary(jfr: Path, recording: Path) -> dict[str, int]:
    result = subprocess.run([str(jfr), "summary", str(recording)], capture_output=True, text=True,
                            encoding="utf-8", check=True)
    counts: dict[str, int] = {}
    for line in result.stdout.splitlines():
        match = re.match(r"\s*(jdk\.[\w.]+)\s+(\d+)\s+\d+", line)
        if match: counts[match.group(1)] = int(match.group(2))
    return counts


def frame_name(frame: dict[str, Any]) -> str | None:
    method = frame.get("method", {})
    owner = method.get("type", {}).get("name")
    name = method.get("name")
    if not owner or not name: return None
    return f"{owner.replace('/', '.')}.{name}"


def sample_frames(events: list[dict[str, Any]]) -> tuple[Counter[str], Counter[str], Counter[str], int, int]:
    all_frames: Counter[str] = Counter(); main_frames: Counter[str] = Counter(); main_inclusive: Counter[str] = Counter()
    total = main = 0
    for event in events:
        values = event.get("values", {})
        frames = (values.get("stackTrace") or {}).get("frames", [])
        label = frame_name(frames[0]) if frames else None
        if not label: continue
        total += 1; all_frames[label] += 1
        if values.get("sampledThread", {}).get("javaName") == "main":
            main += 1; main_frames[label] += 1
            for frame in frames:
                name = frame_name(frame)
                if name: main_inclusive[name] += 1
    return all_frames, main_frames, main_inclusive, total, main


def allocation_samples(events: list[dict[str, Any]]) -> dict[str, Any]:
    raw_class: Counter[str] = Counter(); raw_frame: Counter[str] = Counter()
    qualified_class: Counter[str] = Counter(); qualified_frame: Counter[str] = Counter()
    raw_count = raw_weight = qualified_count = qualified_weight = 0
    first_event_by_thread: set[str] = set()
    for event in sorted(events, key=lambda event: event.get("values", {}).get("startTime", "")):
        values = event.get("values", {})
        allocation_weight = values.get("weight", 0)
        if not isinstance(allocation_weight, (int, float)): allocation_weight = 0
        raw_count += 1; raw_weight += int(allocation_weight)
        object_type = (values.get("objectClass") or {}).get("name", "<unknown>").replace("/", ".")
        frames = (values.get("stackTrace") or {}).get("frames", [])
        label = frame_name(frames[0]) if frames else "<no stack>"
        label = label or "<no stack>"
        raw_class[object_type] += int(allocation_weight); raw_frame[label] += int(allocation_weight)
        thread = values.get("eventThread") or {}
        thread_key = str(thread.get("javaThreadId", thread.get("osThreadId", "unknown")))
        # A first ObjectAllocationSample can cover allocation before JFR began.
        # Preserve raw totals, then expose a separately labelled qualified view.
        if thread_key in first_event_by_thread:
            qualified_count += 1; qualified_weight += int(allocation_weight)
            qualified_class[object_type] += int(allocation_weight); qualified_frame[label] += int(allocation_weight)
        else:
            first_event_by_thread.add(thread_key)
    return {"rawEventDenominator": raw_count, "rawSampledWeightBytes": raw_weight,
            "topRawObjectClassesBySampledWeight": top(raw_class, raw_weight),
            "topRawLeafFramesBySampledWeight": top(raw_frame, raw_weight),
            "withinRecordingAfterFirstPerThreadEventDenominator": qualified_count,
            "withinRecordingAfterFirstPerThreadSampledWeightBytes": qualified_weight,
            "topWithinRecordingObjectClassesBySampledWeight": top(qualified_class, qualified_weight),
            "topWithinRecordingLeafFramesBySampledWeight": top(qualified_frame, qualified_weight)}


def top(counter: Counter[str], denominator: int, limit: int = 12) -> list[dict[str, Any]]:
    return [{"name": name, "count": count, "share": count / denominator if denominator else None}
            for name, count in counter.most_common(limit)]


def profile_rows(profile_dir: Path, jfr: Path) -> list[dict[str, Any]]:
    journal = [json.loads(line) for line in (profile_dir / "profile-samples.jsonl").read_text(encoding="utf-8").splitlines()]
    samples = {(row.get("revision"), row.get("caseId")): row for row in journal
               if row.get("kind") == "profileSample" and row.get("phase") == "profile"}
    rows = []
    for path in sorted(profile_dir.glob("*.jfr")):
        match = re.fullmatch(r"(baseline|candidate)-(A150-quarter-off|N30-on)\.jfr", path.name)
        if not match: continue
        revision, case_id = match.groups()
        execution = jfr_json(jfr, path, "ExecutionSample")
        allocations = jfr_json(jfr, path, "ObjectAllocationSample")
        all_frames, main_frames, inclusive_main, total_exec, main_exec = sample_frames(execution)
        allocation = allocation_samples(allocations)
        focus = {name: count for name, count in main_frames.items()
                 if any(token in name.lower() for token in ("equilibr", "band", "convert"))}
        worker = samples.get((revision, case_id), {}).get("sample", {})
        support = worker.get("terminalSupport", {})
        closure = worker.get("closure", {})
        rows.append({"revision": revision, "caseId": case_id, "jfrFile": path.name, "jfrBytes": path.stat().st_size,
                     "eventCounts": jfr_summary(jfr, path),
                     "executionSamples": {"allThreadDenominator": total_exec, "mainThreadDenominator": main_exec,
                                          "topAllThreadLeafFrames": top(all_frames, total_exec),
                                          "topMainThreadLeafFrames": top(main_frames, main_exec),
                                          "equilibrationBandConversionMainSamples": top(Counter(focus), main_exec),
                                          "inclusiveMainFrameCounts": {
                                              name: {"count": inclusive_main[name], "share": inclusive_main[name] / main_exec if main_exec else None}
                                              for name in ("com.wormzjl.createcheme.science.column.v3.linalg.V3BandedPivotedSolver$SparseRows.columnMaximum",
                                                           "com.wormzjl.createcheme.science.column.v3.linalg.V3BandedPivotedSolver$SparseRows.rowMaximum",
                                                           "com.wormzjl.createcheme.science.column.v3.linalg.V3BandedPivotedSolver.solve",
                                                           "java.util.TreeMap.getEntry")}},
                     "allocationSamples": allocation,
                     "profileCall": {"status": samples.get((revision, case_id), {}).get("status"),
                                     "elapsedMs": worker.get("elapsedMs"),
                                     "workerThreadAllocatedBytesDelta": worker.get("threadAllocatedBytes"),
                                     "accepted": worker.get("acceptanceAudit", {}).get("accepted"),
                                     "hydrocarbonClosed": closure.get("hydrocarbonClosed"), "waterClosed": closure.get("waterClosed"),
                                     "retainedPointCount": support.get("retainedPointCount"), "removedPointCount": support.get("removedPointCount"),
                                     "nativeReducedSuccess": support.get("nativeReducedSuccess"),
                                     "fallbackToUntruncated": support.get("fallbackToUntruncated")}})
    return rows


def markdown(rows: list[dict[str, Any]]) -> str:
    lines = ["# V3 cold-core JFR profile summary", "",
             "These are four post-timing, serial JFR recordings. They are not primary timing samples: JFR `settings=profile`, its repository/temp flags, and one profile call per cell change the measurement population.", "",
             "Execution-frame shares use the listed **main-thread execution-sample denominator**. The worker's `threadAllocatedBytes` delta is the quantitative allocation field for its call scope. JFR `ObjectAllocationSample.weight` is an interval sample estimate; each thread's first sample can span pre-recording warmup allocation, so raw weights remain contaminated diagnostics and source rankings use a separately labelled after-first-event view.", "",
             "JFR starts before worker request dispatch and stops after its result, so it includes protocol/request construction and result serialization around the calculator. Neither elapsed time nor allocation samples from this panel are primary benchmark metrics.", "",
             "| Revision | Case | Status | Accepted | HC / water closure | Retained / removed | Exact worker allocation delta | Main execution samples | Raw JFR weight | Qualified JFR weight |", "|---|---|---|:---:|---|---:|---:|---:|---:|---:|"]
    for row in rows:
        call, execution, allocation = row["profileCall"], row["executionSamples"], row["allocationSamples"]
        lines.append(f"| {row['revision']} | {row['caseId']} | {call['status']} | {call['accepted']} | {call['hydrocarbonClosed']} / {call['waterClosed']} | {call['retainedPointCount']} / {call['removedPointCount']} | {call['workerThreadAllocatedBytesDelta']} | {execution['mainThreadDenominator']} | {allocation['rawSampledWeightBytes']} | {allocation['withinRecordingAfterFirstPerThreadSampledWeightBytes']} |")
    lines += ["", "## Main-thread sampled hotspots", ""]
    for row in rows:
        execution, allocation = row["executionSamples"], row["allocationSamples"]
        lines.append(f"### {row['revision']} {row['caseId']}")
        for item in execution["topMainThreadLeafFrames"][:5]:
            lines.append(f"- `{item['name']}` — {item['count']}/{execution['mainThreadDenominator']} samples ({item['share']:.1%}).")
        focus = execution["equilibrationBandConversionMainSamples"]
        if focus:
            lines.append("- Equilibration/band/conversion-name matches: " + ", ".join(f"`{item['name']}` {item['count']}" for item in focus) + ".")
        inclusive = execution["inclusiveMainFrameCounts"]
        lines.append("- Inclusive main-stack samples: " + ", ".join(f"`{name}` {item['count']}/{execution['mainThreadDenominator']}" for name, item in inclusive.items()) + ".")
        lines.append("- Qualified post-first-event allocation sources: " + ", ".join(f"`{item['name']}` {item['share']:.1%}" for item in allocation["topWithinRecordingLeafFramesBySampledWeight"][:3]) + ".")
        lines.append("")
    lines += ["## Interpretation limits", "", "JFR execution samples identify where sampled CPU time landed; leaf and inclusive stack counts are distinct and neither proves causal inclusive cost. The qualified allocation view only removes the known first-event boundary contamination and remains sampled evidence. These profiles suggest follow-up targets but do not establish a primary benchmark speedup, slowdown, or causal ablation.", ""]
    return "\n".join(lines)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("profile_dir", type=Path)
    parser.add_argument("--jfr", type=Path, default=Path(r"C:\Program Files\Zulu\zulu-25\bin\jfr.exe"))
    args = parser.parse_args(argv)
    rows = profile_rows(args.profile_dir.resolve(), args.jfr.resolve())
    if len(rows) != 4: raise RuntimeError(f"expected four JFR files, found {len(rows)}")
    (args.profile_dir / "profile-summary.json").write_text(json.dumps({"recordings": rows}, indent=2) + "\n", encoding="utf-8")
    (args.profile_dir / "profile-summary.md").write_text(markdown(rows), encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
