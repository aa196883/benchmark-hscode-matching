"""Extraire les codes HS6 et titres du fichier HSCodeComp fourni."""
import argparse
import csv
import json
from pathlib import Path
import re
import sys


def prepare(source: Path, output: Path) -> int:
    if source.resolve() == output.resolve():
        raise ValueError('Les chemins d’entrée et de sortie doivent être différents.')
    text = source.read_text(encoding='utf-8-sig')
    # Le fichier fourni contient des objets multilignes et des retours à la ligne
    # non échappés dans les chaînes. Accepte aussi le JSONL standard.
    decoder = json.JSONDecoder(strict=False)
    rows = []
    position = 0
    while position < len(text):
        if text[position].isspace():
            position += 1
            continue
        entry, position = decoder.raw_decode(text, position)
        number = len(rows) + 1
        if not isinstance(entry, dict):
            raise ValueError(f'Entrée {number} : objet JSON attendu.')
        code = entry.get('hs_code')
        description = entry.get('product_name')
        if type(code) is int:
            code = str(code)
        if not isinstance(code, str) or not re.fullmatch(r'[0-9]{6,}', code.strip()):
            raise ValueError(f'Entrée {number} : hs_code doit contenir au moins six chiffres.')
        if not isinstance(description, str) or not description.strip():
            raise ValueError(f'Entrée {number} : product_name doit être un texte non vide.')
        rows.append((code.strip()[:6], ' '.join(description.split())))
    if not rows:
        raise ValueError('Le fichier d’entrée ne contient aucun produit.')
    # Valider toutes les entrées avant d’ouvrir la sortie.
    with output.open('w', encoding='utf-8', newline='') as stream:
        writer = csv.writer(stream, delimiter=';')
        writer.writerow(['HS code', 'description'])
        writer.writerows(rows)
    return len(rows)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('input', type=Path, help='Fichier test_data.jsonl de HSCodeComp')
    parser.add_argument('output', type=Path, help='CSV de sortie, par exemple hscodecomp_hs6_v1.csv')
    args = parser.parse_args(argv)
    try:
        count = prepare(args.input, args.output)
    except (OSError, ValueError) as exc:
        print(f'Erreur : {exc}', file=sys.stderr)
        return 1
    print(f'{count} produits exportés vers {args.output}')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
