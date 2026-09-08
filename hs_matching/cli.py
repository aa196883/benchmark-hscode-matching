import argparse
import json
import os
from pathlib import Path
import sys

from hs_matching.approaches.base import PredictionContext
from hs_matching.approaches.registry import REGISTRY
from hs_matching.catalog import Catalog
from hs_matching.experiments import run_prediction, save_run
from hs_matching.providers.openai import ModelConfig, OpenAIProvider


def load_env(path):
    """Petit format KEY=value (sans interpolation), l'environnement est prioritaire."""
    path = Path(path)
    if not path.exists():
        return
    for line in path.read_text(encoding='utf-8').splitlines():
        line = line.strip()
        if not line or line.startswith('#'):
            continue
        key, sep, value = line.partition('=')
        if not sep or not key.strip().isidentifier():
            raise ValueError('Ligne .env invalide ; format attendu KEY=value')
        value = value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in ('"', "'"):
            value = value[1:-1]
        os.environ.setdefault(key.strip(), value)


def main(argv=None):
    parser = argparse.ArgumentParser(prog='hs-matching', description='Correspondance description EN → HS 2022')
    sub = parser.add_subparsers(dest='command', required=True)
    sub.add_parser('approaches', help='Lister les approches disponibles')
    predict = sub.add_parser('predict', help='Proposer des codes et sauvegarder un run')
    predict.add_argument('query', help='Description anglaise ; - pour lire stdin')
    predict.add_argument('--approach', choices=sorted(REGISTRY), default='llm_direct')
    predict.add_argument('--model', action='append', help='Répétable pour comparer plusieurs modèles')
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
        context = PredictionContext(Catalog(args.catalog))
        models = args.model or [os.environ.get('OPENAI_MODEL', 'gpt-4.1-mini')]
        configs = [ModelConfig(model=m, max_output_tokens=args.max_output_tokens,
                               temperature=args.temperature, reasoning_effort=args.reasoning_effort,
                               timeout=args.timeout) for m in models]
        run = run_prediction(query, args.top_k, context, configs,
                             OpenAIProvider(os.environ.get('OPENAI_API_KEY')), args.approach)
        path = save_run(run, args.runs_dir)
    except (OSError, ValueError, KeyError) as exc:
        print(f'Erreur : {exc}', file=sys.stderr)
        return 2
    if args.json:
        print(json.dumps(run, ensure_ascii=False, indent=2))
    else:
        for prediction in run['predictions']:
            print(f"{prediction['metadata']['config']['model']} — {prediction['status']}")
            for candidate in prediction['candidates']:
                print(f"  {candidate['rank']}. {candidate['code']}  {candidate['description']}")
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
