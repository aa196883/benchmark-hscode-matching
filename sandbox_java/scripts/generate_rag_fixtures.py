"""Regenerate portable Java business cases (JSON text contract) using the reference RAG. No network calls."""
from pathlib import Path
import json
import sys
import tempfile
from unittest.mock import Mock

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from hs_matching.approaches.base import PredictionContext
from hs_matching.approaches.rag import RAG
from hs_matching.catalog import Catalog
from hs_matching.embedding_index import build_index, EmbeddingIndex, EmbeddingRetriever
from hs_matching.providers.openai import ModelConfig
from hs_matching.vectorization.base import EmbeddingConfig, EmbeddingBatch

OUT = ROOT / 'sandbox_java/simplified_env/extension/src/test/resources/rag-fixtures'
MODEL = 'text-embedding-3-small'

def envelope(codes=(), status='ok', missing=(), explanation='Supported by supplied description'):
    return {'status': 'completed', 'model': 'test-llm', 'usage': {'total_tokens': 42},
            'output': [{'type': 'message', 'content': [{'type': 'output_text', 'text': json.dumps({
                'status': status, 'missing_information': list(missing),
                'candidates': [{'code': c, 'explanation': explanation} for c in codes]})}]}]}

def main():
    OUT.mkdir(parents=True, exist_ok=True)
    rows = [{'code': c, 'description': text, 'contextual_description': 'Animals > ' + text,
             'edition': '2022', 'language': 'en', 'level': 6, 'is_candidate': True}
            for c, text in [('010121', 'Breeding horses'), ('010129', 'Other horses'), ('010130', 'Asses')]]
    catalog_rows = rows + [dict(rows[0], code='990000', is_special=True), dict(rows[0], code='01', level=2, is_candidate=False)]
    (OUT / 'catalog.jsonl').write_text('\n'.join(json.dumps(r, ensure_ascii=False, indent=2) for r in catalog_rows)+'\n')
    vectorizer = Mock()
    vectorizer.provider = 'openai'
    vectorizer.config = EmbeddingConfig(MODEL)
    vectorizer.embed.return_value = EmbeddingBatch([[1., 0.], [0.9, 0.1], [-1., 0.]], MODEL)
    with tempfile.TemporaryDirectory(dir=OUT) as directory:
        source = Path(directory) / 'candidates.jsonl'
        source.write_text('\n'.join(json.dumps(r) for r in rows)+'\n')
        index_path = Path(directory) / 'index'
        build_index(source, index_path, vectorizer)
        manifest = json.loads((index_path/'manifest.json').read_text())
        manifest['created_at'] = '2026-09-30T00:00:00+00:00'
        manifest['duration_seconds'] = 0
        manifest['source']['path'] = 'candidates.jsonl'
        (OUT/'manifest.json').write_text(json.dumps(manifest, indent=2)+'\n')
        (OUT/'vectors.jsonl').write_bytes((index_path/'vectors.jsonl').read_bytes())
    vectorizer.embed.return_value = EmbeddingBatch([[1., 0.]], MODEL, {'total_tokens': 2})
    catalog = Catalog(OUT/'catalog.jsonl')
    index = EmbeddingIndex(OUT, catalog)
    retriever = EmbeddingRetriever(index, vectorizer)
    cases = []
    def add(name, raw, top_k=2, retrieval_k=2, query='Live horses'):
        provider = Mock(); provider.name = 'openai'; provider.generate.return_value = raw
        vectorizer.embed.reset_mock()
        result = RAG(provider, ModelConfig(model='test-llm'), retriever, retrieval_k).predict(query, top_k, PredictionContext(catalog))
        expected = result.to_dict()
        metadata = expected['metadata']
        expected['metadata'] = {key: metadata[key] for key in ('rejected_candidates', 'model_status', 'prompt', 'retrieved_candidates') if key in metadata}
        if expected['error']:
            expected['error'] = {'kind': expected['error']['kind']}
        cases.append(dict(name=name, response=raw['output'][0]['content'][0]['text'], query=query, top_k=top_k, retrieval_k=retrieval_k,
                          embedding_calls=vectorizer.embed.call_count, generation_calls=provider.generate.call_count, expected=expected))
    add('llm_order', envelope(['010129', '010121']))
    add('null_explanation', envelope(['010121'], explanation=None))
    add('needs_info_with_candidates', envelope(['010129'], 'needs_info', ['Intended use?']))
    add('needs_info_without_candidates', envelope([], 'needs_info', ['Intended use?']))
    add('abstained', envelope([], 'abstained'))
    add('abstained_with_questions', envelope([], 'abstained', ['Use?']))
    add('outside_retrieval_preserves_rank', envelope(['010130', '010121']))
    add('all_outside_retrieval', envelope(['010130']))
    add('duplicates_and_excess', envelope(['010121', '010121', '010129']))
    add('no_backfill_after_rejection', envelope(['bad', '010121', '010129']))
    add('unknown_code', envelope(['888888', '010121']))
    add('ineligible_code', envelope(['990000', '010121']))
    add('invalid_short_code', envelope(['01', '010121']))
    add('all_rejected_needs_info', envelope(['010130'], 'needs_info', ['Use?']))
    add('k_larger_than_catalogue', envelope(['010130']), retrieval_k=20)
    (OUT/'cases.json').write_text(json.dumps(cases, indent=2, ensure_ascii=False)+'\n')
    (OUT/'query.json').write_text('[1.0,0.0]\n')
    (OUT/'response.json').write_text(json.dumps(json.loads(envelope(['010129', '010121'])['output'][0]['content'][0]['text']),indent=2)+'\n')
    print(f'{len(cases)} reference cases written to {OUT}')

if __name__ == '__main__':
    main()
