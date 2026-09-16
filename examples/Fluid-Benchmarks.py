"""Audit and summarize preserved fluid benchmark evidence.

Run from the checkout root with Python 3. No third-party packages are required.
"""
import argparse
import csv
import hashlib
import json
import math
import re
from collections import Counter, defaultdict
from datetime import datetime, timedelta, timezone
from pathlib import Path


RUNTIME_ERRORS = (
    "Error executing task on Server",
    "process_solver lifecycle=DRAIN_FAILED",
    "Encountered an unexpected exception",
    "Game test server crashed",
    "process_solver lifecycle=STOPPED_WITH_FAULT",
    "OutOfMemoryError",
)

MEMORY_JFR_EVENTS = (
    "jdk.ObjectAllocationSample",
    "jdk.GarbageCollection",
    "jdk.GCPhasePause",
    "jdk.GCHeapSummary",
    "jdk.CPULoad",
)


def runtime_error_lines(log_bytes):
    return [
        line
        for line in log_bytes.decode("utf-8-sig").splitlines()
        if any(marker in line for marker in RUNTIME_ERRORS)
    ]


def audit_runtime(report_path, log_path):
    report_bytes = report_path.read_bytes()
    log_bytes = log_path.read_bytes()
    report = json.loads(report_bytes)
    matches = runtime_error_lines(log_bytes)
    return {
        "status": "FAILED_RUNTIME_ERRORS" if matches else "PASS",
        "errorCount": len(matches),
        "patterns": RUNTIME_ERRORS,
        "examples": matches[:25],
        "artifactSha256": report["manifest"]["artifactSha256"],
        "reportSha256": hashlib.sha256(report_bytes).hexdigest(),
        "logSha256": hashlib.sha256(log_bytes).hexdigest(),
    }


def statistics(values):
    values = sorted(values)
    if not values:
        return {"count": 0, "median": None, "p95": None, "max": None}
    return {
        "count": len(values),
        "median": values[math.ceil(len(values) * 0.5) - 1],
        "p95": values[math.ceil(len(values) * 0.95) - 1],
        "max": values[-1],
    }


def stress_statistics(values):
    summary = statistics(values)
    return {
        "count": summary["count"],
        "p95": summary["p95"],
        "max": summary["max"],
    }


def memory_statistics(values):
    values = sorted(values)
    summary = statistics(values)
    return {
        "count": summary["count"],
        "min": values[0] if values else None,
        "median": summary["median"],
        "p95": summary["p95"],
        "p99": values[math.ceil(len(values) * 0.99) - 1] if values else None,
        "max": summary["max"],
    }


def parse_jfr_duration_seconds(value):
    if isinstance(value, (int, float)) and not isinstance(value, bool):
        return float(value)
    match = re.fullmatch(r"PT(?:(\d+(?:\.\d+)?)H)?(?:(\d+(?:\.\d+)?)M)?(\d+(?:\.\d+)?)S", value)
    if not match:
        raise ValueError(f"Unsupported JFR duration: {value!r}")
    hours, minutes, seconds = (float(part or 0) for part in match.groups())
    return hours * 3600 + minutes * 60 + seconds


def parse_jfr_time(value):
    return datetime.fromisoformat(value.replace("Z", "+00:00"))


def jfr_class_name(value):
    name = value.get("name", "<unknown>") if isinstance(value, dict) else str(value)
    dimensions = len(name) - len(name.lstrip("["))
    if not dimensions:
        return name.replace("/", ".")
    component = name[dimensions:]
    primitives = {"B": "byte", "C": "char", "D": "double", "F": "float", "I": "int",
                  "J": "long", "S": "short", "Z": "boolean"}
    if component in primitives:
        base = primitives[component]
    elif component.startswith("L") and component.endswith(";"):
        base = component[1:-1].replace("/", ".")
    else:
        base = component.replace("/", ".")
    return base + "[]" * dimensions


def jfr_allocation_site(values):
    frames = (values.get("stackTrace") or {}).get("frames") or []
    if not frames:
        return "<no-stack>"
    method = frames[0].get("method") or {}
    owner = (method.get("type") or {}).get("name", "<unknown>").replace("/", ".")
    return f"{owner}.{method.get('name', '<unknown>')}"


def ranked_weights(weights, total, limit):
    return [
        {"name": name, "sampledWeightBytes": weight,
         "share": weight / total if total else None}
        for name, weight in sorted(weights.items(), key=lambda item: (-item[1], item[0]))[:limit]
    ]


def summarize_memory_events(events, measurement_start=None, measurement_end=None, top=15):
    start = parse_jfr_time(measurement_start) if isinstance(measurement_start, str) else measurement_start
    end = parse_jfr_time(measurement_end) if isinstance(measurement_end, str) else measurement_end
    if (start is None) != (end is None) or (start is not None and end <= start):
        raise ValueError("Measurement start and end must form a positive explicit window")

    first_time = last_time = None
    allocation_weight = allocation_samples = gc_events = 0
    allocation_types, allocation_sites = Counter(), Counter()
    pause_millis, heap_before, heap_after = [], [], []
    jvm_cpu_load, machine_cpu_load = [], []
    clipped_pause_events = 0
    for event in events:
        event_type = event.get("type")
        values = event.get("values") or {}
        if event_type not in MEMORY_JFR_EVENTS or "startTime" not in values:
            continue
        timestamp = parse_jfr_time(values["startTime"])
        event_end = timestamp
        if event_type == "jdk.GCPhasePause":
            duration_seconds = parse_jfr_duration_seconds(values["duration"])
            event_end = timestamp + timedelta(seconds=duration_seconds)
        first_time = timestamp if first_time is None or timestamp < first_time else first_time
        last_time = event_end if last_time is None or event_end > last_time else last_time
        if event_type == "jdk.GCPhasePause":
            if start is None:
                pause_millis.append(duration_seconds * 1000)
            else:
                overlap_start = max(timestamp, start)
                overlap_end = min(event_end, end)
                if overlap_end > overlap_start:
                    overlap_seconds = (overlap_end - overlap_start).total_seconds()
                    pause_millis.append(overlap_seconds * 1000)
                    clipped_pause_events += timestamp < start or event_end > end
            continue
        if start is not None and not (start <= timestamp < end):
            continue
        if event_type == "jdk.ObjectAllocationSample":
            weight = values["weight"]
            if not isinstance(weight, int) or isinstance(weight, bool) or weight < 0:
                raise ValueError("Allocation sample weight must be a nonnegative integer")
            allocation_samples += 1
            allocation_weight += weight
            allocation_types[jfr_class_name(values.get("objectClass", {}))] += weight
            allocation_sites[jfr_allocation_site(values)] += weight
        elif event_type == "jdk.GarbageCollection":
            gc_events += 1
        elif event_type == "jdk.CPULoad":
            jvm_cpu_load.append(values["jvmUser"] + values["jvmSystem"])
            machine_cpu_load.append(values["machineTotal"])
        else:
            heap_used = values["heapUsed"]
            if not isinstance(heap_used, int) or isinstance(heap_used, bool) or heap_used < 0:
                raise ValueError("GC heap-used value must be a nonnegative integer")
            if values["when"] == "Before GC":
                heap_before.append(heap_used)
            elif values["when"] == "After GC":
                heap_after.append(heap_used)

    effective_start = start or first_time
    effective_end = end or last_time
    if effective_start is None or effective_end is None:
        raise ValueError("JFR export contains none of the required memory events")
    duration_seconds = (effective_end - effective_start).total_seconds()
    total_pause = sum(pause_millis)
    pause_summary = memory_statistics(pause_millis)
    pause_summary["total"] = total_pause
    pause_summary["fractionOfWindow"] = total_pause / (duration_seconds * 1000) if duration_seconds > 0 else None
    return {
        "window": {
            "start": effective_start.isoformat(),
            "end": effective_end.isoformat(),
            "durationSeconds": duration_seconds,
            "explicit": start is not None,
        },
        "allocationSamples": allocation_samples,
        "sampledAllocationWeightBytes": allocation_weight,
        "sampledAllocationWeightBytesPerSecond": allocation_weight / duration_seconds if duration_seconds > 0 else None,
        "topAllocationTypes": ranked_weights(allocation_types, allocation_weight, top),
        "topAllocationSites": ranked_weights(allocation_sites, allocation_weight, top),
        "gcCollectionEvents": gc_events,
        "gcPauseEvents": len(pause_millis),
        "gcPauseClippedEvents": clipped_pause_events,
        "gcPauseMilliseconds": pause_summary,
        "heapUsedBeforeGcBytes": memory_statistics(heap_before),
        "heapUsedAfterGcBytes": memory_statistics(heap_after),
        "cpuLoad": {
            "jvmFraction": {
                "count": len(jvm_cpu_load),
                "mean": sum(jvm_cpu_load) / len(jvm_cpu_load) if jvm_cpu_load else None,
                "p95": statistics(jvm_cpu_load)["p95"],
            },
            "machineFraction": {
                "count": len(machine_cpu_load),
                "mean": sum(machine_cpu_load) / len(machine_cpu_load) if machine_cpu_load else None,
                "p95": statistics(machine_cpu_load)["p95"],
            },
        },
        "semantics": {
            "sampledAllocationWeightBytes": "JFR sampled allocation weight estimates allocation pressure; it is neither exact allocated bytes nor live retained memory.",
            "heapUsedAfterGcBytes": "Heap used after a collector event is an observed heap-occupancy point, not a retained-object measurement or a hard live-set floor.",
            "gcCounts": "Collection-event count and pause-event count are separate; one collection may have multiple pauses and concurrent work.",
            "gcPauseMilliseconds": "Pause events that overlap the measurement boundaries are included, with duration clipped to the exact window.",
            "cpuLoad": "JFR CPULoad samples are fractions of total host CPU capacity; these are means of timestamp-selected samples, not isolated CPU-time attribution.",
        },
    }


def iter_jfr_json_events(stream, chunk_size=1024 * 1024):
    decoder = json.JSONDecoder()
    buffer = ""
    position = 0
    found_events = False
    end_of_input = False
    while True:
        if position:
            buffer = buffer[position:]
            position = 0
        if not end_of_input and not buffer:
            chunk = stream.read(chunk_size)
            if chunk:
                buffer += chunk
            else:
                end_of_input = True
        if not found_events:
            match = re.search(r'"events"\s*:\s*\[', buffer)
            if match:
                position = match.end()
                found_events = True
                continue
            if end_of_input:
                raise ValueError("JFR JSON export has no recording.events array")
            buffer = buffer[-32:]
            chunk = stream.read(chunk_size)
            if chunk:
                buffer += chunk
            else:
                end_of_input = True
            continue
        while position < len(buffer) and (buffer[position].isspace() or buffer[position] == ","):
            position += 1
        if position < len(buffer) and buffer[position] == "]":
            return
        try:
            event, position = decoder.raw_decode(buffer, position)
            yield event
        except json.JSONDecodeError:
            if end_of_input:
                raise ValueError("Truncated or invalid event in JFR JSON export")
            if position:
                buffer = buffer[position:]
                position = 0
            chunk = stream.read(chunk_size)
            if chunk:
                buffer += chunk
            else:
                end_of_input = True
            continue


def memory_benchmark_metadata(report):
    manifest = report.get("manifest", {})
    fields = ("status", "profile", "processId", "startEpochMillis", "measuredStartEpochMillis",
              "warmupTicks", "warmupHeldIntervals", "measuredSeconds", "elapsedSeconds", "workers",
              "islands", "heldIntervals", "approximateIntervals", "everyIslandAdvanced",
              "stressIntegrityPassed", "finalDebtSeconds")
    return {
        "runId": manifest.get("runId"),
        "artifactSha256": manifest.get("artifactSha256"),
        "fixtureClassesSha256": manifest.get("fixtureClassesSha256"),
        "configSha256": manifest.get("configSha256"),
        **{field: report.get(field) for field in fields if field in report},
    }


def summarize_scientific_progress(report):
    samples = report.get("samples", [])
    accepted = [sample for sample in samples if sample.get("timing", {}).get("accepted") is True]
    simulated_seconds = sum(
        (sample["timing"]["endTick"] - sample["timing"]["startTick"]) / 20
        for sample in accepted
    )
    measured_seconds = report.get("measuredSeconds")
    islands = report.get("islands")
    return {
        "acceptedIntervals": len(accepted),
        "simulatedSecondsAdvanced": simulated_seconds,
        "measuredSeconds": measured_seconds,
        "equivalentFiveSecondIntervalsPerSecond": (
            simulated_seconds / (5 * measured_seconds) if measured_seconds else None
        ),
        "aggregateRealtimeRatio": (
            simulated_seconds / (islands * measured_seconds) if islands and measured_seconds else None
        ),
        "heldIntervals": report.get("heldIntervals"),
        "approximateIntervals": report.get("approximateIntervals"),
        "everyIslandAdvanced": report.get("everyIslandAdvanced"),
        "stressIntegrityPassed": report.get("stressIntegrityPassed"),
    }


def summarize_jvm_memory_samples(report):
    marker = report.get("measuredStartEpochMillis")
    duration = report.get("measuredSeconds")
    end_millis = marker + 1000 * duration if isinstance(marker, (int, float)) and isinstance(duration, (int, float)) else None
    samples = [
        sample for sample in report.get("memorySamples", [])
        if sample.get("measured") is True
        and (end_millis is None or marker <= sample["epochMillis"] < end_millis)
    ]
    if not samples:
        return {"available": False, "sampleCount": 0}
    byte_fields = (
        "heapUsed",
        "heapCommitted",
        "heapMax",
        "nonHeapUsed",
        "directBufferBytes",
        "mappedBufferBytes",
    )
    result = {
        "available": True,
        "sampleCount": len(samples),
        "firstEpochMillis": min(sample["epochMillis"] for sample in samples),
        "lastEpochMillis": max(sample["epochMillis"] for sample in samples),
    }
    for field in byte_fields:
        values = [sample[field] for sample in samples if isinstance(sample.get(field), (int, float))]
        result[field] = memory_statistics(values)
    for field in ("gcCount", "gcMillis"):
        values = [sample[field] for sample in samples if isinstance(sample.get(field), (int, float))]
        result[f"{field}Delta"] = max(values) - min(values) if values else None
    return result


def summarize_rss_csv(path, start, end):
    rows = []
    with path.open(newline="", encoding="utf-8-sig") as stream:
        for row in csv.DictReader(stream):
            epoch_millis = int(row["epochMillis"])
            if start.timestamp() * 1000 <= epoch_millis < end.timestamp() * 1000:
                rows.append({key: int(value) for key, value in row.items() if value not in (None, "")})
    if not rows:
        raise ValueError("RSS CSV has no samples in the measurement window")
    return {
        "sampleCount": len(rows),
        "firstEpochMillis": min(row["epochMillis"] for row in rows),
        "lastEpochMillis": max(row["epochMillis"] for row in rows),
        "workingSetBytes": memory_statistics([row["workingSetBytes"] for row in rows]),
        "privateBytes": memory_statistics([row["privateBytes"] for row in rows]),
        "peakWorkingSetBytesProcessLifetime": max(
            (row["peakWorkingSetBytes"] for row in rows if "peakWorkingSetBytes" in row),
            default=None,
        ),
        "semantics": {
            "workingSetBytes": "The maximum is the largest sampled working set inside the measurement window.",
            "peakWorkingSetBytesProcessLifetime": "The OS counter is process-lifetime peak working set, even when observed inside the measurement window.",
        },
    }


def build_memory_summary(jfr_events, report, measurement_start, measurement_end, rss_csv=None, top=15):
    result = summarize_memory_events(jfr_events, measurement_start, measurement_end, top)
    result["benchmark"] = memory_benchmark_metadata(report)
    result["scientificProgress"] = summarize_scientific_progress(report)
    result["jvmMemorySamples"] = summarize_jvm_memory_samples(report)
    progress = result["scientificProgress"]
    weight = result["sampledAllocationWeightBytes"]
    alignment = memory_window_alignment(report, measurement_start, measurement_end)
    result["scientificProgressAlignment"] = alignment
    result["sampledAllocationWeightPerAcceptedInterval"] = (
        weight / progress["acceptedIntervals"]
        if alignment["aligned"] and progress["acceptedIntervals"]
        else None
    )
    result["sampledAllocationWeightPerSimulatedSecond"] = (
        weight / progress["simulatedSecondsAdvanced"]
        if alignment["aligned"] and progress["simulatedSecondsAdvanced"]
        else None
    )
    result["rssSamples"] = summarize_rss_csv(rss_csv, parse_jfr_time(measurement_start), parse_jfr_time(measurement_end)) if rss_csv else None
    return result


def memory_window_alignment(report, start, end):
    marker = report.get("measuredStartEpochMillis")
    duration = report.get("measuredSeconds")
    if not isinstance(marker, (int, float)) or not isinstance(duration, (int, float)):
        return {
            "aligned": False,
            "reason": "Report lacks exact measuredStartEpochMillis/measuredSeconds markers; progress normalization is suppressed.",
        }
    actual_start, actual_end = parse_jfr_time(start), parse_jfr_time(end)
    expected_start = datetime.fromtimestamp(marker / 1000, timezone.utc)
    expected_end = expected_start + timedelta(seconds=duration)
    tolerance_seconds = 0.001
    aligned = (
        abs((actual_start - expected_start).total_seconds()) <= tolerance_seconds
        and abs((actual_end - expected_end).total_seconds()) <= tolerance_seconds
    )
    return {
        "aligned": aligned,
        "reason": (
            "JFR window matches the report's measured phase markers."
            if aligned
            else "Explicit JFR window differs from the report's measured phase; progress normalization is suppressed."
        ),
    }


def measurement_window(report, explicit_start=None, explicit_end=None):
    if explicit_start is not None or explicit_end is not None:
        if explicit_start is None or explicit_end is None:
            raise ValueError("Both explicit measurement timestamps are required")
        start, end = parse_jfr_time(explicit_start), parse_jfr_time(explicit_end)
    else:
        start_millis = report.get("measuredStartEpochMillis")
        measured_seconds = report.get("measuredSeconds")
        if not isinstance(start_millis, (int, float)) or not isinstance(measured_seconds, (int, float)):
            raise ValueError("Report lacks measuredStartEpochMillis/measuredSeconds; provide explicit timestamps")
        start = datetime.fromtimestamp(start_millis / 1000, timezone.utc)
        end = start + timedelta(seconds=measured_seconds)
    if end <= start:
        raise ValueError("Measurement window must have positive duration")
    return start.isoformat(), end.isoformat()


def validate_measured_slices(report):
    """Require complete, contiguous five-second intervals for each island."""
    by_island = defaultdict(list)
    for sample in report["samples"]:
        timing = sample["timing"]
        if timing["endTick"] - timing["startTick"] != 100:
            raise ValueError("Ordinary sample is not a complete five-second interval")
        if timing["startTick"] < report["warmupTicks"]:
            raise ValueError("Measured slice includes pre-warmup simulation time")
        by_island[str(sample["island"])].append((timing["startTick"], timing["endTick"]))
    counts = Counter({island: len(slices) for island, slices in by_island.items()})
    expected = {str(island): count for island, count in report["measuredIntervalsPerIsland"].items()}
    if dict(counts) != expected or len(counts) != report["islands"] or min(counts.values(), default=0) < 200:
        raise ValueError("Recorded per-island sample counts do not establish 200 full intervals")
    for slices in by_island.values():
        ordered = sorted(slices)
        if any(previous[1] != current[0] for previous, current in zip(ordered, ordered[1:])):
            raise ValueError("Measured physical trajectory has a gap, overlap or duplicate interval")


def validate_runtime_audit(path, report, runtime_root):
    audit = json.loads(path.with_name("runtime-audit.json").read_text(encoding="utf-8"))
    manifest = report["manifest"]
    if manifest["runId"] != path.parent.name:
        raise ValueError("Run directory does not match the recorded run identity")
    log_bytes = (runtime_root / path.parent.name / "logs/latest.log").read_bytes()
    if (
        audit.get("status") != "PASS"
        or audit.get("errorCount") != 0
        or audit.get("reportSha256") != hashlib.sha256(path.read_bytes()).hexdigest()
        or audit.get("artifactSha256") != manifest["artifactSha256"]
        or audit.get("logSha256") != hashlib.sha256(log_bytes).hexdigest()
        or runtime_error_lines(log_bytes)
    ):
        raise ValueError("Failed, stale or mismatched runtime-error audit/log")


def validate_ordinary_report(report):
    samples = report["samples"]
    if (
        report["gatesPassed"] is not True
        or report["warmupTicks"] < 2400
        or report["cadenceSeconds"] != 5
        or report["adaptiveCadence"] is not False
        or report["profile"] not in ("one", "many", "module")
        or report["islands"] != {"one": 1, "many": 100, "module": 2}.get(report["profile"])
        or (report["reservoirs"], report["physicalPipes"], report["components"]) != (100, 1000, 22)
        or report["heldIntervals"] != 0
        or report["approximateIntervals"] != 0
        or not all(sample["acceptance"] == "FULL" and sample["timing"]["accepted"] is True for sample in samples)
    ):
        raise ValueError("Incomplete ordinary-load qualification samples/fixture")
    validate_measured_slices(report)
    latency = [sample["readyToPublicationMillis"] for sample in samples]
    worker = [sample["timing"]["workerNanos"] for sample in samples]
    engine = report["rawEngineMillisecondsPerTick"]
    balances = [report["componentBalanceToleranceUnits"], report["energyBalanceToleranceUnits"]]
    times = [report["measuredSeconds"], report["elapsedSeconds"]]
    numbers = latency + worker + engine + balances + times
    if len(engine) < 20000 or not all(
        isinstance(value, (int, float))
        and not isinstance(value, bool)
        and math.isfinite(value)
        and value >= 0
        for value in numbers
    ):
        raise ValueError("Missing, negative or nonfinite raw measurements")
    if (
        report["measuredSeconds"] < 1000
        or report["elapsedSeconds"] < 1120
        or max(balances) > 1
        or statistics(latency)["p95"] >= 2000
        or statistics(engine)["p95"] >= 2
    ):
        raise ValueError("Raw duration, conservation or performance gate failed")
    configured = report["manifest"]["configuredWorkers"]
    if configured not in (0, 1, 2) or report["workers"] < 1 or (configured and report["workers"] != configured):
        raise ValueError("Worker configuration does not match the qualification group")


def summarize_ordinary(root, candidate_artifact=None, runtime_root=Path("run/fluid-benchmark")):
    candidate_hash = (
        hashlib.sha256(candidate_artifact.read_bytes()).hexdigest()
        if candidate_artifact and candidate_artifact.is_file()
        else None
    )
    rows, rejected, contention = [], [], []
    for path in sorted(root.glob("*/report.json")):
        try:
            report = json.loads(path.read_text(encoding="utf-8"))
            status = report.get("status", "MISSING_STATUS")
            runtime_pass, runtime_reason = False, None
            try:
                validate_runtime_audit(path, report, runtime_root)
                runtime_pass = True
            except (OSError, ValueError, KeyError, TypeError) as error:
                runtime_reason = str(error)
            if status.startswith("CONTENTION_"):
                manifest = report.get("manifest", {})
                contention.append(
                    {
                        "path": str(path),
                        "status": status,
                        "workers": report.get("workers"),
                        "configuredWorkers": manifest.get("configuredWorkers"),
                        "artifactSha256": manifest.get("artifactSha256"),
                        "propertyRevision": report.get("propertyRevision"),
                        "matchesCandidateArtifact": candidate_hash is not None
                        and manifest.get("artifactSha256") == candidate_hash,
                        "runtimeAuditPassed": runtime_pass,
                        "runtimeAuditFailure": runtime_reason,
                        "qualificationEligible": status == "CONTENTION_PASSED"
                        and report.get("gatesPassed") is True
                        and runtime_pass,
                        "maximumDebtTicks": report.get("maximumDebtTicks"),
                        "finalDebtTicks": report.get("finalDebtTicks"),
                    }
                )
                continue
            if status != "REPLICATE_PASSED":
                rejected.append({"path": str(path), "reason": status})
                continue
            if not runtime_pass:
                rejected.append({"path": str(path), "reason": runtime_reason})
                continue
            validate_ordinary_report(report)
            manifest = report["manifest"]
            key = (
                manifest["artifactSha256"],
                report["propertyRevision"],
                manifest["configuredWorkers"],
                report["profile"],
                manifest["configSha256"],
                manifest["fixtureClassesSha256"],
            )
            rows.append((key, path, report))
        except (OSError, ValueError, KeyError, TypeError) as error:
            rejected.append({"path": str(path), "reason": str(error)})

    groups = []
    for key in sorted({row[0] for row in rows}):
        matching = [(path, report) for row_key, path, report in rows if row_key == key]
        processes = {report["processId"] for _, report in matching}
        samples = [sample for _, report in matching for sample in report["samples"]]
        replicate_summaries = []
        for path, report in matching:
            islands = defaultdict(list)
            for sample in report["samples"]:
                islands[str(sample["island"])].append(sample)
            replicate_summaries.append(
                {
                    "runId": report["manifest"]["runId"],
                    "report": str(path),
                    "processId": report["processId"],
                    "measuredSeconds": report["measuredSeconds"],
                    "warmupHeldIntervals": report.get("warmupHeldIntervals"),
                    "componentBalanceToleranceUnits": report["componentBalanceToleranceUnits"],
                    "energyBalanceToleranceUnits": report["energyBalanceToleranceUnits"],
                    "islands": {
                        island: {
                            "fullIntervals": len(values),
                            "simulatedSeconds": sum(
                                sample["timing"]["endTick"] - sample["timing"]["startTick"]
                                for sample in values
                            )
                            / 20,
                            "readyToPublicationMilliseconds": statistics(
                                [sample["readyToPublicationMillis"] for sample in values]
                            ),
                        }
                        for island, values in sorted(islands.items())
                    },
                }
            )
        groups.append(
            {
                "artifactSha256": key[0],
                "propertyRevision": key[1],
                "configuredWorkers": key[2],
                "profile": key[3],
                "configSha256": key[4],
                "fixtureClassesSha256": key[5],
                "replicates": len(matching),
                "distinctProcessIds": len(processes),
                "repetitionsComplete": len(matching) >= 3 and len(processes) >= 3,
                "reports": [str(path) for path, _ in matching],
                "replicateSummaries": replicate_summaries,
                "fixtureHashes": sorted(
                    {report["manifest"]["fixtureClassesSha256"] for _, report in matching}
                ),
                "workerMilliseconds": statistics(
                    [sample["timing"]["workerNanos"] / 1e6 for sample in samples]
                ),
                "readyToPublicationMilliseconds": statistics(
                    [sample["readyToPublicationMillis"] for sample in samples]
                ),
                "engineServerMillisecondsPerTick": statistics(
                    [value for _, report in matching for value in report["rawEngineMillisecondsPerTick"]]
                ),
            }
        )

    artifacts = sorted({(group["artifactSha256"], group["propertyRevision"]) for group in groups})
    complete = []
    for artifact, revision in artifacts:
        covered = {
            (group["configuredWorkers"], group["profile"])
            for group in groups
            if group["artifactSha256"] == artifact
            and group["propertyRevision"] == revision
            and group["repetitionsComplete"]
        }
        missing = [
            {"workers": workers, "profile": profile}
            for workers in (2, 1, 0)
            for profile in ("one", "many", "module")
            if (workers, profile) not in covered
        ]
        complete.append(
            {
                "artifactSha256": artifact,
                "propertyRevision": revision,
                "missingRepetitionGroups": missing,
            }
        )
    return {
        "status": "ORDINARY_REPETITIONS_COMPLETE"
        if any(
            item["artifactSha256"] == candidate_hash and not item["missingRepetitionGroups"]
            for item in complete
        )
        else "INCOMPLETE",
        "candidateArtifactSha256": candidate_hash,
        "note": "This aggregation cannot pass M9: the matrix, contention, soak and final release gates require separate evidence. Automatic worker sizing is configuredWorkers=0.",
        "ordinaryGroups": groups,
        "coverageByArtifact": complete,
        "contentionReports": contention,
        "excludedReports": rejected,
    }


def summarize_stress(root, artifact):
    candidate = hashlib.sha256(artifact.read_bytes()).hexdigest() if artifact.is_file() else None
    runs = []
    for path in sorted(root.glob("*/report.json")):
        report = json.loads(path.read_text(encoding="utf-8"))
        if report.get("profile") != "stress100":
            continue
        samples = report["samples"]
        seconds = report["measuredSeconds"]
        advanced = sum(
            (sample["timing"]["endTick"] - sample["timing"]["startTick"]) / 20
            for sample in samples
            if sample["timing"]["accepted"]
        )
        per_island = []
        for island, reservoirs in report["reservoirsPerIsland"].items():
            own = [sample for sample in samples if str(sample["island"]) == island]
            successful = [sample for sample in own if sample["timing"]["accepted"]]
            per_island.append(
                {
                    "island": island,
                    "reservoirs": reservoirs,
                    "completions": len(successful),
                    "holds": len(own) - len(successful),
                    "simulatedSecondsAdvanced": sum(
                        (sample["timing"]["endTick"] - sample["timing"]["startTick"]) / 20
                        for sample in successful
                    ),
                }
            )
        observed_end = max(
            [sample["timing"]["publishedAtTick"] for sample in samples]
            + [sample["onlineTick"] for sample in report["stressSamples"]]
        )
        tail = [
            sample
            for sample in samples
            if sample["timing"]["publishedAtTick"] >= observed_end - 1200
        ]
        runs.append(
            {
                "run": path.parent.name,
                "path": str(path),
                "artifactSha256": report["manifest"]["artifactSha256"],
                "matchesCandidateArtifact": report["manifest"]["artifactSha256"] == candidate,
                "workersAtEnd": report["workers"],
                "warmupTicks": report["warmupTicks"],
                "measuredSeconds": seconds,
                "stressIntegrityPassed": report["stressIntegrityPassed"],
                "everyIslandAdvanced": report["everyIslandAdvanced"],
                "warmupHolds": report["warmupHeldIntervals"],
                "measuredHolds": report["heldIntervals"],
                "approximateIntervals": report["approximateIntervals"],
                "equivalentFiveSecondIntervalsPerSecond": advanced / (5 * seconds),
                "aggregateRealtimeRatio": advanced / (report["islands"] * seconds),
                "finalDebtSeconds": report["finalDebtSeconds"],
                "engineMillisecondsPerTick": report["engineServerMillisecondsPerTick"],
                "maximumAllocatedWorkers": max(
                    (sample.get("workerLimit", report["workers"]) for sample in report["stressSamples"]),
                    default=report["workers"],
                ),
                "maximumOutstandingJobs": max(
                    (sample["outstandingJobs"] for sample in report["stressSamples"]),
                    default=0,
                ),
                "observedTail": {
                    "endTick": observed_end,
                    "windowTicks": 1200,
                    "holds": sum(not sample["timing"]["accepted"] for sample in tail),
                    "approximate": sum(sample.get("acceptance") == "APPROXIMATE" for sample in tail),
                    "readyToPublicationMilliseconds": stress_statistics(
                        sample["readyToPublicationMillis"]
                        for sample in tail
                        if sample.get("readyToPublicationMillis") is not None
                    ),
                    "engineMillisecondsPerTick": stress_statistics(
                        report["rawEngineMillisecondsPerTick"][-1200:]
                    ),
                },
                "islands": per_island,
            }
        )
    return {
        "qualification": False,
        "candidateArtifactSha256": candidate,
        "note": "Stress characterization. Compare matching windows and record JFR use. Tail recovery cannot erase startup failures or substitute for M9 repetitions.",
        "runs": runs,
    }


def build_parser():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)

    audit_parser = commands.add_parser("audit", help="Audit one preserved run's runtime log")
    audit_parser.add_argument("run_id")

    summary_parser = commands.add_parser("summarize", help="Summarize ordinary and contention evidence")
    summary_parser.add_argument("--root", type=Path, default=Path("build/reports/fluid/M9"))
    summary_parser.add_argument("--output", type=Path, default=Path("build/reports/fluid/M9-benchmark.json"))
    summary_parser.add_argument(
        "--artifact",
        "--candidate-artifact",
        dest="artifact",
        type=Path,
        default=Path("build/libs/createcheme-0.1.0.jar"),
    )
    summary_parser.add_argument(
        "--runtime-root",
        type=Path,
        default=Path("run/fluid-benchmark"),
        help="Preserved per-run runtime logs",
    )

    stress_parser = commands.add_parser("stress", help="Summarize stress characterization runs")
    stress_parser.add_argument("--root", type=Path, default=Path("build/reports/fluid/M9"))
    stress_parser.add_argument(
        "--output",
        type=Path,
        default=Path("build/reports/fluid/stress100-summary.json"),
    )
    stress_parser.add_argument(
        "--artifact",
        type=Path,
        default=Path("build/libs/createcheme-0.1.0.jar"),
    )

    memory_parser = commands.add_parser(
        "memory",
        help="Summarize JFR memory events with benchmark and optional process telemetry",
    )
    memory_parser.add_argument(
        "--jfr-json",
        type=Path,
        required=True,
        help="JSON from jfr print containing allocation, GC pause, collection and heap-summary events",
    )
    memory_parser.add_argument(
        "--jfr-recording",
        type=Path,
        help="Original .jfr recording retained as the authoritative source",
    )
    memory_parser.add_argument("--report", type=Path, required=True, help="Matching benchmark report.json")
    memory_parser.add_argument("--rss-csv", type=Path, help="Optional 1 Hz process working-set/private-byte CSV")
    memory_parser.add_argument(
        "--output",
        type=Path,
        default=Path("build/reports/fluid/memory-summary.json"),
    )
    memory_parser.add_argument(
        "--measurement-start",
        help="ISO-8601 override for reports without measuredStartEpochMillis",
    )
    memory_parser.add_argument("--measurement-end", help="ISO-8601 exclusive measurement-window end")
    memory_parser.add_argument("--top", type=int, default=15, help="Number of allocation types/sites to retain")
    return parser


def write_json(path, result):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")


def main(argv=None):
    parser = build_parser()
    args = parser.parse_args(argv)
    if args.command == "audit":
        if not re.fullmatch(r"[A-Za-z0-9_-]+", args.run_id):
            parser.error("Invalid run ID")
        report_path = Path("build/reports/fluid/M9") / args.run_id / "report.json"
        result = audit_runtime(
            report_path,
            Path("run/fluid-benchmark") / args.run_id / "logs/latest.log",
        )
        write_json(report_path.with_name("runtime-audit.json"), result)
        print(f"{result['status']}: {result['errorCount']} runtime errors")
        return 0 if result["status"] == "PASS" else 1
    if args.command == "summarize":
        result = summarize_ordinary(args.root, args.artifact, args.runtime_root)
        write_json(args.output, result)
        print(
            f"{result['status']}: {len(result['ordinaryGroups'])} ordinary groups, "
            f"{len(result['contentionReports'])} contention reports; {args.output}"
        )
        return 0
    if args.command == "stress":
        result = summarize_stress(args.root, args.artifact)
        write_json(args.output, result)
        print(f"{len(result['runs'])} stress runs; {args.output}")
        return 0
    if args.top < 1:
        parser.error("--top must be positive")
    report = json.loads(args.report.read_text(encoding="utf-8"))
    try:
        start, end = measurement_window(report, args.measurement_start, args.measurement_end)
        with args.jfr_json.open(encoding="utf-8") as stream:
            result = build_memory_summary(
                iter_jfr_json_events(stream), report, start, end, args.rss_csv, args.top
            )
    except ValueError as error:
        parser.error(str(error))
    result["sources"] = {
        "jfrJson": str(args.jfr_json),
        "jfrRecording": str(args.jfr_recording) if args.jfr_recording else None,
        "benchmarkReport": str(args.report),
        "rssCsv": str(args.rss_csv) if args.rss_csv else None,
    }
    write_json(args.output, result)
    print(
        f"{result['allocationSamples']} allocation samples, {result['gcPauseEvents']} GC pauses; "
        f"{args.output}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
