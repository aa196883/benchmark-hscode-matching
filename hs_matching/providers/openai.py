"""Adaptateur Responses API, bibliothèque standard uniquement."""
from dataclasses import asdict, dataclass
import json
import math
from typing import Protocol
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


@dataclass(frozen=True)
class ModelConfig:
    model: str = 'gpt-4.1-mini'
    max_output_tokens: int = 2048
    temperature: float | None = None
    reasoning_effort: str | None = None
    timeout: float = 60.0

    def __post_init__(self):
        if not self.model.strip() or self.max_output_tokens < 1 or not math.isfinite(self.timeout) or self.timeout <= 0:
            raise ValueError('Modèle, limite de tokens ou délai invalide')
        if self.temperature is not None and (not math.isfinite(self.temperature) or not 0 <= self.temperature <= 2):
            raise ValueError('La température doit être comprise entre 0 et 2')

    def to_dict(self):
        return asdict(self)


class ProviderError(Exception):
    def __init__(self, kind, message, status_code=None, request_id=None):
        super().__init__(message)
        self.details = dict(kind=kind, message=message, status_code=status_code, request_id=request_id)


class LLMProvider(Protocol):
    name: str

    def generate(self, *, instructions: str, user_input: str, schema: dict, config: ModelConfig) -> dict: ...


class OpenAIProvider:
    name = 'openai'

    def __init__(self, api_key: str | None):
        self._api_key = api_key

    def generate(self, *, instructions, user_input, schema, config):
        if not self._api_key or self._api_key.strip() == 'YOUR_OPENAI_API_KEY_HERE':
            raise ProviderError('configuration', 'Remplacez le placeholder OPENAI_API_KEY dans .env.')
        payload = {
            'model': config.model, 'instructions': instructions, 'input': user_input,
            'max_output_tokens': config.max_output_tokens, 'store': False,
            'text': {'format': {'type': 'json_schema', 'name': 'hs_prediction', 'strict': True, 'schema': schema}},
        }
        if config.temperature is not None:
            payload['temperature'] = config.temperature
        if config.reasoning_effort is not None:
            payload['reasoning'] = {'effort': config.reasoning_effort}
        request = Request('https://api.openai.com/v1/responses',
                          data=json.dumps(payload).encode('utf-8'), method='POST',
                          headers={'Authorization': 'Bearer ' + self._api_key, 'Content-Type': 'application/json'})
        try:
            with urlopen(request, timeout=config.timeout) as response:
                return json.load(response)
        except HTTPError as exc:
            # Ne pas journaliser les headers ni un corps d'erreur susceptible de contenir la clé.
            raise ProviderError('http_error', 'Appel OpenAI refusé ; vérifier modèle, paramètres et accès.',
                                exc.code, exc.headers.get('x-request-id')) from None
        except (URLError, TimeoutError, OSError):
            raise ProviderError('connection_error', 'Connexion OpenAI impossible ou délai dépassé.') from None
        except (ValueError, UnicodeError):
            raise ProviderError('invalid_response', 'Réponse HTTP non JSON.') from None
