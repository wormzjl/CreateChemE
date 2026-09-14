"""The design generator's request-only feasibility filter, against the shipped solver's own verdicts.

The claim under test is not "this Python agrees with itself". It is that a condition this generator admits
is not one production types ``INFEASIBLE_SPECIFICATION`` without solving, and the only way to establish that
is to compare against what the Java admission actually answered. ``V3RequestAdmissionProbe`` recorded that
verdict for all 3,297 archived requests; this test replays those verdicts through the Python filter, case
for case, whenever the probe output is present, and falls back to the committed exclusion lists otherwise.
"""
import copy
import json
import math
import unittest
from pathlib import Path

import generalized_design as design

ROOT = Path(__file__).resolve().parents[2]
PROBE = ROOT / 'build/benchmark-population/v1/admission'
POPULATIONS = ROOT / 'build/benchmark-population/inputs'
FROZEN = ROOT / 'tools/benchmark-population/v1'


def rows(path):
    with Path(path).open(encoding='utf-8') as stream:
        return [json.loads(line) for line in stream if line.strip()]


class RequestOnlyFilterTest(unittest.TestCase):
    def test_the_filter_is_off_by_default_so_archived_matrices_still_reproduce(self):
        baseline = json.loads(design.DEFAULT_BASELINE.read_text(encoding='utf-8-sig'))['input']
        starved = copy.deepcopy(baseline)
        # A draw far beyond what any tray's liquid supply can deliver, but still below the total feed, so
        # the pre-existing material gates say nothing about it.
        feed = math.fsum(starved['feedComponentMolarFlowsMolPerSecond'])
        starved['sideDraws'] = [{'trayNumber': 1, 'molarFlowMolPerSecond': 0.9 * feed}]
        ratio, tray = design.liquid_supply_ratio(starved)
        self.assertGreater(ratio, design.PRODUCTION_SCREEN_RATIO)
        self.assertEqual(1, tray)
        self.assertEqual([], design.screen_input(starved),
                         'the default filter must be byte-identical to the one the frozen matrices used')
        codes = [i['code'] for i in design.screen_input(starved, None, design.PRODUCTION_SCREEN_RATIO)]
        self.assertIn(codes[0], ('LIQUID_SUPPLY_ENVELOPE', 'LIQUID_SUPPLY_BALANCE'))

    def test_the_two_tiers_make_different_claims(self):
        baseline = json.loads(design.DEFAULT_BASELINE.read_text(encoding='utf-8-sig'))['input']
        feed = math.fsum(baseline['feedComponentMolarFlowsMolPerSecond'])

        # Total reflux of zero with no steam and no cooling: nothing at all reaches tray 1, so any draw
        # there is unsuppliable by a closed balance rather than by a calibration.
        impossible = copy.deepcopy(baseline)
        impossible['steamFeeds'] = []
        impossible['pumparounds'] = []
        impossible['specifications'] = [{'ratio': 0.0} if 'ratio' in s else s
                                        for s in impossible['specifications']]
        impossible['sideDraws'] = [{'trayNumber': 1, 'molarFlowMolPerSecond': 0.5 * feed}]
        self.assertGreater(impossible['feedStageNumber'], 1)
        necessary = design.request_only_exclusions(impossible, design.PRODUCTION_SCREEN_RATIO)
        self.assertEqual(['LIQUID_SUPPLY_BALANCE'], [i['code'] for i in necessary])
        self.assertEqual(['physical_necessity'], [i['category'] for i in necessary])
        # The necessary tier is a proof and fires with the calibrated tier disabled; the envelope does not.
        self.assertEqual(['LIQUID_SUPPLY_BALANCE'],
                         [i['code'] for i in design.request_only_exclusions(impossible, 0.0)])

        # A draw the balance permits but the solver has never been shown to reach.
        envelope = copy.deepcopy(baseline)
        for fraction in (0.05, 0.2, 0.4, 0.6, 0.8, 0.9, 0.95, 0.99):
            envelope['sideDraws'] = [{'trayNumber': 1, 'molarFlowMolPerSecond': fraction * feed}]
            ratio = design.liquid_supply_ratio(envelope)[0]
            if design.PRODUCTION_SCREEN_RATIO <= ratio < design.NECESSARY_LIQUID_SUPPLY_RATIO:
                break
        else:
            self.fail('no draw rate lands between the calibrated and the necessary tier')
        self.assertEqual([], design.request_only_exclusions(envelope, 0.0))
        calibrated = design.request_only_exclusions(envelope, design.PRODUCTION_SCREEN_RATIO)
        self.assertEqual(['LIQUID_SUPPLY_ENVELOPE'], [i['code'] for i in calibrated])
        self.assertEqual(['solver_admission'], [i['category'] for i in calibrated])
        self.assertIn('Not a proof', calibrated[0]['proof'])

    def test_a_request_with_no_draws_is_never_screened(self):
        baseline = json.loads(design.DEFAULT_BASELINE.read_text(encoding='utf-8-sig'))['input']
        without = copy.deepcopy(baseline)
        without['sideDraws'] = []
        self.assertEqual((0.0, 0), design.liquid_supply_ratio(without))
        self.assertEqual([], design.request_only_exclusions(without, design.PRODUCTION_SCREEN_RATIO))

    def test_the_filter_reproduces_the_shipped_admission_case_for_case(self):
        """Against the Java verdict where it has been recorded, against the frozen exclusions otherwise."""
        checked = 0
        for population in ('validation', 'g4fresh', 'g6fresh', 'historical-test', 'train'):
            verdicts = PROBE / population / 'admission.jsonl'
            inputs = POPULATIONS / f'{population}.jsonl'
            if verdicts.exists() and inputs.exists():
                expected = {row['id']: row for row in rows(verdicts)}
                for row in rows(inputs):
                    java = expected[row['id']]
                    mine = design.request_only_exclusions(row['input'], design.PRODUCTION_SCREEN_RATIO)
                    self.assertEqual(java['gate'] == 'liquidSupplyScreen', bool(mine), row['id'])
                    if java['liquidSupplyRatio'] is not None:
                        self.assertAlmostEqual(java['liquidSupplyRatio'],
                                               design.liquid_supply_ratio(row['input'])[0],
                                               places=9, msg=row['id'])
                    checked += 1
                continue
            frozen = FROZEN / population / 'exclusions.json'
            filtered = FROZEN / population / f'{population}-inputs.jsonl'
            if not frozen.exists():
                continue
            screened = {row['id'] for row in json.loads(frozen.read_text(encoding='utf-8'))['exclusions']
                        if row['gate'] == 'liquidSupplyScreen'}
            for row in rows(filtered):
                self.assertEqual([], design.request_only_exclusions(row['input'], design.PRODUCTION_SCREEN_RATIO),
                                 row['id'])
                self.assertNotIn(row['id'], screened)
                checked += 1
        self.assertGreater(checked, 0, 'no population evidence was available to check against')


if __name__ == '__main__':
    unittest.main()
