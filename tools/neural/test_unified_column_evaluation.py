"""Reject subset/mixed-budget results from the unified test report."""
import copy
import unittest
from unified_column_evaluation import MODES, POLICY, PLAN, ROOT, read, validate_run, verify_plan
from prepare_transformer_data import read_rows


class UnifiedEvaluationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if not PLAN.exists():
            raise unittest.SkipTest('Prepare unified evaluation first')
        cls.plan = read(PLAN)
        cls.source = read_rows(ROOT / cls.plan['sets']['test']['path'])

    def inputs(self):
        rows = [{**r, **{mode: {'success': False} for mode in MODES}} for r in self.source]
        meta = {**POLICY, 'completed': 252, 'modelSha256': 'model', 'sourceSha256': 'source'}
        return rows, meta

    def test_plan_has_only_validation_and_full_test(self):
        plan = verify_plan()
        self.assertEqual(set(plan['sets']), {'validation', 'test'})
        self.assertEqual(plan['sets']['validation']['cases'], 405)
        self.assertEqual(plan['sets']['test']['cases'], 252)
        rows, meta = self.inputs()
        validate_run(rows, meta, self.source, 'model', 'source')

    def test_old_64_case_subset_cannot_be_relabelled_as_full_test(self):
        rows, meta = self.inputs()
        with self.assertRaises(ValueError):
            validate_run(rows[:64], meta, self.source, 'model', 'source')

    def test_budget_and_concurrency_mismatch_rejected(self):
        rows, meta = self.inputs()
        for field, value in (('workers', 10), ('neuralBudgetMillis', 10000), ('deadlineMillis', 60000)):
            with self.subTest(field=field), self.assertRaises(ValueError):
                validate_run(rows, {**meta, field: value}, self.source, 'model', 'source')

    def test_duplicate_missing_strategy_and_changed_hash_rejected(self):
        rows, meta = self.inputs()
        with self.assertRaises(ValueError):
            validate_run(rows[:-1] + [rows[0]], meta, self.source, 'model', 'source')
        incomplete = copy.deepcopy(rows)
        del incomplete[0]['neuralFirst']
        with self.assertRaises(ValueError):
            validate_run(incomplete, meta, self.source, 'model', 'source')
        with self.assertRaises(ValueError):
            validate_run(rows, {**meta, 'modelSha256': 'changed'}, self.source, 'model', 'source')


if __name__ == '__main__':
    unittest.main()
