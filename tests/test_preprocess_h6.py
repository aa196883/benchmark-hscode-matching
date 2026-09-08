import copy
import json
from pathlib import Path
import unittest
from scripts.preprocess_h6 import prepare

class PrepareTests(unittest.TestCase):
    def setUp(self):
        root = Path(__file__).resolve().parents[1]
        source = root / 'data/H6.json'
        if not source.exists():
            source = root / 'H6.json'
        self.payload = json.loads(source.read_bytes())

    def test_real_catalog(self):
        original = copy.deepcopy(self.payload)
        rows, excluded = prepare(self.payload)
        nodes = {row['code']: row for row in rows}
        self.assertEqual(self.payload, original)
        self.assertEqual(len(rows), 6939)
        self.assertEqual(sum(row['is_candidate'] for row in rows), 5612)
        self.assertEqual([row['source']['id'] for row in excluded], ['TOTAL'])
        self.assertIsNone(nodes['01']['parent_code'])
        self.assertEqual(nodes['010121']['ancestor_codes'], ['01', '0101'])
        self.assertTrue(nodes['010121']['contextual_description'].startswith('Animals; live > '))
        self.assertFalse(nodes['999999']['is_candidate'])

    def test_reject_corrupt_input(self):
        for field, value in (('parent', '02'), ('isLeaf', '0'), ('id', '10121')):
            with self.subTest(field=field):
                payload = copy.deepcopy(self.payload)
                row = next(row for row in payload['results'] if row['id'] == '010121')
                row[field] = value
                with self.assertRaises(ValueError):
                    prepare(payload)
        self.payload['results'].append(self.payload['results'][1])
        with self.assertRaises(ValueError):
            prepare(self.payload)
