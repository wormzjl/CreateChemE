import contextlib
import copy
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import checkpoint_selection as policy
import historical_neural_inputs as historical
import native_checkpoint_selection_v1 as driver
from prepare_generalized_evaluation import canonical_input_hash
from prepare_transformer_data import digest


def write(path, value, rows=False):
    path.parent.mkdir(parents=True,exist_ok=True)
    path.write_text(''.join(json.dumps(r)+'\n' for r in value) if rows else json.dumps(value)+'\n',encoding='utf-8')


class HistoricalInputTests(unittest.TestCase):
    def fresh(self):
        return [{'id':f'{n}-{steam}-{rep}','split':'test','design':{},
                 'input':{'stageCount':n,'specifications':[],'steamFeeds':[{'stageNumber':1}] if steam else [],'variant':rep}}
                for n in range(2,65) for steam in (False,True) for rep in range(2)]

    def test_missing_required_historical_pool_fails_closed(self):
        with tempfile.TemporaryDirectory(prefix='checkpoint-history-') as directory:
            missing = Path(directory)/'unselected-historical-pool.jsonl'
            with self.assertRaises(FileNotFoundError): historical.read_required_population(missing,'missing')

    def test_unselected_historical_input_also_blocks_new_pool(self):
        unselected = {'input':{'specifications':[],'stageCount':38,'variant':'unselected'}}
        with self.assertRaises(ValueError):
            historical.new_pool([unselected],{canonical_input_hash(unselected['input'])})

    def test_fresh_population_strata_uniqueness_and_no_teacher_fields(self):
        rows = self.fresh(); historical.fresh_test(rows,set())
        variants = [rows[:-1],rows[:-1]+[rows[0]]]
        wrong = copy.deepcopy(rows); wrong[0]['input']['stageCount'] = 3; variants.append(wrong)
        teacher = copy.deepcopy(rows); teacher[0]['seed'] = {}; variants.append(teacher)
        duplicate_input = copy.deepcopy(rows); duplicate_input[1]['input'] = duplicate_input[0]['input']; variants.append(duplicate_input)
        for value in variants:
            with self.assertRaises(ValueError): historical.fresh_test(value,set())


class FrozenEvidenceTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix='checkpoint-evidence-')
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name).resolve()
        self.area, self.accuracy, self.plan_path = self.root/'selection',self.root/'accuracy',self.root/'plan.json'
        self.context = contextlib.ExitStack(); self.addCleanup(self.context.close)
        for name, value in (('ROOT',self.root),('AREA',self.area),('ACCURACY',self.accuracy),('PLAN',self.plan_path)):
            self.context.enter_context(patch.object(driver,name,value))
        self.context.enter_context(patch.object(driver,'execution_lock'))
        self.gradle = self.context.enter_context(patch.object(driver,'gradle'))
        self.context.enter_context(contextlib.redirect_stdout(io.StringIO()))
        self.source = [{'id':str(i),'input':{'specifications':[],'stageCount':i+2}} for i in range(12)]
        self.rows = [{**r,'status':'BENCHMARKED','queueWaitMillis':0.,
                     **{m:{'success':False,'status':'NONCONVERGENCE','ms':10.,'cpuMillis':None,'allocatedBytes':None}
                        for m in policy.MODES}} for r in self.source]
        source_path, test_path = self.root/'validation-inputs.jsonl',self.root/'test-inputs.jsonl'
        write(source_path,self.source,True); write(test_path,self.source,True)
        artifacts, models = {}, {}
        for seed in policy.SEEDS:
            cp = self.accuracy/'fits'/f'baseline-{seed}'/'model.pt'; cp.parent.mkdir(parents=True)
            cp.write_bytes(str(seed).encode())
            fit = cp.with_name('report.json'); write(fit,{'validation':{}})
            directory = self.area/'models'/str(seed)
            doc = {'checkpointSha256':digest(cp),'modelId':str(seed)}
            if seed == policy.REFERENCE: write(self.accuracy/'native/model.json',doc)
            write(directory/'model.json',doc); write(directory/'fixture.json',[])
            models[str(seed)] = {**driver.info(directory/'model.json'),'checkpoint':driver.info(cp),
                                'fitReport':driver.info(fit),'fixture':driver.info(directory/'fixture.json')}
            artifacts[cp.relative_to(self.root).as_posix()] = digest(cp)
        artifacts[(self.accuracy/'native/model.json').relative_to(self.root).as_posix()] = digest(self.accuracy/'native/model.json')
        self.plan = {'registered':{'validation':driver.info(source_path),'test':driver.info(test_path)},'artifacts':artifacts}
        write(self.plan_path,self.plan)
        write(self.area/'models.json',{'planSha256':digest(self.plan_path),'models':models})
        write(self.area/'execution-lock.json',{'testFixture':True})
        self.context.enter_context(patch.object(driver,'verify_plan',return_value=self.plan))
        for split, seeds in (('validation',policy.SEEDS),('test',(policy.REFERENCE,))):
            for seed in seeds:
                directory = self.area/split/str(seed)
                write(directory/'evaluation.jsonl',self.rows,True)
                write(directory/'run.json',{**policy.POLICY,'complete':True,'completed':12,'caseCount':12,
                    'strategies':list(policy.MODES),'modelSha256':models[str(seed)]['sha256'],
                    'sourceSha256':self.plan['registered'][split]['sha256'],'java':'21','maximumHeapBytes':4294967296,
                    'availableProcessors':16,'scheduling':{'submitted':12,'completed':12,'terminated':True,
                        'maximumInFlight':10,'maximumActive':10,'distinctWorkerThreads':10}})
        driver.select()

    def change_journal(self, split, seed, remove=False):
        path = self.area/split/str(seed)/'evaluation.jsonl'
        rows = driver.read_rows(path)
        if remove: del rows[0]['neuralFirst']
        else: rows[0]['neuralFirst']['ms'] += .125
        write(path,rows,True)

    def test_complete_report_matches_current_dependency_chain(self):
        driver.report()
        self.assertTrue(driver.verify_report()['sameArtifact'])

    def test_validation_journal_change_after_selection_blocks_testing(self):
        self.change_journal('validation',20260910)
        with self.assertRaises(ValueError): driver.test()
        self.gradle.assert_not_called()

    def test_test_journal_change_after_reporting_blocks_sealing(self):
        driver.report(); self.change_journal('test',policy.REFERENCE)
        with self.assertRaises(ValueError): driver.seal()
        self.assertFalse((self.root/'.neural-cache/checkpoint-selection-v1/study.zip').exists())

    def test_missing_strategy_blocks_sealing(self):
        driver.report(); self.change_journal('test',policy.REFERENCE,remove=True)
        with self.assertRaises(ValueError): driver.seal()

    def test_substituted_model_blocks_testing(self):
        path = self.area/'models'/str(policy.REFERENCE)/'model.json'
        doc = driver.read(path); doc['modelId'] = 'substituted'; write(path,doc)
        with self.assertRaises(ValueError): driver.test()
        self.gradle.assert_not_called()

    def test_changed_report_text_blocks_sealing(self):
        driver.report()
        with (self.area/'report.md').open('a',encoding='utf-8') as stream: stream.write('altered')
        with self.assertRaises(ValueError): driver.seal()


if __name__ == '__main__':
    unittest.main()
