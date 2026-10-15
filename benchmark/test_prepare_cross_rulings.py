import json
from pathlib import Path
import tempfile
import unittest

from benchmark.datasets import read_csv
from benchmark.prepare_cross_rulings import extract, prepare
from hs_matching.catalog import Catalog


def entry(labels='0101.21.0000', description='live horses'):
    return {'messages': [
        {'role': 'user', 'content': f'What is the HTS US Code for {description}?'},
        {'role': 'assistant', 'content': f'HTS US Code -> {labels}\nReasoning -> 9999.99.9999'},
    ]}


class CrossRulingsTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.catalog_path = self.root / 'catalog.jsonl'
        self.catalog_path.write_text(json.dumps({
            'code': '010121', 'description': 'Horses', 'edition': '2022',
            'language': 'en', 'level': 6, 'is_candidate': True,
        }))
        self.catalog = Catalog(self.catalog_path)

    def test_codes_and_ambiguity(self):
        for labels in ('0101.21.0000', '0101210000', '0101.21.0000; 0101.21.1000'):
            self.assertEqual(extract(entry(labels), self.catalog), (('010121', 'live horses'), None))
        for labels, reason in [('0101.21.0000; 0101.29.0000', 'multiple_hs6'),
                               ('9999.99.9999', 'unknown_code'),
                               ('0101.21.00000', 'unsupported_code_header'),
                               ('0101.21.0000 or unknown', 'unsupported_code_header')]:
            self.assertEqual(extract(entry(labels), self.catalog), (None, reason))
        self.assertEqual(extract(entry(description='horses 0101.21.0000'), self.catalog)[1],
                         'possible_label_leakage')
        self.assertEqual(extract({}, self.catalog)[1], 'invalid_messages')

    def test_csv_order_duplicates_and_report(self):
        source = self.root / 'test.jsonl'
        source.write_text('\n'.join(json.dumps(e) for e in
                                   [entry(), entry('9999.99.9999'), entry()]))
        output = self.root / 'data.csv'
        counts = prepare(source, output, self.catalog_path)
        self.assertEqual(counts['accepted'], 2)
        rows = read_csv(output.read_bytes())
        self.assertEqual([r['hs_code'] for r in rows], ['010121', '010121'])
        report = json.loads(output.with_suffix('.report.json').read_text())
        self.assertEqual([r['source_line'] for r in report['rows']], [1, 3])
        with self.assertRaises(ValueError):
            prepare(source, output, self.catalog_path)


if __name__ == '__main__':
    unittest.main()
