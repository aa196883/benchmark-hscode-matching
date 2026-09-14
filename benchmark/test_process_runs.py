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
