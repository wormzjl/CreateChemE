"""Copied/synthetic fixtures exercise mandatory guards; solver launches are mocked."""
import copy
from contextlib import ExitStack
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import benchmark as b
import run_test as gate
import verify_study as v
import verify_results as final
import report as reports
import archive as seal
import native_checkpoint_selection_v1 as common
from prepare_transformer_data import digest

REAL_AREA=b.AREA


class GuardContracts(unittest.TestCase):
    def setUp(self):
        with (REAL_AREA/'validation/block-1/incumbent/evaluation.jsonl').open() as stream:
            original=json.loads(next(stream))
        meta=json.loads((REAL_AREA/'validation/block-1/incumbent/run.json').read_text())
        root=REAL_AREA/'guard-test-work';root.mkdir(exist_ok=True)
        self.temporary=tempfile.TemporaryDirectory(prefix='guard-',dir=root)
        self.root=Path(self.temporary.name).resolve()
        assert self.root.is_relative_to(root.resolve())
        self.stack=ExitStack();self.addCleanup(self.temporary.cleanup);self.addCleanup(self.stack.close)
        self.area=self.root/'study';self.area.mkdir()
        for module in (b,gate,v,final,reports,seal):
            self.stack.enter_context(patch.object(module,'ROOT',self.root))
            self.stack.enter_context(patch.object(module,'AREA',self.area))
        self.stack.enter_context(patch.object(common,'ROOT',self.root))
        self.stack.enter_context(patch.object(b,'PLAN',self.area/'benchmark-plan.json'))
        self.stack.enter_context(patch.object(final,'PLAN',b.PLAN))
        self.stack.enter_context(patch.object(seal,'CACHE',self.root/'cache'))
        source=self.root/'report-source.py';source.write_text('synthetic report source identity')
        self.stack.enter_context(patch.object(reports,'__file__',str(source)))
        self.stack.enter_context(patch.object(reports,'training_summary',return_value={'fits':{}}))
        self.write(self.root/'tools/neural/salvage_verification.json',{'syntheticFixture':True})
        inputs={}
        for split in ('validation','test'):
            row={'id':'fixture-'+split,'input':original['input'],'split':split}
            path=self.area/(split+'.jsonl');self.rows(path,[row]);inputs[split]=common.info(path)
        for name in ('warmup','training'):
            path=self.area/(name+'.json');self.write(path,{});inputs[name]=common.info(path)
        models={}
        for name in b.ORDER:
            weight=self.area/'models'/name/'weights.json';self.write(weight,{'fixture':name})
            pipeline=self.area/'models'/name/'pipeline.json';self.write(pipeline,{'fixture':name,'weights':common.info(weight)})
            models[name]={**common.info(pipeline),'weights':common.info(weight)}
        plan=dict(orderByBlock=[b.ORDER,list(reversed(b.ORDER))],blocks=2,models=models,sources=[],checks=[],**inputs)
        self.write(b.PLAN,plan)
        for split in ('validation','test'):
            for block in (1,2):
                for name in b.ORDER:
                    row=copy.deepcopy(original);row['id']='fixture-'+split;row['split']=split
                    for mode in b.old_policy.MODES:
                        row[mode]=copy.deepcopy(original['neuralFirst'])
                        elapsed=12. if name=='incumbent' else 11. if name=='incumbent-wrapper' else 8.+int(name.rsplit('-',1)[1])-20260910
                        row[mode]['ms']=elapsed;row[mode]['cold_ms']=elapsed
                    runmeta=copy.deepcopy(meta)
                    runmeta.update(caseCount=1,completed=1,complete=True,sourceSha256=inputs[split]['sha256'],
                        modelSha256=models[name]['sha256'],warmupSha256=inputs['warmup']['sha256'],
                        scheduling=dict(submitted=1,completed=1,maximumInFlight=1,maximumActive=1,distinctWorkerThreads=1,terminated=True))
                    folder=self.area/split/f'block-{block}'/name
                    self.rows(folder/'evaluation.jsonl',[row]);self.write(folder/'run.json',runmeta)
        b.select()
        # Synthetic test journals are stored elsewhere until the mocked launch.
        self.test_dir=self.area/'test';self.test_stash=self.area/'test-fixtures'
        assert self.test_dir.resolve().is_relative_to(self.root) and self.test_stash.resolve().is_relative_to(self.root)
        self.test_dir.rename(self.test_stash)
        self.write(self.area/'test-gate-registration.json',dict(sources=[],benchmarkPlan=common.info(b.PLAN)))
        self.launch=self.stack.enter_context(patch.object(b,'run'))

    @staticmethod
    def write(path,value):
        path.parent.mkdir(parents=True,exist_ok=True);path.write_text(json.dumps(value,sort_keys=True)+'\n')

    @staticmethod
    def rows(path,rows):
        path.parent.mkdir(parents=True,exist_ok=True);path.write_text(''.join(json.dumps(r,sort_keys=True)+'\n' for r in rows))

    def rejected(self):
        with self.assertRaises((AssertionError,ValueError,KeyError)):gate.main()
        self.launch.assert_not_called()
        self.assertFalse((self.area/'test-execution-lock.json').exists())

    def test_valid_selection_launches_only_after_lock(self):
        def launched(split):
            self.assertEqual(split,'test');self.assertTrue((self.area/'test-execution-lock.json').exists())
        self.launch.side_effect=launched;gate.main();self.launch.assert_called_once_with('test')

    def test_altered_validation_timing_blocks_test(self):
        path=self.area/'validation/block-1/N-20260910/evaluation.jsonl';rows=b.read_rows(path)
        rows[0]['neuralFirst']['ms']+=1;self.rows(path,rows);self.rejected()

    def test_replaced_representative_blocks_test(self):
        path=self.area/'selection.json';selection=json.loads(path.read_text())
        selection['representatives']['N']='N-20260912';self.write(path,selection);self.rejected()

    def test_missing_strategy_blocks_even_if_file_hash_is_updated(self):
        path=self.area/'validation/block-1/N-20260910/evaluation.jsonl';rows=b.read_rows(path)
        del rows[0]['neural'];self.rows(path,rows)
        selection=json.loads((self.area/'selection.json').read_text())
        for item in selection['validationFiles']:
            if item['path']==path.relative_to(self.root).as_posix():item['sha256']=digest(path)
        self.write(self.area/'selection.json',selection);self.rejected()

    def test_substituted_pipeline_blocks_test(self):
        path=self.area/'models/N-20260910/pipeline.json';path.write_text('{}');self.rejected()

    def test_post_report_test_journal_change_blocks_archive(self):
        gate.main()
        assert self.test_stash.resolve().is_relative_to(self.root) and self.test_dir.resolve().is_relative_to(self.root)
        self.test_stash.rename(self.test_dir)
        summary,cases,text=reports.build_outputs()
        self.write(self.area/'summary.json',summary);self.rows(self.area/'case-map.jsonl',cases)
        (self.area/'report.md').write_text(text,encoding='utf-8')
        path=self.area/'test/block-1/incumbent/evaluation.jsonl';rows=b.read_rows(path)
        rows[0]['neuralFirst']['ms']+=1;rows[0]['neuralFirst']['cold_ms']+=1;self.rows(path,rows)
        with self.assertRaises(ValueError):seal.make('results')
        self.assertFalse((self.root/'cache/results.zip').exists())


if __name__=='__main__':unittest.main()
