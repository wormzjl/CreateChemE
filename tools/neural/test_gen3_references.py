"""Reference attachment must preserve requests and never retain a stale label."""
import copy
import json
import unittest

import attach_gen3_references as attachment
import generalized_design
from prepare_generalized_evaluation import canonical_input_hash


class Gen3ReferenceAttachmentTest(unittest.TestCase):
    def setUp(self):
        item = json.loads(generalized_design.DEFAULT_BASELINE.read_text(encoding="utf-8-sig"))["input"]
        self.authored = {"id": "g3fresh-case", "split": "test", "input": item,
                         "design": {"gen3Cohorts": ["fresh_test", "fresh_serial_benchmark"], "trainingAllowed": False}}
        self.reference = {"id": "g3fresh-case", "split": "test", "input": copy.deepcopy(item),
                          "success": True, "status": "ACCEPTED", "equilibriumQualified": True,
                          "waterQualification": "DRY_EQUILIBRIUM", "seed": {"input": copy.deepcopy(item)},
                          "ms": 17.0, "cpuMillis": 12.0, "allocatedBytes": 1000}

    def test_attachment_preserves_frozen_identity_and_does_not_mutate_sources(self):
        before = copy.deepcopy(self.authored)
        result = attachment.attach_reference(self.authored, self.reference, "fresh252_CURRENT_ONLY")
        self.assertEqual(before, self.authored)
        self.assertEqual(before["input"], result["input"])
        self.assertEqual(before["design"]["gen3Cohorts"], result["design"]["gen3Cohorts"])
        self.assertEqual(canonical_input_hash(before["input"]), canonical_input_hash(result["seed"]["input"]))
        self.assertTrue(result["success"])
        self.assertTrue(result["design"]["gen3Reference"]["equilibriumQualified"])

    def test_wrong_input_fold_id_or_seed_is_rejected(self):
        for change in ("input", "split", "id", "seed", "missing_seed"):
            wrong = copy.deepcopy(self.reference)
            if change == "input":
                wrong["input"]["feedTemperatureKelvin"] += 1
            elif change == "split":
                wrong["split"] = "train"
            elif change == "id":
                wrong["id"] = "another-case"
            elif change == "seed":
                wrong["seed"]["input"]["topPressurePascal"] += 1
            else:
                wrong.pop("seed")
            with self.assertRaises(ValueError, msg=change):
                attachment.attach_reference(self.authored, wrong, "current")

    def test_failed_reference_clears_any_stale_successful_seed(self):
        authored = copy.deepcopy(self.authored)
        authored.update(seed=self.reference["seed"], success=True, equilibriumQualified=True)
        failed = {"id": authored["id"], "split": authored["split"], "input": authored["input"],
                  "success": False, "status": "NONCONVERGENCE"}
        result = attachment.attach_reference(authored, failed, "cached_original_CURRENT_ONLY")
        self.assertNotIn("seed", result)
        self.assertFalse(result["success"])
        self.assertFalse(result["equilibriumQualified"])

    def test_cached_control_omits_mixed_run_timing(self):
        source = attachment.attach_reference(self.authored, self.reference, "fresh252_CURRENT_ONLY")
        source.update(ms=17.0, cold_ms=17.0, cpuMillis=12.0, allocatedBytes=1000, heapUsedAfterBytes=10000)
        control = attachment.baseline_row(source)
        self.assertTrue(control["referenceOnly"])
        self.assertTrue(control["success"])
        for key in ("ms", "cold_ms", "cpuMillis", "allocatedBytes", "heapUsedAfterBytes"):
            self.assertNotIn(key, control)


if __name__ == "__main__":
    unittest.main()
