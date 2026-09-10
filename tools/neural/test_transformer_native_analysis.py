"""Scientific counting and pairing checks independent of runtime timing noise."""
import copy
from pathlib import Path
import unittest
from analyze_transformer_native import outcomes, paired
from prepare_transformer_data import read_rows, strict


class NativeAnalysisTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        path=Path(__file__).resolve().parents[2]/'build/neural-transformer/data-v2/cases.jsonl'
        if not path.exists(): raise unittest.SkipTest('Restore transformer data first')
        cls.teacher=next(r for r in read_rows(path) if strict(r))

    def row(self,identifier,qualified=True,advisory=False,ms=1.):
        row=copy.deepcopy(self.teacher); row['id']=identifier; row['ms']=ms
        if advisory: row['equilibriumQualified']=False; row['waterQualification']='DRY_ADVISORY'
        elif not qualified: row['success']=False; row['equilibriumQualified']=False; row.pop('seed',None)
        return row

    def test_advisory_and_failure_are_not_qualified(self):
        result=outcomes([self.row('a',ms=1),self.row('b',advisory=True,ms=3),self.row('c',qualified=False,ms=5)])
        self.assertEqual(result['strictQualified'],1)
        self.assertEqual(result['nativeAccepted'],2)
        self.assertEqual(result['advisoryOnly'],1)
        self.assertEqual(result['ms']['mean'],3)
        self.assertEqual(result['ms']['sampleSd'],2)

    def test_pairing_uses_ids_and_preserves_losses(self):
        a=[self.row('a'),self.row('b',qualified=False),self.row('c')]
        b=[self.row('c',qualified=False),self.row('b'),self.row('a')]
        result=paired(a,b)
        self.assertEqual(result['bothQualified'],1)
        self.assertEqual(result['firstOnlyIds'],['c'])
        self.assertEqual(result['secondOnlyIds'],['b'])
        with self.assertRaises(ValueError): paired(a,b[:2])

    def test_claimed_success_with_bad_certificate_fails_analysis(self):
        row=self.row('a')
        row['diagnostics']['convergenceEvidence']['finalLinearBackwardError']=1e-8
        with self.assertRaises(ValueError): outcomes([row])


if __name__=='__main__': unittest.main()
