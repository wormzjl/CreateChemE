"""Prospective selection and fitted-input/reporting separation for generation 3."""
import copy
from collections import Counter
import json
import unittest

import generalized_design
import prepare_gen3_holdouts as preparation
from prepare_generalized_evaluation import canonical_input_hash
import summarize_gen3 as summary


class Gen3AnalysisTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.baseline = json.loads(generalized_design.DEFAULT_BASELINE.read_text(encoding="utf-8-sig"))["input"]
        _, cls.pool, _, _ = generalized_design.generate(cls.baseline, preparation.NEW_POOL_SEED)
        cls.selected = preparation.select_fresh(cls.pool)

    def test_fresh_population_has_every_stage_and_both_steam_states(self):
        self.assertEqual(252, len(self.selected))
        self.assertEqual(Counter({n: 4 for n in range(2, 65)}), Counter(row["input"]["stageCount"] for row in self.selected))
        for n in range(2, 65):
            self.assertEqual(2, sum(bool(row["input"]["steamFeeds"]) for row in self.selected if row["input"]["stageCount"] == n))
        self.assertEqual(100, sum(row["input"]["stageCount"] >= 40 for row in self.selected))
        self.assertEqual(252, len({canonical_input_hash(row["input"]) for row in self.selected}))

    def test_selection_ignores_outcomes_and_input_order(self):
        changed = [{**row, "success": index % 2 == 0, "equilibriumQualified": index % 7 == 0, "ms": 1000 - index}
                   for index, row in enumerate(reversed(self.pool))]
        self.assertEqual([row["id"] for row in self.selected], [row["id"] for row in preparation.select_fresh(changed)])
        benchmark = preparation.balanced_subset(self.selected, 64, "fresh-serial-benchmark")
        self.assertEqual(64, len(benchmark))
        self.assertEqual(50, len({preparation.structural_stratum(row) for row in benchmark}))

    def test_training_replay_never_becomes_test_from_split_name(self):
        item = {"id": "trained", "split": "test", "input": self.baseline,
                "design": {"gen3Cohorts": ["rescued_train_replay"]}}
        key = canonical_input_hash(self.baseline)
        self.assertEqual(["rescued_train_replay"], summary.row_cohorts(item, {"rescued_train_replay": {key}, "fresh_test": set()}))
        item["design"]["gen3Cohorts"] = ["fresh_test"]
        with self.assertRaises(ValueError):
            summary.row_cohorts(item, {"rescued_train_replay": {key}, "fresh_test": set()})

    def test_validation_run_scope_does_not_become_recovery_from_cached_membership(self):
        row = {"id": "cached-validation", "split": "validation", "input": self.baseline, "design": {}}
        key = canonical_input_hash(self.baseline)
        populations = {"validation": {key}, "recovery_comparison": {key}, "remaining_gen2_failure": {key}}
        self.assertEqual(["validation"], summary.run_cohorts("validation:gen2", row, populations))
        with self.assertRaises(ValueError):
            summary.run_cohorts("validation:gen2", {**row, "split": "train"}, populations)

    def test_strict_reference_errors_use_reference_flag_not_candidate_success(self):
        rows = []
        for index, reference_flag in enumerate((True, False, None)):
            item = copy.deepcopy(self.baseline)
            item["feedTemperatureKelvin"] += index
            rows.append({"id": str(index), "input": item, "split": "test", "success": index != 0,
                "waterQualification": "DRY_EQUILIBRIUM" if index != 0 else None,
                "equilibriumQualified": index != 0, "rawVsTeacher": {"temperatureRmseKelvin": 1.0 if index == 0 else 999.0},
                "design": {"gen3Reference": {"equilibriumQualified": reference_flag}}})
        value = summary.cohort_summary(rows, 3, "fresh_test", self.baseline)
        self.assertEqual(3, value["rawVsProvidedReference"]["profilesCompared"])
        self.assertEqual(1, value["rawVsStrictProvidedReference"]["referenceQualifiedInputs"])
        self.assertEqual(1, value["rawVsStrictProvidedReference"]["profilesCompared"])
        self.assertEqual(1.0, value["rawVsStrictProvidedReference"]["metrics"]["temperatureRmseKelvin"]["median"])

    def test_comparisons_count_qualified_gains_losses_and_advisory_rescues(self):
        base, candidate = [], []
        for index in range(3):
            item = copy.deepcopy(self.baseline)
            item["feedTemperatureKelvin"] += index
            common = {"id": str(index), "input": item, "split": "test"}
            base.append({**common, "success": index < 2, "equilibriumQualified": index < 2,
                         "waterQualification": "DRY_EQUILIBRIUM" if index < 2 else None})
            candidate.append({**common, "success": True, "equilibriumQualified": index > 0,
                              "waterQualification": "DRY_EQUILIBRIUM" if index > 0 else "DRY_SUPERSATURATED"})
        compared = summary.matched_comparison(base, candidate)
        self.assertEqual(3, compared["matchedInputs"])
        self.assertEqual(1, compared["qualifiedGains"])
        self.assertEqual(1, compared["qualifiedLosses"])
        self.assertEqual(1, compared["bothQualified"])
        rescues = summary.strict_rescues(candidate)
        self.assertEqual(3, rescues["original_matrix"]["nativeAccepted"])
        self.assertEqual(2, rescues["original_matrix"]["strictQualified"])
        self.assertEqual(1, rescues["original_matrix"]["advisoryOnlyOrUnqualified"])

    def test_isolated_ratios_exclude_rejections_and_one_sided_rescues(self):
        current, candidate = [], []
        for index in range(3):
            item = copy.deepcopy(self.baseline)
            item["feedTemperatureKelvin"] += index
            common = {"id": str(index), "input": item, "split": "test"}
            current.append({**common, "success": index != 2, "equilibriumQualified": index != 2,
                "waterQualification": "DRY_EQUILIBRIUM" if index != 2 else None,
                "ms": 100.0, "cpuMillis": 80.0, "allocatedBytes": 1000})
            candidate.append({**common, "success": index != 1, "equilibriumQualified": index != 1,
                "waterQualification": "DRY_EQUILIBRIUM" if index != 1 else None,
                "ms": 50.0 if index != 1 else 0.1, "cpuMillis": 40.0, "allocatedBytes": 200})
        paired = summary.paired_isolated_comparison(current, candidate)
        self.assertEqual(3, paired["matchedInputs"])
        self.assertEqual(1, paired["strictBothQualifiedPairs"])
        self.assertEqual(2.0, paired["metrics"]["ms"]["currentOverCandidateRatio"]["median"])
        self.assertEqual(-50.0, paired["metrics"]["ms"]["candidateMinusCurrentDifference"]["median"])
        self.assertEqual(-800, paired["metrics"]["allocatedBytes"]["candidateMinusCurrentDifference"]["median"])

    def test_full_recovery_union_counts_each_request_once(self):
        neural, transfer, population = [], [], set()
        for index in range(4):
            item = copy.deepcopy(self.baseline)
            item["feedTemperatureKelvin"] += index
            population.add(canonical_input_hash(item))
            common = {"id": str(index), "input": item, "split": "train"}
            neural.append({**common, "success": index in (0, 1), "equilibriumQualified": index in (0, 1),
                "waterQualification": "DRY_EQUILIBRIUM" if index in (0, 1) else None})
            transfer.append({**common, "success": index in (1, 2), "equilibriumQualified": index in (1, 2),
                "waterQualification": "DRY_EQUILIBRIUM" if index in (1, 2) else None})
        union = summary.full_recovery_union(neural, transfer, population)
        self.assertTrue(union["completePopulation"])
        self.assertEqual(1, union["strictQualified"]["intersection"])
        self.assertEqual(3, union["strictQualified"]["union"])
        self.assertEqual(1, union["strictQualified"]["neitherOnPairedInputs"])
        partial = summary.full_recovery_union(neural[:2], transfer, population)
        self.assertFalse(partial["completePopulation"])
        self.assertEqual(2, partial["notYetPaired"])


if __name__ == "__main__":
    unittest.main()
