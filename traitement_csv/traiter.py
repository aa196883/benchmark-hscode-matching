"""Traitement autonome du CSV avec le moteur RAG du projet."""
import argparse
import csv
import hashlib
import io
import json
import os
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from hs_matching.approaches.base import PredictionContext
from hs_matching.catalog import Catalog
from hs_matching.config import load_env
from hs_matching.embedding_index import EmbeddingIndex, EmbeddingRetriever
from hs_matching.experiments import run_prediction, save_run
from hs_matching.providers.openai import ModelConfig, OpenAIProvider
from hs_matching.vectorization.openai import OpenAIVectorizer


def write_csv(path, fields, records):
    temporary = path.with_suffix(path.suffix + '.tmp')
    with temporary.open('w', encoding='utf-8', newline='') as stream:
        writer = csv.DictWriter(stream, fieldnames=fields)
        writer.writeheader()
        writer.writerows(records)
    temporary.replace(path)


def replace_descriptions(rows, code_field, description_field, labels):
    result = []
    for number, row in enumerate(rows, 1):
        code = row[code_field]
        reason = labels.rejection_reason(code)
        if reason:
            raise ValueError(f'Ligne {number} : code HS {code!r} invalide ({reason})')
        result.append({**row, description_field: labels.rows[code]['description']})
    return result


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input', type=Path, required=True)
    parser.add_argument('--output', type=Path, help='Sortie ; par défaut le CSV d’entrée en mode descriptions seules')
    parser.add_argument('--work-dir', type=Path, default=Path(__file__).resolve().parent / 'resultats')
    parser.add_argument('--descriptions-only', action='store_true',
                        help='Remplacer les descriptions depuis les codes existants, sans API')
    parser.add_argument('--candidates', type=Path, default=ROOT / 'data/processed/h6_2022/candidates.jsonl')
    args = parser.parse_args(argv)
    if args.output is None:
        args.output = args.input if args.descriptions_only else Path(__file__).resolve().parent / 'dataset_liens_subtils.csv' 
    raw = args.input.read_bytes()
    reader = csv.DictReader(io.StringIO(raw.decode('utf-8-sig'), newline=''))
    fields = reader.fieldnames
    rows = list(reader)
    if not fields:
        raise ValueError('CSV sans en-tête')
    description_field = 'marchandise' if 'marchandise' in fields else 'description marchandise'
    if description_field not in fields or fields[0] == description_field:
        raise ValueError('Colonnes code et description marchandise attendues')
    if not rows or any(None in row or any(v is None for v in row.values()) or not row[description_field].strip() for row in rows):
        raise ValueError('CSV vide ou lignes invalides')
    labels = Catalog(args.candidates)
    if args.descriptions_only:
        result = replace_descriptions(rows, fields[0], description_field, labels)
        write_csv(args.output, fields, result)
        print(f'{len(result)} descriptions remplacées sans API : {args.output}')
        return
    args.work_dir.mkdir(parents=True, exist_ok=True)
    backup = args.work_dir / 'original.csv'
    if backup.exists() and backup.read_bytes() != raw:
        raise ValueError('Le dossier de travail contient une autre source ; choisir un nouveau --work-dir')
    if not backup.exists():
        backup.write_bytes(raw)
    load_env(ROOT / '.env')
    catalog = Catalog(ROOT / 'data/processed/h6_2022/catalog.jsonl')
    context = PredictionContext(catalog)
    index = EmbeddingIndex(ROOT / 'artifacts/embeddings/h6_2022', catalog)
    retriever = EmbeddingRetriever(index, OpenAIVectorizer(os.environ.get('OPENAI_API_KEY'), index.config, timeout=60))
    provider = OpenAIProvider(os.environ.get('OPENAI_API_KEY'))
    config = ModelConfig(model='gpt-4.1-mini', max_output_tokens=2048, timeout=60)
    audit = []
    inferred = []
    unresolved = []
    for number, row in enumerate(rows, 1):
        query = row[description_field]
        key = hashlib.sha256((query + catalog.sha256 + labels.sha256 + json.dumps(index.manifest, sort_keys=True) + json.dumps(config.to_dict(), sort_keys=True)).encode()).hexdigest()
        checkpoint = args.work_dir / f'{key}.json'
        if checkpoint.exists():
            run = json.loads(checkpoint.read_text())
        else:
            run = run_prediction(query, 5, context, [config], provider, 'rag', retriever=retriever, retrieval_k=20)
            save_run(run, args.work_dir / 'runs')
            if run['predictions'][0]['status'] != 'error':
                checkpoint.write_text(json.dumps(run, ensure_ascii=False, indent=2) + '\n')
        prediction = run['predictions'][0]
        candidates = prediction['candidates']
        chosen = min(candidates, key=lambda item: item['rank']) if candidates and prediction['status'] in ('ok', 'needs_info') else None
        code = chosen['code'] if chosen else None
        if code and labels.rejection_reason(code):
            raise ValueError(f'Code absent du référentiel candidat : {code}')
        entry = dict(row)
        entry[fields[0]] = code or ''
        inferred.append(entry)
        audit.append({'row': number, 'original_description': query, 'code': code, 'description': labels.rows[code]['description'] if code else None, 'status': prediction['status'], 'missing_information': prediction['missing_information'], 'candidates': candidates, 'error': prediction['error'], 'run_id': run['run_id']})
        if not code:
            unresolved.append(number)
        print(f'{number}/{len(rows)} {prediction["status"]}: {query} -> {code}', flush=True)
    (args.work_dir / 'audit.json').write_text(json.dumps(audit, ensure_ascii=False, indent=2) + '\n')
    write_csv(args.work_dir / 'codes_inferes.csv', fields, inferred)
    if unresolved:
        raise SystemExit(f'Lignes sans code : {unresolved}. Voir audit.json ; sortie finale non écrite.')
    result = replace_descriptions(inferred, fields[0], description_field, labels)
    write_csv(args.output, fields, result)
    print(f'Résultat : {args.output}')


if __name__ == '__main__':
    main()
