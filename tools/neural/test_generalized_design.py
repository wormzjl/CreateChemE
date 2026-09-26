"""Independent design invariants and necessary-condition counterexamples."""
import copy
import itertools
import json
import math
import unittest

import generalized_design as design


class GeneralizedDesignTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.baseline = json.loads(design.DEFAULT_BASELINE.read_text(encoding="utf-8-sig"))["input"]
        cls.candidates, cls.admitted, cls.excluded, cls.report = design.generate(cls.baseline)

    def test_complete_structural_cells_and_whole_stage_holdouts(self):
        expected = set(itertools.product(range(2, 65), (False, True), range(5), range(4)))
        actual = set()
        split_by_stage = {}
        for row in self.candidates:
            cell = row["design"]["structuralCell"]
            actual.add(tuple(cell[k] for k in ("stageCount", "steamEnabled", "paCount", "sideDrawCount")))
            split_by_stage.setdefault(cell["stageCount"], set()).add(row["split"])
        self.assertEqual(expected, actual)
        self.assertEqual(2809, len(self.candidates))
        self.assertEqual(2809, len({row["id"] for row in self.candidates}))
        self.assertTrue(all(len(splits) == 1 for splits in split_by_stage.values()))
        self.assertEqual(len(self.candidates), len(self.admitted) + len(self.excluded))
        # Every structural exclusion is retained, including both impossible-to-author N=2 cells.
        self.assertTrue(any(e["code"] == "SIDE_DRAW_TOPOLOGY" for r in self.excluded for e in r["exclusions"]))
        self.assertTrue(any(e["code"] == "PA_TOPOLOGY" for r in self.excluded for e in r["exclusions"]))
        self.assertTrue(all(e["category"] == "model_contract" for r in self.excluded for e in r["exclusions"]))

    def test_all_compositions_vary_and_remain_in_bounded_simplex(self):
        reference = self.baseline["feedComponentMolarFlowsMolPerSecond"]
        reference = [x / math.fsum(reference) for x in reference]
        extrema = [[float("inf"), -float("inf")] for _ in reference]
        for row in self.admitted:
            flows = row["input"]["feedComponentMolarFlowsMolPerSecond"]
            fractions = [x / math.fsum(flows) for x in flows]
            self.assertAlmostEqual(1.0, math.fsum(fractions), places=13)
            for i, (actual, base) in enumerate(zip(fractions, reference)):
                ratio = actual / base
                self.assertGreaterEqual(ratio, 0.8 - 1e-12)
                self.assertLessEqual(ratio, 1.2 + 1e-12)
                extrema[i][0] = min(extrema[i][0], ratio)
                extrema[i][1] = max(extrema[i][1], ratio)
        self.assertTrue(all(high - low > 0.35 for low, high in extrema))
        # Permuting the component axis permutes the result: no species has a special role.
        latent = [(i + 0.5) / 20 for i in range(20)]
        forward = design.bounded_mixture(reference, latent)
        reverse = design.bounded_mixture(reference[::-1], latent[::-1])[::-1]
        for left, right in zip(forward, reverse):
            self.assertAlmostEqual(left, right, places=14)

    def test_resolved_pressure_and_all_latent_pair_strata(self):
        names = self.report["factorNames"]
        points = [[row["design"]["latentFactors"][name] for name in names] for row in self.candidates]
        proof = design.verify_oa(points, 53)
        self.assertEqual(48, proof["latinMarginalsVerified"])
        self.assertEqual(1128, proof["orthogonalPairsVerified"])
        for row in self.admitted:
            item = row["input"]
            p = item["topPressurePascal"]
            bottom = p + (item["stageCount"] - 1) * item["stagePressureDropPascal"]
            self.assertGreaterEqual(p, 100_000.0)
            self.assertLessEqual(bottom, 300_000.0 + 1e-8)
            self.assertEqual([], design.screen_input(item, row["design"]))

    def test_filter_proofs_do_not_equate_solver_difficulty_with_impossibility(self):
        self.assertEqual([], design.screen_input(self.baseline))
        material = copy.deepcopy(self.baseline)
        material["sideDraws"][0]["molarFlowMolPerSecond"] = 2000.0
        material_issues = design.screen_input(material)
        self.assertTrue(any(i["category"] == "physical_necessity" and i["code"] == "WITHDRAWAL_EXCEEDS_FEED" for i in material_issues))
        water = copy.deepcopy(self.baseline)
        water["steamFeeds"][0]["temperatureKelvin"] = 298.15
        self.assertTrue(any(i["code"] == "STEAM_NOT_VAPOR" and i["evidence"]["saturationKelvin"] > 298.15 for i in design.screen_input(water)))
        # Extreme authored cooling is not itself a proof: an energy/phase solve is still required.
        difficult = copy.deepcopy(self.baseline)
        difficult["pumparounds"][0]["dutyWatts"] = -1e12
        self.assertEqual([], design.screen_input(difficult))
        repeated = design.orthogonal_latin_hypercube(7, 8, 124)
        self.assertEqual(repeated, design.orthogonal_latin_hypercube(7, 8, 124))


if __name__ == "__main__":
    unittest.main()
