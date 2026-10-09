"""Tests ciblés sur de petits datasets, vrai backend MLflow et fournisseurs simulés."""
from contextlib import redirect_stderr, redirect_stdout
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

os.environ.setdefault('MLFLOW_ENABLE_ASYNC_LOGGING', 'false')
os.environ.setdefault('MLFLOW_DISABLE_TELEMETRY', 'true')
os.environ.setdefault('MLFLOW_DISABLE_AGENT_HINT', 'true')

import mlflow
from mlflow.genai import datasets as ml_datasets

from benchmark.cli import main
from benchmark.datasets import get_snapshot, read_csv, versions
from benchmark.metrics import summarize, token_counts
from benchmark.tracking import configure
from hs_matching.approaches.base import Candidate, Prediction
from hs_matching.providers.openai import ModelConfig


class DatasetTests(unittest.TestCase):
    def test_csv_validation_and_leading_zeroes(self):
        rows = read_csv(b'HS code;description\n010121;Horse\n010121;Horse\n')
        self.assertEqual([r['row_id'] for r in rows], [1, 2])
        self.assertEqual(rows[0]['hs_code'], '010121')
        rows = read_csv('\ufeff010121,"Horses, live; breeding\nanimals"\n'.encode())
        self.assertIn('\n', rows[0]['description'])
        for text in ('', 'HS code;description\n', '10121;Horse\n', '010121;\n', '010121;Horse;extra\n'):
            with self.subTest(text=text), self.assertRaises(ValueError):
                read_csv(text.encode())

    def test_source_archive_excludes_generated_mlflow_sources(self):
        from benchmark.runner import log_sources
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for filename in ('benchmark/cli.py', 'hs_matching/approaches/rag.py',
                             'benchmark/mlartifacts/old/source/benchmark/cli.py'):
                path = root / filename
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text('# source')
            tracker = Mock()
            with patch('benchmark.runner.ROOT', root):
                log_sources(tracker)
            archived = [Path(call.args[0]).relative_to(root).as_posix()
                        for call in tracker.log_artifact.call_args_list]
            self.assertEqual(archived, ['benchmark/cli.py', 'hs_matching/approaches/rag.py'])

    def test_metrics_statuses_ranks_and_missing_tokens(self):
        rows = []
        for status, codes in [('needs_info', ['999999', '010121']), ('ok', ['010129']),
                              ('ok', ['019999']), ('abstained', ['010121']), ('error', ['010121'])]:
            rows.append(dict(ground_truth='010121', response_time=2, input_tokens=None, output_tokens=None,
                             answer={'status': status, 'candidates': [{'code': c, 'rank': 4} for c in codes]}))
        rows[0].update(input_tokens=20, output_tokens=10)
        stats = summarize(rows)
        self.assertEqual([stats[k] for k in ('chapter_accuracy', 'heading_accuracy', 'hs6_accuracy')], [.6, .4, .2])
        self.assertEqual(stats['mean_total_tokens'], 30)
        self.assertEqual(stats['input_tokens_measured_rows'], 1)
        self.assertEqual(stats['error_rate'], .2)
        self.assertEqual(summarize([]), {'evaluated_rows': 0})
        for usage in ({'input_tokens': 12, 'output_tokens': 4}, {'prompt_tokens': 12, 'completion_tokens': 4}):
            self.assertEqual(token_counts({'usage': usage}, 'llm_direct'), {'input_tokens': 12, 'output_tokens': 4})
        self.assertEqual(token_counts({'usage': {'prompt_tokens': 12}, 'retrieval': {'usage': {'prompt_tokens': 3}}}, 'rag'),
                         {'input_tokens': 15, 'output_tokens': None})
        self.assertEqual(token_counts({}, 'embeddings'), {'input_tokens': None, 'output_tokens': 0})


class MLflowTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.uri = 'sqlite:///' + str(self.root / 'mlflow.db')
        configure(self.uri)
        self.folder = self.root / 'tiny'
        self.folder.mkdir()
        self.manifest = dict(schema_version=1, name='tiny', csv='data.csv', edition='2022', language='en')
        (self.folder / 'manifest.json').write_text(json.dumps(self.manifest))
        self.csv = self.folder / 'data.csv'
        self.csv.write_text('HS code;description\n010121;Live horses\n010121;Live horses\n010129;Other horses\n')
        self.client = mlflow.MlflowClient(tracking_uri=self.uri)
        self.exp_id = self.client.create_experiment('hs-matching/tiny', artifact_location=(self.root / 'artifacts').as_uri())
        self.args = ['--dataset', str(self.folder), '--tracking-uri', self.uri, '--env-file', str(self.root / 'absent')]
        self.output = io.StringIO()
        self.addCleanup(mlflow.end_run)

    def call(self, *args):
        with redirect_stdout(self.output), redirect_stderr(self.output):
            return main(list(args))

    def run_args(self, approach='llm_direct', **kwargs):
        args = ['run', *self.args, '--approach', approach, '--runs-dir', str(self.root / 'runs')]
        for key, value in kwargs.items():
            args += ['--' + key.replace('_', '-'), str(value)]
        return args

    def fake_engine(self, side_effect=None):
        engine = Mock()
        engine.config = ModelConfig()
        engine.retriever.index.config.model = 'fake-embedding'
        engine.retriever.index.manifest = {'provider': 'fake', 'dimensions': 3}
        engine.predict.return_value = Prediction('ok', [Candidate('010121', 1, 'Horses')],
            metadata={'usage': {'input_tokens': 10, 'output_tokens': 2},
                      'retrieval': {'usage': {'prompt_tokens': 3}}})
        if side_effect is not None:
            engine.predict.side_effect = side_effect
        context = SimpleNamespace(catalog=SimpleNamespace(sha256='abc'))
        return engine, context

    def runs(self):
        return self.client.search_runs([self.exp_id])

    def artifact(self, run, path):
        destination = self.root / 'downloads'
        destination.mkdir(exist_ok=True)
        path = self.client.download_artifacts(run.info.run_id, path, str(destination))
        return Path(path)

    def test_dataset_versions_store_all_rows_and_survive_local_removal(self):
        one = get_snapshot(self.folder)
        again = get_snapshot(self.folder)
        self.assertEqual(one.dataset_id, again.dataset_id)
        self.assertEqual(len(one.rows), 3)
        self.csv.write_text('010121;Changed\n')
        two = get_snapshot(self.folder)
        self.assertEqual(two.version, 2)
        self.csv.unlink()
        (self.folder / 'manifest.json').unlink()
        frozen = get_snapshot('tiny', 1)
        self.assertEqual(frozen.rows, one.rows)
        self.assertEqual(len(versions('tiny', self.exp_id)), 2)
        with self.assertRaises(ValueError):
            get_snapshot('tiny', 3)

    def test_changed_stored_dataset_is_rejected(self):
        snapshot = get_snapshot(self.folder)
        stored = ml_datasets.get_dataset(dataset_id=snapshot.dataset_id)
        stored.merge_records([{'inputs': {'row_id': 1, 'description': 'Live horses'}, 'expectations': {'hs_code': '999999'}}])
        with self.assertRaisesRegex(ValueError, 'intégrité'):
            get_snapshot('tiny', 1)

    def test_limit_and_all_approaches_record_real_mlflow_runs(self):
        for name in ('llm_direct', 'embeddings', 'rag'):
            with self.subTest(approach=name):
                engine, context = self.fake_engine()
                with patch('benchmark.runner.build_approach', return_value=(engine, context)):
                    self.assertEqual(self.call(*self.run_args(name, limit=1)), 0, self.output.getvalue())
                self.assertEqual(engine.predict.call_count, 1)
                run = self.runs()[0]
                self.assertEqual(run.info.status, 'FINISHED')
                self.assertEqual(run.data.params['dataset_size'], '3')
                self.assertEqual(run.data.params['selected_rows'], '1')
                self.assertEqual(run.data.tags['full_dataset'], 'false')
                self.assertEqual(run.data.tags['complete'], 'true')
                self.assertEqual(run.data.metrics['hs6_accuracy'], 1)
                self.assertTrue(run.inputs.dataset_inputs)
                results = json.loads(self.artifact(run, 'evaluation/results.json').read_text())
                self.assertEqual(results[0]['ground_truth'], '010121')
                self.assertNotIn('metadata', results[0]['answer'])
                self.assertEqual(results[0]['input_tokens'], 13 if name == 'rag' else 10)
                self.assertEqual(results[0]['output_tokens'], 0 if name == 'embeddings' else 2)
                self.assertTrue(self.artifact(run, 'dataset/dataset.csv').exists())
                self.assertTrue(self.artifact(run, 'evaluation/table.json').exists())
        self.assertEqual(len(versions('tiny', self.exp_id)), 1)
        self.assertEqual(len(self.runs()), 3)

    def test_errors_continue_and_interruption_preserves_partial_results(self):
        engine, context = self.fake_engine([RuntimeError('private detail'), Prediction('abstained')])
        with patch('benchmark.runner.build_approach', return_value=(engine, context)):
            self.assertEqual(self.call(*self.run_args(limit=2)), 1, self.output.getvalue())
        run = self.runs()[0]
        self.assertEqual(run.info.status, 'FAILED')
        self.assertEqual(run.data.metrics['evaluated_rows'], 2)
        self.assertEqual(run.data.metrics['error_rate'], .5)
        self.assertNotIn('private detail', self.artifact(run, 'evaluation/results.json').read_text())
        engine, context = self.fake_engine([Prediction('abstained'), KeyboardInterrupt()])
        with patch('benchmark.runner.build_approach', return_value=(engine, context)):
            self.assertEqual(self.call(*self.run_args()), 130, self.output.getvalue())
        run = self.runs()[0]
        self.assertEqual(run.info.status, 'KILLED')
        self.assertEqual(run.data.tags['complete'], 'false')
        self.assertEqual(run.data.metrics['evaluated_rows'], 1)
        self.assertEqual(len(self.artifact(run, 'evaluation/results.jsonl').read_text().splitlines()), 1)

    def test_initialization_failure_is_a_failed_run(self):
        with patch('benchmark.runner.build_approach', side_effect=ValueError('Missing index')):
            self.assertEqual(self.call(*self.run_args('rag', limit=1)), 2)
        run = self.runs()[0]
        self.assertEqual(run.info.status, 'FAILED')
        self.assertEqual(run.data.metrics['evaluated_rows'], 0)
        self.assertTrue(self.artifact(run, 'dataset/dataset.csv').exists())
        self.assertIsNone(mlflow.active_run())

    def test_invalid_parameters_and_csv_prevent_calls_even_outside_limit(self):
        with patch('benchmark.runner.build_approach') as build:
            for options in ({'limit': 0}, {'offset': -1}, {'top_k': 0}, {'dataset_version': 0}):
                self.assertEqual(self.call(*self.run_args(**options)), 2)
            self.assertEqual(self.call(*self.run_args('rag', top_k=5, retrieval_k=2)), 2)
            self.csv.write_text('010121;Good\ninvalid;Bad\n')
            self.assertEqual(self.call(*self.run_args(limit=1)), 2)
            build.assert_not_called()
        self.assertEqual(self.runs(), [])
        self.assertEqual(versions('tiny', self.exp_id), {})

    def test_saved_version_offset_uses_frozen_descriptions(self):
        get_snapshot(self.folder)
        self.csv.unlink()
        engine, context = self.fake_engine()
        args = self.run_args(dataset_version=1, offset=2, limit=1)
        args[args.index('--dataset') + 1] = 'tiny'
        with patch('benchmark.runner.build_approach', return_value=(engine, context)):
            self.assertEqual(self.call(*args), 0, self.output.getvalue())
        self.assertEqual(engine.predict.call_args.args[0], 'Other horses')
        result = json.loads(self.artifact(self.runs()[0], 'evaluation/results.json').read_text())[0]
        self.assertEqual(result['row_id'], 3)

    def test_tracking_failure_stops_calls_and_keeps_local_checkpoint(self):
        engine, context = self.fake_engine()
        original = mlflow.log_artifact
        def fail_results(local_path, *args, **kwargs):
            if Path(local_path).name == 'results.jsonl':
                raise OSError('Unavailable artifact store')
            return original(local_path, *args, **kwargs)
        with patch('benchmark.runner.build_approach', return_value=(engine, context)), \
                patch('mlflow.log_artifact', side_effect=fail_results):
            self.assertEqual(self.call(*self.run_args()), 2)
        self.assertEqual(engine.predict.call_count, 1)
        run = self.runs()[0]
        self.assertEqual(run.info.status, 'FAILED')
        checkpoint = self.root / 'runs' / run.info.run_id / 'results.jsonl'
        self.assertEqual(len(checkpoint.read_text().splitlines()), 1)

    def test_experiment_per_dataset_and_cli_from_benchmark_directory(self):
        first = get_snapshot(self.folder)
        self.manifest['name'] = 'another'
        (self.folder / 'manifest.json').write_text(json.dumps(self.manifest))
        second = get_snapshot(self.folder)
        self.assertNotEqual(first.experiment_id, second.experiment_id)
        self.assertEqual(self.client.get_experiment(second.experiment_id).name, 'hs-matching/another')
        result = subprocess.run([sys.executable, 'benchmark.py', 'import-dataset', *self.args],
                                cwd=Path(__file__).parent, capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('another v1', result.stdout)


if __name__ == '__main__':
    unittest.main()
