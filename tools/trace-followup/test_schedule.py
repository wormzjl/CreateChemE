"""Exact exposure and paired-stream regressions for the registered endpoints."""
import hashlib
import unittest
import numpy as np
from train import batches


def accounting(n,seed):
    counts=np.zeros(n,dtype=np.int64);digest=hashlib.sha256();checkpoints={}
    for step,indices in enumerate(batches(n,4640,seed),1):
        ix=indices.numpy();counts[ix]+=1;digest.update(ix.tobytes())
        if step in (3120,4160,4640):checkpoints[step]=(counts.copy(),digest.hexdigest())
    return checkpoints


class ExposureTests(unittest.TestCase):
    def test_exact_presentation_controls(self):
        for seed in (20260911,20260912):
            c=accounting(804,seed);d=accounting(905,seed)
            self.assertEqual(set(c[4160][0]),{160})
            self.assertEqual(set(d[4640][0]),{160})
            self.assertEqual(set(d[4160][0]),{143,144})
            self.assertEqual(set(c[4640][0]),{178,179})

    def test_shuffle_stream_is_independent_of_model_rng(self):
        import torch
        a=accounting(804,20260911);torch.manual_seed(100);torch.randn(999)
        b=accounting(804,20260911)
        for step in a:
            self.assertTrue(np.array_equal(a[step][0],b[step][0]));self.assertEqual(a[step][1],b[step][1])


if __name__=='__main__':unittest.main(verbosity=2)
