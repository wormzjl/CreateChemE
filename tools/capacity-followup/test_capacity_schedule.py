"""Bounded paired exposure and native checkpoint ranking semantics."""
import unittest
from capacity_common import *
from capacity_benchmark import rank


class CapacityScheduleTests(unittest.TestCase):
    def test_full_pass_endpoints_preserve_partial_batches(self):
        for seed in (20260913,20260914):
            counts=np.zeros(804,dtype=int)
            for step,indices in enumerate(batches(804,3120,seed),1):
                counts[indices.numpy()]+=1
                if step in (1040,2080,3120):self.assertTrue(np.all(counts==step//26))

    def test_classical_preservation_precedes_count(self):
        keep=dict(strictFirstIds=['a'],firstMeanMillis=500)
        lose=dict(strictFirstIds=['b','c'],firstMeanMillis=1)
        self.assertLess(rank(keep,{'a'},2080),rank(lose,{'a'},1040))

    def test_count_then_latency_then_earlier_step(self):
        more=dict(strictFirstIds=['b','c'],firstMeanMillis=500)
        fewer=dict(strictFirstIds=['b'],firstMeanMillis=1)
        self.assertLess(rank(more,{'a'},3120),rank(fewer,{'a'},1040))
        self.assertLess(rank(fewer,set(),1040),rank(fewer,set(),2080))
        faster=dict(strictFirstIds=['b'],firstMeanMillis=.5)
        self.assertLess(rank(faster,set(),2080),rank(fewer,set(),1040))


if __name__=='__main__':unittest.main(verbosity=2)
