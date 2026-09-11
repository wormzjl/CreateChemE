"""Focused regressions for scientific false-merging and input isolation."""
import copy
import unittest
import numpy as np
import curate as c


class CurationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.records=c.allowed_source()
        cls.record=next(r for r in cls.records if r['row']['seed']['branch']=='TWO_PHASE')
        cls.a=c.describe(cls.record)
        cls.scales=c.scales_for([cls.a])

    def test_identity_is_represented(self):
        self.assertTrue(c.compare(self.a,self.a,self.scales)['admissible'])

    def test_near_inputs_different_temperature_not_redundant(self):
        b=copy.deepcopy(self.a);b['temperature'][1]+=3
        self.assertIn('temperature',c.compare(self.a,b,self.scales)['failedGates'])

    def test_trace_crossing_not_relaxed_by_sensitivity(self):
        b=copy.deepcopy(self.a);b['bands'][1,0,0]=(int(b['bands'][1,0,0])+1)%4
        self.assertIn('trace_band_crossing',c.compare(self.a,b,self.scales,2)['failedGates'])

    def test_structural_absence_not_merged(self):
        b=copy.deepcopy(self.a);b['structural'][0,0,0]=not b['structural'][0,0,0]
        self.assertIn('structural_phase_mismatch',c.compare(self.a,b,self.scales)['failedGates'])

    def test_positive_flow_in_structurally_absent_phase_rejected(self):
        record=copy.deepcopy(next(r for r in self.records if r['row']['seed']['branch']=='LIQUID_ONLY'))
        record['row']['seed']['vapor'][0][0]=1e-20
        with self.assertRaises(AssertionError):c.describe(record)

    def test_nontransitive_graph_requires_direct_cover(self):
        adj=np.array([[1,1,0],[1,1,1],[0,1,1]],bool)
        selected,_=c.choose_cover(adj,np.zeros((3,3)),{0},{},['a','b','c'])
        self.assertIn(0,selected);self.assertGreater(len(selected),1)
        self.assertTrue(adj[list(selected)].any(0).all())

    def test_forbidden_sources_fail_before_read(self):
        for path in ('test.jsonl','validation.jsonl','model.pt','errors.json','Nplus1-certified-cases.jsonl'):
            with self.subTest(path=path),self.assertRaises(PermissionError):c.allowed_source(c.ROOT/path)

    def test_metadata_is_not_a_feature(self):
        r=copy.deepcopy(self.record);r['row']['ms']=-1e99;r['row']['modelError']=1e99;r['row']['solverPath']='invented'
        d=c.describe(r)
        self.assertEqual(self.a['values'],d['values']);self.assertEqual(self.a['signature'],d['signature'])
        self.assertTrue(c.compare(self.a,d,self.scales)['admissible'])

    def test_positive_below_floor_is_preserved(self):
        r=copy.deepcopy(self.record);feed=np.asarray(r['row']['input']['feedComponentMolarFlowsMolPerSecond'])
        component=int(np.flatnonzero(feed>0)[0]);r['row']['seed']['liquid'][1][component]=self.a['floor'][component]/2
        d=c.describe(r);self.assertEqual(d['bands'][1,0,component],1)
        self.assertGreater(d['flow'][1,0,component],0)


if __name__=='__main__':unittest.main(verbosity=2)
