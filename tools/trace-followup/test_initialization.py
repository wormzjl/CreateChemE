"""Initial function preservation and learnability of initially zero features."""
import copy
import unittest
from common import *
from initialization import ANCHORS
from export_models import fixture_rows


class InitializationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        torch.set_num_threads(4);cls.norm=read(OUT/'normalization.json')
        cls.batch=hybrid.tensors(fixture_rows()[:3],cls.norm,hybrid.load_anchors(ANCHORS),incumbent_document()['branchesSeen'],'cpu')

    def test_arbitrary_finite_anchor_values_cannot_change_initial_function(self):
        batch=copy.deepcopy(self.batch)
        batch['anchors']=torch.linspace(-1000,1000,batch['anchors'].numel()).reshape(batch['anchors'].shape)
        for layout,inputs in [('full',185),('compact',103)]:
            with torch.no_grad():
                expected,eb=absolute_forward(incumbent_model().eval(),batch,self.norm)
                actual,ab=absolute_forward(incumbent_model(inputs).eval(),batch,self.norm,layout)
            self.assertTrue(torch.equal(expected,actual));self.assertTrue(torch.equal(eb,ab))

    def test_unavailable_anchor_preserves_initial_prediction(self):
        batch=copy.deepcopy(self.batch);batch['anchors'].zero_();batch['available'].zero_()
        with torch.no_grad():
            expected,eb=absolute_forward(incumbent_model().eval(),batch,self.norm)
            actual,ab=absolute_forward(incumbent_model(103).eval(),batch,self.norm,'compact')
        self.assertTrue(torch.equal(expected,actual));self.assertTrue(torch.equal(eb,ab))

    def test_teacher_outputs_are_not_inference_inputs(self):
        batch=copy.deepcopy(self.batch)
        batch['labels']=1-batch['labels'];batch['raw'].add_(100);batch['flowTarget'].mul_(100);batch['temperatureTarget'].zero_()
        model=incumbent_model(103).eval()
        with torch.no_grad():
            expected,eb=absolute_forward(model,self.batch,self.norm,'compact')
            actual,ab=absolute_forward(model,batch,self.norm,'compact')
        self.assertTrue(torch.equal(expected,actual));self.assertTrue(torch.equal(eb,ab))

    def test_zero_projection_can_learn(self):
        model=incumbent_model(103).train();raw,branch=absolute_forward(model,self.batch,self.norm,'compact')
        ym=raw.new_tensor(self.norm['ym']);ys=raw.new_tensor(self.norm['yscale'])
        loss=pilot.loss((raw-ym)/ys,branch,self.batch,ym,ys);loss.backward()
        self.assertGreater(float(model.embed.weight.grad[:,96:].abs().sum()),0.)
        self.assertTrue(torch.isfinite(model.embed.weight.grad).all())


if __name__=='__main__':unittest.main(verbosity=2)
