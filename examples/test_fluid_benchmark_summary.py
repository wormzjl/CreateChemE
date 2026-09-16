"""Portable evidence-gate regressions; no Minecraft process or generated reports required.

Run: python -m unittest discover -s examples -p test_fluid_benchmark_summary.py
Run outside paced performance measurements, like other validation work.
"""
import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location("fluid_benchmarks", Path(__file__).with_name("Fluid-Benchmarks.py"))
SUMMARY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SUMMARY)


def fixture():
    samples = [{"island": island, "acceptance": "FULL", "readyToPublicationMillis": 500.0,
                "timing": {"accepted": True, "startTick": 2400 + step * 100,
                           "endTick": 2500 + step * 100, "workerNanos": 400_000_000}}
               for island in (1, 2) for step in range(200)]
    return {"status": "REPLICATE_PASSED", "gatesPassed": True, "profile": "module", "processId": 1,
            "manifest": {"runId": "r1", "artifactSha256": "artifact", "configuredWorkers": 2,
                         "configSha256": "config", "fixtureClassesSha256": "fixture"},
            "propertyRevision": "science", "workers": 2, "cadenceSeconds": 5, "adaptiveCadence": False,
            "warmupTicks": 2400, "elapsedSeconds": 1120.1, "measuredSeconds": 1000.1,
            "reservoirs": 100, "physicalPipes": 1000, "components": 22, "islands": 2,
            "measuredIntervalsPerIsland": {"1": 200, "2": 200}, "heldIntervals": 0,
            "approximateIntervals": 0, "componentBalanceToleranceUnits": 0.01,
            "energyBalanceToleranceUnits": 0.01, "samples": samples,
            "rawEngineMillisecondsPerTick": [0.2] * 20001}


class BenchmarkEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / "reports"
        self.runtime = Path(self.temp.name) / "runtime"

    def record(self, report=None, run="r1", process=1, log=b"Normal shutdown\n"):
        report = copy.deepcopy(report if report is not None else fixture())
        report["manifest"]["runId"] = run
        report["processId"] = process
        path = self.root / run / "report.json"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(report), encoding="utf-8")
        log_path = self.runtime / run / "logs/latest.log"
        log_path.parent.mkdir(parents=True, exist_ok=True)
        log_path.write_bytes(log)
        audit = {"status": "PASS", "errorCount": 0, "artifactSha256": "artifact",
                 "reportSha256": hashlib.sha256(path.read_bytes()).hexdigest(),
                 "logSha256": hashlib.sha256(log).hexdigest()}
        path.with_name("runtime-audit.json").write_text(json.dumps(audit), encoding="utf-8")
        return path, log_path

    def summary(self):
        return SUMMARY.summarize_ordinary(self.root, runtime_root=self.runtime)

    def test_cli_subcommands_and_artifact_alias(self):
        parser = SUMMARY.build_parser()
        self.assertEqual("audit", parser.parse_args(["audit", "run-01"]).command)
        summary = parser.parse_args(["summarize", "--candidate-artifact", "candidate.jar"])
        self.assertEqual(Path("candidate.jar"), summary.artifact)
        stress = parser.parse_args(["stress", "--artifact", "candidate.jar"])
        self.assertEqual(Path("candidate.jar"), stress.artifact)

    def test_audit_scans_every_runtime_marker_and_records_hashes(self):
        path, log_path = self.record()
        for marker in SUMMARY.RUNTIME_ERRORS:
            with self.subTest(marker=marker):
                log = f"prefix {marker} suffix\n".encode()
                log_path.write_bytes(log)
                result = SUMMARY.audit_runtime(path, log_path)
                self.assertEqual("FAILED_RUNTIME_ERRORS", result["status"])
                self.assertEqual(1, result["errorCount"])
                self.assertEqual(hashlib.sha256(path.read_bytes()).hexdigest(), result["reportSha256"])
                self.assertEqual(hashlib.sha256(log).hexdigest(), result["logSha256"])

    def test_stress_profile_discovery_remains_nonqualifying(self):
        self.record()
        stress = {
            "profile": "stress100", "manifest": {"artifactSha256": "artifact"},
            "workers": 2, "warmupTicks": 2400, "measuredSeconds": 5, "islands": 1,
            "stressIntegrityPassed": True, "everyIslandAdvanced": True,
            "warmupHeldIntervals": 0, "heldIntervals": 0, "approximateIntervals": 0,
            "finalDebtSeconds": 0, "engineServerMillisecondsPerTick": {"p95": 1},
            "rawEngineMillisecondsPerTick": [0.2], "reservoirsPerIsland": {"1": 1},
            "stressSamples": [{"onlineTick": 2500, "outstandingJobs": 0, "workerLimit": 2}],
            "samples": [{"island": 1, "acceptance": "FULL", "readyToPublicationMillis": 10,
                         "timing": {"accepted": True, "startTick": 2400, "endTick": 2500,
                                    "publishedAtTick": 2500}}],
        }
        path = self.root / "stress" / "report.json"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(stress), encoding="utf-8")
        result = SUMMARY.summarize_stress(self.root, Path(self.temp.name) / "missing.jar")
        self.assertIs(False, result["qualification"])
        self.assertEqual(["stress"], [run["run"] for run in result["runs"]])

    def test_three_clean_processes_complete_only_this_group(self):
        for index in range(1, 4):
            self.record(run=f"r{index}", process=index)
        result = self.summary()
        self.assertEqual("INCOMPLETE", result["status"])
        self.assertEqual([], result["excludedReports"])
        self.assertTrue(result["ordinaryGroups"][0]["repetitionsComplete"])
        self.assertEqual(1200, result["ordinaryGroups"][0]["readyToPublicationMilliseconds"]["count"])

    def test_raw_gates_override_nominal_pass(self):
        for field, value in (("componentBalanceToleranceUnits", 1.1), ("energyBalanceToleranceUnits", float("nan")),
                             ("measuredSeconds", 999.9), ("elapsedSeconds", 1119.9),
                             ("adaptiveCadence", True), ("workers", 1), ("reservoirs", 99)):
            with self.subTest(field=field):
                report = fixture()
                report[field] = value
                with self.assertRaises(ValueError):
                    SUMMARY.validate_ordinary_report(report)
        for metric in ("latency", "worker", "engine"):
            for value in (float("nan"), float("inf"), -1, None):
                with self.subTest(metric=metric, value=value):
                    report = fixture()
                    if metric == "latency":
                        report["samples"][0]["readyToPublicationMillis"] = value
                    elif metric == "worker":
                        report["samples"][0]["timing"]["workerNanos"] = value
                    else:
                        report["rawEngineMillisecondsPerTick"][0] = value
                    with self.assertRaises(ValueError):
                        SUMMARY.validate_ordinary_report(report)

    def test_recalculates_percentiles_from_raw_measurements(self):
        report = fixture()
        report["readyToPublicationMilliseconds"] = {"p95": 0.01}
        for sample in report["samples"]:
            sample["readyToPublicationMillis"] = 2000.0
        with self.assertRaisesRegex(ValueError, "performance gate"):
            SUMMARY.validate_ordinary_report(report)
        report = fixture()
        report["rawEngineMillisecondsPerTick"] = [2.0] * 20001
        with self.assertRaisesRegex(ValueError, "performance gate"):
            SUMMARY.validate_ordinary_report(report)

    def test_rejects_short_duplicate_gapped_and_warmup_slices(self):
        for mutation in ("short", "duplicate", "gap", "warmup", "count", "tick-history"):
            with self.subTest(mutation=mutation):
                report = fixture()
                timing = report["samples"][0]["timing"]
                if mutation == "short":
                    timing["endTick"] -= 1
                elif mutation == "duplicate":
                    report["samples"][1] = copy.deepcopy(report["samples"][0])
                elif mutation == "gap":
                    timing["startTick"] += 1
                    timing["endTick"] += 1
                elif mutation == "warmup":
                    timing["startTick"] -= 100
                    timing["endTick"] -= 100
                elif mutation == "count":
                    report["measuredIntervalsPerIsland"]["1"] += 1
                else:
                    report["rawEngineMillisecondsPerTick"] = [0.2]
                with self.assertRaises(ValueError):
                    SUMMARY.validate_ordinary_report(report)

    def test_changed_or_missing_runtime_log_cannot_reuse_audit(self):
        _, log = self.record()
        log.write_bytes(b"Changed after audit\n")
        self.assertFalse(self.summary()["ordinaryGroups"])
        log.unlink()
        self.assertFalse(self.summary()["ordinaryGroups"])

    def test_runtime_error_is_rejected_even_with_nominal_green_audit(self):
        self.record(log=b"Error executing task on Server\n")
        result = self.summary()
        self.assertFalse(result["ordinaryGroups"])
        self.assertIn("runtime-error", result["excludedReports"][0]["reason"])

    def test_report_change_invalidates_hash(self):
        path, _ = self.record()
        path.write_text(path.read_text(encoding="utf-8") + "\n", encoding="utf-8")
        self.assertFalse(self.summary()["ordinaryGroups"])

    def test_different_configs_and_fixtures_are_not_pooled(self):
        for index in (1, 2):
            self.record(run=f"r{index}", process=index)
        for index, field in ((3, "configSha256"), (4, "fixtureClassesSha256")):
            report = fixture()
            report["manifest"][field] = "different"
            self.record(report, run=f"r{index}", process=index)
        groups = self.summary()["ordinaryGroups"]
        self.assertEqual(3, len(groups))
        self.assertTrue(all(not group["repetitionsComplete"] for group in groups))

    def test_same_process_does_not_establish_three_fresh_processes(self):
        for index in range(1, 4):
            self.record(run=f"r{index}", process=1)
        self.assertFalse(self.summary()["ordinaryGroups"][0]["repetitionsComplete"])


if __name__ == "__main__":
    unittest.main()
