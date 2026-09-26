"""Checks that evaluation selection, qualification and metrics resist common bias."""
import copy
import json
from pathlib import Path
import tempfile
import unittest

import generalized_design as design
import prepare_generalized_evaluation as preparation
import summarize_generalized as summary


class GeneralizedEvaluationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.baseline = json.loads(design.DEFAULT_BASELINE.read_text(encoding="utf-8-sig"))["input"]
        _, cls.matrix, _, _ = design.generate(cls.baseline)

    def test_benchmark_is_input_only_complete_stratification(self):
        selected = preparation.select_benchmark(self.matrix, 64)
        changed_outcomes = [{**row, "success": index % 3 == 0, "ms": index * 27.0, "status": "arbitrary"}
                            for index, row in enumerate(reversed(self.matrix))]
        changed_selected = preparation.select_benchmark(changed_outcomes, 64)
        self.assertEqual([row["id"] for row in selected], [row["id"] for row in changed_selected])
        self.assertEqual(64, len(selected))
        self.assertEqual(50, len({preparation.benchmark_stratum(row) for row in selected}))
        self.assertTrue(all(row["split"] == "test" for row in selected))

    def test_input_hash_ignores_authored_collection_order_but_preserves_axis(self):
        changed = copy.deepcopy(self.baseline)
        changed["stageCount"] = float(changed["stageCount"])
        for key in ("pumparounds", "sideDraws", "steamFeeds", "specifications"):
            changed[key].reverse()
        self.assertEqual(preparation.canonical_input_hash(self.baseline), preparation.canonical_input_hash(changed))
        changed["componentBasis"]["componentIds"].reverse()
        changed["feedComponentMolarFlowsMolPerSecond"].reverse()
        self.assertNotEqual(preparation.canonical_input_hash(self.baseline), preparation.canonical_input_hash(changed))

    def test_partial_reader_skips_only_unfinished_final_write(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "journal.jsonl"
            path.write_text('{"id":1}\n{"id":', encoding="utf-8")
            rows, state = preparation.load_jsonl(path, allow_partial=True)
            self.assertEqual([{"id": 1}], rows)
            self.assertTrue(state["incompleteFinalLine"])
            with self.assertRaises(json.JSONDecodeError):
                preparation.load_jsonl(path)
            path.write_text('{"id":\n{"id":2}\n', encoding="utf-8")
            with self.assertRaises(json.JSONDecodeError):
                preparation.load_jsonl(path, allow_partial=True)

    def test_qualification_and_speedup_do_not_reward_fast_rejection(self):
        qualified = {"success": True, "status": "ACCEPTED", "waterQualification": "DRY_EQUILIBRIUM", "ms": 100.0}
        warning = {"success": True, "status": "ACCEPTED", "waterQualification": "DRY_SUPERSATURATED", "ms": 1.0}
        failed = {"success": False, "status": "DEADLINE_EXCEEDED", "ms": 0.1}
        values = summary.solve_summary(iter([qualified, warning, failed]))
        self.assertEqual(2, values["accepted"])
        self.assertEqual(1, values["equilibriumQualified"])
        benchmark = summary.benchmark_summary([
            {"id": "a", "input": self.baseline, "current": qualified, "neural": {**qualified, "ms": 50.0}},
            {"id": "b", "input": self.baseline, "current": qualified, "neural": failed},
            {"id": "c", "input": self.baseline, "current": qualified, "neural": warning},
        ])
        self.assertEqual(1, benchmark["bothQualifiedCases"])
        self.assertEqual(2.0, benchmark["qualifiedPairsClassicalOverNeuralWallRatio"]["median"])
        self.assertEqual(1, benchmark["currentOnlyAcceptedCases"])
        self.assertEqual(2, benchmark["bothAcceptedCases"])
        three_modes = summary.benchmark_summary([
            {"id": "a", "input": self.baseline, "current": qualified, "neural": failed,
             "neuralFirst": {**qualified, "ms": 150.0, "diagnostics": {"events": ["initializer=CURRENT_BACKUP; original input"]}}},
            {"id": "b", "input": self.baseline, "current": failed, "neural": qualified, "neuralFirst": qualified},
        ])
        self.assertEqual(2, three_modes["neuralFirst"]["equilibriumQualified"])
        self.assertEqual(1, three_modes["neuralFirstComparison"]["classicalAcceptedCasesRetained"])
        self.assertEqual(1, three_modes["neuralFirstComparison"]["classicalFailuresRescued"])
        self.assertEqual(1, three_modes["neuralFirstComparison"]["classicalFallbackEvents"])

    def test_profile_comparison_reports_temperature_flow_water_and_branch(self):
        item = copy.deepcopy(self.baseline)
        item["stageCount"] = 2
        item["feedStageNumber"] = 2
        nodes, components = 4, len(item["componentBasis"]["componentIds"])
        profile = {"input": item, "temperatures": [300.0] * nodes, "liquid": [[2.0] * components for _ in range(nodes)],
                   "vapor": [[3.0] * components for _ in range(nodes)], "freeWater": [0.0] * nodes,
                   "wetTrays": [False] * nodes, "branch": "TWO_PHASE"}
        perturbed = copy.deepcopy(profile)
        perturbed["temperatures"] = [302.0] * nodes
        perturbed["freeWater"] = [1.0] * nodes
        perturbed["wetTrays"][1] = True
        perturbed["branch"] = "LIQUID_ONLY"
        error = summary.profile_difference(perturbed, profile, item)
        self.assertEqual(2.0, error["temperatureRmseKelvin"])
        self.assertEqual(0.0, error["componentFlowRmseMolPerSecond"])
        self.assertEqual(1.0, error["freeWaterRmseMolPerSecond"])
        self.assertEqual(1, error["wetMaskMismatches"])
        self.assertFalse(error["branchMatches"])


if __name__ == "__main__":
    unittest.main()
