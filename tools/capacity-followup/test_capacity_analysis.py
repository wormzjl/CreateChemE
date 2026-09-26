"""Decision checks that distinguish net gains, preservation and timing regressions."""
import unittest
from capacity_analysis import gate


def record(success, milliseconds):
    return {'modes': {'neuralFirst': {'outcome': 'strict' if success else 'failed', 'milliseconds': milliseconds}}}


class GateTests(unittest.TestCase):
    def fixture(self):
        return {(block, name): {i: record(i < count, cost) for i in range(5)}
            for block in (1, 2) for name, count, cost in [('L2', 2, 100), ('L4', 3, 90)]}

    def test_replicated_gain_and_timing_pass(self):
        self.assertTrue(gate('L2', 'L4', [1, 2], self.fixture(), {0, 1})['passed'])

    def test_gain_in_one_block_is_insufficient(self):
        cases = self.fixture()
        cases[2, 'L4'][2] = record(False, 90)
        self.assertFalse(gate('L2', 'L4', [1, 2], cases, {0, 1})['passed'])

    def test_net_gain_does_not_hide_classical_loss(self):
        cases = self.fixture()
        cases[1, 'L4'][0] = record(False, 90)
        cases[1, 'L4'][3] = record(True, 90)
        result = gate('L2', 'L4', [1, 2], cases, {0, 1})
        self.assertTrue(result['improvesStrictFirstBothBlocks'])
        self.assertFalse(result['passed'])
        self.assertEqual(result['classicalMissedIds']['1'], [0])

    def test_latency_includes_failures(self):
        cases = self.fixture()
        cases[2, 'L4'][4] = record(False, 300)
        result = gate('L2', 'L4', [1, 2], cases, {0, 1})
        self.assertFalse(result['noPooledMeanLatencyRegression'])
        self.assertFalse(result['passed'])


if __name__ == '__main__': unittest.main()
