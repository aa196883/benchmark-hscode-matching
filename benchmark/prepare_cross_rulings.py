"""Préparer le split test CROSS en CSV HS6, sans appel à un modèle."""
import argparse
from collections import Counter
import csv
import hashlib
import json
from pathlib import Path
import re
import sys
from urllib.request import urlopen

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from hs_matching.catalog import Catalog

REPO = 'Dayanand314Krishna/cross_rulings_hts_dataset_for_tariffs'
REVISION = '0a66ad345d3fc9828f362836d86b31e20798513c'
URL = f'https://huggingface.co/datasets/{REPO}/resolve/{REVISION}/test.jsonl'
PREFIX = 'What is the HTS US Code for '
CODE = r'(?:[0-9]{4}\.[0-9]{2}\.[0-9]{4}|[0-9]{10})'


def extract(entry, catalog):
    """Retourner (HS6, description) ou un motif de rejet explicite."""
    messages = entry.get('messages') if isinstance(entry, dict) else None
    if (not isinstance(messages, list) or len(messages) != 2
            or not all(isinstance(m, dict) for m in messages)
            or [m.get('role') for m in messages] != ['user', 'assistant']):
        return None, 'invalid_messages'
    question, answer = (m.get('content') for m in messages)
    if not isinstance(question, str) or not isinstance(answer, str):
        return None, 'invalid_content'
    question = question.strip()
    if not question.startswith(PREFIX) or not question.endswith('?'):
        return None, 'unexpected_question'
    description = ' '.join(question[len(PREFIX):-1].split())
    if not description:
        return None, 'empty_description'
    # Lire exclusivement l'en-tête de classification, jamais le raisonnement.
    header = re.match(r'\A\s*HTS US Code\s*->\s*([^\r\n]+)', answer)
    if not header:
        return None, 'missing_code_header'
    labels = header.group(1).strip()
    # Une syntaxe inconnue est rejetée, plutôt que d'en extraire un code partiel.
    if not re.fullmatch(rf'{CODE}(?:\s*[;,]\s*{CODE})*', labels):
        return None, 'unsupported_code_header'
    codes = {code.replace('.', '')[:6] for code in re.findall(CODE, labels)}
    if len(codes) != 1:
        return None, 'multiple_hs6'
    code = next(iter(codes))
    reason = catalog.rejection_reason(code)
    if reason:
        return None, reason
    if re.search(rf'(?<![0-9]){CODE}(?![0-9])', description) or code in description:
        return None, 'possible_label_leakage'
    return (code, description), None


def prepare(source, output, catalog_path):
    catalog = Catalog(catalog_path)
    report_path = output.with_suffix('.report.json')
    for path in (output, report_path):
        if path.exists():
            raise ValueError(f'Sortie déjà existante : {path}')
    counts = Counter()
    accepted, rejected = [], []
    source_hash = hashlib.sha256()
    with source.open('rb') as stream:
        for number, raw in enumerate(stream, 1):
            source_hash.update(raw)
            try:
                row, reason = extract(json.loads(raw), catalog)
            except (ValueError, UnicodeError):
                row, reason = None, 'invalid_json'
            counts['source_rows'] += 1
            if reason:
                counts[reason] += 1
                rejected.append({'source_line': number, 'reason': reason})
            else:
                accepted.append((number, row))
    if not accepted:
        raise ValueError(f'Aucune ligne utilisable : {dict(counts)}')
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open('x', encoding='utf-8', newline='') as stream:
        writer = csv.writer(stream, delimiter=';')
        writer.writerow(['HS code', 'description'])
        writer.writerows(row for _, row in accepted)
    report = {
        'source_path': str(source.resolve()), 'source_sha256': source_hash.hexdigest(),
        'reference_url': URL, 'reference_revision': REVISION, 'split': 'test',
        'catalog_sha256': catalog.sha256, 'target_edition': '2022',
        'source_edition': 'unspecified; no historical code conversion',
        'output_sha256': hashlib.sha256(output.read_bytes()).hexdigest(),
        'counts': {**counts, 'accepted': len(accepted), 'rejected': len(rejected)},
        'rows': [{'row_id': i, 'source_line': number}
                 for i, (number, _) in enumerate(accepted, 1)],
        'rejections': rejected,
    }
    with report_path.open('x', encoding='utf-8') as stream:
        json.dump(report, stream, ensure_ascii=False, indent=2)
    return report['counts']


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, default=ROOT / 'data/cross_rulings/test.jsonl')
    parser.add_argument('--download', action='store_true', help='Télécharger le split test à la révision figée')
    parser.add_argument('--output', type=Path, default=ROOT / 'benchmark/datasets/cross_rulings_test/data.csv')
    parser.add_argument('--catalog', type=Path, default=ROOT / 'data/processed/h6_2022/catalog.jsonl')
    args = parser.parse_args(argv)
    try:
        if args.download:
            if args.source.exists():
                raise ValueError('Source déjà existante ; omettre --download pour la réutiliser.')
            args.source.parent.mkdir(parents=True, exist_ok=True)
            with urlopen(URL, timeout=60) as response, args.source.open('xb') as stream:
                while chunk := response.read(65536):
                    stream.write(chunk)
        counts = prepare(args.source, args.output, args.catalog)
    except (OSError, ValueError) as exc:
        print(f'Erreur : {exc}', file=sys.stderr)
        return 1
    print(json.dumps(counts, sort_keys=True))
    print(f'CSV : {args.output}')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
