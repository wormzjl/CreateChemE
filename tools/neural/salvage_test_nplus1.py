"""Salvage registration and strict-label rejection checks; no solver reruns."""
import copy, json, unittest
import salvage_nplus1 as s
class SalvageContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.n=s.read_rows(s.AREA/'N.jsonl')
        cls.matrix=s.read_rows(s.AREA/'matrix.jsonl')
    def test_exact_n_and_unique_population(self):
        original=s.read_rows(s.N_SOURCE)
        expected=[r for r in original if r['split']=='train' and r['labelProvenance']['eligibleForFitting'] and s.strict(r)]
        self.assertEqual(self.n,expected);self.assertEqual(len(self.n),805)
        self.assertEqual(len(self.matrix),2793)
        self.assertEqual(len({s.canonical_input_hash(r['input']) for r in self.matrix}),2793)
    def test_no_heldout_n_overlap(self):
        heldout={s.canonical_input_hash(r['input']) for r in self.matrix if r['split']!='train'}
        self.assertFalse(heldout.intersection(s.canonical_input_hash(r['input']) for r in self.n))
    def test_advisory_rejected(self):
        row=copy.deepcopy(self.n[0]);row['equilibriumQualified']=False
        self.assertFalse(s.strict(row))
    def test_mismatched_native_seed_rejected(self):
        row=copy.deepcopy(self.n[0]);row['seed']['input']['feedTemperatureKelvin']+=1
        with self.assertRaises(ValueError):s.strict(row)
    def test_missing_final_newton_rejected(self):
        row=copy.deepcopy(self.n[0]);row['diagnostics']['convergenceEvidence']['hasFinalNewtonStep']=False
        with self.assertRaises(ValueError):s.strict(row)
    def test_failed_native_audit_rejected(self):
        row=copy.deepcopy(self.n[0]);row['diagnostics']['acceptanceAudit']['checks'][0]['passed']=False
        with self.assertRaises(ValueError):s.strict(row)
    def test_registered_sources_unchanged(self):
        s.verify(json.loads((s.AREA/'plan.json').read_text()))
if __name__=='__main__':unittest.main()
