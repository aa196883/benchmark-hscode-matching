"""Adaptation du moteur commun aux runs de benchmark."""
import os
from hs_matching.approaches.base import PredictionContext
from hs_matching.approaches.registry import create_approach
from hs_matching.catalog import Catalog
from hs_matching.providers.openai import ModelConfig, OpenAIProvider
from hs_matching.providers.qwen import QWEN_MODEL, QwenProvider


def build_approach(args):
    context = PredictionContext(Catalog(args.catalog))
    dependencies = {}
    if args.approach in ('embeddings', 'rag'):
        from hs_matching.embedding_index import EmbeddingIndex, EmbeddingRetriever
        from hs_matching.vectorization.openai import OpenAIVectorizer
        index = EmbeddingIndex(args.index, context.catalog)
        if index.manifest['provider'] != 'openai':
            raise ValueError('Fournisseur de vectorisation non pris en charge par la CLI')
        vectorizer = OpenAIVectorizer(os.environ.get('OPENAI_API_KEY'), index.config, timeout=args.timeout)
        dependencies['retriever'] = EmbeddingRetriever(index, vectorizer)
    if args.approach != 'embeddings':
        config = ModelConfig(model=QWEN_MODEL if args.model == 'qwen3' else args.model,
                             max_output_tokens=args.max_output_tokens, temperature=args.temperature,
                             reasoning_effort=args.reasoning_effort, timeout=args.timeout)
        provider = (QwenProvider(os.environ.get('LOCAL_QWEN_KEY'),
                                os.environ.get('QWEN_BASE_URL', 'http://localhost:8000/v1'))
                    if config.model == QWEN_MODEL else OpenAIProvider(os.environ.get('OPENAI_API_KEY')))
        dependencies.update(provider=provider, config=config)
    if args.approach == 'rag':
        dependencies['retrieval_k'] = args.retrieval_k
    return create_approach(args.approach, **dependencies), context

