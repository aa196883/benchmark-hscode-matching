"""Adaptateur Qwen Chat Completions via un serveur exposé par tunnel SSH."""
import json
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import Request, urlopen

from hs_matching.providers.openai import ProviderError

QWEN_MODEL = 'Qwen/Qwen3-VL-4B-Instruct-FP8'


class QwenProvider:
    name = 'qwen'

    def __init__(self, api_key: str | None, base_url: str = 'http://localhost:8000/v1'):
        self._api_key = api_key
        self._base_url = base_url.rstrip('/')

    def generate(self, *, instructions, user_input, schema, config):
        if not self._api_key or not self._api_key.strip() or self._api_key == 'YOUR_LOCAL_QWEN_KEY_HERE':
            raise ProviderError('configuration', 'Définissez LOCAL_QWEN_KEY dans .env.')
        url = urlsplit(self._base_url)
        if url.scheme not in ('http', 'https') or not url.hostname or url.username or url.password or url.query or url.fragment:
            raise ProviderError('configuration', 'QWEN_BASE_URL doit être une URL HTTP(S) sans identifiants, query ni fragment.')
        if config.reasoning_effort is not None:
            raise ProviderError('configuration', '--reasoning-effort n’est pas pris en charge par le provider Qwen.')
        payload = {
            'model': config.model,
            'messages': [{'role': 'system', 'content': instructions},
                         {'role': 'user', 'content': user_input}],
            'max_tokens': config.max_output_tokens,
            'response_format': {'type': 'json_schema', 'json_schema': {
                'name': 'hs_prediction', 'strict': True, 'schema': schema}},
        }
        if config.temperature is not None:
            payload['temperature'] = config.temperature
        try:
            request = Request(self._base_url + '/chat/completions',
                              data=json.dumps(payload).encode('utf-8'), method='POST',
                              headers={'Authorization': 'Bearer ' + self._api_key,
                                       'Content-Type': 'application/json'})
            with urlopen(request, timeout=config.timeout) as response:
                raw = json.load(response)
        except HTTPError as exc:
            raise ProviderError('http_error', 'Appel Qwen refusé ; vérifier modèle, clé, paramètres et support du schéma JSON.',
                                exc.code, exc.headers.get('x-request-id')) from None
        except (URLError, TimeoutError, OSError):
            raise ProviderError('connection_error', 'Connexion Qwen impossible ou délai dépassé ; vérifier le tunnel SSH et QWEN_BASE_URL.') from None
        except (ValueError, UnicodeError):
            raise ProviderError('invalid_response', 'Réponse HTTP Qwen non JSON ou configuration HTTP invalide.') from None
        try:
            choices = raw['choices']
            if not isinstance(choices, list) or len(choices) != 1:
                raise ValueError
            choice = choices[0]
            if choice['finish_reason'] != 'stop':
                raise ProviderError('incomplete_response', 'Réponse Qwen non terminée ; vérifier la limite de tokens et les paramètres.')
            content = choice['message']['content']
            if not isinstance(content, str) or not content.strip():
                raise ValueError
            # Enveloppe commune attendue par les approches ; conserver aussi la réponse native.
            return {'id': raw.get('id'), 'model': raw.get('model'), 'status': 'completed',
                    'usage': raw.get('usage'), 'provider_response': raw,
                    'output': [{'type': 'message', 'content': [{'type': 'output_text', 'text': content}]}]}
        except (KeyError, TypeError, ValueError, AttributeError):
            raise ProviderError('invalid_response', 'Réponse Qwen mal formée ou contenu absent.') from None
