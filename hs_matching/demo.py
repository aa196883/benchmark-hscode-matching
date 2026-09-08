"""Fixtures de présentation : aucun catalogue, index ou fournisseur requis."""
from hs_matching.approaches.base import Candidate, Prediction

DEMO_QUERY = 'Short-sleeved knitted T-shirts, made of 100% cotton, for adults.'
DEMO_DURATIONS = {'llm_direct': 2.84, 'embeddings': 0.46, 'rag': 3.72}


def demo_run(approach, options):
    descriptions = {
        '610910': 'T-shirts, singlets and other vests; of cotton, knitted or crocheted',
        '610990': 'T-shirts, singlets and other vests; of textile materials other than cotton, knitted or crocheted',
        '611020': 'Jerseys, pullovers, cardigans, waistcoats and similar articles; of cotton, knitted or crocheted',
    }
    codes = ['610910', '610990', '611020']
    explanations = {
        '610910': 'The product is explicitly described as a knitted T-shirt made entirely of cotton. Both its construction and material match this description.',
        '610990': 'The garment type matches, but the material does not: the product specifies cotton rather than other textile fibres.',
        '611020': 'The cotton composition and knitted construction match, but this heading describes pullovers and similar garments rather than T-shirts.',
    }
    scores = [0.894, 0.812, 0.746]
    if approach == 'rag':
        codes = codes[:2]
    candidates = [Candidate(code, rank, descriptions[code],
                            score=scores[rank - 1] if approach == 'embeddings' else None,
                            score_type='cosine_similarity' if approach == 'embeddings' else 'none',
                            explanation=None if approach == 'embeddings' else explanations[code])
                  for rank, code in enumerate(codes[:options['top_k']], 1)]
    metadata = {'approach': approach, 'config': {'model': 'text-embedding-3-small' if approach == 'embeddings' else options['model']},
                'duration_seconds': DEMO_DURATIONS[approach], 'total_duration_seconds': DEMO_DURATIONS[approach],
                'retrieval_k': options['retrieval_k'], 'demo': True}
    if approach == 'rag':
        metadata['retrieved_candidates'] = [{'code': c, 'rank': rank, 'score': scores[rank - 1]}
                                            for rank, c in enumerate(descriptions, 1)]
    prediction = Prediction('ok', candidates=candidates, metadata=metadata)
    if options.get('scenario') == 'states':
        if approach == 'llm_direct':
            prediction.status = 'needs_info'
            prediction.missing_information = ['Is the fabric knitted or woven?', 'What is the fibre composition?']
        elif approach == 'embeddings':
            prediction.status, prediction.candidates = 'error', []
            prediction.error = {'kind': 'demo_error', 'message': 'Index indisponible. Vérifiez le dossier d’embeddings configuré.'}
        else:
            prediction.status, prediction.candidates = 'abstained', []
    return {'predictions': [prediction.to_dict()], 'demo': True}
