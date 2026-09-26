import copy
from pathlib import Path
import unittest

import checkpoint_selection as selection


class CheckpointSelectionTests(unittest.TestCase):
    def records(self):
        return {20260910: {'firstIds': ['a','b','c'], 'currentIds': ['a'], 'firstMeanMillis': 10.},
                20260911: {'firstIds': ['a','b'], 'currentIds': ['a'], 'firstMeanMillis': 10.},
                20260912: {'firstIds': ['a','b'], 'currentIds': ['a'], 'firstMeanMillis': 9.}}

    def test_strict_qualification_improvement_and_equal_latency_boundary(self):
        result = selection.choose(self.records())
        self.assertEqual(result['selectedSeed'], 20260910)
        self.assertTrue(result['decisions']['20260910']['eligible'])
        self.assertFalse(result['decisions']['20260912']['eligible'])
        values = self.records(); values[20260910]['firstMeanMillis'] = 10.00000001
        self.assertEqual(selection.choose(values)['selectedSeed'], selection.REFERENCE)

    def test_classical_union_includes_other_campaign_controls(self):
        values = self.records(); values[20260912]['currentIds'] = ['z']
        result = selection.choose(values)
        self.assertTrue(result['retainedReference'])
        self.assertEqual(result['decisions']['20260910']['missingClassicalIds'], ['z'])
        self.assertFalse(result['decisions']['20260911']['preservesClassicalUnion'])
        self.assertEqual(result['classicalControlDisagreements'], ['a','z'])

    def test_rank_by_count_then_unrounded_latency_then_seed(self):
        values = self.records(); values[20260912] = copy.deepcopy(values[20260910])
        self.assertEqual(selection.choose(values)['selectedSeed'], 20260910)
        values[20260912]['firstMeanMillis'] = 9.99999999
        self.assertEqual(selection.choose(values)['selectedSeed'], 20260912)
        values[20260910]['firstIds'].append('d')
        self.assertEqual(selection.choose(values)['selectedSeed'], 20260910)

    def test_invalid_or_incomplete_selection_is_rejected(self):
        values = self.records(); del values[20260912]
        with self.assertRaises(ValueError): selection.choose(values)
        for invalid in (float('nan'), float('inf'), -1., True):
            values = self.records(); values[20260910]['firstMeanMillis'] = invalid
            with self.subTest(invalid=invalid), self.assertRaises(ValueError): selection.choose(values)

    def population(self):
        source = [{'id':str(i), 'input':{'specifications':[], 'stageCount':i+2}} for i in range(12)]
        rows = [{**r, 'status':'BENCHMARKED', 'queueWaitMillis':0.,
                 **{m:{'success':False,'status':'NONCONVERGENCE','ms':10.,'cpuMillis':None,'allocatedBytes':None}
                    for m in selection.MODES}} for r in source]
        meta = {**selection.POLICY, 'complete':True, 'completed':12, 'caseCount':12,
                'strategies':list(selection.MODES), 'modelSha256':'model', 'sourceSha256':'source',
                'scheduling':{'submitted':12,'completed':12,'terminated':True,'maximumInFlight':10,
                              'maximumActive':10,'distinctWorkerThreads':10}}
        return rows, meta, source

    def test_completed_concurrent_order_is_not_input_order(self):
        rows, meta, source = self.population()
        selection.validate_run(list(reversed(rows)), meta, source, 'model', 'source')

    def test_incomplete_duplicate_or_changed_population_is_rejected(self):
        rows, meta, source = self.population()
        for invalid in (rows[:-1], rows[:-1]+[rows[0]]):
            with self.assertRaises(ValueError): selection.validate_run(invalid, meta, source, 'model', 'source')
        changed = copy.deepcopy(rows); changed[0]['input']['stageCount'] = 99
        with self.assertRaises(ValueError): selection.validate_run(changed, meta, source, 'model', 'source')

    def test_policy_hash_lifecycle_and_strategy_mismatches_are_rejected(self):
        rows, meta, source = self.population()
        for key, value in (('workers',1),('complete',False),('modelSha256','other'),('sourceSha256','other'),
                           ('deadlineMillis',60000),('neuralBudgetMillis',10000)):
            with self.subTest(key=key), self.assertRaises(ValueError):
                selection.validate_run(rows, {**meta,key:value}, source, 'model', 'source')
        changed = copy.deepcopy(meta); changed['scheduling']['terminated'] = False
        with self.assertRaises(ValueError): selection.validate_run(rows, changed, source, 'model', 'source')
        changed = copy.deepcopy(rows); del changed[0]['neuralFirst']
        with self.assertRaises(ValueError): selection.validate_run(changed, meta, source, 'model', 'source')

    def test_nonfinite_elapsed_is_rejected_but_missing_optional_counters_are_preserved(self):
        rows, meta, source = self.population()
        for invalid in (float('nan'), float('inf'), -1., None, True):
            changed = copy.deepcopy(rows); changed[0]['current']['ms'] = invalid
            with self.subTest(invalid=invalid), self.assertRaises(ValueError):
                selection.validate_run(changed, meta, source, 'model', 'source')
        selection.validate_run(rows, meta, source, 'model', 'source')

    def test_test_requires_frozen_selection_and_deduplicates_artifact(self):
        models = {str(s):{'sha256':str(s)} for s in selection.SEEDS}
        with self.assertRaises(ValueError): selection.test_gate(None, 'plan', 'test', models)
        frozen = {'validationComplete':True,'testUsed':False,'planSha256':'plan','testSha256':'test',
                  'decision':{'selectedSeed':20260910},'selectedModelSha256':'20260910'}
        self.assertEqual(selection.test_gate(frozen, 'plan', 'test', models), 20260910)
        for key, value in (('validationComplete',False),('testUsed',True),('planSha256','changed'),
                           ('testSha256','changed'),('selectedModelSha256','changed')):
            with self.subTest(key=key), self.assertRaises(ValueError):
                selection.test_gate({**frozen,key:value}, 'plan', 'test', models)
        self.assertEqual(selection.test_seeds(20260910, models), [20260911,20260910])
        self.assertEqual(selection.test_seeds(20260911, models), [20260911])
        models['20260910']['sha256'] = models['20260911']['sha256']
        self.assertEqual(selection.test_seeds(20260910, models), [20260911])

    def test_native_measurement_helpers_match_archived_serial_source(self):
        root = Path(__file__).parent
        old = (root/'V3GeneralTrainingProbe.java').read_text(encoding='utf-8')
        new = (root/'V3ConcurrentColumnEvaluationProbe.java').read_text(encoding='utf-8')
        marker = '    private static void compareRaw('
        self.assertEqual(old[old.index(marker):], new[new.index(marker):])

    def test_native_certification_is_bound_to_exact_training_inputs(self):
        fixtures = [{'id':str(i),'input':{'specifications':[],'stageCount':i+2}} for i in range(10)]
        report = {'allPassed':True,'numerical':{'passed':True,'distinctWorkerThreads':10,
            'maximumActiveSolverCalls':10,'executorTerminated':True,
            'cases':[{**r,'passed':True,'differingProfileValues':0} for r in fixtures]}}
        selection.validate_numerical_check(report,fixtures)
        altered = copy.deepcopy(report); altered['numerical']['cases'][0]['input']['stageCount'] = 99
        with self.assertRaises(ValueError): selection.validate_numerical_check(altered,fixtures)
        altered = copy.deepcopy(report); altered['numerical']['maximumActiveSolverCalls'] = 1
        with self.assertRaises(ValueError): selection.validate_numerical_check(altered,fixtures)


if __name__ == '__main__':
    unittest.main()
