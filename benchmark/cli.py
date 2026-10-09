"""CLI Python autonome du benchmark MLflow."""
import argparse
import csv
import json
import math
import os
from pathlib import Path
import subprocess
import sys

from hs_matching.approaches.registry import REGISTRY
from hs_matching.config import load_env
from hs_matching.providers.openai import ModelConfig
from hs_matching.providers.qwen import QWEN_MODEL
from benchmark.datasets import DATASETS, get_snapshot, versions
from benchmark.tracking import ROOT, configure

PROJECT = ROOT.parent


def parser():
    result = argparse.ArgumentParser(description=__doc__)
    commands = result.add_subparsers(dest='command', required=True)
    run = commands.add_parser('run', help='Évaluer une approche sur un dataset et enregistrer un run MLflow')
    run.add_argument('--approach', required=True, choices=sorted(REGISTRY))
    run.add_argument('--dataset', required=True, help='Nom, dossier ou CSV avec manifest.json adjacent')
    run.add_argument('--dataset-version', type=int, help='Version MLflow exacte, sans lecture du CSV local')
    run.add_argument('--limit', type=int, help='Nombre maximal de lignes à inférer')
    run.add_argument('--offset', type=int, default=0, help='Ignorer les N premières lignes (défaut : 0)')
    run.add_argument('--model')
    run.add_argument('--top-k', type=int, default=5)
    run.add_argument('--retrieval-k', type=int)
    run.add_argument('--catalog', type=Path, default=PROJECT / 'data/processed/h6_2022/catalog.jsonl')
    run.add_argument('--index', type=Path, default=PROJECT / 'artifacts/embeddings/h6_2022')
    run.add_argument('--max-output-tokens', type=int, default=2048)
    run.add_argument('--temperature', type=float)
    run.add_argument('--reasoning-effort')
    run.add_argument('--timeout', type=float, default=60)
    run.add_argument('--runs-dir', type=Path, default=ROOT / 'runs', help='Checkpoints locaux de secours')
    run.add_argument('--run-name')
    importer = commands.add_parser('import-dataset', help='Sauvegarder/versionner un dataset sans inférence')
    importer.add_argument('--dataset', required=True)
    listing = commands.add_parser('datasets', help='Lister les datasets locaux et les versions MLflow')
    ui = commands.add_parser('ui', help='Ouvrir le serveur MLflow local')
    ui.add_argument('--port', type=int, default=5001)
    for command in (run, importer, listing, ui):
        command.add_argument('--tracking-uri', help='Backend SQL ou serveur MLflow ; défaut : benchmark/mlflow.db')
        command.add_argument('--env-file', type=Path, default=PROJECT / '.env')
    return result


def validate(args):
    if args.limit is not None and args.limit < 1:
        raise ValueError('--limit doit être positif')
    if args.offset < 0 or (args.dataset_version is not None and args.dataset_version < 1):
        raise ValueError('--offset doit être positif ou nul ; --dataset-version doit être positif')
    if args.top_k < 1 or not math.isfinite(args.timeout) or args.timeout <= 0:
        raise ValueError('--top-k et --timeout doivent être positifs')
    if args.retrieval_k is not None and args.approach != 'rag':
        raise ValueError('--retrieval-k est réservé au RAG')
    if args.approach == 'rag':
        args.retrieval_k = args.retrieval_k if args.retrieval_k is not None else 20
        if args.retrieval_k < args.top_k:
            raise ValueError('--retrieval-k doit être >= --top-k')
    if args.approach == 'embeddings':
        if args.model or args.temperature is not None or args.reasoning_effort is not None:
            raise ValueError('Embeddings : modèle imposé par l’index ; paramètres LLM interdits')
        args.model = None
    else:
        args.model = args.model or os.environ.get('OPENAI_MODEL', 'gpt-4.1-mini')
        ModelConfig(args.model, args.max_output_tokens, args.temperature, args.reasoning_effort, args.timeout)
        if args.model in ('qwen3', QWEN_MODEL) and args.reasoning_effort is not None:
            raise ValueError('--reasoning-effort n’est pas pris en charge par Qwen')


def main(argv=None):
    args = parser().parse_args(argv)
    try:
        load_env(args.env_file)
        if args.command == 'run':
            validate(args)
        uri = configure(args.tracking_uri)
        import mlflow
        if args.command == 'ui':
            if uri.startswith(('http://', 'https://')):
                print(f'Serveur MLflow : {uri}')
                return 0
            return subprocess.call([sys.executable, '-m', 'mlflow', 'server', '--backend-store-uri', uri,
                                    '--host', '127.0.0.1', '--port', str(args.port)], cwd=ROOT)
        if args.command == 'datasets':
            local = [json.loads(p.read_text())['name'] for p in sorted(DATASETS.glob('*/manifest.json'))]
            print('Datasets locaux : ' + ', '.join(local))
            for exp in mlflow.MlflowClient().search_experiments():
                if exp.name.startswith('hs-matching/'):
                    name = exp.name.split('/', 1)[1]
                    for version, dataset in sorted(versions(name, exp.experiment_id).items()):
                        print(f'{name} v{version} : {dataset.dataset_id}')
            return 0
        snapshot = get_snapshot(args.dataset, getattr(args, 'dataset_version', None))
        if args.command == 'import-dataset':
            print(f'{snapshot.name} v{snapshot.version} : {snapshot.dataset_id}, {len(snapshot.rows)} lignes')
            return 0
        from benchmark.runner import evaluate
        return evaluate(args, snapshot)
    except KeyboardInterrupt:
        return 130
    except (OSError, ValueError, KeyError, ImportError, csv.Error) as exc:
        print(f'Erreur : {exc}', file=sys.stderr)
        return 2
    except Exception as exc:
        # Les erreurs MLflow (backend indisponible, SQL, artifacts) font échouer la commande.
        print(f'Erreur de benchmark ({type(exc).__name__}) : {exc}', file=sys.stderr)
        return 2
