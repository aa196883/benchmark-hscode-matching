from hs_matching.approaches.llm_direct import LLMDirect
from hs_matching.approaches.embeddings import Embeddings
from hs_matching.approaches.rag import RAG

REGISTRY = {'llm_direct': LLMDirect, 'embeddings': Embeddings, 'rag': RAG}


def create_approach(name, **dependencies):
    try:
        factory = REGISTRY[name]
    except KeyError:
        raise ValueError(f'Approche inconnue : {name}') from None
    return factory(**dependencies)
