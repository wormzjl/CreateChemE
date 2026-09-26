"""Cheap contract tests for generalized trainer transformations; no training or solver runs."""
import copy
import json
from pathlib import Path
import tempfile
import unittest

import numpy as np
import train_generalized as training


def authored(stages=17):
    return {"feedComponentMolarFlowsMolPerSecond": list(range(1, 21)), "feedTemperatureKelvin": 650,
            "stageCount": stages, "feedStageNumber": max(1, stages//2), "topPressurePascal": 200000,
            "stagePressureDropPascal": 100, "specifications": [{"kelvin": 313.15}, {"ratio": 2}, {"watts": 1e6}],
            "pumparounds": [], "sideDraws": [], "steamFeeds": []}


class GeneralizedTrainingTest(unittest.TestCase):
    def test_dimensions_do_not_depend_on_stage_count_and_pressure_matches_native(self):
        for stages in (2, 17, 64):
            inp = authored(stages)
            x = training.node_features(inp, "TWO_PHASE")
            self.assertEqual((stages+2, 99), x.shape)
            self.assertEqual(2, x[0, 74+6]); self.assertEqual(2, x[1, 74+6])
            self.assertAlmostEqual(2+(stages-1)*.001, x[-1, 74+6])
            self.assertEqual(x[-1, 74+6], x[-2, 74+6])

    def test_every_composition_fraction_uses_the_same_coordinate_rule(self):
        inp = authored()
        for c in range(20):
            changed = copy.deepcopy(inp); changed["feedComponentMolarFlowsMolPerSecond"][c] *= 1.1
            feed = np.asarray(changed["feedComponentMolarFlowsMolPerSecond"])
            np.testing.assert_allclose(training.global_features(changed)[17:37], feed/feed.sum())

    def test_local_and_cumulative_heat_include_equipment_position(self):
        inp = authored()
        inp["pumparounds"] = [{"returnTray": 2, "drawTray": 3, "dutyWatts": -1000, "split": "UNIFORM"}]
        first = training.node_features(inp, "TWO_PHASE")
        inp["pumparounds"][0].update(returnTray=5, drawTray=6)
        changed = training.node_features(inp, "TWO_PHASE")
        self.assertEqual(first[0, 11], changed[0, 11])
        self.assertLess(first[2, 74+7], 0); self.assertEqual(changed[2, 74+7], 0)
        self.assertLess(changed[5, 74+7], 0)

    def test_round_trip_preserves_zero_components_and_wet_constraints(self):
        inp = authored(2); inp["feedComponentMolarFlowsMolPerSecond"][0] = 0
        inp["steamFeeds"] = [{"stageNumber": 3, "molarFlowMolPerSecond": 2, "temperatureKelvin": 533.15}]
        l, v = np.ones((4, 20)), np.full((4, 20), 2.)
        l[:, 0] = 0; v[:, 0] = 0
        seed = {"temperatures": [313.15, 400, 500, 550], "liquid": l, "vapor": v,
                "freeWater": [0, .5, 0, 0], "wetTrays": [False, True, False, False]}
        target = training.targets({"input": inp, "seed": seed})
        t, ll, vv, w, wet = training.decode(inp, target, "TWO_PHASE")
        np.testing.assert_allclose(ll, l); np.testing.assert_allclose(vv, v)
        np.testing.assert_allclose(t, seed["temperatures"]); np.testing.assert_allclose(w, seed["freeWater"])
        np.testing.assert_array_equal(wet, seed["wetTrays"])
        target[1, 1] = 31
        with self.assertRaisesRegex(ValueError, "unbounded_flow"): training.decode(inp, target, "TWO_PHASE")

    def test_whole_column_weighting_and_duplicate_fold_protection(self):
        mean, _ = training.moments(np.asarray([[10.], [10.]] + [[20.]]*64), np.asarray([.5]*2+[1/64]*64))
        self.assertAlmostEqual(15, mean[0])
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)/"cases.jsonl"
            rows = [{"id": "a", "split": "train", "input": authored()}, {"id": "b", "split": "test", "input": authored()}]
            path.write_text("\n".join(json.dumps(r) for r in rows))
            with self.assertRaisesRegex(ValueError, "multiple splits"): training.load_rows(path)

    def test_compound_design_constraints_are_authored_and_enforced(self):
        inp = authored(64); inp["topPressurePascal"] = 290000
        inp["steamFeeds"] = [{"stageNumber": 65, "molarFlowMolPerSecond": 2, "temperatureKelvin": 533.15}]
        inp["pumparounds"] = [{"returnTray": 2, "drawTray": 3, "dutyWatts": -1000, "split": "UNIFORM"}]
        limits = training.derive_design_constraints([{"input": inp}],
                    {"variationBounds": {"topAndAllStagePressuresPascal": [100000, 300000]}})
        self.assertEqual({"minimumNodePressurePascal": 100000, "maximumNodePressurePascal": 300000,
                          "steamAtSumpOnly": True, "pumparoundSplits": ["UNIFORM"]}, limits)
        self.assertIsNone(training.design_constraint_rejection(inp, limits))
        pressure = copy.deepcopy(inp); pressure["stagePressureDropPascal"] = 900
        self.assertEqual("outside_node_pressure_envelope", training.design_constraint_rejection(pressure, limits))
        moved = copy.deepcopy(inp); moved["steamFeeds"][0]["stageNumber"] = 5
        self.assertEqual("untrained_steam_location", training.design_constraint_rejection(moved, limits))
        split = copy.deepcopy(inp); split["pumparounds"][0]["split"] = "RETURN_TRAY"
        self.assertEqual("untrained_pumparound_split", training.design_constraint_rejection(split, limits))
        generic = training.derive_design_constraints([{"input": moved}, {"input": split}])
        self.assertFalse(generic["steamAtSumpOnly"])
        self.assertEqual(["RETURN_TRAY", "UNIFORM"], generic["pumparoundSplits"])

    def test_malformed_compound_constraints_are_rejected(self):
        limits = {"minimumNodePressurePascal": 300000, "maximumNodePressurePascal": 100000,
                  "steamAtSumpOnly": True, "pumparoundSplits": ["UNIFORM"]}
        with self.assertRaisesRegex(ValueError, "Invalid design constraints"): training.validate_design_constraints(limits)
        limits["maximumNodePressurePascal"] = 300000; limits["pumparoundSplits"] = ["UNKNOWN"]
        with self.assertRaisesRegex(ValueError, "Invalid design constraints"): training.validate_design_constraints(limits)


if __name__ == "__main__": unittest.main()
