"""Audit and summarize preserved fluid benchmark evidence.

Run from the checkout root with Python 3. No third-party packages are required.
"""
import argparse
import hashlib
import json
import math
import re
from collections import Counter, defaultdict
from pathlib import Path


RUNTIME_ERRORS = (
    "Error executing task on Server",
    "process_solver lifecycle=DRAIN_FAILED",
    "Encountered an unexpected exception",
    "Game test server crashed",
    "process_solver lifecycle=STOPPED_WITH_FAULT",
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
    result = summarize_stress(args.root, args.artifact)
    write_json(args.output, result)
    print(f"{len(result['runs'])} stress runs; {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
