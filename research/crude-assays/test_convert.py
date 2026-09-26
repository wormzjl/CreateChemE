"""Conservation, source retention and independent tail checks for the assay conversion."""
import copy
import json
import math
from pathlib import Path
import unittest

from convert import AssayCurve, SOURCE, basis, convert


class ConversionTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.spec = json.loads(SOURCE.read_text(encoding="utf-8"))
        cls.package, cls.properties, cls.windows = basis()
        cls.rows = [convert(s, cls.spec, cls.properties, cls.windows) for s in cls.spec["sources"]]

    def test_source_light_ends_and_each_disjoint_cut_are_conserved(self):
        for source, row in zip(self.spec["sources"], self.rows):
            with self.subTest(crude=source["id"]):
                mass = row["mass_fractions"]
                light = source["light_ends_mass_percent"]
                self.assertEqual(mass[0], 0)
                for i, key in enumerate(["methane + ethane", "propane", "isobutane", "n-butane", "isopentane", "n-pentane"], 1):
                    self.assertAlmostEqual(mass[i] * 100, light[key], places=12)
                allocation = row["source_cut_to_pseudocomponent_mass_percent"]
                self.assertEqual(len(allocation), 12)
                scale = row["residual_mass_rounding_scale"]
                for i, cells in enumerate(allocation):
                    expected = source["cut_mass_percent"][i]
                    if i == 0:
                        expected -= light["isopentane"] + light["n-pentane"]
                    self.assertAlmostEqual(math.fsum(cells), expected * scale, places=12)
                    for j, (low, high) in enumerate(self.windows):
                        if i > 0:
                            start = self.spec["cut_start_celsius"][i]
                            end = self.spec["cut_end_celsius"][i]
                            if high <= start or (end is not None and low >= end):
                                self.assertEqual(cells[j], 0)
                for i in range(13):
                    self.assertAlmostEqual(mass[i + 7] * 100, math.fsum(r[i] for r in allocation), places=12)

    def test_vectors_close_and_moles_reconstruct_mass(self):
        for row in self.rows:
            for name in ["mass_fractions", "mole_fractions", "proxy_standard_liquid_volume_fractions"]:
                vector = row[name]
                self.assertEqual(len(vector), 20)
                self.assertTrue(all(math.isfinite(v) and v >= 0 for v in vector))
                self.assertAlmostEqual(math.fsum(vector), 1, places=12)
            reconstructed = [z * p["molecular_weight_kg_per_mol"]
                             for z, p in zip(row["mole_fractions"], self.properties)]
            total = math.fsum(reconstructed)
            for actual, expected in zip(reconstructed, row["mass_fractions"]):
                self.assertAlmostEqual(actual / total, expected, places=12)

    def test_tail_is_continuous_monotone_and_matches_source_residue_mean(self):
        for source in self.spec["sources"]:
            with self.subTest(crude=source["id"]):
                curve = AssayCurve(source)
                self.assertAlmostEqual(curve.remaining(590 + 1e-8), curve.remaining(590), places=7)
                sampled = [curve.remaining(t) for t in range(550, 2000)]
                self.assertTrue(all(b <= a for a, b in zip(sampled, sampled[1:])))
                self.assertEqual(curve.remaining(math.inf), 0)
                # Independent numerical quadrature, rather than reusing the fitted integral formula.
                grid = list(range(550, 591)) + [590 + curve.tail_scale * i / 1000 for i in range(1, 25001)]
                area = math.fsum((b-a) * (curve.remaining(a) + curve.remaining(b)) / 2
                                 for a, b in zip(grid, grid[1:]))
                recovered_mean = 550 + area / curve.remaining(550)
                self.assertAlmostEqual(recovered_mean, source["residue_550_plus_volume_average_boiling_point_celsius"], delta=.001)

    def test_invalid_sources_are_rejected(self):
        original = self.spec["sources"][0]
        for mutation in [
            lambda s: s["cut_mass_percent"].__setitem__(5, 90),
            lambda s: s["cut_mass_percent"].__setitem__(0, 0),
            lambda s: s["tbp_cumulative_volume_percent"].__setitem__(5, -1),
            lambda s: s["tbp_cumulative_volume_percent"].__setitem__(5, 99),
            lambda s: s.__setitem__("residue_550_plus_volume_average_boiling_point_celsius", 550),
        ]:
            source = copy.deepcopy(original)
            mutation(source)
            with self.assertRaises(ValueError):
                convert(source, self.spec, self.properties, self.windows)

    def test_committed_full_precision_results_are_reproducible(self):
        output = json.loads(Path(__file__).with_name("converted-compositions.json").read_text(encoding="utf-8"))
        self.assertEqual(output["components"], self.package["components"])
        self.assertEqual(output["crudes"], self.rows)


if __name__ == "__main__":
    unittest.main()
