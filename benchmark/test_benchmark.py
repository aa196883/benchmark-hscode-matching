"""Tests de collecte hors réseau : python -m unittest discover -s benchmark -t .."""
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch

from benchmark.benchmark import Trace, main, read_dataset
from hs_matching.approaches.base import Prediction


class BenchmarkTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.dataset = self.root / 'tiny.csv'
        self.dataset.write_text('HS code;description\n010121;Live horses\n010129;Other horses\n', encoding='utf-8')

    def test_csv_formats_and_leading_zeroes(self):
        self.assertEqual(read_dataset(self.dataset)[0], ('010121', 'Live horses'))
        self.dataset.write_text('\ufeff010121,"Horses, live; breeding\nanimals"\n', encoding='utf-8')
        self.assertEqual(read_dataset(self.dataset), [('010121', 'Horses, live; breeding\nanimals')])

    def test_invalid_datasets_before_inference(self):
        for text in ('', 'HS code;description\n', '10121;Horse\n', '010121;\n', '010121;Horse;extra\n', 'garbage;description\n'):
            with self.subTest(text=text):
                self.dataset.write_text(text)
                with patch('benchmark.benchmark.build_approach') as build:
                    self.assertEqual(main(['--datasets', str(self.dataset), '--approach', 'llm_direct',
                                           '--runs-dir', str(self.root / 'runs')]), 2)
                    build.assert_not_called()
                self.assertFalse((self.root / 'runs').exists())

    def test_trace_readable_after_each_result(self):
        path = self.root / 'trace.json'
        trace = Trace(path, {'model': '', 'approach': 'embeddings'})
        self.addCleanup(trace.close)
        self.assertEqual(json.loads(path.read_text())['results'], [])
        for answer in ('é🐴', {'hits': [1, 2]}, ['010121']):
            trace.append({'answer': answer})
            self.assertEqual(json.loads(path.read_text())['results'][-1]['answer'], answer)

    def test_all_approaches_keep_complete_answers_and_continue_after_error(self):
        for name in ('llm_direct', 'embeddings', 'rag'):
            with self.subTest(approach=name):
                runs = self.root / name
                raw = {'output': 'unparsed response', 'extra': [1, 2]}
                prediction = Prediction(status='abstained', metadata={'raw_response': raw})
                approach = Mock()
                approach.predict.side_effect = [RuntimeError('secret'), prediction]
                def build(args):
                    self.assertEqual(json.loads(next(runs.glob('*.json')).read_text())['results'], [])
                    return approach, None
                with patch('benchmark.benchmark.build_approach', side_effect=build):
                    status = main(['--datasets', str(self.dataset), '--approach', name, '--runs-dir', str(runs)])
                self.assertEqual(status, 1)
                run = json.loads(next(runs.glob('*.json')).read_text())
                self.assertEqual(len(run['results']), 2)
                self.assertEqual(run['results'][0]['ground_truth'], '010121')
                self.assertEqual(run['results'][0]['answer']['status'], 'error')
                self.assertEqual(run['results'][1]['answer'], prediction.to_dict())
                self.assertGreaterEqual(run['results'][1]['response_time'], 0)
                if name == 'embeddings':
                    self.assertEqual(run['model'], '')

    def test_multiple_datasets_and_interruption(self):
        approach = Mock()
        approach.predict.side_effect = [Prediction(status='abstained'), KeyboardInterrupt()]
        runs = self.root / 'runs'
        with patch('benchmark.benchmark.build_approach', return_value=(approach, None)):
            result = main(['--datasets', str(self.dataset), str(self.dataset), '--approach', 'llm_direct',
                           '--model', 'qwen3', '--runs-dir', str(runs)])
        self.assertEqual(result, 130)
        documents = [json.loads(path.read_text()) for path in runs.glob('*.json')]
        self.assertEqual(len(documents), 2)
        self.assertEqual(sorted(len(doc['results']) for doc in documents), [0, 1])
        self.assertTrue(all(doc['model'] == 'qwen3' for doc in documents))


if __name__ == '__main__':
    unittest.main()
