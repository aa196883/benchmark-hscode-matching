from hs_matching.approaches.llm_direct import LLMDirect

REGISTRY = {'llm_direct': LLMDirect}


def create_approach(name, *, provider, config):
    try:
        factory = REGISTRY[name]
    except KeyError:
        raise ValueError(f'Approche inconnue : {name}') from None
    return factory(provider=provider, config=config)
