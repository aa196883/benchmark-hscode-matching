"""CLI de collecte des prédictions et d’analyse des runs de benchmark."""
import argparse
import csv
from datetime import datetime, timezone
import json
import math
import os
from pathlib import Path
import re
import sys
from time import perf_counter
from uuid import uuid4

# Permet python benchmark/benchmark.py depuis la racine sans installation.
if __package__ in (None, ''):
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from hs_matching.approaches.base import Prediction, PredictionContext
from hs_matching.approaches.registry import REGISTRY, create_approach
from hs_matching.catalog import Catalog
from hs_matching.config import load_env
from hs_matching.providers.openai import ModelConfig, OpenAIProvider
from hs_matching.providers.qwen import QWEN_MODEL, QwenProvider

DEFAULT_DATASET = Path(__file__).with_name('hscodecomp_hs6_v1.csv')


def read_dataset(path):
    """Valide deux colonnes, sans convertir ni normaliser les codes HS."""
    rows = []
    with Path(path).open(encoding='utf-8-sig', newline='') as stream:
        first = stream.readline()
        stream.seek(0)
        # L'en-tête ou le premier code détermine le séparateur, même si la
        # description contient elle-même des virgules ou points-virgules.
        delimiter = ';' if ';' in first.split(',', 1)[0] else ','
        reader = csv.reader(stream, delimiter=delimiter, strict=True)
        for number, row in enumerate(reader, 1):
            if len(row) != 2:
                raise ValueError(f'{path}, ligne {reader.line_num} : exactement deux colonnes requises')
            code, description = row
            header = re.sub(r'[ _-]', '', code.lower())
            if number == 1 and header in ('hscode', 'hs6', 'code') and description.lower().strip() == 'description':
                continue
            if re.fullmatch(r'[0-9]{6}', code) is None or not description.strip():
                raise ValueError(f'{path}, ligne {reader.line_num} : code HS à 6 chiffres et description non vide requis')
            rows.append((code, description))
    if not rows:
        raise ValueError(f'{path} : dataset vide')
    return rows


class Trace:
    """Maintient un document JSON lisible après chaque résultat, sans tout réécrire."""
    def __init__(self, path, metadata):
        self.stream = path.open('x+', encoding='utf-8', newline='\n')
        self.stream.write(json.dumps(metadata, ensure_ascii=False, indent=2)[:-2] + ',\n  "results": [')
        self.position = self.stream.tell()
        self.count = 0
        self._finish()

    def _finish(self):
        self.stream.write('\n  ]\n}\n')
        self.stream.truncate()
        self.stream.flush()

    def append(self, result):
        payload = json.dumps(result, ensure_ascii=False, indent=2)
        self.stream.seek(self.position)
        self.stream.write((',' if self.count else '') + '\n' + '\n'.join('    ' + line for line in payload.splitlines()))
        self.position = self.stream.tell()
        self.count += 1
        self._finish()

    def close(self):
        self.stream.close()


class Progress:
    """Barre en terminal ; lignes autonomes pour les logs des scripts."""
    def __init__(self, model, approach, dataset, total):
        self.label = f'model={model or "(index)"} | approach={approach} | dataset={dataset}'
        self.total = total
        self.terminal = sys.stderr.isatty()

    def update(self, completed):
        filled = 20 * completed // self.total
        bar = '[' + '#' * filled + '-' * (20 - filled) + '] '
        text = f'{self.label} | {bar if self.terminal else ""}{completed}/{self.total}'
        print(('\r' if self.terminal else '') + text,
              end='' if self.terminal else '\n', file=sys.stderr, flush=True)

    def close(self):
        if self.terminal:
            print(file=sys.stderr, flush=True)


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


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--datasets', nargs='+', default=[str(DEFAULT_DATASET)])
    parser.add_argument('--approach', choices=sorted(REGISTRY))
    parser.add_argument('--model', help='Modèle OpenAI ou qwen3 ; vide pour embeddings')
    parser.add_argument('--top-k', type=int, default=5)
    parser.add_argument('--retrieval-k', type=int)
    parser.add_argument('--index', default='artifacts/embeddings/h6_2022')
    parser.add_argument('--catalog', default='data/processed/h6_2022/catalog.jsonl')
    parser.add_argument('--env-file', default='.env')
    parser.add_argument('--max-output-tokens', type=int, default=2048)
    parser.add_argument('--temperature', type=float)
    parser.add_argument('--reasoning-effort')
    parser.add_argument('--timeout', type=float, default=60.0)
    parser.add_argument('--runs-dir', default='benchmark/runs')
    parser.add_argument('--process-runs', action='store_true', help='Analyser les runs existants sans inférence')
    parser.add_argument('--runs', nargs='+', help='Fichiers JSON ou motifs glob à regrouper')
    parser.add_argument('--output', default='benchmark/report.md', help='Rapport Markdown (mode --process-runs)')
    args = parser.parse_args(argv)
    if args.process_runs:
        if not args.runs:
            parser.error('--runs est requis avec --process-runs')
        from benchmark.process_runs import process_runs
        try:
            path = process_runs(args.runs, args.output)
        except (OSError, ValueError) as exc:
            print(f'Erreur : {exc}', file=sys.stderr)
            return 2
        print(f'Rapport : {path}', file=sys.stderr)
        return 0
    if args.runs:
        parser.error('--runs nécessite --process-runs')
    if args.approach is None:
        parser.error('--approach est requis pour lancer un benchmark')
    traces = []
    failed = False
    try:
        load_env(args.env_file)
        if args.top_k < 1 or not math.isfinite(args.timeout) or args.timeout <= 0:
            raise ValueError('--top-k et --timeout doivent être positifs')
        if args.retrieval_k is not None and args.approach != 'rag':
            raise ValueError('--retrieval-k est réservé au RAG')
        if args.approach == 'rag':
            args.retrieval_k = args.retrieval_k if args.retrieval_k is not None else 20
            if args.retrieval_k < args.top_k:
                raise ValueError('--retrieval-k doit être supérieur ou égal à --top-k')
        if args.approach == 'embeddings':
            if args.model or args.temperature is not None or args.reasoning_effort is not None:
                raise ValueError('Embeddings : le modèle vient de --index ; les paramètres LLM ne s’appliquent pas')
            args.model = ''
        else:
            args.model = args.model if args.model is not None else os.environ.get('OPENAI_MODEL', 'gpt-4.1-mini')
            ModelConfig(args.model, args.max_output_tokens, args.temperature, args.reasoning_effort, args.timeout)
        datasets = [(path, read_dataset(path)) for path in args.datasets]
        directory = Path(args.runs_dir)
        directory.mkdir(parents=True, exist_ok=True)
        for dataset, rows in datasets:
            metadata = dict(model=args.model, approach=args.approach, dataset=str(dataset),
                            created_at=datetime.now(timezone.utc).isoformat(),
                            dataset_size=len(rows), top_k=args.top_k, timeout=args.timeout, catalog=args.catalog)
            if args.approach in ('embeddings', 'rag'):
                metadata['index'] = args.index
            if args.approach == 'rag':
                metadata['retrieval_k'] = args.retrieval_k
            if args.approach != 'embeddings':
                metadata.update(max_output_tokens=args.max_output_tokens, temperature=args.temperature,
                                reasoning_effort=args.reasoning_effort)
            path = directory / f'{args.approach}_{uuid4().hex}.json'
            trace = Trace(path, metadata)
            traces.append((trace, rows))
            print(f'Trace : {path}', file=sys.stderr)
        approach, context = build_approach(args)
        for (dataset, _), (trace, rows) in zip(datasets, traces):
            progress = Progress(args.model, args.approach, dataset, len(rows))
            progress.update(0)
            try:
                for code, description in rows:
                    start = perf_counter()
                    try:
                        answer = approach.predict(description, args.top_k, context).to_dict()
                    except Exception as exc:
                        answer = Prediction(status='error', error={'kind': type(exc).__name__,
                                            'message': 'Erreur interne pendant la prédiction.'}).to_dict()
                    elapsed = perf_counter() - start
                    answer.pop('metadata', None)
                    if args.approach == 'llm_direct':
                        answer.pop('raw_response', None)
                    trace.append(dict(response_time=elapsed, ground_truth=code, description=description, answer=answer))
                    failed |= answer.get('status') == 'error'
                    progress.update(trace.count)
            finally:
                progress.close()
    except KeyboardInterrupt:
        print('Benchmark interrompu ; résultats déjà collectés conservés.', file=sys.stderr)
        return 130
    except (OSError, ValueError, KeyError, ImportError, csv.Error) as exc:
        print(f'Erreur : {exc}', file=sys.stderr)
        return 2
    finally:
        for trace, _ in traces:
            trace.close()
    return 1 if failed else 0


if __name__ == '__main__':
    raise SystemExit(main())
