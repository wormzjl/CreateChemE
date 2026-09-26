"""Depth expansion must preserve the trained function and remain trainable."""
import unittest
from capacity_common import *


class CapacityInitializationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        torch.set_num_threads(4)
        rows = read_rows(SOURCE / 'N804.jsonl')
        cls.rows = [min(rows, key=lambda r: r['input']['stageCount']),
                    max(rows, key=lambda r: r['input']['stageCount']),
                    next(r for r in rows if r['seed']['branch'] == 'LIQUID_ONLY')]
        cls.batch = dataset(cls.rows)
        cls.norm = normalization()

    def test_added_blocks_preserve_raw_outputs_and_branch_exactly(self):
        small, large = initial_model(2).eval(), initial_model(4).eval()
        with torch.no_grad():
            a, ab = forward(small, self.batch, self.norm)
            b, bb = forward(large, self.batch, self.norm)
        self.assertTrue(torch.equal(a, b))
        self.assertTrue(torch.equal(ab, bb))
        _, af, ac, ai = accuracy.decode(a, ab, self.batch)
        _, bf, bc, bi = accuracy.decode(b, bb, self.batch)
        self.assertTrue(torch.equal(af == 0, bf == 0))
        self.assertTrue(torch.equal(ac, bc))
        self.assertTrue(torch.equal(ai, bi))

    def test_shared_parameters_are_exact_copies(self):
        small, large = initial_model(2), initial_model(4)
        for name, value in small.state_dict().items():
            self.assertTrue(torch.equal(value, large.state_dict()[name]), name)

    def test_zero_output_projections_receive_gradient(self):
        model = initial_model(4).train()
        raw, branch = forward(model, self.batch, self.norm)
        ym, ys = raw.new_tensor(self.norm['ym']), raw.new_tensor(self.norm['yscale'])
        loss = pilot.loss((raw - ym) / ys, branch, self.batch, ym, ys)
        loss.backward()
        for index in (2, 3):
            for name in ('self_attn.out_proj.weight', 'linear2.weight'):
                parameter = dict(model.blocks[index].named_parameters())[name]
                self.assertTrue(torch.isfinite(parameter.grad).all())
                self.assertGreater(float(parameter.grad.norm()), 0)

    def test_expansion_is_independent_of_training_rng(self):
        torch.manual_seed(13); a = initial_model(4)
        torch.manual_seed(97); b = initial_model(4)
        self.assertEqual(state_digest(a), state_digest(b))

    def test_first_update_opens_gradients_into_added_interiors(self):
        model=initial_model(4).train()
        optimizer=torch.optim.AdamW(model.parameters(),lr=8e-5,weight_decay=1e-4)
        ym=torch.tensor(self.norm['ym']);ys=torch.tensor(self.norm['yscale'])
        raw,branch=forward(model,self.batch,self.norm)
        pilot.loss((raw-ym)/ys,branch,self.batch,ym,ys).backward()
        torch.nn.utils.clip_grad_norm_(model.parameters(),1.);optimizer.step();optimizer.zero_grad(set_to_none=True)
        raw,branch=forward(model,self.batch,self.norm)
        pilot.loss((raw-ym)/ys,branch,self.batch,ym,ys).backward()
        for index in (2,3):
            for name in ('self_attn.in_proj_weight','linear1.weight','norm1.weight','norm2.weight'):
                gradient=dict(model.blocks[index].named_parameters())[name].grad
                self.assertTrue(torch.isfinite(gradient).all(),name)
                self.assertGreater(float(gradient.norm()),0,name)

    def test_expansion_preserves_unavailable_anchor_behavior(self):
        batch={k:v.clone() for k,v in self.batch.items()}
        batch['available'].zero_();batch['anchors'].zero_()
        with torch.no_grad():
            a,ab=forward(initial_model(2).eval(),batch,self.norm)
            b,bb=forward(initial_model(4).eval(),batch,self.norm)
        self.assertTrue(torch.equal(a,b));self.assertTrue(torch.equal(ab,bb))

    def test_teacher_targets_do_not_change_predictions(self):
        batch={k:v.clone() for k,v in self.batch.items()}
        batch['raw'].fill_(170.);batch['fractions'].zero_();batch['labels'].zero_()
        model=initial_model(4).eval()
        with torch.no_grad():
            a,ab=forward(model,self.batch,self.norm);b,bb=forward(model,batch,self.norm)
        self.assertTrue(torch.equal(a,b));self.assertTrue(torch.equal(ab,bb))

    def test_minibatch_stream_is_independent_of_model_rng(self):
        a = [x.tolist() for x in batches(804, 55, 20260913)]
        torch.rand(1700)
        b = [x.tolist() for x in batches(804, 55, 20260913)]
        self.assertEqual(a, b)
        self.assertEqual(len(a[25]), 4)


if __name__ == '__main__':
    unittest.main(verbosity=2)
