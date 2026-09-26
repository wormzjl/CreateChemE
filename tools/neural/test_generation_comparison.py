import contextlib
import copy
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import checkpoint_selection as policy
import generation_comparison as comparison
import native_generation_comparison as driver
import unified_column_evaluation as old_analysis


def rows(first_ids, current_ids=(0,), neural_ids=()):
    result=[]
    for i in range(4):
        row={'id':str(i),'input':{'specifications':[],'stageCount':2+i,'steamFeeds':[]},
             'queueWaitMillis':0,'rawPrediction':{'supported':i!=3}}
        for mode,ids in (('current',current_ids),('neuralFirst',first_ids),('neural',neural_ids)):
            row[mode]={'success':i in ids,'status':'ACCEPTED' if i in ids else 'NONCONVERGENCE',
                'strictFixture':i in ids,'ms':float(i+1),'cpuMillis':None,'allocatedBytes':None}
        result.append(row)
    return result


class ComparisonTests(unittest.TestCase):
    def setUp(self):
        context=contextlib.ExitStack(); self.addCleanup(context.close)
        for module in (comparison,policy,old_analysis):
            context.enter_context(patch.object(module,'strict',side_effect=lambda row:row.get('strictFixture') is True))
        self.models={'gen2':rows((0,1)), 'gen3':rows((0,2)), 'transformer':rows((0,1,3))}

    def test_paired_ids_and_diagnostic_union_are_not_a_cascade(self):
        result=comparison.compare(self.models)
        cross=result['pairedAgainstTransformer']['gen3']['neuralFirst']
        self.assertEqual(cross['gainedIds'],['2']); self.assertEqual(cross['lostIds'],['1','3'])
        union=result['complementarity']['neuralFirst']
        self.assertEqual(union['separateRunUnionQualified'],4)
        self.assertEqual(union['extraBeyondTransformerIds'],['2'])
        self.assertFalse(union['measuredCascade'])

    def test_completion_order_does_not_change_matched_results(self):
        before=comparison.compare(self.models)
        self.models['gen2'].reverse(); self.models['gen3']=self.models['gen3'][1:]+self.models['gen3'][:1]
        after=comparison.compare(self.models)
        self.assertEqual(before,after)

    def test_missing_generation_duplicate_and_changed_input_fail(self):
        variants=[{k:v for k,v in self.models.items() if k!='gen2'},copy.deepcopy(self.models),copy.deepcopy(self.models)]
        variants[1]['gen2'][1]=copy.deepcopy(variants[1]['gen2'][0])
        variants[2]['gen2'][0]['input']['stageCount']=4
        for variant in variants:
            with self.assertRaises(ValueError): comparison.compare(variant)

    def test_classical_disagreement_and_unknown_prediction_reason_are_explicit(self):
        self.models['gen2']=rows((0,1),current_ids=(1,))
        result=comparison.compare(self.models)
        self.assertEqual(result['classicalControlDisagreements'],['0','1'])
        d=result['models']['gen2']['diagnostics']
        self.assertEqual(d['rawUnavailable'],1)
        self.assertEqual(d['predictionUnavailableReasons'],{'unavailable/unspecified':1})

    def test_profiles_use_common_inputs_and_preserve_population_denominators(self):
        for label,values in self.models.items():
            for row in values[:(2 if label=='gen2' else 3)]:
                row['rawVsTeacher']={field:float(int(row['id'])+1) for field in comparison.PROFILE_FIELDS}
        result=comparison.compare(self.models,{'0','1','2'})
        self.assertEqual(result['commonTeacherProfileErrors']['ids'],['0','1'])
        self.assertEqual(result['commonTeacherProfileErrors']['models']['gen3']['temperatureRmseKelvin']['count'],2)
        self.assertEqual(result['models']['gen3']['strategies']['current']['cases'],4)
        self.assertEqual(sum(r['cases'] for k,r in result['strata'].items() if k.startswith('stages/')),4)
        self.assertEqual(len(list(comparison.case_map('test',self.models))),4)

    def test_uncertified_teacher_metrics_are_excluded_and_unavailability_has_strata(self):
        for values in self.models.values():
            for row in values:
                row['rawVsTeacher']={field:1.0 for field in comparison.PROFILE_FIELDS}
        result=comparison.compare(self.models,{'0'})
        self.assertEqual(result['commonTeacherProfileErrors']['ids'],['0'])
        self.assertEqual(result['commonTeacherProfileErrors']['certifiedReferences'],1)
        self.assertEqual(result['strata']['stageCount/5']['models']['gen2']['rawSupported'],0)

    def test_check_requires_supported_inference_inside_the_barrier(self):
        r={'passed':True,'modelSha256':'model','fixturesSha256':'fixture','trainingFixtures':10,'supportedFixtures':2,
            'parallelPredictions':40,'supportedParallelPredictions':40,'synchronizedSupportedPredictions':10,
            'barrierInsidePrediction':True,'serialParallelBitIdentical':True,'cancellationPassed':True,
            'subsequentPredictionsUnchanged':True,'scheduling':{'submitted':40,'completed':40,'maximumActive':10,
                'maximumInFlight':10,'distinctWorkerThreads':10,'terminated':True}}
        comparison.check_prediction_evidence(r,'model','fixture')
        for key,value in (('supportedParallelPredictions',1),('barrierInsidePrediction',False),
                          ('synchronizedSupportedPredictions',9),('modelSha256','wrong')):
            wrong=copy.deepcopy(r); wrong[key]=value
            with self.assertRaises(ValueError): comparison.check_prediction_evidence(wrong,'model','fixture')


class FrozenFilesTests(unittest.TestCase):
    def setUp(self):
        directory=tempfile.TemporaryDirectory(prefix='generation-comparison-'); self.addCleanup(directory.cleanup)
        self.root=Path(directory.name); self.area=self.root/'output'; self.area.mkdir()
        context=contextlib.ExitStack(); self.addCleanup(context.close)
        for key,value in (('ROOT',self.root),('AREA',self.area),('PLAN',self.root/'plan.json')):
            context.enter_context(patch.object(driver,key,value))
        self.command=context.enter_context(patch.object(driver,'command'))
        self.source=self.root/'frozen.txt'; self.source.write_text('original',encoding='utf-8')
        entry=driver.info(self.source)
        self.plan={'revision':driver.REVISION,'policy':policy.POLICY,'models':{k:entry for k in comparison.LABELS},
            'sets':{},'newCampaignOrder':[list(x) for x in driver.ORDER],'trainingAllowed':False,
            'selectionAllowed':False,'testAlreadyExposed':True,'frozenEvidence':[entry],'dependencies':[],
            'sources':{'frozen.txt':entry['sha256']}}
        driver.freeze(driver.PLAN,self.plan)

    def test_changed_frozen_model_or_reference_blocks_execution(self):
        driver.verify_plan(); self.source.write_text('changed',encoding='utf-8')
        with self.assertRaises(ValueError): driver.run()
        self.command.assert_not_called()

    def test_changed_results_block_sealing_before_archive_creation(self):
        expected={'test':'expected'}
        driver.freeze(self.area/'summary.json',{'test':'altered'})
        with patch.object(driver,'results',return_value=(expected,[])):
            with self.assertRaises(ValueError): driver.seal()
        self.assertFalse((self.root/'.neural-cache').exists())


if __name__=='__main__': unittest.main()
