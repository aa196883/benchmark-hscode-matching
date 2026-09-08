"""Interface indépendante du fournisseur pour vectoriser documents et requêtes."""
from hs_matching.vectorization.base import EmbeddingBatch, EmbeddingConfig, Vectorizer

__all__ = ['EmbeddingBatch', 'EmbeddingConfig', 'Vectorizer']
