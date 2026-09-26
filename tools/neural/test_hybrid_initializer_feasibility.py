import copy
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import hybrid_initializer_feasibility as study


def example(case_id, raw=True, completed=True):
    inp = {'stageCount': 8, 'pumparounds': [], 'sideDraws': [], 'steamFeeds': [], 'specifications': [], 'caseMarker': case_id}
    row = {'id': case_id, 'split': 'train', 'input': inp, 'diagnostics': {'rawSupported': False}}
    for arm, success in zip(study.ARMS, (raw, completed)):
        row[arm] = {'success': success, 'status': 'ACCEPTED' if success else 'FAILED', 'ms': 10 if arm == 'raw' else 12,
                    'cpuMillis': None, 'preprocessing': {}, 'classicalFallback': True,
                    'equilibriumQualified': success, 'waterQualification': 'DRY_EQUILIBRIUM',
                    'seed': {'input': copy.deepcopy(inp)}, 'diagnostics': {
                        'acceptanceAudit': {'checks': [{'passed': True}]},
                        'convergenceEvidence': {'hasFinalNewtonStep': True, 'closureTolerance': 1e-8,
                                                'finalLinearBackwardError': 1e-13, 'maximumLogFlowChange': 1e-9,
                                                'maximumTemperatureStepRatio': .1}}}
    return {'id': case_id, 'split': 'train', 'input': inp}, row


class FeasibilityAnalysisTests(unittest.TestCase):
    def test_pairing_ignores_completion_order_and_preserves_gains_losses(self):
        fixtures, journal = zip(example('a', True, False), example('b', False, True))
        value = study.analyze(list(fixtures), list(reversed(journal)))
        self.assertEqual(value['paired']['gained'], ['b'])
        self.assertEqual(value['paired']['lost'], ['a'])
        self.assertEqual(value['paired']['elapsedDeltaMillis']['mean'], 2)
        self.assertEqual(value['treatments']['raw']['elapsedMillis']['count'], 2)
        self.assertEqual(value['treatments']['raw']['cpuMillis']['count'], 0)

    def test_advisories_are_not_strict_and_failed_times_remain(self):
        fixture, row = example('a')
        row['materialCompletion']['equilibriumQualified'] = False
        row['materialCompletion']['waterQualification'] = 'ADVISORY'
        value = study.analyze([fixture], [row])
        self.assertEqual(value['treatments']['materialCompletion']['strict'], 0)
        self.assertEqual(value['treatments']['materialCompletion']['advisoryOnly'], 1)
        self.assertEqual(value['paired']['lost'], ['a'])
        row['materialCompletion']['equilibriumQualified'] = True
        row['materialCompletion']['waterQualification'] = 'DRY_EQUILIBRIUM'
        row['materialCompletion']['diagnostics']['acceptanceAudit']['checks'][0]['passed'] = False
        with self.assertRaises(ValueError):
            study.analyze([fixture], [row])

    def test_missing_duplicate_mutated_inputs_and_missing_arms_fail(self):
        fixture, row = example('a')
        for data in ([], [row, row]):
            with self.assertRaises(ValueError):
                study.analyze([fixture], data)
        changed = copy.deepcopy(row)
        changed['input']['stageCount'] = 9
        with self.assertRaises(ValueError):
            study.analyze([fixture], [changed])
        del row['materialCompletion']
        with self.assertRaises(ValueError):
            study.analyze([fixture], [row])

    def test_selection_uses_original_train_and_input_quantiles(self):
        source = [{'id': f'train-{i:04d}', 'split': 'train',
                   'labelProvenance': {'eligibleForFitting': True},
                   'input': {'stageCount': 2 + i % 63, 'steamFeeds': [], 'sideDraws': [], 'pumparounds': [], 'specifications': [], 'marker': i}}
                  for i in range(805)]
        source += [{'id': 'validation', 'split': 'validation', 'labelProvenance': {'eligibleForFitting': True},
                    'input': {'stageCount': 2, 'steamFeeds': [], 'sideDraws': [], 'pumparounds': [], 'specifications': []}}]
        with patch.object(study, 'strict', return_value=True):
            selected, count = study.select(list(reversed(source)))
        self.assertEqual(count, 805)
        ordered = sorted(source[:-1], key=lambda row: (row['input']['stageCount'], row['id']))
        self.assertEqual([row['id'] for row in selected], [ordered[i * 804 // 19]['id'] for i in range(20)])
        self.assertTrue(all(row['split'] == 'train' for row in selected))

    def test_changed_dependency_and_policy_block_execution(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            asset = root / 'model.json'
            asset.write_text('frozen')
            expected = {'model.json': study.sha(asset)}
            with patch.object(study, 'ROOT', root):
                study.verify_hashes(expected)
                asset.write_text('changed')
                with self.assertRaises(ValueError):
                    study.verify_hashes(expected)
            plan = root / 'plan.json'
            policy = dict(study.POLICY, neuralBudgetMillis=10_000)
            plan.write_text(json.dumps({'policy': policy}))
            with patch.object(study, 'PLAN', plan), self.assertRaises(ValueError):
                study.verify_plan()

    def test_changed_report_blocks_sealing(self):
        fixture, row = example('a')
        value = study.analyze([fixture], [row])
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'summary.json').write_text(study.encode(value))
            (root / 'report.md').write_text('mutated report')
            cache = root / 'cache'
            with patch.object(study, 'AREA', root), patch.object(study, 'CACHE', cache), patch.object(study, 'results', return_value=value):
                with self.assertRaises(ValueError):
                    study.seal()
                self.assertFalse(cache.exists())


if __name__ == '__main__':
    unittest.main()
