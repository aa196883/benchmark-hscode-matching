"""Évaluation séquentielle et persistance des résultats dans MLflow."""
import csv
import hashlib
import json
from pathlib import Path
import subprocess
import sys
from time import perf_counter

from benchmark.datasets import digest
from benchmark.metrics import summarize, token_counts
from benchmark.runtime import build_approach
from hs_matching.approaches.base import Prediction

ROOT = Path(__file__).resolve().parents[1]


def log_sources(mlflow):
    sources = {}
    paths = sorted((ROOT / 'benchmark').glob('*.py')) + sorted((ROOT / 'hs_matching').rglob('*.py'))
    for path in paths:
        relative = path.relative_to(ROOT)
        sources[str(relative)] = hashlib.sha256(path.read_bytes()).hexdigest()
        mlflow.log_artifact(str(path), 'source/' + str(relative.parent))
    try:
        revision = subprocess.run(['git', 'rev-parse', 'HEAD'], cwd=ROOT, capture_output=True, text=True)
        revision_id = revision.stdout.strip() if revision.returncode == 0 else 'unknown'
    except OSError:
        revision_id = 'unknown'
    mlflow.set_tag('git_revision', revision_id)
    mlflow.log_dict(sources, 'source/sha256.json')


def evaluate(args, snapshot):
    import mlflow
    import pandas as pd

    selected = snapshot.rows[args.offset:]
    if args.limit is not None:
        selected = selected[:args.limit]
    if not selected:
        raise ValueError('--offset dépasse le nombre de lignes du dataset')
    results = []
    status, exit_code = 'FAILED', 2
    run = mlflow.start_run(experiment_id=snapshot.experiment_id,
                          run_name=args.run_name or f'{args.approach}-{args.model or "index"}')
    run_id = run.info.run_id
    directory = args.runs_dir / run_id
    try:
        directory.mkdir(parents=True, exist_ok=False)
        mlflow.set_tags({'approach': args.approach, 'dataset.name': snapshot.name,
                         'dataset.id': snapshot.dataset_id, 'dataset.version': str(snapshot.version),
                         'complete': 'false', 'full_dataset': str(len(selected) == len(snapshot.rows)).lower()})
        params = dict(approach=args.approach, dataset=snapshot.name, dataset_id=snapshot.dataset_id,
                      dataset_version=snapshot.version, dataset_sha256=snapshot.sha256,
                      source_sha256=snapshot.source_sha256, dataset_size=len(snapshot.rows),
                      selected_rows=len(selected), selected_sha256=digest(selected),
                      offset=args.offset, limit=args.limit, top_k=args.top_k, timeout=args.timeout)
        if args.approach != 'embeddings':
            params.update(model=args.model, max_output_tokens=args.max_output_tokens,
                          temperature=args.temperature, reasoning_effort=args.reasoning_effort)
        if args.approach == 'rag':
            params['retrieval_k'] = args.retrieval_k
        mlflow.log_params({k: 'none' if v is None else v for k, v in params.items()})
        mlflow.log_dict(snapshot.manifest, 'dataset/manifest.json')
        mlflow.log_dict(selected, 'dataset/selected_rows.json')
        with (directory / 'dataset.csv').open('w', encoding='utf-8', newline='') as stream:
            writer = csv.DictWriter(stream, fieldnames=['row_id', 'hs_code', 'description'])
            writer.writeheader()
            writer.writerows(snapshot.rows)
        mlflow.log_artifact(str(directory / 'dataset.csv'), 'dataset')
        # MLflow DatasetInput matérialise aussi la lignée du sous-ensemble évalué.
        data = mlflow.data.from_pandas(pd.DataFrame(selected), name=snapshot.name,
                                      digest=digest(selected)[:32], targets='hs_code')
        mlflow.log_input(data, context='evaluation', tags={'dataset_id': snapshot.dataset_id,
                                                        'version': str(snapshot.version)})
        log_sources(mlflow)
        approach, context = build_approach(args)
        mlflow.log_params({'catalog_sha256': context.catalog.sha256, 'catalog': str(args.catalog)})
        if args.approach in ('embeddings', 'rag'):
            index = approach.retriever.index
            mlflow.log_dict(index.manifest, 'resources/index_manifest.json')
            mlflow.log_params({'embedding_model': index.config.model, 'index': str(args.index)})
        if args.approach != 'embeddings':
            mlflow.log_param('resolved_model', approach.config.model)
        print(f'Run MLflow : {run_id} | hs-matching/{snapshot.name} | {len(selected)} lignes', file=sys.stderr)
        print(f'0/{len(selected)}', file=sys.stderr)
        with (directory / 'results.jsonl').open('w', encoding='utf-8') as stream:
            for row in selected:
                start = perf_counter()
                try:
                    prediction = approach.predict(row['description'], args.top_k, context).to_dict()
                except Exception as exc:
                    prediction = Prediction('error', error={'kind': type(exc).__name__,
                                            'message': 'Erreur interne pendant la prédiction.'}).to_dict()
                elapsed = perf_counter() - start
                counts = token_counts(prediction.pop('metadata', {}), args.approach)
                result = dict(row_id=row['row_id'], description=row['description'], ground_truth=row['hs_code'],
                              response_time=elapsed, **counts, answer=prediction)
                results.append(result)
                stream.write(json.dumps(result, ensure_ascii=False) + '\n')
                stream.flush()
                mlflow.log_artifact(str(directory / 'results.jsonl'), 'evaluation')
                mlflow.log_metrics(summarize(results), step=len(results))
                print(f'{len(results)}/{len(selected)} {prediction["status"]}', file=sys.stderr)
        exit_code = 1 if any(r['answer']['status'] == 'error' for r in results) else 0
        status = 'FAILED' if exit_code else 'FINISHED'
    except KeyboardInterrupt:
        status, exit_code = 'KILLED', 130
        print('Interruption : résultats déjà collectés conservés.', file=sys.stderr)
    finally:
        try:
            mlflow.log_metrics(summarize(results), step=len(results))
            mlflow.set_tag('complete', str(len(results) == len(selected)).lower())
            if results:
                mlflow.log_dict(results, 'evaluation/results.json')
                mlflow.log_table(pd.DataFrame([dict(row_id=r['row_id'], description=r['description'],
                    ground_truth=r['ground_truth'], status=r['answer']['status'],
                    candidates=json.dumps(r['answer']['candidates'], ensure_ascii=False),
                    response_time=r['response_time'], input_tokens=r['input_tokens'],
                    output_tokens=r['output_tokens']) for r in results]), 'evaluation/table.json')
        except BaseException as exc:
            status = 'KILLED' if isinstance(exc, KeyboardInterrupt) else 'FAILED'
            raise
        finally:
            mlflow.end_run(status=status)
    print(json.dumps({'run_id': run_id, 'experiment': f'hs-matching/{snapshot.name}',
                      'dataset_version': snapshot.version, 'evaluated_rows': len(results),
                      'status': status, 'metrics': summarize(results)}, ensure_ascii=False))
    return exit_code
