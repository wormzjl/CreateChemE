"""Leakage, masked-sequence and native-label contract checks for the pilot."""
import json
from pathlib import Path
import unittest
import numpy as np
try:
    import torch
except ImportError:
    torch = None

from prepare_transformer_data import read_rows, strict, digest
from prepare_generalized_evaluation import canonical_input_hash
if torch is not None:
    from train_transformer import ColumnModel, normalization, tensors, loss


@unittest.skipIf(torch is None, 'Optional PyTorch environment is not installed')
class TransformerChecks(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        torch.set_num_threads(2)
        cls.root=Path(__file__).resolve().parents[2]
        cls.path=cls.root/'build/neural-transformer/data-v2'
        if not cls.path.exists(): raise unittest.SkipTest('Restore or prepare transformer data-v2 first')
        cls.rows=read_rows(cls.path/'cases.jsonl')
        cls.train=[r for r in cls.rows if r['labelProvenance']['eligibleForFitting']]

    def test_folds_and_native_evidence(self):
        originals=read_rows(self.root/'build/neural-gen3/data/cases.jsonl')
        by_hash={canonical_input_hash(r['input']):r for r in self.rows}
        self.assertEqual(len(by_hash),len(self.rows))
        for old in originals:
            row=by_hash[canonical_input_hash(old['input'])]
            self.assertEqual(old['split'],row['split'])
            if strict(old): self.assertEqual(old['seed'],row['seed'])
        self.assertEqual(len(self.train),805)
        self.assertEqual(sum(r['split']=='challenge' for r in self.rows),35)
        for r in self.train:
            self.assertEqual(r['split'],'train'); self.assertTrue(strict(r))
            self.assertNotEqual(r['labelProvenance']['origin'],'historical_challenge')
        self.assertEqual(sum(r['labelProvenance']['origin']=='retired_gen3_fresh' for r in self.train),93)

    def test_frozen_holdout_and_hashes(self):
        info=json.loads((self.path/'data.json').read_text())
        for name,sha in info['fileSha256'].items(): self.assertEqual(sha,digest(self.path/name))
        holdout=read_rows(self.path/'fresh-holdout.jsonl')
        prior=read_rows(self.root/'build/neural-gen3/fresh-design/candidate-pool.jsonl')
        prior_hash={canonical_input_hash(r['input']) for r in prior+self.rows}
        self.assertEqual(len(holdout),252)
        for r in holdout:
            self.assertNotIn(canonical_input_hash(r['input']),prior_hash)
            self.assertNotIn('seed',r)
        for n in range(2,65):
            group=[r for r in holdout if r['input']['stageCount']==n]
            self.assertEqual(len(group),4)
            self.assertEqual(sum(bool(r['input']['steamFeeds']) for r in group),2)

    def test_padding_invariance_and_finite_gradient(self):
        rows=sorted(self.train,key=lambda r:r['input']['stageCount'])
        chosen=[rows[0],rows[-1]]
        norm=normalization(chosen); batch=tensors(chosen,norm,'cpu')
        for kind in ('transformer','mlp'):
            torch.manual_seed(42); model=ColumnModel(kind).eval()
            with torch.no_grad():
                pred,branch=model(batch['x'],batch['g'],batch['valid'])
                short=tensors(chosen[:1],norm,'cpu')
                p,b=model(short['x'],short['g'],short['valid'])
                torch.testing.assert_close(p[0],pred[0,:p.shape[1]],atol=2e-6,rtol=2e-6)
                changed=batch['x'].clone(); changed[~batch['valid']]=1234
                pc,bc=model(changed,batch['g'],batch['valid'])
                torch.testing.assert_close(pc[batch['valid']],pred[batch['valid']],atol=2e-6,rtol=2e-6)
            model.train(); pred,branch=model(batch['x'],batch['g'],batch['valid'])
            value=loss(pred,branch,batch,torch.tensor(norm['ym']),torch.tensor(norm['yscale']))
            value.backward()
            self.assertTrue(torch.isfinite(value))
            self.assertTrue(all(p.grad is not None and torch.isfinite(p.grad).all() for p in model.parameters()))

    def test_no_teacher_branch_in_stage_features(self):
        rows=self.train[:2]; norm=normalization(rows); batch=tensors(rows,norm,'cpu')
        import copy
        changed=copy.deepcopy(rows)
        for r in changed: r['seed']['branch']='VAPOR_ONLY'
        other=tensors(changed,norm,'cpu')
        torch.testing.assert_close(batch['x'],other['x'])
        torch.testing.assert_close(batch['g'],other['g'])

    def test_advisory_and_invalid_native_labels_rejected(self):
        import copy
        row=copy.deepcopy(self.train[0])
        row['equilibriumQualified']=False
        self.assertFalse(strict(row))
        row=copy.deepcopy(self.train[0])
        row['diagnostics']['convergenceEvidence']['maximumLogFlowChange']=1e-5
        with self.assertRaises(ValueError): strict(row)
        row=copy.deepcopy(self.train[0])
        row['seed']['input']['feedTemperatureKelvin']+=1
        with self.assertRaises(ValueError): strict(row)


if __name__=='__main__': unittest.main()
