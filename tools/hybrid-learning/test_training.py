"""Training contracts for branch conditioning, failures and gradient flow."""
import copy
import unittest
from pathlib import Path
import torch
import train_hybrid as h


class TrainingContracts(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        torch.set_num_threads(2)
        rows=h.read_rows(Path('build/neural-salvage/nplus1-v1/N-cases.jsonl'))
        cls.rows=[r for r in rows if r['split']=='train']
        cls.anchors=h.load_anchors([Path('build/neural-hybrid-learning/v1/N-anchors.jsonl')])
        cls.norm=h.normalization(cls.rows,cls.anchors)
        cls.row=min(cls.rows,key=lambda r:r['input']['stageCount'])

    def test_classifier_controls_anchor_without_target_branch(self):
        batch=h.tensors([self.row],self.norm,self.anchors,[True,True,False],'cpu')
        model=h.pilot.ColumnModel('transformer',inputs=185).eval()
        with torch.no_grad():
            model.branch[2].weight.zero_();model.branch[2].bias.copy_(torch.tensor([100.,0.,-100.]))
            first,_=h.forward(model,batch,self.norm)
            batch['labels'][:]=1-batch['labels']
            second,_=h.forward(model,batch,self.norm)
        self.assertTrue(torch.equal(first,second))

    def test_failed_anchor_is_retained_and_trainable(self):
        anchors=copy.deepcopy({self.row['id']:self.anchors[self.row['id']]})
        for a in anchors[self.row['id']].values():
            a['available']=False;a['values']=[[0.]*85 for _ in a['values']]
        batch=h.tensors([self.row],self.norm,anchors,[True,True,False],'cpu')
        model=h.pilot.ColumnModel('transformer',inputs=185)
        raw,branch=h.forward(model,batch,self.norm)
        ym,ys=(torch.tensor(self.norm[k]) for k in ('ym','yscale'))
        loss=h.pilot.loss((raw-ym)/ys,branch,batch,ym,ys);loss.backward()
        self.assertTrue(torch.isfinite(loss))
        self.assertTrue(torch.isfinite(model.embed.weight.grad).all())
        self.assertGreater(model.branch[2].weight.grad.abs().sum().item(),0)
        self.assertEqual(len(raw),1)

    def test_water_and_presence_are_absolute(self):
        batch=h.tensors([self.row],self.norm,self.anchors,[True,True,False],'cpu')
        model=h.pilot.ColumnModel('transformer',inputs=185).eval()
        with torch.no_grad():
            model.output[1].weight.zero_();model.output[1].bias.zero_()
            raw,_=h.forward(model,batch,self.norm)
        expected=raw.new_tensor(self.norm['dm'][43:]).expand_as(raw[...,43:])
        self.assertTrue(torch.equal(raw[...,43:],expected))
        self.assertEqual(sum(p.numel() for p in model.parameters()),89496)


if __name__=='__main__':unittest.main()
