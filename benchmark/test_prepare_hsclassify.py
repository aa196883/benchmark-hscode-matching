import csv
import json
from pathlib import Path
import tempfile
import unittest

from benchmark.datasets import read_csv
from benchmark.prepare_hsclassify import prepare


class HSClassifyTests(unittest.TestCase):
    def test_all_english_rows_order_duplicates_unknowns_and_zeroes(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            catalog = root / 'catalog.jsonl'
            catalog.write_text(json.dumps({'code': '020130', 'description': 'Beef',
                'edition': '2022', 'language': 'en', 'level': 6, 'is_candidate': True}))
            source = root / 'source.csv'
            description = 'beef; fresh,\nboneless'
            with source.open('w', newline='') as stream:
                writer = csv.writer(stream)
                writer.writerow(['text', 'expected_hs_code', 'category', 'language', 'notes'])
                writer.writerows([(description, '020130', 'easy', 'en', 'note'),
                                  ('boeuf', '020130', 'multilingual', 'fr', ''),
                                  ('old label', '999999', 'known_failure', 'en', ''),
                                  (description, '020130', 'edge_case', 'en', '')])
            output = root / 'data.csv'
            report = prepare(source, output, catalog)
            rows = read_csv(output.read_bytes())
            self.assertEqual([r['hs_code'] for r in rows], ['020130', '020130'])
            self.assertEqual(rows[0]['description'], description)
            self.assertEqual([r['source_record'] for r in report['rows']], [1, 4])
            self.assertEqual(report['exported_rows'], 2)
            self.assertEqual(report['rejections'], [
                {'source_record': 3, 'code': '999999', 'reason': 'unknown_code'}])
            with self.assertRaises(ValueError):
                prepare(source, output, catalog)
            source.write_text('text,expected_hs_code,category,language,notes\nbeef,20130,easy,en,\n')
            with self.assertRaises(ValueError):
                prepare(source, root / 'invalid.csv', catalog)
            self.assertFalse((root / 'invalid.csv').exists())


if __name__ == '__main__':
    unittest.main()
