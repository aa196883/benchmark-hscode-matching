"""Adaptateurs de présentation : données + fragment Jinja propre à l'approche."""
APPROACHES = {
    'llm_direct': {'title': 'LLM direct', 'subtitle': 'Connaissances du modèle', 'symbol': '01'},
    'embeddings': {'title': 'Embeddings', 'subtitle': 'Proximité des descriptions', 'symbol': '02'},
    'rag': {'title': 'RAG', 'subtitle': 'Recherche puis reclassement', 'symbol': '03'},
}
STATUS_LABELS = {'ok': 'Terminé', 'needs_info': 'À préciser', 'abstained': 'Abstention', 'error': 'Erreur'}


def direct_card(candidate, metadata):
    return {'template': 'cards/llm_direct.html', 'candidate': candidate}


def embedding_card(candidate, metadata):
    score = candidate.get('score')
    return {'template': 'cards/embeddings.html', 'candidate': candidate,
            'meter': max(0, min(100, (score or 0) * 100))}


def rag_card(candidate, metadata):
    retrieved = next((r for r in metadata.get('retrieved_candidates', []) if r['code'] == candidate['code']), None)
    return {'template': 'cards/rag.html', 'candidate': candidate, 'retrieved': retrieved}


CARD_ADAPTERS = {'llm_direct': direct_card, 'embeddings': embedding_card, 'rag': rag_card}


def present(run):
    prediction = run['predictions'][0]
    metadata = prediction['metadata']
    approach = metadata['approach']
    return {**APPROACHES[approach], 'approach': approach, 'prediction': prediction,
            'status_label': STATUS_LABELS[prediction['status']],
            'duration': metadata['total_duration_seconds'],
            'model': metadata.get('config', {}).get('model', 'Indisponible'),
            'cards': [CARD_ADAPTERS[approach](c, metadata) for c in prediction['candidates']]}
