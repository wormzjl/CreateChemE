"""Factorized transformations and analytic loss gradient checks without training or solver calls."""
import copy
import unittest

import numpy as np
import test_train_generalized as fixture
import train_gen3_factorized as factorized


def profile(stages=2):
    inp = fixture.authored(stages)
    inp["feedComponentMolarFlowsMolPerSecond"][0] = 0
    feed = np.asarray(inp["feedComponentMolarFlowsMolPerSecond"], dtype=float)
    liquid = np.tile(feed*2, (stages+2, 1)); vapor = np.tile(feed*.5, (stages+2, 1))
    liquid[1, 1] = 0; vapor[2, 2] = 0
    inp["steamFeeds"] = [{"stageNumber": stages+1, "molarFlowMolPerSecond": 2, "temperatureKelvin": 533.15}]
    temperatures = np.full(stages+2, 400.); temperatures[0] = 313.15
    water = np.zeros(stages+2); water[1] = .5
    wet = np.zeros(stages+2, dtype=bool); wet[1] = True
    seed = {"temperatures": temperatures, "liquid": liquid, "vapor": vapor, "freeWater": water, "wetTrays": wet}
    return {"input": inp, "seed": seed}


class FactorizedTest(unittest.TestCase):
    def test_round_trip_separates_phase_totals_compositions_and_exact_absence(self):
        row = profile()
        y, _ = factorized.targets(row)
        decoded = factorized.decode(row["input"], y, "TWO_PHASE", .02)
        self.assertEqual((4, 85), y.shape)
        for key in ("temperatures", "liquid", "vapor", "freeWater"):
            np.testing.assert_allclose(decoded[key], row["seed"][key], atol=1e-10, rtol=1e-10)
        np.testing.assert_array_equal(decoded["wetTrays"], row["seed"]["wetTrays"])
        self.assertEqual(0, decoded["liquid"][1, 1]); self.assertEqual(0, decoded["vapor"][2, 2])
        np.testing.assert_array_equal(decoded["liquid"][:, 0], 0)

    def test_composition_perturbation_changes_distribution_but_preserves_head_total(self):
        row = profile(17); y, _ = factorized.targets(row)
        baseline = factorized.decode(row["input"], y, "TWO_PHASE", .02)
        changed = y.copy(); changed[:, 7] += 2
        result = factorized.decode(row["input"], changed, "TWO_PHASE", .02)
        np.testing.assert_allclose(result["liquid"].sum(axis=1), baseline["liquid"].sum(axis=1), rtol=1e-12)
        self.assertGreater(result["liquid"][5, 4], baseline["liquid"][5, 4])

    def test_branch_and_support_rules_are_seed_constraints(self):
        row = profile(); y, _ = factorized.targets(row)
        y[1, 45+4] = -20
        masked = factorized.decode(row["input"], y, "LIQUID_ONLY", .02)
        unmasked = factorized.decode(row["input"], y, "TWO_PHASE", 0)
        self.assertEqual(0, masked["vapor"][0].sum())
        self.assertEqual(0, masked["liquid"][1, 4]); self.assertGreater(unmasked["liquid"][1, 4], 0)
        np.testing.assert_allclose(masked["liquid"].sum(axis=1), unmasked["liquid"].sum(axis=1), rtol=1e-12)
        for column, value in ((0, 1600), (1, 20), (3, 121), (45, float("nan"))):
            invalid = y.copy(); invalid[0, column] = value
            with self.assertRaises(ValueError): factorized.decode(row["input"], invalid, "TWO_PHASE", .02)

    def test_analytic_derivative_matches_finite_differences_for_every_head(self):
        row = profile(); y, fractions = factorized.targets(row)
        rng = np.random.default_rng(31)
        mean = y.mean(axis=0); scale = np.maximum(y.std(axis=0), 1.)
        mean[45:] = 0; scale[45:] = 1
        wanted = (y-mean)/scale
        predicted = wanted+rng.normal(0, .05, wanted.shape)
        feed = np.asarray(row["input"]["feedComponentMolarFlowsMolPerSecond"], dtype=float)
        prior = np.tile(feed/feed.sum(), (len(y), 1))
        weights = np.full(len(y), 1/len(y))
        _, gradient = factorized.loss_and_derivative(predicted, wanted, y, fractions, prior, weights, mean, scale, 20)
        epsilon = 1e-6
        for column in (0, 1, 2, 3, 7, 22, 23, 28, 42, 43, 44, 45, 48, 65, 71, 84):
            plus = predicted.copy(); minus = predicted.copy()
            plus[1, column] += epsilon; minus[1, column] -= epsilon
            high = factorized.loss_and_derivative(plus, wanted, y, fractions, prior, weights, mean, scale, 20)[0]
            low = factorized.loss_and_derivative(minus, wanted, y, fractions, prior, weights, mean, scale, 20)[0]
            self.assertAlmostEqual((high-low)/(2*epsilon), gradient[1, column], delta=2e-7)


if __name__ == "__main__": unittest.main()
