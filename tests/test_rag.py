from contextlib import redirect_stdout, redirect_stderr
import io
import json
import os
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

from hs_matching.approaches.base import PredictionContext
from hs_matching.approaches.rag import RAG
from hs_matching.catalog import Catalog
from hs_matching.cli import main
from hs_matching.embedding_index import SearchHit, build_index
from hs_matching.experiments import run_prediction
from hs_matching.providers.openai import ModelConfig, ProviderError
from hs_matching.vectorization.base import EmbeddingBatch, EmbeddingConfig


def response(codes, status='ok', missing=None):
    return {'status': 'completed', 'model': 'test-llm', 'usage': {'total_tokens': 42},
            'output': [{'type': 'message', 'content': [{'type': 'output_text', 'text': json.dumps({
                'status': status, 'missing_information': missing or [],
                'candidates': [{'code': c, 'explanation': 'Supported by supplied description'} for c in codes]})}]}]}


class RAGTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source = self.root / 'candidates.jsonl'
        rows = [{'code': c, 'edition': '2022', 'language': 'en', 'level': 6,
                 'is_candidate': True, 'description': text, 'contextual_description': 'Animals > ' + text}
                for c, text in [('010121', 'Breeding horses'), ('010129', 'Other horses'), ('010130', 'Asses')]]
        self.source.write_text(''.join(json.dumps(row) + '\n' for row in rows))
        self.catalog = Catalog(self.source)
        self.context = PredictionContext(self.catalog)
        self.hits = [SearchHit(r['code'], rank, 1 / rank, r['description'], r['contextual_description'])
                     for rank, r in enumerate(rows[:2], 1)]
        self.retriever = Mock()
        self.retriever.index = SimpleNamespace(catalog=self.catalog, directory=self.root / 'index', manifest={'config': {'model': 'test-vector'}})
        self.retriever.retrieve.return_value = (self.hits, {'usage': {'total_tokens': 2}, 'query_vector': [1., 0.]})
        self.provider = Mock(name='provider')
        self.provider.name = 'fake'
        self.provider.generate.return_value = response(['010129', '010121'])

    def predict(self, top_k=2, retrieval_k=2, query='Live horses'):
        return RAG(self.provider, ModelConfig(), self.retriever, retrieval_k).predict(query, top_k, self.context)

    def test_prompt_and_final_ranking(self):
        result = self.predict()
        self.assertEqual(result.status, 'ok')
        self.retriever.retrieve.assert_called_once_with('Live horses', 2)
        args = self.provider.generate.call_args.kwargs
        data = json.loads(args['user_input'])
        self.assertEqual(data['product_description'], 'Live horses')
        self.assertEqual([c['code'] for c in data['candidates']], ['010121', '010129'])
        self.assertIn('Animals > Breeding horses', args['user_input'])
        self.assertNotIn('010130', args['user_input'])
        self.assertIn('Use ONLY', args['instructions'])
        self.assertIn('facts from training', args['instructions'])
        self.assertEqual(args['schema']['properties']['candidates']['items']['properties']['code']['enum'], ['010121', '010129'])
        self.assertEqual([c.code for c in result.candidates], ['010129', '010121'])
        self.assertIsNone(result.candidates[0].score)
        self.assertEqual(result.candidates[0].references, ['catalog:2022:010129'])
        self.assertEqual(result.metadata['retrieved_candidates'][0]['score'], 1.)
        self.assertEqual(result.metadata['retrieval']['usage']['total_tokens'], 2)
        self.assertEqual(result.metadata['usage']['total_tokens'], 42)

    def test_known_code_outside_retrieval_is_rejected(self):
        self.provider.generate.return_value = response(['010130', '010121'])
        result = self.predict()
        self.assertEqual([(c.code, c.rank) for c in result.candidates], [('010121', 2)])
        self.assertEqual(result.metadata['rejected_candidates'][0]['reason'], 'not_retrieved')
        self.provider.generate.return_value = response(['010130'])
        self.assertEqual(self.predict().status, 'error')

    def test_duplicates_and_excess_results(self):
        self.provider.generate.return_value = response(['010121', '010121', '010129'])
        result = self.predict(top_k=2)
        self.assertEqual([r['reason'] for r in result.metadata['rejected_candidates']], ['duplicate', 'beyond_top_k'])

    def test_invalid_counts_and_query_do_not_call_models(self):
        for kwargs in [{'retrieval_k': 1}, {'retrieval_k': 0}, {'top_k': 0}, {'query': ''}]:
            self.assertEqual(self.predict(**kwargs).status, 'error')
        self.provider.generate.assert_not_called()
        self.retriever.retrieve.assert_not_called()

    def test_empty_retrieval_and_retrieval_error(self):
        self.retriever.retrieve.return_value = ([], {})
        self.assertEqual(self.predict().status, 'abstained')
        self.provider.generate.assert_not_called()
        self.retriever.retrieve.side_effect = ProviderError('http_error', 'Simulated', 429)
        result = self.predict()
        self.assertEqual(result.error['status_code'], 429)
        self.assertEqual(result.metadata['error_stage'], 'retrieval')
        self.provider.generate.assert_not_called()

    def test_missing_information_refusal_and_bad_response(self):
        self.provider.generate.return_value = response([], 'needs_info', ['Intended use?'])
        self.assertEqual(self.predict().missing_information, ['Intended use?'])
        self.provider.generate.return_value = response([], 'abstained')
        self.assertEqual(self.predict().status, 'abstained')
        raw = response([])
        raw['output'][0]['content'] = [{'type': 'refusal', 'refusal': 'Refused'}]
        self.provider.generate.return_value = raw
        self.assertEqual(self.predict().status, 'abstained')
        for raw in [dict(status='incomplete'), response([]), response([], 'needs_info')]:
            self.provider.generate.return_value = raw
            result = self.predict()
            self.assertEqual(result.status, 'error')
            self.assertEqual(result.metadata['raw_response'], raw)

    def test_generation_failure_keeps_retrieval_and_other_models_continue(self):
        self.provider.generate.side_effect = [ProviderError('http_error', 'Simulated', 500), response(['010121'])]
        run = run_prediction('Live horses', 2, self.context, [ModelConfig(model='bad'), ModelConfig(model='good')],
                             self.provider, 'rag', retriever=self.retriever, retrieval_k=2)
        self.assertEqual([p['status'] for p in run['predictions']], ['error', 'ok'])
        meta = run['predictions'][0]['metadata']
        self.assertEqual(meta['error_stage'], 'generation')
        self.assertEqual(len(meta['retrieved_candidates']), 2)
        self.assertIn('generation_duration_seconds', meta)

    def test_cli_real_retriever_with_fake_models(self):
        vectorizer = Mock()
        vectorizer.provider = 'openai'
        vectorizer.config = EmbeddingConfig('test-vector', 2)
        vectorizer.embed.return_value = EmbeddingBatch([[1., 0.], [0.9, 0.1], [-1., 0.]], 'test-vector')
        index_path = self.root / 'index'
        build_index(self.source, index_path, vectorizer)
        vectorizer.embed.reset_mock()
        vectorizer.embed.return_value = EmbeddingBatch([[1., 0.]], 'test-vector')
        stdout = io.StringIO()
        with patch.dict(os.environ, {}, clear=True), redirect_stdout(stdout), redirect_stderr(io.StringIO()):
            with patch('hs_matching.vectorization.openai.OpenAIVectorizer', return_value=vectorizer), patch('hs_matching.cli.OpenAIProvider', return_value=self.provider):
                code = main(['predict', 'Live horses', '--approach', 'rag', '--catalog', str(self.source),
                             '--index', str(index_path), '--retrieval-k', '2', '--top-k', '1',
                             '--env-file', str(self.root / 'absent'), '--runs-dir', str(self.root / 'runs'), '--json'])
        self.assertEqual(code, 0)
        vectorizer.embed.assert_called_once_with(['Live horses'])
        run = json.loads(stdout.getvalue())
        self.assertEqual(run['predictions'][0]['metadata']['retrieval_k'], 2)
        self.assertEqual(run['predictions'][0]['candidates'][0]['code'], '010129')
        self.assertEqual(json.loads(next((self.root / 'runs').glob('*.json')).read_text()), run)

    def test_cli_rejects_k_less_than_n(self):
        with redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()), patch('hs_matching.cli.OpenAIProvider') as provider:
            code = main(['predict', 'text', '--approach', 'rag', '--retrieval-k', '1', '--top-k', '3',
                         '--env-file', str(self.root / 'absent')])
        self.assertEqual(code, 2)
        provider.assert_not_called()
