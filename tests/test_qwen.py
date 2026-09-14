from contextlib import redirect_stderr, redirect_stdout
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from urllib.error import HTTPError, URLError

from hs_matching.cli import main
from hs_matching.providers.openai import ModelConfig, ProviderError
from hs_matching.providers.qwen import QWEN_MODEL, QwenProvider


def response():
    return {'model': QWEN_MODEL, 'usage': {'prompt_tokens': 10, 'completion_tokens': 20},
            'choices': [{'finish_reason': 'stop', 'message': {'content': json.dumps({
                'status': 'ok', 'missing_information': [],
                'candidates': [{'code': '010121', 'explanation': 'Breeding horses'}]})}}]}


class QwenTests(unittest.TestCase):
    def generate(self, **kwargs):
        return QwenProvider('secret').generate(instructions='same prompt', user_input='horses',
                                               schema={'type': 'object'}, config=ModelConfig(model=QWEN_MODEL, **kwargs))

    def test_transport_and_normalization(self):
        with patch('hs_matching.providers.qwen.urlopen') as network:
            network.return_value.__enter__.return_value = io.StringIO(json.dumps(response()))
            raw = self.generate(temperature=0.2, timeout=7, max_output_tokens=123)
        request = network.call_args.args[0]
        self.assertEqual(request.full_url, 'http://localhost:8000/v1/chat/completions')
        payload = json.loads(request.data)
        self.assertEqual(payload['model'], QWEN_MODEL)
        self.assertEqual(payload['messages'], [{'role': 'system', 'content': 'same prompt'}, {'role': 'user', 'content': 'horses'}])
        self.assertEqual(payload['response_format']['json_schema']['schema'], {'type': 'object'})
        self.assertEqual(payload['max_tokens'], 123)
        self.assertEqual(payload['temperature'], 0.2)
        self.assertEqual(network.call_args.kwargs['timeout'], 7)
        self.assertEqual(raw['status'], 'completed')
        self.assertEqual(raw['provider_response'], response())

    def test_errors_no_retry_and_no_secret(self):
        for error, kind in [(URLError('secret'), 'connection_error'), (TimeoutError(), 'connection_error'),
                            (HTTPError('url', 401, 'secret', {}, None), 'http_error')]:
            with self.subTest(kind=kind), patch('hs_matching.providers.qwen.urlopen', side_effect=error) as network:
                with self.assertRaises(ProviderError) as caught:
                    self.generate()
                self.assertEqual(caught.exception.details['kind'], kind)
                self.assertNotIn('secret', str(caught.exception))
                network.assert_called_once()

    def test_invalid_and_truncated_responses(self):
        truncated = response()
        truncated['choices'][0]['finish_reason'] = 'length'
        for raw in ['not json', 'null', '{}', json.dumps(truncated)]:
            with self.subTest(raw=raw), patch('hs_matching.providers.qwen.urlopen') as network:
                network.return_value.__enter__.return_value = io.StringIO(raw)
                with self.assertRaises(ProviderError):
                    self.generate()

    def test_configuration_no_network(self):
        with patch('hs_matching.providers.qwen.urlopen') as network:
            with self.assertRaises(ProviderError):
                QwenProvider(None).generate(instructions='', user_input='', schema={}, config=ModelConfig())
            with self.assertRaises(ProviderError):
                self.generate(reasoning_effort='high')
            network.assert_not_called()

    def test_cli_success_and_failure_without_openai(self):
        for failure in [False, True]:
            with self.subTest(failure=failure), tempfile.TemporaryDirectory() as tmp:
                env = Path(tmp) / '.env'
                env.write_text('LOCAL_QWEN_KEY=test\n')
                stdout = io.StringIO()
                with patch.dict(os.environ, {}, clear=True), patch('hs_matching.providers.qwen.urlopen') as network, \
                        patch('hs_matching.providers.openai.urlopen') as openai, \
                        redirect_stdout(stdout), redirect_stderr(io.StringIO()):
                    if failure:
                        network.side_effect = URLError('unavailable')
                    else:
                        network.return_value.__enter__.return_value = io.StringIO(json.dumps(response()))
                    code = main(['predict', 'Live purebred breeding horses', '--model', 'qwen3',
                                 '--env-file', str(env), '--runs-dir', tmp, '--json'])
                self.assertEqual(code, 1 if failure else 0)
                openai.assert_not_called()
                prediction = json.loads(stdout.getvalue())['predictions'][0]
                self.assertEqual(prediction['metadata']['provider'], 'qwen')
                self.assertEqual(prediction['metadata']['config']['model'], QWEN_MODEL)
                self.assertEqual(prediction['status'], 'error' if failure else 'ok')
                self.assertEqual(len(list(Path(tmp).glob('*.json'))), 1)

    def test_cli_mixed_models_route_in_order(self):
        from test_llm_direct import response as openai_response
        with tempfile.TemporaryDirectory() as tmp, patch.dict(os.environ, {'LOCAL_QWEN_KEY': 'test'}, clear=True):
            stdout = io.StringIO()
            with patch('hs_matching.providers.qwen.urlopen', side_effect=URLError('offline')) as qwen, \
                    patch('hs_matching.providers.openai.OpenAIProvider.generate', return_value=openai_response()) as openai, \
                    redirect_stdout(stdout), redirect_stderr(io.StringIO()):
                code = main(['predict', 'horses', '--model', 'qwen3', '--model', 'gpt-4.1-mini',
                             '--env-file', tmp + '/absent', '--runs-dir', tmp, '--json'])
            self.assertEqual(code, 1)
            predictions = json.loads(stdout.getvalue())['predictions']
            self.assertEqual([p['metadata']['provider'] for p in predictions], ['qwen', 'openai'])
            self.assertEqual([p['status'] for p in predictions], ['error', 'ok'])
            qwen.assert_called_once()
            openai.assert_called_once()

    def test_rag_preserves_prompt_and_restricted_schema(self):
        from types import SimpleNamespace
        from unittest.mock import Mock
        from hs_matching.approaches.base import PredictionContext
        from hs_matching.approaches.rag import PROMPT, RAG
        from hs_matching.catalog import Catalog
        from hs_matching.embedding_index import SearchHit
        catalog = Catalog('data/processed/h6_2022/catalog.jsonl')
        retriever = Mock()
        retriever.index = SimpleNamespace(catalog=catalog, directory=Path('/tmp/index'), manifest={})
        retriever.retrieve.return_value = ([SearchHit('010121', 1, 1., 'Breeding horses', 'Animals > Breeding horses')], {})
        with patch('hs_matching.providers.qwen.urlopen') as network:
            network.return_value.__enter__.return_value = io.StringIO(json.dumps(response()))
            prediction = RAG(QwenProvider('test'), ModelConfig(model=QWEN_MODEL), retriever, 1).predict(
                'Live purebred breeding horses', 1, PredictionContext(catalog))
        self.assertEqual(prediction.status, 'ok')
        payload = json.loads(network.call_args.args[0].data)
        self.assertEqual(payload['messages'][0]['content'], PROMPT.format(top_k=1))
        self.assertEqual(payload['response_format']['json_schema']['schema']['properties']['candidates']['items']['properties']['code']['enum'], ['010121'])
