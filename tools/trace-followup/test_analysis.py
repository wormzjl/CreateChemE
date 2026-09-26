"""Check that missing/ambiguous evidence is not silently called success or zero error."""
import copy
import unittest
from analysis_common import *


class EvidenceTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):cls.reference=read_rows(OUT/'N804.jsonl')[0]

    def row(self):
        r=self.reference
        return dict(id=r['id'],input=r['input'],rawPrediction=dict(seedPresentedByPipeline=copy.deepcopy(r['seed']),supported=True))

    def test_identical_native_seed_has_no_profile_omissions(self):
        e=profile_evidence(self.row(),self.reference)
        self.assertEqual(e['aboveFloorOmissions'],0);self.assertEqual(e['temperatureMaeKelvin'],0.)

    def test_missing_prediction_is_unavailable_not_zero_error(self):
        row=self.row();row['rawPrediction']=dict(supported=False)
        e=profile_evidence(row,self.reference)
        self.assertFalse(e['available']);self.assertNotIn('aboveFloorOmissions',e)
        summary=summarize_profiles([e]);self.assertEqual(summary['unavailable'],1);self.assertEqual(summary['available'],0)

    def test_erased_above_floor_component_is_counted(self):
        row=self.row();seed=row['rawPrediction']['seedPresentedByPipeline']
        feed=np.asarray(row['input']['feedComponentMolarFlowsMolPerSecond']);floor=1e-10*np.maximum(feed,1e-12*feed.sum())
        node,component=np.argwhere(np.asarray(seed['liquid'])>=floor)[0]
        seed['liquid'][int(node)][int(component)]=0.
        e=profile_evidence(row,self.reference);self.assertEqual(e['aboveFloorOmissions'],1)

    def test_first_fallback_remains_unknown_without_events(self):
        row=dict(neuralFirst=dict(success=False,status='DEADLINE_EXCEEDED',ms=30000,diagnostics=None))
        e=mode_evidence(row,'neuralFirst');self.assertIsNone(e['fallbackObserved'])
        self.assertEqual(e['terminalTrajectoryOwner'],'unknown');self.assertIn('whole_request_deadline',e['observedStopPhrases'])

    def test_first_backup_has_classical_terminal_owner(self):
        row=dict(neuralFirst=dict(success=False,status='NONCONVERGENCE',ms=300,
            diagnostics=dict(events=['initializer=CURRENT_BACKUP; neural correction INITIALIZATION_FAILURE','iteration budget exhausted'])))
        e=mode_evidence(row,'neuralFirst');self.assertTrue(e['fallbackObserved'])
        self.assertEqual(e['terminalTrajectoryOwner'],'classical_backup')

    def test_paired_profiles_use_only_common_available_predictions(self):
        good=profile_evidence(self.row(),self.reference);missing=dict(id=good['id'],available=False)
        r=paired_profiles({good['id']:good},{good['id']:missing},[good['id']])
        self.assertEqual(r['bothAvailable'],0);self.assertEqual(r['candidateUnavailable'],1)


if __name__=='__main__':unittest.main(verbosity=2)
