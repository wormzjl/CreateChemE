"""Packaging checks bind observations to immutable cohorts and analyzed bytes."""
import copy
import json
from pathlib import Path
import tempfile
import unittest

import finalize_gen3_report as finalizer
from prepare_generalized_evaluation import sha


def input_value():
    return {"componentBasis": {"componentIds": ["A"]}, "feedComponentMolarFlowsMolPerSecond": [1.0],
        "feedTemperatureKelvin": 400.0, "stageCount": 2, "feedStageNumber": 2,
        "topPressurePascal": 100000.0, "stagePressureDropPascal": 0.0,
        "specifications": [{"kelvin": 300.0}, {"ratio": 1.0}, {"watts": 0.0}],
        "sideDraws": [], "steamFeeds": [], "pumparounds": []}


class Gen3ArchiveTest(unittest.TestCase):
    def test_selected_cohorts_and_reference_survive_pool_first_insertion(self):
        pool = {"id": "fresh-case", "split": "test", "input": input_value(),
                "design": {"gen3Cohorts": ["unused_fresh_candidate_pool"], "selectedForFreshTest": True}}
        observed = copy.deepcopy(pool)
        observed["design"] = {"gen3Cohorts": ["fresh_test", "fresh_serial_benchmark"],
            "gen3Origin": "new_operating_holdout", "gen3Reference": {"origin": "fresh252_CURRENT_ONLY", "equilibriumQualified": True}}
        observed.update(success=True, status="ACCEPTED", equilibriumQualified=True, sourceSuccess=True)
        original = copy.deepcopy(observed)
        records = {}
        finalizer.archive_record(records, pool)
        entry = finalizer.add_observation(records, observed, "candidate")
        self.assertEqual(["fresh_serial_benchmark", "fresh_test"], entry["cohorts"])
        self.assertNotIn("unused_fresh_candidate_pool", entry["design"]["gen3Cohorts"])
        self.assertEqual(["unused_fresh_candidate_pool"], entry["firstDesignProvenance"]["gen3Cohorts"])
        self.assertEqual(observed["design"]["gen3Reference"], entry["observations"]["candidate"]["gen3Reference"])
        self.assertTrue(entry["observations"]["candidate"]["sourceSuccess"])
        self.assertEqual(original, observed)

    def test_duplicate_profile_and_fallback_observations_are_rejected(self):
        row = {"id": "case", "split": "test", "input": input_value(), "design": {}, "success": False}
        for name in ("candidate", "serial-profile:current", "serial-fallback:nearest-k1:neuralFirst"):
            records = {}
            entry = finalizer.add_observation(records, row, name)
            with self.assertRaises(ValueError):
                finalizer.add_observation(records, row, name, {"success": True})
            self.assertEqual(1, len(entry["observations"]))
            self.assertFalse(entry["observations"][name]["success"])

    def test_identical_input_cannot_change_case_id_or_fold(self):
        row = {"id": "case", "split": "test", "input": input_value(), "design": {}}
        records = {}
        finalizer.archive_record(records, row)
        with self.assertRaises(ValueError):
            finalizer.archive_record(records, {**row, "id": "other"})
        with self.assertRaises(ValueError):
            finalizer.archive_record(records, {**row, "split": "train"})


class Gen3EvidenceTest(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        self.root = Path(self.folder.name)
        self.source = self.root / "source.jsonl"
        self.journal = self.root / "evaluation.jsonl"
        self.request = {"id": "case", "split": "test", "input": input_value()}
        self.source.write_text(json.dumps(self.request) + "\n", encoding="utf-8")
        self.journal.write_text(json.dumps({**self.request, "success": False}) + "\n", encoding="utf-8")
        self.budget = {"workers": 10, "parentDeadlineMillis": 30000, "candidateMillis": 10000, "candidateIterations": 16}
        self.metadata = {"mode": "evaluate", "source": str(self.source), "sourceSha256": sha(self.source),
            "caseCount": 1, "completed": 1, "workers": 10, "deadlineMillis": 30000,
            "neuralBudgetMillis": 10000, "neuralMaximumIterations": 16, "modelSha256": "model-a"}
        self.save_metadata()

    def tearDown(self):
        self.folder.cleanup()

    def save_metadata(self):
        (self.root / "run.json").write_text(json.dumps(self.metadata), encoding="utf-8")
        self.evidence = {"journal": str(self.journal), "journalSha256AtSnapshot": sha(self.journal),
                         "complete": True, "run": copy.deepcopy(self.metadata)}

    def verify(self, **options):
        return finalizer.verify_run_evidence(self.evidence, count=1, mode="evaluate", expected_model_sha="model-a",
            budget=self.budget, expected_source=self.source, **options)

    def test_changed_journal_or_source_cannot_be_packaged_with_stale_summary(self):
        self.assertEqual(1, self.verify())
        original = self.journal.read_bytes()
        self.journal.write_text(json.dumps({**self.request, "success": True}) + "\n", encoding="utf-8")
        with self.assertRaises(ValueError):
            self.verify()
        self.journal.write_bytes(original)
        self.source.write_text(json.dumps({**self.request, "split": "train"}) + "\n", encoding="utf-8")
        with self.assertRaises(ValueError):
            self.verify()

    def test_model_and_budget_must_match_the_selected_candidate(self):
        self.metadata["modelSha256"] = "model-b"
        self.save_metadata()
        with self.assertRaises(ValueError):
            self.verify()
        self.metadata["modelSha256"] = "model-a"
        self.metadata["deadlineMillis"] = 15000
        self.save_metadata()
        with self.assertRaises(ValueError):
            self.verify()

    def test_only_explicit_legacy_validation_can_default_to16_iterations(self):
        self.metadata.pop("neuralMaximumIterations")
        self.save_metadata()
        with self.assertRaises(ValueError):
            self.verify()
        self.assertEqual(1, self.verify(legacy_iterations=True))


if __name__ == "__main__":
    unittest.main()
