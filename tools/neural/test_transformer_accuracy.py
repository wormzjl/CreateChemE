"""Decoder equivalence and gradients for the new training objective."""
import copy
import unittest
from pathlib import Path
import numpy as np
import torch

import train_transformer_accuracy as accuracy
import train_transformer as pilot
import train_gen3_factorized as factor
import train_generalized as base
from prepare_transformer_data import read_rows


class AccuracyTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        torch.set_num_threads(1)
        rows = read_rows(Path('build/neural-transformer/data-v2/cases.jsonl'))
        train = [r for r in rows if r['labelProvenance']['eligibleForFitting']]
        cls.rows = [min(train, key=lambda r:r['input']['stageCount']),
                    max(train, key=lambda r:r['input']['stageCount']),
                    next(r for r in train if r['seed']['branch']=='LIQUID_ONLY')]
        cls.norm = pilot.normalization(cls.rows)

    def batch(self, rows):
        batch = accuracy.tensors(rows, self.norm, 'cpu')
        for key, value in batch.items():
            if value.dtype.is_floating_point:
                batch[key] = value.double()
        batch['feed'] = torch.tensor(np.array([np.array(r['input']['feedComponentMolarFlowsMolPerSecond']) /
            sum(r['input']['feedComponentMolarFlowsMolPerSecond']) for r in rows]), dtype=torch.float64)
        batch['fixedTemperature'] = torch.tensor([next((s['kelvin'] for s in r['input']['specifications'] if 'kelvin' in s), float('nan')) for r in rows], dtype=torch.float64)
        return batch

    def test_hard_decoder_matches_numpy_on_boundaries_and_trace_masks(self):
        for row in self.rows:
            batch = self.batch([row]); values, _ = factor.targets(row)
            values[:, 1:3] += .02
            # Exercise all-absent fallback, active masks and a negative total.
            values[1, 45:85] = -8
            values[-1, 1] = -.2
            for branch in ('LIQUID_ONLY', 'TWO_PHASE'):
                scores = torch.full((1, 3), -10., dtype=torch.float64)
                scores[0, base.BRANCHES.index(branch)] = 10
                t, q, b, invalid = accuracy.decode(torch.tensor(values)[None], scores, batch)
                expected = factor.decode(row['input'], values, branch, .02)
                feed = sum(row['input']['feedComponentMolarFlowsMolPerSecond'])
                np.testing.assert_allclose(t[0].numpy(), expected['temperatures'], atol=1e-12)
                for i, name in enumerate(('liquid', 'vapor')):
                    np.testing.assert_allclose(q[0, :, i].numpy(), expected[name]/feed, atol=1e-12, rtol=1e-12)
                    np.testing.assert_array_equal(q[0, :, i].numpy()==0, expected[name]==0)
                self.assertFalse(invalid.item())
                self.assertEqual(b.item(), base.BRANCHES.index(branch))

    def test_decoded_loss_gradient_matches_finite_difference(self):
        row = self.rows[0]; batch = self.batch([row]); values, _ = factor.targets(row)
        values[:, 1:3] += .03
        raw = torch.tensor(values[None], dtype=torch.float64, requires_grad=True)
        branch = torch.tensor([[0., 10., -10.]], dtype=torch.float64)
        self.assertTrue(torch.autograd.gradcheck(lambda x:accuracy.decoded_flow_loss(x, branch, batch),
                                                (raw,), fast_mode=True, atol=1e-5, rtol=1e-4))

    def test_invalid_outputs_are_penalized_and_padding_is_ignored(self):
        batch = self.batch([self.rows[0]]); values, _ = factor.targets(self.rows[0])
        raw = torch.tensor(values[None]); scores = torch.tensor([[0., 10., -10.]])
        good = accuracy.metrics(raw, scores, batch)
        raw[:, 0, 0] = 1600
        bad = accuracy.metrics(raw, scores, batch)
        self.assertEqual(bad['invalidPrediction'].item(), 1)
        self.assertGreater(bad['selectionScore'].item(), good['selectionScore'].item()+9)
        expanded = copy.deepcopy(batch)
        expanded['valid'] = torch.cat((batch['valid'], torch.zeros((1, 1), dtype=torch.bool)), 1)
        for key in ('raw', 'fractions', 'flowTarget', 'temperatureTarget'):
            expanded[key] = torch.cat((batch[key], torch.zeros_like(batch[key][:, :1])), 1)
        raw = torch.tensor(values[None]); padded = torch.cat((raw, torch.zeros_like(raw[:, :1])), 1)
        a, b = accuracy.metrics(raw, scores, batch), accuracy.metrics(padded, scores, expanded)
        for key in a:
            torch.testing.assert_close(a[key], b[key])

    def test_dropout_preserves_inference_and_parameter_layout(self):
        torch.manual_seed(123); reference = accuracy.model_for(0.).eval()
        candidate = accuracy.model_for(.1).eval(); candidate.load_state_dict(reference.state_dict())
        batch = accuracy.tensors([self.rows[0]], self.norm, 'cpu')
        with torch.no_grad():
            a = reference(batch['x'], batch['g'], batch['valid'])
            b = candidate(batch['x'], batch['g'], batch['valid'])
        for x, y in zip(a, b):
            torch.testing.assert_close(x, y, rtol=0, atol=0)


if __name__ == '__main__':
    unittest.main()
