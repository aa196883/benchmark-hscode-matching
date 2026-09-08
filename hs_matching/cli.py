import argparse
import json
import os
import sys

from hs_matching.approaches.base import PredictionContext
from hs_matching.approaches.registry import REGISTRY
from hs_matching.config import load_env
from hs_matching.catalog import Catalog
from hs_matching.experiments import run_prediction, save_run
from hs_matching.providers.openai import ModelConfig, OpenAIProvider


def main(argv=None):
    parser = argparse.ArgumentParser(prog='hs-matching', description='Correspondance description EN → HS 2022')
    sub = parser.add_subparsers(dest='command', required=True)
    sub.add_parser('approaches', help='Lister les approches disponibles')
    predict = sub.add_parser('predict', help='Proposer des codes et sauvegarder un run')
    predict.add_argument('query', help='Description anglaise ; - pour lire stdin')
    predict.add_argument('--approach', choices=sorted(REGISTRY), default='llm_direct')
    predict.add_argument('--index', default='artifacts/embeddings/h6_2022', help='Index précalculé pour embeddings ou RAG')
    predict.add_argument('--model', action='append', help='Répétable pour comparer plusieurs modèles')
    predict.add_argument('--retrieval-k', type=int, help='Nombre de voisins pour RAG (défaut : 20, doit être >= top-k)')
    predict.add_argument('--top-k', type=int, default=5)
    predict.add_argument('--catalog', default='data/processed/h6_2022/catalog.jsonl')
    predict.add_argument('--env-file', default='.env')
    predict.add_argument('--max-output-tokens', type=int, default=2048)
    predict.add_argument('--temperature', type=float)
    predict.add_argument('--reasoning-effort', help='Valeur acceptée par le modèle choisi')
    predict.add_argument('--timeout', type=float, default=60.0)
    predict.add_argument('--runs-dir', default='runs')
    predict.add_argument('--json', action='store_true', help='Résultat complet sur stdout')
    args = parser.parse_args(argv)
    if args.command == 'approaches':
        print('\n'.join(sorted(REGISTRY)))
        return 0
    try:
        load_env(args.env_file)
        query = sys.stdin.read() if args.query == '-' else args.query
        if not query.strip() or args.top_k < 1:
            raise ValueError('Description non vide et --top-k positif requis')
        if args.retrieval_k is not None and args.approach != 'rag':
            raise ValueError('--retrieval-k est réservé au RAG')
        retrieval_k = args.retrieval_k if args.retrieval_k is not None else 20
        if args.approach == 'rag' and retrieval_k < args.top_k:
            raise ValueError('--retrieval-k doit être supérieur ou égal à --top-k')
        context = PredictionContext(Catalog(args.catalog))
        retriever = None
        if args.approach == 'embeddings':
            if args.model or args.temperature is not None or args.reasoning_effort is not None:
                raise ValueError('Embeddings : le modèle vient de --index ; les paramètres de génération LLM ne s’appliquent pas.')
        if args.approach in ('embeddings', 'rag'):
            from hs_matching.embedding_index import EmbeddingIndex, EmbeddingRetriever
            from hs_matching.vectorization.openai import OpenAIVectorizer
            index = EmbeddingIndex(args.index, context.catalog)
            if index.manifest['provider'] != 'openai':
                raise ValueError('Fournisseur de vectorisation non pris en charge par la CLI')
            vectorizer = OpenAIVectorizer(os.environ.get('OPENAI_API_KEY'), index.config, timeout=args.timeout)
            retriever = EmbeddingRetriever(index, vectorizer)
        if args.approach == 'embeddings':
            run = run_prediction(query, args.top_k, context, [index.config], None,
                                 args.approach, retriever=retriever)
        else:
            models = args.model or [os.environ.get('OPENAI_MODEL', 'gpt-4.1-mini')]
            configs = [ModelConfig(model=m, max_output_tokens=args.max_output_tokens,
                                   temperature=args.temperature, reasoning_effort=args.reasoning_effort,
                                   timeout=args.timeout) for m in models]
            run = run_prediction(query, args.top_k, context, configs,
                                 OpenAIProvider(os.environ.get('OPENAI_API_KEY')), args.approach,
                                 retriever=retriever, retrieval_k=retrieval_k)
        path = save_run(run, args.runs_dir)
    except (OSError, ValueError, KeyError, ImportError) as exc:
        print(f'Erreur : {exc}', file=sys.stderr)
        return 2
    if args.json:
        print(json.dumps(run, ensure_ascii=False, indent=2))
    else:
        for prediction in run['predictions']:
            print(f"{prediction['metadata']['config']['model']} — {prediction['status']}")
            for candidate in prediction['candidates']:
                score = '' if candidate['score'] is None else f"  [cosinus={candidate['score']:.4f}]"
                print(f"  {candidate['rank']}. {candidate['code']}  {candidate['description']}{score}")
                if candidate['explanation']:
                    print(f"     {candidate['explanation']}")
            for question in prediction['missing_information']:
                print(f'  Information requise : {question}')
            rejected = prediction['metadata'].get('rejected_candidates', [])
            if rejected:
                print(f'  {len(rejected)} candidat(s) rejeté(s), détails dans le run.')
            if prediction['error']:
                print(f"  Erreur : {prediction['error']['message']}")
    print(f'Run sauvegardé : {path}', file=sys.stderr)
    return 1 if any(p['status'] == 'error' for p in run['predictions']) else 0
