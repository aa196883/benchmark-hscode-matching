import csv
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from traiter import main, ROOT
from hs_matching.catalog import Catalog


class DescriptionTests(unittest.TestCase):
    def test_existing_codes_replace_descriptions_without_api(self):
        for field in ('marchandise', 'description marchandise'):
            with self.subTest(field=field), tempfile.TemporaryDirectory() as tmp:
                source = Path(tmp) / 'data.csv'
                source.write_text(f'HS code,{field},entreprise\n280920,Phosphoric acid,Example\n010121,Horse,Other\n')
                with patch('traiter.load_env', side_effect=AssertionError('No environment needed')), patch('traiter.EmbeddingIndex', side_effect=AssertionError('No index needed')), patch('traiter.run_prediction', side_effect=AssertionError('No API allowed')):
                    main(['--input', str(source), '--descriptions-only'])
                    first = source.read_bytes()
                    main(['--input', str(source), '--descriptions-only'])
                self.assertEqual(first, source.read_bytes())
                with source.open(newline='') as stream:
                    rows = list(csv.DictReader(stream))
                labels = Catalog(ROOT / 'data/processed/h6_2022/candidates.jsonl')
                self.assertEqual(rows[0][field], 'Phosphoric acid and polyphosphoric acids')
                self.assertEqual(rows[1][field], labels.rows['010121']['description'])
                self.assertEqual([r['HS code'] for r in rows], ['280920', '010121'])
                self.assertEqual([r['entreprise'] for r in rows], ['Example', 'Other'])

    def test_invalid_code_leaves_files_untouched(self):
        for code in ('', '12345', '000000'):
            with self.subTest(code=code), tempfile.TemporaryDirectory() as tmp:
                source, output = Path(tmp) / 'source.csv', Path(tmp) / 'output.csv'
                original = f'HS code,marchandise\n280920,Phosphoric acid\n{code},Other\n'
                source.write_text(original)
                output.write_text('existing output')
                with self.assertRaises(ValueError):
                    main(['--input', str(source), '--output', str(output), '--descriptions-only'])
                self.assertEqual(source.read_text(), original)
                self.assertEqual(output.read_text(), 'existing output')


if __name__ == '__main__':
    unittest.main()
