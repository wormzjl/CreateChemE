import copy
import json
import math
from pathlib import Path
import unittest
from build_quality import ELEMENTS, HYDROGEN, profile

HERE = Path(__file__).resolve().parent


class QualityTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.sources = json.loads((HERE/"source-data.json").read_text(encoding="utf-8"))["sources"]
        cls.quality = json.loads((HERE/"source-quality.json").read_text(encoding="utf-8"))["sources"]
        cls.converted = json.loads((HERE/"converted-compositions.json").read_text(encoding="utf-8"))
        cls.generated = json.loads((HERE/"cut-quality-profiles.json").read_text(encoding="utf-8"))

    def test_all_tracked_elements_close_to_reported_whole_crude(self):
        for source, quality, converted in zip(self.sources, self.quality, self.converted["crudes"]):
            p = profile(source, quality, converted, self.converted["components"])
            self.assertEqual(p, self.generated[converted["id"]])
            for element in ELEMENTS:
                total = math.fsum(w*c["elements"][element]["mass_fraction"] for w,c in zip(converted["mass_fractions"], p["components"]))
                self.assertAlmostEqual(total, quality["fields"][element]["whole_reported_mass_fraction"], delta=1e-14)

    def test_light_chemistry_and_missing_carbon_are_not_overwritten(self):
        for p in self.generated.values():
            for i,c in enumerate(p["components"]):
                if i < 7:
                    self.assertAlmostEqual(c["elements"]["H"]["mass_fraction"], HYDROGEN[i])
                    self.assertFalse(c["elements"]["H"]["estimated"])
                    self.assertEqual(c["elements"]["S"]["mass_fraction"], 0)
                    self.assertAlmostEqual(sum(v["mass_fraction"] for v in c["elements"].values()), 1)
                else:
                    self.assertNotIn("C", c["elements"])
                    self.assertNotIn("O", c["elements"])
                    self.assertNotIn("Hg", c["elements"])
                    self.assertLess(sum(v["mass_fraction"] for v in c["elements"].values()), 1)
                    self.assertTrue(all(v["estimated"] for v in c["elements"].values()))

    def test_source_blanks_and_known_cells_keep_their_positions(self):
        bonga = next(q for q in self.quality if q["id"] == "bonga")
        self.assertEqual(bonga["fields"]["H"]["cuts_reported_mass_fraction"][-1], None)
        self.assertEqual(bonga["fields"]["H"]["residue_370_plus_reported_mass_fraction"], None)
        self.assertAlmostEqual(bonga["fields"]["H"]["cuts_reported_mass_fraction"][-4], .118)
        self.assertAlmostEqual(bonga["fields"]["N"]["cuts_reported_mass_fraction"][4], 21e-6)
        self.assertAlmostEqual(bonga["fields"]["N"]["cuts_reported_mass_fraction"][-1], 8533e-6)
        cold = next(q for q in self.quality if q["id"] == "cold_lake_blend")
        self.assertAlmostEqual(cold["fields"]["V"]["cuts_reported_mass_fraction"][-1], 400.6e-6)
        # Printed whole-crude zero does not erase a positive reported cut indicator.
        wti = next(q for q in self.quality if q["id"] == "wti_light")
        self.assertEqual(wti["fields"]["c7_asphaltenes"]["whole_reported_mass_fraction"], 0)
        self.assertAlmostEqual(self.generated["wti_light_export"]["components"][-1]["indicators"]["c7_asphaltenes"]["mass_fraction"], .007)

    def test_uniform_tail_does_not_invent_elemental_enrichment(self):
        for p in self.generated.values():
            for element in ELEMENTS:
                values=[c["elements"][element]["mass_fraction"] for c in p["components"][-3:]]
                self.assertAlmostEqual(values[0],values[1],places=14)
                self.assertAlmostEqual(values[0],values[2],places=14)

    def test_nonphysical_hydrogen_balance_and_large_reconciliation_are_rejected(self):
        for field,value in [("H", .9), ("S", .5)]:
            q=copy.deepcopy(self.quality[0]);q["fields"][field]["whole_reported_mass_fraction"]=value
            with self.assertRaises(ValueError):
                profile(self.sources[0],q,self.converted["crudes"][0],self.converted["components"])


if __name__ == "__main__":
    unittest.main()
