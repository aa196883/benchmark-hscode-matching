from time import perf_counter

from hs_matching.approaches.base import Candidate, Prediction
from hs_matching.embedding_index import EmbeddingRetriever
from hs_matching.providers.openai import ProviderError


class Embeddings:
    def __init__(self, retriever: EmbeddingRetriever):
        self.retriever = retriever

    def predict(self, query, top_k, context):
        start = perf_counter()
        index = self.retriever.index
        metadata = {'approach': 'embeddings', 'provider': index.manifest['provider'],
                    'config': index.config.to_dict(), 'edition': context.edition, 'language': context.language,
                    'top_k': top_k, 'catalog_sha256': context.catalog.sha256,
                    'index_path': str(index.directory), 'index_manifest': index.manifest}
        prediction = Prediction(status='error', metadata=metadata)
        try:
            if context.edition != '2022' or context.language != 'en' or context.catalog.sha256 != index.catalog.sha256:
                raise ValueError('Le contexte de prédiction ne correspond pas au catalogue de l’index chargé')
            hits, retrieval_metadata = self.retriever.retrieve(query, top_k)
            metadata.update(retrieval_metadata)
            prediction.candidates = [Candidate(code=hit.code, rank=hit.rank, description=hit.description,
                                               score=hit.score, score_type='cosine_similarity') for hit in hits]
            prediction.status = 'ok'
        except ProviderError as exc:
            prediction.error = exc.details
        except (ValueError, TypeError, KeyError) as exc:
            prediction.error = {'kind': 'validation_error', 'message': str(exc)}
        finally:
            metadata['duration_seconds'] = perf_counter() - start
        return prediction
