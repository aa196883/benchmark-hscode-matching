"""Compare the reference implementation and the packaged Java RAG on the full index, without API calls."""
from pathlib import Path
import argparse
import json
import subprocess
import sys
import tempfile
from unittest.mock import Mock

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from hs_matching.approaches.base import PredictionContext
from hs_matching.approaches.rag import RAG
from hs_matching.catalog import Catalog
from hs_matching.embedding_index import EmbeddingIndex, EmbeddingRetriever
from hs_matching.providers.openai import ModelConfig
from hs_matching.vectorization.base import EmbeddingBatch


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--catalog', type=Path, default=ROOT/'data/processed/h6_2022/catalog.jsonl')
    parser.add_argument('--index', type=Path, default=ROOT/'artifacts/embeddings/h6_2022')
    parser.add_argument('--jar', type=Path, default=ROOT/'sandbox_java/simplified_env/runner/target/sandbox.jar')
    parser.add_argument("--classpath", action="store_true", help="Load the packaged Java index without file-system paths")
    args = parser.parse_args()
    catalog = Catalog(args.catalog)
    index = EmbeddingIndex(args.index, catalog)
    vectorizer = Mock(); vectorizer.provider = 'openai'; vectorizer.config = index.config
    retriever = EmbeddingRetriever(index, vectorizer)
    work = ROOT/'sandbox_java/simplified_env/.cache'
    work.mkdir(exist_ok=True)
    reports = []
    with tempfile.TemporaryDirectory(prefix='parity-', dir=work) as folder:
        folder = Path(folder)
        for position in (0, len(index.codes)//3, 2*len(index.codes)//3, len(index.codes)-1):
            # Replayed query vector is a known index row, not a freshly computed text embedding.
            vector = index.matrix[position].astype(float).tolist()
            vectorizer.embed.return_value = EmbeddingBatch([vector], index.manifest['response_model'])
            hits, _ = retriever.retrieve('offline parity query', 20)
            selected = [hits[1].code, hits[0].code]
            answer = {'status':'ok','missing_information':[], 'candidates':[
                {'code':code,'explanation':'Offline parity fixture'} for code in selected]}
            raw = {'status':'completed','model':'fixture', 'output':[{'type':'message','content':[
                {'type':'output_text','text':json.dumps(answer)}]}]}
            provider = Mock(); provider.name = 'openai'; provider.generate.return_value = raw
            py = RAG(provider, ModelConfig(), retriever, 20).predict('offline parity query', 5, PredictionContext(catalog))
            (folder/'query.json').write_text(json.dumps(vector))
            (folder/'response.json').write_text(json.dumps(answer))
            command = ['java','-jar',str(args.jar.resolve()),'rag-replay',str(args.catalog.resolve()),str(args.index.resolve()),
                       str(folder/'query.json'),str(folder/'response.json'),'offline parity query','5','20']
            if args.classpath:
                command = ['java', '-jar', str(args.jar.resolve()), 'rag-replay-classpath',
                           str(folder/'query.json'), str(folder/'response.json'), 'offline parity query', '5', '20']
            completed = subprocess.run(command, check=True, text=True, capture_output=True, cwd=folder, timeout=90)
            output = json.loads(completed.stdout); java = output['prediction']
            assert java['status'] == py.status == 'ok'
            if args.classpath:
                assert java['metadata']['index_path'] == 'classpath:/com/semsoft/lestr/tradeanalysis/infra/service/analysis/ia/h6_2022/'
            assert [(c['code'],c['rank'],c['explanation']) for c in java['candidates']] == [(c.code,c.rank,c.explanation) for c in py.candidates]
            java_hits = java['metadata']['retrieved_candidates']
            assert [h['code'] for h in java_hits] == [h.code for h in hits], f'Retrieval order differs for position {position}'
            max_error = max(abs(j['score']-p.score) for j,p in zip(java_hits,hits))
            assert max_error <= 2e-6, max_error
            assert java['metadata']['prompt']['instructions'] == py.metadata['prompt']['instructions']
            assert java['metadata']['prompt']['schema'] == py.metadata['prompt']['schema']
            assert json.loads(java['metadata']['prompt']['input']) == json.loads(py.metadata['prompt']['input'])
            assert all(c['score'] == 2.5 for c in java['candidates'])
            assert [c['code'] for c in output['search_result']['matchingHSCodes']] == selected
            assert all(c['score'] == 3 for c in output['search_result']['matchingHSCodes'])
            reports.append({'query_index_code':index.codes[position], 'retrieval_k':20, 'same_order':True, 'max_cosine_error':max_error})
    report = {'index_count':len(index.codes), 'dimensions':index.manifest['dimensions'],
              'vectors_sha256':index.manifest['vectors_sha256'], 'api_calls':0, 'resource_mode':'classpath' if args.classpath else 'filesystem', 'cases':reports}
    target = ROOT/'sandbox_java/simplified_env/extension/target/full-index-parity.json'
    target.parent.mkdir(parents=True,exist_ok=True)
    target.write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report,indent=2))

if __name__ == '__main__':
    main()
