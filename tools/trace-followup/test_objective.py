"""Regression checks for trace-loss semantics before experimental registration."""
import unittest
import torch
from trace_objective import prepruning_log_flows,trace_margin


class TraceObjectiveTests(unittest.TestCase):
    def fixture(self):
        raw=torch.zeros((1,3,85),dtype=torch.float64)
        raw[...,1:3]=1.
        raw[...,3]=-35.;raw[...,23]=-35.
        raw.requires_grad_()
        feed=torch.zeros((1,20),dtype=torch.float64);feed[0,:2]=.5
        target=torch.zeros((1,3,2,20),dtype=torch.float64)
        target[0,1,0,0]=1e-10
        return raw,dict(feed=feed,valid=torch.tensor([[True,True,False]]),flowTarget=target)

    def test_below_floor_reference_has_zero_loss(self):
        raw,batch=self.fixture();batch['flowTarget'][0,1,0,0]=1e-11
        loss,detail=trace_margin(raw,batch);self.assertEqual(float(loss.detach()),0.)
        self.assertEqual(int(detail['referenceEntries']),0)

    def test_reference_present_pruned_trace_has_restoring_gradient(self):
        raw,batch=self.fixture();loss,_=trace_margin(raw,batch);loss.backward()
        self.assertGreater(float(loss.detach()),0.)
        self.assertLess(float(raw.grad[0,1,3]),0.)
        self.assertTrue(torch.isfinite(raw.grad).all())

    def test_structural_zeros_and_padding_have_no_gradient(self):
        raw,batch=self.fixture();batch['flowTarget'][0,2,0,0]=10.
        loss,_=trace_margin(raw,batch);loss.backward()
        self.assertEqual(float(raw.grad[0,0].abs().sum()),0.)
        self.assertEqual(float(raw.grad[0,2].abs().sum()),0.)
        self.assertEqual(float(raw.grad[0,1,23:43].abs().sum()),0.)

    def test_margin_stops_at_ten_floors(self):
        raw,batch=self.fixture();batch['flowTarget'][0,1,0,0]=1e-6
        a,_=trace_margin(raw,batch);batch['flowTarget'][0,1,0,0]=1.
        b,_=trace_margin(raw,batch);self.assertEqual(float(a.detach()),float(b.detach()))

    def test_auxiliary_does_not_silently_train_presence_logits(self):
        raw,batch=self.fixture();loss,_=trace_margin(raw,batch);loss.backward()
        self.assertEqual(float(raw.grad[...,45:85].abs().sum()),0.)

    def test_all_active_surrogate_is_explicit(self):
        raw,batch=self.fixture();a=prepruning_log_flows(raw,batch)
        with torch.no_grad():raw[...,45:85]=-100.
        b=prepruning_log_flows(raw,batch)
        self.assertTrue(torch.equal(a,b))

    def test_empty_phase_and_negative_total_are_finite(self):
        raw,batch=self.fixture()
        with torch.no_grad():raw[...,1:3]=-10.
        loss,_=trace_margin(raw,batch);loss.backward()
        self.assertTrue(torch.isfinite(loss));self.assertTrue(torch.isfinite(raw.grad).all())

    def test_reference_at_and_above_floor_are_included(self):
        for value in (5e-11,5.00001e-11):
            raw,batch=self.fixture();batch['flowTarget'][0,1,0,0]=value
            loss,detail=trace_margin(raw,batch)
            self.assertEqual(int(detail['referenceEntries']),1);self.assertGreater(float(loss.detach()),0.)

    def test_inactive_feed_does_not_get_supervised(self):
        raw,batch=self.fixture();batch['flowTarget'].zero_();batch['flowTarget'][0,1,0,2]=1.
        loss,detail=trace_margin(raw,batch)
        self.assertEqual(float(loss.detach()),0.);self.assertEqual(int(detail['referenceEntries']),0)


if __name__=='__main__':unittest.main(verbosity=2)
