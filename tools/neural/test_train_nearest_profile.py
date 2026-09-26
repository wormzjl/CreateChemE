"""Cheap transfer/export contracts. These tests neither train a network nor invoke the column solver."""
import copy
import unittest

import numpy as np
import train_nearest_profile as nearest


def authored(stages=4, feed_stage=3, temperature=650., steam=False):
    return {"schemaVersion": 1, "packageId": "test:nearest", "assayId": "test:nearest",
            "componentBasis": {"componentIds": [f"c{i}" for i in range(20)]},
            "feedComponentMolarFlowsMolPerSecond": list(range(1, 21)),
            "feedTemperatureKelvin": temperature, "stageCount": stages, "feedStageNumber": feed_stage,
            "topPressurePascal": 200000., "stagePressureDropPascal": 100.,
            "specifications": [{"kelvin": 313.15}, {"ratio": 2.}, {"watts": 1e6}],
            "pumparounds": [], "sideDraws": [],
            "steamFeeds": [{"stageNumber": stages+1, "molarFlowMolPerSecond": 2., "temperatureKelvin": 533.15}] if steam else []}


def accepted(case_id, inp=None, branch="TWO_PHASE", inner_temperature=500.):
    inp = authored() if inp is None else inp
    nodes, feed = inp["stageCount"]+2, np.asarray(inp["feedComponentMolarFlowsMolPerSecond"], dtype=float)
    liquid = np.outer(np.arange(1, nodes+1), feed)
    vapor = liquid/2
    if branch == "LIQUID_ONLY": vapor[0] = 0
    if branch == "VAPOR_ONLY": liquid[0] = 0
    return {"id": case_id, "split": "train", "input": inp, "success": True, "equilibriumQualified": True,
            "waterQualification": "DRY_EQUILIBRIUM", "formulationRevision": "test-formulation",
            "seed": {"input": copy.deepcopy(inp), "propertyRevision": "test-properties", "branch": branch,
                     "temperatures": [313.15]+[inner_temperature]*(nodes-1),
                     "liquid": liquid.tolist(), "vapor": vapor.tolist(), "freeWater": [0.]*nodes,
                     "wetTrays": [False]*nodes}}


def domain(case_id, inp):
    return {"id": case_id, "split": "train", "input": inp, "success": False}


class NearestProfileTest(unittest.TestCase):
    def test_only_qualified_train_targets_enter_library_or_distance_fit(self):
        train = accepted("a")
        validation = accepted("v", authored(10, 8)); validation["split"] = "validation"
        test = accepted("t", authored(7, 5)); test["split"] = "test"
        advisory = accepted("advisory", authored(5, 3)); advisory["equilibriumQualified"] = False
        rows = [train, validation, test, advisory]
        first, report = nearest.build_model(rows)
        self.assertEqual(["a"], [ref["id"] for ref in first["references"]])
        self.assertEqual({"train": 1}, report["referenceSourceSplits"])
        self.assertFalse(report["validationOrTestProfilesStored"])
        self.assertEqual(10, first["maximumStages"])  # Authored held-out input bounds are allowed, labels are not.
        validation["seed"] = {"invalid": "target must never be inspected"}
        test["seed"] = {"invalid": "target must never be inspected"}
        second, _ = nearest.build_model(rows)
        self.assertEqual(first, second)

    def test_same_authored_input_cannot_cross_folds(self):
        train = accepted("a")
        leaked = copy.deepcopy(train); leaked.update(id="v", split="validation")
        with self.assertRaisesRegex(ValueError, "crosses folds"): nearest.build_model([train, leaked])

    def test_exact_match_preserves_reference_even_for_k_three(self):
        row = accepted("a")
        model, _ = nearest.build_model([row], neighbors=3)
        result = nearest.predict(model, row["input"])
        for name in ("liquid", "vapor", "temperatures", "freeWater", "wetTrays"):
            np.testing.assert_array_equal(result[name], row["seed"][name])
        self.assertEqual(nearest.primitive_storage_bytes(model), nearest.build_model([row])[1]["primitiveStorageBytesJava"])

    def test_alignment_preserves_terminals_feed_and_upstream_discontinuity(self):
        row = accepted("a")
        row["seed"]["liquid"][3] = (100*np.asarray(row["input"]["feedComponentMolarFlowsMolPerSecond"])).tolist()
        target = authored(8, 6)
        target["feedComponentMolarFlowsMolPerSecond"] = [2*x for x in target["feedComponentMolarFlowsMolPerSecond"]]
        target["feedComponentMolarFlowsMolPerSecond"][0] = 0
        model, _ = nearest.build_model([row, domain("domain", target)], maximum_distance=100)
        result = nearest.predict(model, target)
        self.assertIsNotNone(result)
        np.testing.assert_array_equal(np.asarray(result["liquid"])[:, 0], np.zeros(10))
        self.assertEqual(row["seed"]["liquid"][0][1]*2, result["liquid"][0][1])
        self.assertEqual(row["seed"]["liquid"][-1][1]*2, result["liquid"][-1][1])
        self.assertEqual(row["seed"]["liquid"][3][1]*2, result["liquid"][6][1])
        self.assertEqual(row["seed"]["liquid"][2][1]*2, result["liquid"][5][1])
        self.assertEqual(313.15, result["temperatures"][0])

    def test_no_average_across_condenser_branches(self):
        a = accepted("a", authored(4, 3, 600), inner_temperature=400)
        b = accepted("b", authored(4, 3, 610), inner_temperature=500)
        c = accepted("c", authored(4, 3, 620), branch="LIQUID_ONLY", inner_temperature=1000)
        target = authored(4, 3, 608)
        model, _ = nearest.build_model([a, b, c, domain("domain", target)], neighbors=3, maximum_distance=100)
        result = nearest.predict(model, target)
        self.assertEqual("TWO_PHASE", result["branch"])
        self.assertGreater(result["temperatures"][1], 400)
        self.assertLess(result["temperatures"][1], 500)

    def test_wet_topology_is_not_averaged_with_dry_reference_and_scales_with_steam(self):
        a = accepted("wet", authored(4, 3, 600, True), inner_temperature=400)
        a["seed"]["wetTrays"][1] = True; a["seed"]["freeWater"][1] = .4
        a["waterQualification"] = "WET_EQUILIBRIUM"
        b = accepted("dry", authored(4, 3, 620, True))
        target = authored(4, 3, 600.1, True); target["steamFeeds"][0]["molarFlowMolPerSecond"] = 4.
        model, _ = nearest.build_model([a, b, domain("domain", target)], neighbors=3, maximum_distance=1e6)
        result = nearest.predict(model, target)
        self.assertTrue(result["wetTrays"][1]); self.assertAlmostEqual(.8, result["freeWater"][1])
        self.assertFalse(result["wetTrays"][0]); self.assertFalse(result["wetTrays"][-1])

    def test_strict_cohort_and_absent_reference_component_decline(self):
        row = accepted("a")
        target = authored(); target["sideDraws"] = [{"trayNumber": 2, "molarFlowMolPerSecond": 1.}]
        model, _ = nearest.build_model([row, domain("domain", target)], maximum_distance=100)
        self.assertIsNone(nearest.predict(model, target))
        zero_input = authored(); zero_input["feedComponentMolarFlowsMolPerSecond"][0] = 0
        zero = accepted("zero", zero_input)
        model, _ = nearest.build_model([zero, domain("domain", authored())], maximum_distance=100)
        self.assertIsNone(nearest.predict(model, authored()))

    def test_distance_weights_include_every_feature_and_treat_all_twenty_fractions_equally(self):
        weights = nearest.feature_weights()
        self.assertEqual(74, len(weights)); self.assertTrue(np.all(weights > 0))
        np.testing.assert_array_equal(weights[17:37], np.ones(20))


if __name__ == "__main__": unittest.main()
