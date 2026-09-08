from contextlib import redirect_stdout, redirect_stderr
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from hs_matching.approaches.base import PredictionContext
from hs_matching.approaches.embeddings import Embeddings
from hs_matching.catalog import Catalog
from hs_matching.cli import main as cli_main
from hs_matching.embedding_index import build_index, EmbeddingIndex, EmbeddingRetriever
from hs_matching.providers.openai import ProviderError
from hs_matching.vectorization.base import EmbeddingBatch, EmbeddingConfig
from hs_matching.vectorization.openai import OpenAIVectorizer
from scripts.build_embeddings import main as build_main


class FakeVectorizer:
    provider = 'openai'

    def __init__(self, config=None):
        self.config = config or EmbeddingConfig('test-embedding', 2)
        self.calls = []

    def embed(self, texts):
        self.calls.append(texts)
        vectors = {'Chapter > First': [2., 0.], 'Chapter > Second': [0., 3.],
                   'Chapter > Third': [-4., 0.]}
        return EmbeddingBatch([vectors.get(text, [5., 0.]) for text in texts], self.config.model,
                              {'prompt_tokens': len(texts), 'total_tokens': len(texts)})


class EmbeddingTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source = self.root / 'candidates.jsonl'
        rows = [{'code': code, 'edition': '2022', 'language': 'en', 'level': 6,
                 'is_candidate': True, 'is_special': False, 'description': text,
                 'contextual_description': 'Chapter > ' + text}
                for code, text in [('010121', 'First'), ('010129', 'Second'), ('010130', 'Third')]]
        self.source.write_text(''.join(json.dumps(row) + '\n' for row in rows))
        self.catalog = Catalog(self.source)
        self.output = self.root / 'index'
        self.vectorizer = FakeVectorizer()

    def build(self):
        return build_index(self.source, self.output, self.vectorizer, batch_size=2)

    def test_readable_artifact_batches_and_cosine(self):
        manifest = self.build()
        self.assertEqual(self.vectorizer.calls, [['Chapter > First', 'Chapter > Second'], ['Chapter > Third']])
        data = [json.loads(line) for line in (self.output / 'vectors.jsonl').read_text().splitlines()]
        self.assertEqual(data[0], {'code': '010121', 'vector': [2., 0.]})
        self.assertEqual(manifest['count'], 3)
        index = EmbeddingIndex(self.output, self.catalog)
        hits = index.search([20., 0.], 10)
        self.assertEqual([hit.code for hit in hits], ['010121', '010129', '010130'])
        self.assertEqual([hit.score for hit in hits], [1., 0., -1.])
        self.assertEqual(index.search([1., 1.], 1)[0].code, '010121')
        self.assertFalse(index.matrix.flags.writeable)

    def test_retriever_and_prediction_embed_query_only(self):
        self.build()
        self.vectorizer.calls.clear()
        retriever = EmbeddingRetriever(EmbeddingIndex(self.output, self.catalog), self.vectorizer)
        result = Embeddings(retriever).predict('input description', 2, PredictionContext(self.catalog))
        self.assertEqual(result.status, 'ok')
        self.assertEqual(self.vectorizer.calls, [['input description']])
        self.assertEqual(result.candidates[0].score_type, 'cosine_similarity')
        self.assertEqual(result.candidates[0].score, 1.)
        hits, metadata = retriever.retrieve('RAG query', 3)
        self.assertEqual(hits[0].contextual_description, 'Chapter > First')
        self.assertEqual(metadata['usage']['total_tokens'], 1)

    def test_model_dimensions_and_provider_must_match(self):
        self.build()
        index = EmbeddingIndex(self.output, self.catalog)
        for config in [EmbeddingConfig('different', 2), EmbeddingConfig('test-embedding', 3)]:
            with self.assertRaises(ValueError):
                EmbeddingRetriever(index, FakeVectorizer(config))
        self.vectorizer.provider = 'different-provider'
        with self.assertRaises(ValueError):
            EmbeddingRetriever(index, self.vectorizer)

    def test_changed_catalog_is_rejected(self):
        self.build()
        self.source.write_text(self.source.read_text().replace('Chapter > First', 'Changed text'))
        with self.assertRaisesRegex(ValueError, 'catalogue a changé'):
            EmbeddingIndex(self.output, Catalog(self.source))

    def test_tampered_or_truncated_index_is_rejected(self):
        self.build()
        vectors = self.output / 'vectors.jsonl'
        original = vectors.read_text()
        vectors.write_text(original.replace('2.0', '3.0'))
        with self.assertRaises(ValueError):
            EmbeddingIndex(self.output, self.catalog)
        vectors.write_text(original.splitlines()[0] + '\n')
        with self.assertRaises(ValueError):
            EmbeddingIndex(self.output, self.catalog)

    def test_failed_build_never_publishes_partial_index(self):
        with patch.object(self.vectorizer, 'embed', side_effect=[EmbeddingBatch([[1., 0.], [0., 1.]], 'test-embedding'), ProviderError('http_error', 'Simulated')]):
            with self.assertRaises(ProviderError):
                self.build()
        self.assertFalse(self.output.exists())
        self.assertEqual(list(self.root.glob('.embeddings-*')), [])

    def test_existing_index_is_not_overwritten_or_recomputed(self):
        self.build()
        self.vectorizer.calls.clear()
        with self.assertRaises(ValueError):
            self.build()
        self.assertEqual(self.vectorizer.calls, [])

    def test_bad_vectors_and_blank_queries(self):
        self.build()
        index = EmbeddingIndex(self.output, self.catalog)
        for vector in [[0., 0.], [float('nan'), 1.], [1.], [True, 1.], [float('inf'), 1.]]:
            with self.assertRaises(ValueError):
                index.search(vector, 1)
        self.vectorizer.calls.clear()
        retriever = EmbeddingRetriever(index, self.vectorizer)
        for query, top_k in [('', 1), ('text', 0)]:
            result = Embeddings(retriever).predict(query, top_k, PredictionContext(self.catalog))
            self.assertEqual(result.status, 'error')
        self.assertEqual(self.vectorizer.calls, [])

    def test_provider_failure_becomes_prediction_error(self):
        self.build()
        retriever = EmbeddingRetriever(EmbeddingIndex(self.output, self.catalog), self.vectorizer)
        with patch.object(self.vectorizer, 'embed', side_effect=ProviderError('http_error', 'Simulated', 429)):
            result = Embeddings(retriever).predict('text', 1, PredictionContext(self.catalog))
        self.assertEqual(result.status, 'error')
        self.assertEqual(result.error['status_code'], 429)

    def test_returned_model_cannot_change(self):
        self.build()
        retriever = EmbeddingRetriever(EmbeddingIndex(self.output, self.catalog), self.vectorizer)
        with patch.object(self.vectorizer, 'embed', return_value=EmbeddingBatch([[1., 0.]], 'new-model')):
            with self.assertRaises(ValueError):
                retriever.retrieve('text', 1)

    def test_cli_build_and_predict_offline(self):
        with patch.dict(os.environ, {}, clear=True), redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()):
            with patch('scripts.build_embeddings.OpenAIVectorizer', return_value=self.vectorizer):
                code = build_main(['--input', str(self.source), '--output-dir', str(self.output), '--env-file', str(self.root / 'absent')])
            self.assertEqual(code, 0)
            with patch('hs_matching.vectorization.openai.OpenAIVectorizer', return_value=self.vectorizer) as factory:
                code = cli_main(['predict', 'text', '--approach', 'embeddings', '--index', str(self.output),
                                 '--catalog', str(self.source), '--env-file', str(self.root / 'absent'),
                                 '--runs-dir', str(self.root / 'runs'), '--top-k', '2'])
            self.assertEqual(code, 0)
            self.assertEqual(factory.call_args.args[1], self.vectorizer.config)
        run = json.loads(next((self.root / 'runs').glob('*.json')).read_text())
        self.assertEqual(run['predictions'][0]['candidates'][0]['code'], '010121')

    def test_missing_index_never_calls_api(self):
        with patch('hs_matching.vectorization.openai.urlopen') as network, redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()):
            code = cli_main(['predict', 'text', '--approach', 'embeddings', '--index', str(self.output),
                             '--catalog', str(self.source), '--env-file', str(self.root / 'absent')])
        self.assertEqual(code, 2)
        network.assert_not_called()


class OpenAIVectorizerTests(unittest.TestCase):
    def test_response_indices_and_request_dimensions(self):
        raw = {'model': 'test', 'data': [{'index': 1, 'embedding': [0., 1.]}, {'index': 0, 'embedding': [1., 0.]}],
               'usage': {'total_tokens': 2}}
        with patch('hs_matching.vectorization.openai.urlopen') as network:
            network.return_value.__enter__.return_value = io.StringIO(json.dumps(raw))
            result = OpenAIVectorizer('fake-key', EmbeddingConfig('test', 2)).embed(['a', 'b'])
            request = network.call_args.args[0]
            self.assertEqual(request.full_url, 'https://api.openai.com/v1/embeddings')
            self.assertEqual(json.loads(request.data), {'model': 'test', 'input': ['a', 'b'], 'encoding_format': 'float', 'dimensions': 2})
            self.assertEqual(result.vectors, [[1., 0.], [0., 1.]])

    def test_bad_response_and_placeholder(self):
        for data in [[{'index': 0, 'embedding': [0., 0.]}], [{'index': 1, 'embedding': [1., 0.]}], []]:
            with patch('hs_matching.vectorization.openai.urlopen') as network:
                network.return_value.__enter__.return_value = io.StringIO(json.dumps({'model': 'test', 'data': data}))
                with self.assertRaises(ProviderError):
                    OpenAIVectorizer('fake-key', EmbeddingConfig()).embed(['text'])
        with patch('hs_matching.vectorization.openai.urlopen') as network:
            with self.assertRaises(ProviderError):
                OpenAIVectorizer('YOUR_OPENAI_API_KEY_HERE', EmbeddingConfig()).embed(['text'])
            network.assert_not_called()
