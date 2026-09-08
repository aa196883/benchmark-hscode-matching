"""Préparation des ressources et appel du moteur commun à la CLI."""
import os
from pathlib import Path
from threading import Lock

from hs_matching.approaches.base import PredictionContext
from hs_matching.catalog import Catalog
from hs_matching.experiments import run_prediction
from hs_matching.providers.openai import ModelConfig, OpenAIProvider


class WebRunner:
    def __init__(self, catalog_path, index_path):
        self.catalog_path, self.index_path = Path(catalog_path), Path(index_path)
        self._catalog_key = self._index_key = None
        self._catalog = self._index = None
        self._lock = Lock()

    @staticmethod
    def stamp(path):
        stat = path.stat()
        return (stat.st_mtime_ns, stat.st_size)

    def resources(self, needs_index):
        with self._lock:
            key = self.stamp(self.catalog_path)
            if key != self._catalog_key:
                self._catalog = Catalog(self.catalog_path)
                self._catalog_key, self._index_key = key, None
            if needs_index:
                from hs_matching.embedding_index import EmbeddingIndex
                index_key = (key, self.stamp(self.index_path / 'manifest.json'), self.stamp(self.index_path / 'vectors.jsonl'))
                if index_key != self._index_key:
                    self._index = EmbeddingIndex(self.index_path, self._catalog)
                    self._index_key = index_key
            return self._catalog, self._index

    def __call__(self, approach, options):
        catalog, index = self.resources(approach in ('embeddings', 'rag'))
        retriever = None
        if approach in ('embeddings', 'rag'):
            from hs_matching.embedding_index import EmbeddingRetriever
            from hs_matching.vectorization.openai import OpenAIVectorizer
            if index.manifest['provider'] != 'openai':
                raise ValueError('Fournisseur d’embeddings non pris en charge.')
            retriever = EmbeddingRetriever(index, OpenAIVectorizer(os.environ.get('OPENAI_API_KEY'), index.config, options['timeout']))
        config = index.config if approach == 'embeddings' else ModelConfig(
            model=options['model'], max_output_tokens=options['max_output_tokens'],
            temperature=options['temperature'], reasoning_effort=options['reasoning_effort'], timeout=options['timeout'])
        return run_prediction(options['query'], options['top_k'], PredictionContext(catalog), [config],
                              OpenAIProvider(os.environ.get('OPENAI_API_KEY')), approach,
                              retriever=retriever, retrieval_k=options['retrieval_k'])
