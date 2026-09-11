"""Scientific checkpoint ranking must honor preservation before aggregate count."""
import unittest
from benchmark_followup import screen_rank


class SelectionTests(unittest.TestCase):
    def test_preservation_precedes_larger_success_count(self):
        preserves=dict(strictFirstIds=['a'],firstMeanMillis=100.)
        loses=dict(strictFirstIds=['b','c'],firstMeanMillis=1.)
        self.assertLess(screen_rank(preserves,{'a'},4160),screen_rank(loses,{'a'},3120))

    def test_if_none_preserves_best_diagnostic_count_is_retained(self):
        more=dict(strictFirstIds=['b','c'],firstMeanMillis=100.)
        fewer=dict(strictFirstIds=['b'],firstMeanMillis=1.)
        self.assertLess(screen_rank(more,{'a'},4160),screen_rank(fewer,{'a'},3120))

    def test_latency_then_earlier_checkpoint_break_ties(self):
        a=dict(strictFirstIds=['a'],firstMeanMillis=10.)
        b=dict(strictFirstIds=['a'],firstMeanMillis=11.)
        self.assertLess(screen_rank(a,{'a'},4640),screen_rank(b,{'a'},3120))
        self.assertLess(screen_rank(a,{'a'},3120),screen_rank(a,{'a'},4160))


if __name__=='__main__':unittest.main(verbosity=2)
