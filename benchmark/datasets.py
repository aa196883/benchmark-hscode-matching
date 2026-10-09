"""Datasets locaux et versions sauvegardées dans MLflow Evaluation Datasets."""
import csv
from dataclasses import dataclass
import hashlib
import io
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parent
DATASETS = ROOT / 'datasets'


def digest(value):
    return hashlib.sha256(json.dumps(value, ensure_ascii=False, sort_keys=True).encode()).hexdigest()


def validate_manifest(value):
    if not isinstance(value, dict) or set(value) != {'schema_version', 'name', 'csv', 'edition', 'language'}:
        raise ValueError('Manifest : schema_version, name, csv, edition et language requis')
    if value['schema_version'] != 1 or (value['edition'], value['language']) != ('2022', 'en'):
        raise ValueError('Dataset attendu : schéma 1, HS 2022 anglais')
    if not isinstance(value['name'], str) or not re.fullmatch(r'[a-zA-Z0-9][a-zA-Z0-9_-]*', value['name']):
        raise ValueError('Nom de dataset invalide')
    filename = value['csv']
    if not isinstance(filename, str) or Path(filename).name != filename or not filename.endswith('.csv'):
        raise ValueError('manifest.csv doit être un nom de fichier CSV dans le dossier du dataset')
    return value


def load_manifest(location):
    path = Path(location)
    if not path.exists() and len(path.parts) == 1:
        path = DATASETS / path
    manifest_path = path / 'manifest.json' if path.is_dir() else path.parent / 'manifest.json'
    manifest = validate_manifest(json.loads(manifest_path.read_text(encoding='utf-8')))
    source = manifest_path.parent / manifest['csv']
    if path.suffix == '.csv' and path.resolve() != source.resolve():
        raise ValueError('Le CSV fourni ne correspond pas au manifest')
    return manifest, source


def read_csv(raw):
    stream = io.StringIO(raw.decode('utf-8-sig'), newline='')
    first = stream.readline()
    stream.seek(0)
    delimiter = ';' if ';' in first.split(',', 1)[0] else ','
    reader = csv.reader(stream, delimiter=delimiter, strict=True)
    rows = []
    for number, row in enumerate(reader, 1):
        if len(row) != 2:
            raise ValueError(f'Ligne {reader.line_num} : exactement deux colonnes requises')
        code, description = row
        if number == 1 and re.sub(r'[ _-]', '', code.lower()) in ('hscode', 'hs6', 'code') and description.lower().strip() == 'description':
            continue
        if re.fullmatch(r'[0-9]{6}', code) is None or not description.strip():
            raise ValueError(f'Ligne {reader.line_num} : code HS6 et description non vide requis')
        rows.append({'row_id': len(rows) + 1, 'hs_code': code, 'description': description})
    if not rows:
        raise ValueError('Dataset vide')
    return rows


@dataclass
class Snapshot:
    manifest: dict
    rows: list
    dataset_id: str
    version: int
    sha256: str
    source_sha256: str
    experiment_id: str

    @property
    def name(self):
        return self.manifest['name']


def versions(name, experiment_id):
    from mlflow.genai import datasets
    found = datasets.search_datasets(experiment_ids=[experiment_id],
                                     filter_string=f"tags.dataset_name = '{name}'")
    result = {}
    for dataset in found:
        version = int(dataset.tags['version'])
        if version in result:
            raise ValueError(f'Version dupliquée pour {name} : {version}')
        result[version] = dataset
    return result


def from_stored(dataset, experiment_id):
    tags = dataset.tags
    manifest = validate_manifest(json.loads(tags['manifest']))
    rows = []
    for record in dataset.to_df().to_dict(orient='records'):
        inputs, expectations = record['inputs'], record['expectations']
        rows.append({'row_id': inputs['row_id'], 'description': inputs['description'],
                     'hs_code': expectations['hs_code']})
    rows.sort(key=lambda row: row['row_id'])
    if (not rows or [row['row_id'] for row in rows] != list(range(1, len(rows) + 1))
            or manifest['name'] != tags['dataset_name']
            or digest({'manifest': manifest, 'rows': rows}) != tags['content_sha256']):
        raise ValueError('Le dataset MLflow a été modifié ou son intégrité est invalide')
    return Snapshot(manifest, rows, dataset.dataset_id, int(tags['version']),
                    tags['content_sha256'], tags['source_sha256'], experiment_id)


def get_snapshot(location, version=None):
    import mlflow
    from mlflow.genai import datasets
    from benchmark.tracking import experiment

    # Une version importée est utilisable par nom, sans aucun fichier local.
    if version is not None and re.fullmatch(r'[a-zA-Z0-9][a-zA-Z0-9_-]*', str(location)):
        name = str(location)
        manifest = source = None
    else:
        manifest, source = load_manifest(location)
        name = manifest['name']
    if version is not None:
        exp = mlflow.get_experiment_by_name(f'hs-matching/{name}')
        existing = versions(name, exp.experiment_id) if exp else {}
        if version not in existing:
            raise ValueError(f'{name} v{version} introuvable ; versions disponibles : {sorted(existing)}')
        return from_stored(existing[version], exp.experiment_id)

    raw = source.read_bytes()
    rows = read_csv(raw)  # Valider tout le CSV, avant MLflow et avant toute inférence.
    content_sha256 = digest({'manifest': manifest, 'rows': rows})
    source_sha256 = hashlib.sha256(raw).hexdigest()
    exp = experiment(name)
    existing = versions(name, exp.experiment_id)
    for dataset in existing.values():
        if (dataset.tags['content_sha256'], dataset.tags['source_sha256']) == (content_sha256, source_sha256):
            return from_stored(dataset, exp.experiment_id)
    next_version = max(existing, default=0) + 1
    dataset = datasets.create_dataset(name=f'hs-matching__{name}__v{next_version}',
        experiment_id=exp.experiment_id, tags={
            'dataset_name': name, 'version': str(next_version),
            'content_sha256': content_sha256, 'source_sha256': source_sha256,
            'source_path': str(source.resolve()), 'manifest': json.dumps(manifest, sort_keys=True),
        })
    try:
        # row_id évite la déduplication MLflow et permet de rétablir l'ordre source.
        # Il n'est jamais transmis au modèle.
        dataset.merge_records([{'inputs': {'row_id': row['row_id'], 'description': row['description']},
                                'expectations': {'hs_code': row['hs_code']}} for row in rows])
        return from_stored(dataset, exp.experiment_id)
    except BaseException:
        datasets.delete_dataset(dataset_id=dataset.dataset_id)
        raise
