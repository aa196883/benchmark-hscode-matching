import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from benchmark.benchmark import main
from benchmark.process_runs import group_runs, summarize, markdown


class ProcessRunsTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def run_file(self, name, rows, model='m', approach='rag'):
        path = self.root / name
        path.write_text(json.dumps(dict(model=model, approach=approach, results=rows)))
        return str(path)

    def row(self, codes=(), status='ok', duration=1):
        return dict(ground_truth='010121', response_time=duration,
                    answer=dict(status=status, candidates=[dict(code=c) for c in codes]))

    def test_weighted_aggregation_all_candidates_and_statuses(self):
        a = self.run_file('a.json', [self.row(['999999', '010121'], 'needs_info', 4)])
        b = self.run_file('b.json', [self.row(['010129']), self.row(['019999']), self.row(),
                                   self.row(['010121'], 'error'), self.row(['010121'], 'abstained')])
        stats = summarize(group_runs([a, b, a]))['m', 'rag']
        self.assertEqual(stats['count'], 6)
        self.assertEqual(stats['matches'], [3, 2, 1])
        self.assertEqual(stats['mean_time'], 1.5)

    def test_token_means_across_runs_and_missing_values(self):
        a = self.run_file('a.json', [dict(self.row(), input_tokens=100, output_tokens=20)])
        b = self.run_file('b.json', [dict(self.row(status='error'), input_tokens=20, output_tokens=0),
                                   dict(self.row(), input_tokens=30, output_tokens=10)])
        summary = summarize(group_runs([a, b]))
        stats = summary['m', 'rag']
        self.assertEqual(stats['mean_input_tokens'], 50)
        self.assertEqual(stats['mean_output_tokens'], 10)
        self.assertEqual(stats['mean_total_tokens'], 60)
        self.assertIn('| 50.00 | 10.00 | 60.00 |', markdown(summary))
        c = self.run_file('c.json', [self.row(), dict(self.row(), input_tokens=None, output_tokens=10)])
        stats = summarize(group_runs([a, b, c]))['m', 'rag']
        self.assertEqual(stats['mean_input_tokens'], 50)
        self.assertEqual(stats['mean_output_tokens'], 10)
        self.assertEqual(stats['mean_total_tokens'], 60)
        missing = summarize(group_runs([self.run_file('old.json', [self.row()])]))
        self.assertIn('| N/A | N/A | N/A |', markdown(missing))

    def test_invalid_tokens(self):
        for name in ('input_tokens', 'output_tokens'):
            for value in (-1, True, '10', 1.5):
                with self.subTest(name=name, value=value):
                    path = self.run_file('bad.json', [dict(self.row(), **{name: value})])
                    with self.assertRaisesRegex(ValueError, name):
                        summarize(group_runs([path]))

    def test_separate_groups_globs_and_empty_runs(self):
        self.run_file('a.json', [self.row(['010121'])], approach='llm_direct')
        self.run_file('b.json', [self.row(['010121'])], model='', approach='embeddings')
        self.run_file('c.json', [])
        summary = summarize(group_runs([str(self.root / '*.json')]))
        self.assertEqual(len(summary), 3)
        report = markdown(summary)
        self.assertIn('1/1 (100.00%)', report)
        self.assertIn('N/A (0 résultat)', report)
        self.assertIn('(sans modèle) / embeddings', report)

    def test_invalid_input_does_not_replace_report(self):
        row = self.row()
        row['ground_truth'] = 10121
        path = self.run_file('bad.json', [row])
        output = self.root / 'report.md'
        output.write_text('original')
        self.assertEqual(main(['--process-runs', '--runs', path, '--output', str(output)]), 2)
        self.assertEqual(output.read_text(), 'original')
        with self.assertRaises(ValueError):
            group_runs([str(self.root / 'missing*.json')])

    def test_process_mode_needs_no_inference_or_env(self):
        path = self.run_file('a.json', [self.row(['010121'])])
        output = self.root / 'report.md'
        with patch('benchmark.benchmark.build_approach') as build, patch('benchmark.benchmark.load_env') as env:
            self.assertEqual(main(['--process-runs', '--runs', path, '--output', str(output)]), 0)
        build.assert_not_called()
        env.assert_not_called()
        self.assertIn('1/1 (100.00%)', output.read_text())
