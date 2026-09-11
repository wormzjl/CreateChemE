import unittest
from unittest.mock import patch

import native_checkpoint_selection_v2 as driver
import test_checkpoint_selection_provenance as prior


class FrozenEvidenceV2Tests(prior.FrozenEvidenceTests):
    def setUp(self):
        replacement = patch.object(prior, 'driver', driver)
        replacement.start()
        self.addCleanup(replacement.stop)
        super().setUp()


class FixtureAmendmentTests(unittest.TestCase):
    def test_original_classical_training_only(self):
        rows = [{'id':str(i),'split':'train', 'input':{'specifications':[],'stageCount':2,'variant':i},
                 'labelProvenance':{'eligibleForFitting':True}} for i in range(12)]
        original = [dict(r, accepted=(i < 10)) for i,r in enumerate(rows)]
        with patch.object(driver,'strict',side_effect=lambda r:r['accepted']):
            result = driver.corrected_fixtures(rows,original)
            self.assertEqual({r['id'] for r in result},{str(i) for i in range(10)})
            original[0]['split'] = 'validation'
            with self.assertRaises(ValueError): driver.corrected_fixtures(rows,original)


if __name__ == '__main__':
    unittest.main()
