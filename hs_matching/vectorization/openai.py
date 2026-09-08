"""Seul module connaissant le protocole HTTP de vectorisation OpenAI."""
import json
import math
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

from hs_matching.providers.openai import ProviderError
from hs_matching.vectorization.base import EmbeddingBatch, EmbeddingConfig, validate_vectors


class OpenAIVectorizer:
    provider = 'openai'

    def __init__(self, api_key: str | None, config: EmbeddingConfig, timeout=60.0):
        if not math.isfinite(timeout) or timeout <= 0:
            raise ValueError('Délai de vectorisation invalide')
        self._api_key, self.config, self.timeout = api_key, config, timeout

    def embed(self, texts: list[str]) -> EmbeddingBatch:
        if not isinstance(texts, list) or not 1 <= len(texts) <= 2048:
            raise ValueError('Un lot doit contenir entre 1 et 2048 textes')
        if any(not isinstance(text, str) or not text.strip() for text in texts):
            raise ValueError('Les textes à vectoriser doivent être non vides')
        if not self._api_key or self._api_key.strip() == 'YOUR_OPENAI_API_KEY_HERE':
            raise ProviderError('configuration', 'Remplacez le placeholder OPENAI_API_KEY dans .env.')
        payload = {'model': self.config.model, 'input': texts, 'encoding_format': 'float'}
        if self.config.dimensions is not None:
            payload['dimensions'] = self.config.dimensions
        request = Request('https://api.openai.com/v1/embeddings', method='POST',
                          data=json.dumps(payload).encode('utf-8'),
                          headers={'Authorization': 'Bearer ' + self._api_key, 'Content-Type': 'application/json'})
        try:
            with urlopen(request, timeout=self.timeout) as response:
                raw = json.load(response)
        except HTTPError as exc:
            raise ProviderError('http_error', 'Vectorisation OpenAI refusée ; vérifier modèle, limites et accès.',
                                exc.code, exc.headers.get('x-request-id')) from None
        except (URLError, TimeoutError, OSError):
            raise ProviderError('connection_error', 'Connexion OpenAI impossible ou délai dépassé.') from None
        except (ValueError, UnicodeError):
            raise ProviderError('invalid_response', 'Réponse de vectorisation non JSON.') from None
        try:
            data = raw['data']
            if not isinstance(data, list) or len(data) != len(texts):
                raise ValueError('Nombre de résultats incohérent')
            indices = [item['index'] for item in data]
            if any(type(i) is not int for i in indices) or sorted(indices) != list(range(len(texts))):
                raise ValueError('Indices de vectorisation incohérents')
            vectors = [item['embedding'] for item in sorted(data, key=lambda item: item['index'])]
            validate_vectors(vectors, len(texts), self.config.dimensions)
            if not isinstance(raw['model'], str) or not raw['model']:
                raise ValueError('Modèle de réponse manquant')
            return EmbeddingBatch(vectors, raw['model'], raw.get('usage', {}))
        except (KeyError, TypeError, ValueError):
            raise ProviderError('invalid_response', 'Réponse de vectorisation invalide.') from None
