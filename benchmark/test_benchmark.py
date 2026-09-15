"""Tests de collecte hors réseau : python -m unittest discover -s benchmark -t .."""
import json
import io
from contextlib import redirect_stderr
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch

from benchmark.benchmark import Trace, main, read_dataset, token_counts
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

    def test_all_approaches_omit_metadata_and_continue_after_error(self):
        for name in ('llm_direct', 'embeddings', 'rag'):
            with self.subTest(approach=name):
                runs = self.root / name
                raw = {'output': 'unparsed response', 'extra': [1, 2]}
                prediction = Prediction(status='abstained', metadata={'raw_response': raw, 'usage': {'prompt_tokens': 42, 'completion_tokens': 7},
                                                                          'retrieval': {'usage': {'prompt_tokens': 3}}})
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
                self.assertIsNone(run['results'][0]['input_tokens'])
                self.assertEqual(run['results'][1]['input_tokens'], 45 if name == 'rag' else 42)
                self.assertEqual(run['results'][1]['output_tokens'], 0 if name == 'embeddings' else 7)
                expected = prediction.to_dict()
                expected.pop('metadata')
                self.assertEqual(run['results'][1]['answer'], expected)
                self.assertTrue(all('metadata' not in row['answer'] for row in run['results']))
                self.assertNotIn('raw_response', run['results'][1]['answer'])
                self.assertEqual(prediction.metadata['raw_response'], raw)
                self.assertGreaterEqual(run['results'][1]['response_time'], 0)
                if name == 'embeddings':
                    self.assertEqual(run['model'], '')

    def test_token_counts(self):
        for usage in ({'input_tokens': 12, 'output_tokens': 4},
                      {'prompt_tokens': 12, 'completion_tokens': 4}):
            self.assertEqual(token_counts({'usage': usage}, 'llm_direct'),
                             {'input_tokens': 12, 'output_tokens': 4})
        for usage in (None, {}, {'input_tokens': -1, 'output_tokens': True}):
            self.assertEqual(token_counts({'usage': usage}, 'llm_direct'),
                             {'input_tokens': None, 'output_tokens': None})
        self.assertEqual(token_counts({'usage': {'input_tokens': 0, 'output_tokens': 0}}, 'llm_direct'),
                         {'input_tokens': 0, 'output_tokens': 0})
        self.assertEqual(token_counts({'usage': {'input_tokens': 12, 'output_tokens': 4}}, 'rag'),
                         {'input_tokens': None, 'output_tokens': 4})
        self.assertEqual(token_counts({'usage': {'prompt_tokens': 3}}, 'embeddings'),
                         {'input_tokens': 3, 'output_tokens': 0})

    def test_progress_in_terminal_and_script_logs(self):
        for terminal in (False, True):
            with self.subTest(terminal=terminal):
                output = io.StringIO()
                output.isatty = lambda: terminal
                approach = Mock()
                approach.predict.return_value = Prediction(status='abstained')
                with redirect_stderr(output), patch('benchmark.benchmark.build_approach', return_value=(approach, None)):
                    result = main(['--datasets', str(self.dataset), '--approach', 'rag', '--model', 'qwen3',
                                   '--runs-dir', str(self.root / 'runs')])
                self.assertEqual(result, 0)
                text = output.getvalue()
                for count in ('0/2', '1/2', '2/2'):
                    self.assertIn(count, text)
                self.assertIn(f'model=qwen3 | approach=rag | dataset={self.dataset}', text)
                self.assertEqual('\r' in text, terminal)
                self.assertTrue(text.endswith('\n'))

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
