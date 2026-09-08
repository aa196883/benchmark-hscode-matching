from contextlib import redirect_stdout, redirect_stderr
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from urllib.error import HTTPError

from hs_matching.approaches.base import PredictionContext
from hs_matching.approaches.llm_direct import LLMDirect
from hs_matching.catalog import Catalog
from hs_matching.cli import load_env, main
from hs_matching.experiments import run_prediction
from hs_matching.providers.openai import ModelConfig, OpenAIProvider, ProviderError

ROOT = Path(__file__).resolve().parents[1]


def response(codes=('010121',), status='ok', missing=None):
    return {'id': 'resp_test', 'status': 'completed', 'model': 'test-model',
            'usage': {'input_tokens': 100, 'output_tokens': 30},
            'output': [{'type': 'message', 'content': [{'type': 'output_text', 'text': json.dumps({
                'status': status, 'missing_information': missing or [],
                'candidates': [{'code': c, 'explanation': 'Example'} for c in codes]})}]}]}


class FakeProvider:
    name = 'fake'

    def __init__(self, raw=None):
        self.raw = raw if raw is not None else response()
        self.calls = []

    def generate(self, **kwargs):
        self.calls.append(kwargs)
        return self.raw


class DirectTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.context = PredictionContext(Catalog(ROOT / 'data/processed/h6_2022/catalog.jsonl'))

    def predict(self, raw, top_k=5):
        return LLMDirect(FakeProvider(raw), ModelConfig()).predict('Live purebred breeding horses', top_k, self.context)

    def test_happy_path_and_no_catalog_in_prompt(self):
        provider = FakeProvider()
        result = LLMDirect(provider, ModelConfig(model='arbitrary-model')).predict('horses', 3, self.context)
        self.assertEqual(result.status, 'ok')
        self.assertEqual(result.candidates[0].code, '010121')
        self.assertEqual(result.candidates[0].description, self.context.catalog.rows['010121']['description'])
        self.assertIsNone(result.candidates[0].score)
        self.assertEqual(provider.calls[0]['user_input'], 'horses')
        self.assertNotIn('Animals; live', json.dumps(provider.calls[0]['schema']))
        self.assertEqual(result.metadata['usage']['input_tokens'], 100)

    def test_rejections_keep_original_ranks(self):
        result = self.predict(response(['0101.21', '000000', '999999', '010121', '010121', '010129']), 5)
        self.assertEqual([(c.code, c.rank) for c in result.candidates], [('010121', 4)])
        self.assertEqual([r['reason'] for r in result.metadata['rejected_candidates']],
                         ['invalid_format', 'unknown_code', 'ineligible_code', 'duplicate', 'beyond_top_k'])
        self.assertEqual(self.predict(response(['000000'])).status, 'error')

    def test_abstention_missing_info_and_refusal(self):
        self.assertEqual(self.predict(response([], 'abstained')).status, 'abstained')
        result = self.predict(response([], 'needs_info', ['What material?']))
        self.assertEqual(result.missing_information, ['What material?'])
        self.assertEqual(result.status, 'needs_info')
        raw = response()
        raw['output'][0]['content'] = [{'type': 'refusal', 'refusal': 'Refused'}]
        self.assertEqual(self.predict(raw).status, 'abstained')

    def test_incomplete_malformed_and_inconsistent(self):
        raw = response()
        raw['status'] = 'incomplete'
        malformed = response()
        malformed['output'][0]['content'][0]['text'] = 'not JSON'
        for item in [raw, malformed, response([], 'ok'), response([], 'needs_info'), response(['010121'], 'abstained')]:
            with self.subTest(item=item):
                result = self.predict(item)
                self.assertEqual(result.status, 'error')
                self.assertEqual(result.metadata['raw_response'], item)

    def test_invalid_input_never_calls_provider(self):
        provider = FakeProvider()
        approach = LLMDirect(provider, ModelConfig())
        self.assertEqual(approach.predict('', 3, self.context).status, 'error')
        self.assertEqual(approach.predict('horses', 0, self.context).status, 'error')
        self.assertEqual(provider.calls, [])

    def test_comparison_continues_on_provider_error(self):
        class Alternating(FakeProvider):
            def generate(self, **kwargs):
                if kwargs['config'].model == 'bad':
                    raise ProviderError('http_error', 'Simulated', 429)
                return response()
        run = run_prediction('horses', 3, self.context, [ModelConfig(model='bad'), ModelConfig(model='good')], Alternating())
        self.assertEqual([p['status'] for p in run['predictions']], ['error', 'ok'])
        self.assertEqual(run['catalog']['sha256'], self.context.catalog.sha256)

    def test_cli_json_and_run(self):
        with tempfile.TemporaryDirectory() as tmp, patch.dict(os.environ, {}, clear=True):
            stdout, stderr = io.StringIO(), io.StringIO()
            with patch('hs_matching.providers.openai.OpenAIProvider.generate', return_value=response()), redirect_stdout(stdout), redirect_stderr(stderr):
                code = main(['predict', 'horses', '--catalog', str(self.context.catalog.path),
                             '--env-file', tmp + '/absent', '--runs-dir', tmp, '--json',
                             '--model', 'first', '--model', 'second'])
            self.assertEqual(code, 0)
            run = json.loads(stdout.getvalue())
            self.assertEqual(len(run['predictions']), 2)
            saved = json.loads(next(Path(tmp).glob('*.json')).read_text())
            self.assertEqual(saved, run)

    def test_placeholder_blocks_network_and_cli_returns_error(self):
        with tempfile.TemporaryDirectory() as tmp, patch.dict(os.environ, {'OPENAI_API_KEY': 'YOUR_OPENAI_API_KEY_HERE'}, clear=True):
            with patch('hs_matching.providers.openai.urlopen') as network, redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()):
                code = main(['predict', 'horses', '--catalog', str(self.context.catalog.path),
                             '--env-file', tmp + '/absent', '--runs-dir', tmp])
            self.assertEqual(code, 1)
            network.assert_not_called()
            self.assertEqual(json.loads(next(Path(tmp).glob('*.json')).read_text())['predictions'][0]['error']['kind'], 'configuration')

    def test_env_precedence(self):
        with tempfile.TemporaryDirectory() as tmp, patch.dict(os.environ, {'OPENAI_API_KEY': 'environment'}, clear=True):
            path = Path(tmp) / '.env'
            path.write_text('OPENAI_API_KEY=file\nOPENAI_MODEL="test-model"\n')
            load_env(path)
            self.assertEqual(os.environ['OPENAI_API_KEY'], 'environment')
            self.assertEqual(os.environ['OPENAI_MODEL'], 'test-model')

    def test_transport_payload_and_errors(self):
        provider = OpenAIProvider('fake-key')
        with patch('hs_matching.providers.openai.urlopen') as network:
            network.return_value.__enter__.return_value = io.StringIO(json.dumps(response()))
            provider.generate(instructions='instructions', user_input='product', schema={}, config=ModelConfig())
            request = network.call_args.args[0]
            payload = json.loads(request.data)
            self.assertEqual(request.full_url, 'https://api.openai.com/v1/responses')
            self.assertTrue(payload['text']['format']['strict'])
            self.assertFalse(payload['store'])
            self.assertNotIn('temperature', payload)
            self.assertNotIn('reasoning', payload)
        error = HTTPError('https://api.openai.com', 401, 'fake-key', {'x-request-id': 'req_1'}, None)
        with patch('hs_matching.providers.openai.urlopen', side_effect=error), self.assertRaises(ProviderError) as caught:
            provider.generate(instructions='', user_input='', schema={}, config=ModelConfig())
        self.assertEqual(caught.exception.details['status_code'], 401)
        self.assertNotIn('fake-key', str(caught.exception))
