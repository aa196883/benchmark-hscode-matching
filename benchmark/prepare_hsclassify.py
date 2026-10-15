"""Extraire les lignes language=en de HSClassify avec un HS6 admissible."""
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

REVISION = '8672fb4eab2fc23b8a1322b4fdb14816138022eb'
URL = f'https://huggingface.co/datasets/Mead0w1ark/HSClassify-benchmark/resolve/{REVISION}/benchmark_cases.csv'


def prepare(source, output, catalog_path):
    report_path = output.with_suffix('.report.json')
    for path in (output, report_path):
        if path.exists():
            raise ValueError(f'Sortie déjà existante : {path}')
    catalog = Catalog(catalog_path)
    rows, provenance, rejections = [], [], []
    languages, categories = Counter(), Counter()
    with source.open(encoding='utf-8-sig', newline='') as stream:
        reader = csv.DictReader(stream, strict=True)
        if not {'text', 'expected_hs_code', 'language', 'category', 'notes'} <= set(reader.fieldnames or []):
            raise ValueError('Colonnes source manquantes.')
        for number, entry in enumerate(reader, 1):
            if None in entry or any(value is None for value in entry.values()):
                raise ValueError(f'Enregistrement {number} : CSV incomplet ou mal formé.')
            languages[entry['language']] += 1
            if entry['language'] != 'en':
                continue
            code, description = entry['expected_hs_code'], entry['text']
            if not re.fullmatch(r'[0-9]{6}', code) or not description.strip():
                raise ValueError(f'Enregistrement {number} : code HS6 ou description invalide.')
            reason = catalog.rejection_reason(code)
            if reason:
                rejections.append({'source_record': number, 'code': code, 'reason': reason})
                continue
            rows.append((code, description))
            categories[entry['category']] += 1
            provenance.append({'row_id': len(rows), 'source_record': number,
                               'category': entry['category'], 'notes': entry['notes']})
    if not rows:
        raise ValueError('Aucune ligne anglaise avec un code admissible.')
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open('x', encoding='utf-8', newline='') as stream:
        writer = csv.writer(stream, delimiter=';')
        writer.writerow(['HS code', 'description'])
        writer.writerows(rows)
    report = {
        'reference_url': URL, 'reference_revision': REVISION,
        'source_path': str(source.resolve()),
        'source_sha256': hashlib.sha256(source.read_bytes()).hexdigest(),
        'output_sha256': hashlib.sha256(output.read_bytes()).hexdigest(),
        'catalog_sha256': catalog.sha256, 'target_edition': '2022',
        'source_edition': 'unspecified; original labels preserved without conversion',
        'source_rows': sum(languages.values()), 'languages': dict(languages),
        'exported_rows': len(rows), 'categories': dict(categories),
        'rows': provenance, 'rejections': rejections,
    }
    with report_path.open('x', encoding='utf-8') as stream:
        json.dump(report, stream, ensure_ascii=False, indent=2)
    return report


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--download', action='store_true', help='Télécharger le CSV à la révision figée')
    parser.add_argument('--source', type=Path, default=ROOT / 'data/hsclassify/benchmark_cases.csv')
    parser.add_argument('--output', type=Path, default=ROOT / 'benchmark/datasets/hsclassify_en/data.csv')
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
        report = prepare(args.source, args.output, args.catalog)
    except (OSError, ValueError, csv.Error) as exc:
        print(f'Erreur : {exc}', file=sys.stderr)
        return 1
    print(json.dumps({key: report[key] for key in
                      ('source_rows', 'languages', 'exported_rows', 'categories')}, sort_keys=True))
    print(f"Lignes exclues par le catalogue : {len(report['rejections'])}")
    print(f'CSV : {args.output}')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
