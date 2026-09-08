"""Précalcul lisible et recherche exacte réutilisable par embeddings et RAG."""
from dataclasses import dataclass
from datetime import datetime, timezone
import hashlib
import json
import math
from pathlib import Path
import tempfile
from time import perf_counter

from hs_matching.catalog import Catalog
from hs_matching.vectorization.base import EmbeddingConfig, Vectorizer, validate_vectors


def candidate_rows(catalog):
    rows = [row for code, row in sorted(catalog.rows.items()) if catalog.rejection_reason(code) is None]
    if not rows:
        raise ValueError('Aucun candidat admissible')
    for row in rows:
        if not isinstance(row.get('contextual_description'), str) or not row['contextual_description'].strip():
            raise ValueError(f"Description contextualisée manquante : {row['code']}")
    return rows


def descriptions_sha256(rows):
    # Même empreinte pour catalog.jsonl et candidates.jsonl ; indépendante de la mise en forme.
    content = [[r['code'], r['contextual_description']] for r in rows]
    return hashlib.sha256(json.dumps(content, ensure_ascii=False, separators=(',', ':')).encode()).hexdigest()


def build_index(source, output_dir, vectorizer: Vectorizer, batch_size=64, progress=None):
    """Publier un index complet ; aucune création implicite pendant l'inférence."""
    if type(batch_size) is not int or not 1 <= batch_size <= 2048:
        raise ValueError('batch_size doit être compris entre 1 et 2048')
    source, output_dir = Path(source), Path(output_dir)
    if output_dir.exists():
        raise ValueError('Le dossier de sortie existe déjà ; choisir un nouveau dossier pour cet index.')
    catalog = Catalog(source)
    rows = candidate_rows(catalog)
    if len(rows) != len(catalog.rows):
        raise ValueError('Le précalcul attend uniquement les candidats de candidates.jsonl')
    output_dir.parent.mkdir(parents=True, exist_ok=True)
    started = perf_counter()
    batches, width, response_model = [], vectorizer.config.dimensions, None
    digest = hashlib.sha256()
    with tempfile.TemporaryDirectory(prefix='.embeddings-', dir=output_dir.parent) as temporary:
        stage = Path(temporary) / 'index'
        stage.mkdir()
        with (stage / 'vectors.jsonl').open('wb') as stream:
            for offset in range(0, len(rows), batch_size):
                batch_rows = rows[offset:offset + batch_size]
                result = vectorizer.embed([r['contextual_description'] for r in batch_rows])
                width = validate_vectors(result.vectors, len(batch_rows), width)
                if not isinstance(result.model, str) or not result.model:
                    raise ValueError('Modèle de réponse manquant')
                if response_model is not None and result.model != response_model:
                    raise ValueError('Le modèle retourné a changé pendant le précalcul')
                response_model = result.model
                for row, vector in zip(batch_rows, result.vectors):
                    line = (json.dumps({'code': row['code'], 'vector': vector}, allow_nan=False) + '\n').encode()
                    stream.write(line)
                    digest.update(line)
                batches.append({'offset': offset, 'count': len(batch_rows), 'usage': result.usage})
                if progress:
                    progress(offset + len(batch_rows), len(rows))
        manifest = {
            'schema_version': 1, 'created_at': datetime.now(timezone.utc).isoformat(),
            'edition': '2022', 'language': 'en', 'text_field': 'contextual_description',
            'provider': vectorizer.provider, 'config': vectorizer.config.to_dict(),
            'response_model': response_model, 'dimensions': width, 'count': len(rows),
            'source': {'path': str(source), 'sha256': catalog.sha256,
                       'descriptions_sha256': descriptions_sha256(rows)},
            'vectors_sha256': digest.hexdigest(), 'normalization': 'raw_on_disk_l2_in_memory',
            'batch_size': batch_size, 'batches': batches, 'duration_seconds': perf_counter() - started,
        }
        (stage / 'manifest.json').write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')
        # Le répertoire final n'apparaît qu'une fois tous les lots réussis.
        stage.rename(output_dir)
    return manifest


@dataclass(frozen=True)
class SearchHit:
    code: str
    rank: int
    score: float
    description: str
    contextual_description: str


class EmbeddingIndex:
    def __init__(self, directory, catalog: Catalog):
        try:
            import numpy as np
        except ImportError:
            raise ImportError("NumPy requis : installer le projet avec pip install -e '.[embeddings]'") from None

        self.directory = Path(directory)
        self.catalog = catalog
        self.manifest = json.loads((self.directory / 'manifest.json').read_text(encoding='utf-8'))
        manifest = self.manifest
        if (manifest.get('schema_version'), manifest.get('edition'), manifest.get('language'), manifest.get('text_field')) != (1, '2022', 'en', 'contextual_description'):
            raise ValueError('Version ou périmètre de l’index incompatible')
        self.config = EmbeddingConfig(**manifest['config'])
        if type(manifest['dimensions']) is not int or manifest['dimensions'] < 1:
            raise ValueError('Dimensions de l’index invalides')
        if self.config.dimensions is not None and self.config.dimensions != manifest['dimensions']:
            raise ValueError('Configuration et dimensions incohérentes')
        rows = candidate_rows(catalog)
        if descriptions_sha256(rows) != manifest['source']['descriptions_sha256']:
            raise ValueError('Le catalogue a changé ; reconstruire explicitement les embeddings.')
        self.codes = [row['code'] for row in rows]
        if manifest['count'] != len(self.codes):
            raise ValueError('Nombre de candidats incohérent')
        # Lecture ligne par ligne : pas de seconde copie géante en listes Python.
        self.matrix = np.empty((len(rows), manifest['dimensions']), dtype=np.float32)
        digest = hashlib.sha256()
        count = 0
        with (self.directory / 'vectors.jsonl').open('rb') as stream:
            for count, line in enumerate(stream, 1):
                digest.update(line)
                row = json.loads(line)
                if count > len(rows) or row['code'] != self.codes[count - 1]:
                    raise ValueError('Ordre, doublon ou code de vecteur incohérent')
                validate_vectors([row['vector']], 1, manifest['dimensions'])
                self.matrix[count - 1] = row['vector']
        if count != len(rows) or digest.hexdigest() != manifest['vectors_sha256']:
            raise ValueError('Index incomplet ou empreinte de vecteurs invalide')
        norms = np.linalg.norm(self.matrix.astype(np.float64), axis=1)
        if not np.isfinite(self.matrix).all() or not np.isfinite(norms).all() or (norms == 0).any():
            raise ValueError('Vecteurs incompatibles avec float32')
        self.matrix /= norms[:, None]
        self.matrix.flags.writeable = False

    def search(self, vector, top_k):
        """Recherche purement locale ; le RAG peut appeler cette même interface."""
        import numpy as np

        if type(top_k) is not int or top_k < 1:
            raise ValueError('top_k doit être un entier positif')
        validate_vectors([vector], 1, self.manifest['dimensions'])
        query = np.asarray(vector, dtype=np.float64)
        query = (query / math.hypot(*vector)).astype(np.float32)
        scores = np.clip(self.matrix @ query, -1.0, 1.0)
        # Tri stable : en cas d'égalité, départager par code (ordre de la matrice).
        indices = np.argsort(-scores, kind='stable')[:top_k]
        return [SearchHit(self.codes[i], rank, float(scores[i]),
                          self.catalog.rows[self.codes[i]]['description'],
                          self.catalog.rows[self.codes[i]]['contextual_description'])
                for rank, i in enumerate(indices, 1)]


class EmbeddingRetriever:
    """Vectorise une requête et cherche K voisins ; utilisable tel quel par un RAG."""
    def __init__(self, index: EmbeddingIndex, vectorizer: Vectorizer):
        if vectorizer.provider != index.manifest['provider'] or vectorizer.config != index.config:
            raise ValueError('Le vectoriseur doit utiliser le fournisseur, modèle et dimensions de l’index')
        self.index, self.vectorizer = index, vectorizer

    def retrieve(self, query, top_k):
        if not isinstance(query, str) or not query.strip() or type(top_k) is not int or top_k < 1:
            raise ValueError('Description non vide et top_k positif requis')
        start = perf_counter()
        result = self.vectorizer.embed([query])
        validate_vectors(result.vectors, 1, self.index.manifest['dimensions'])
        if result.model != self.index.manifest['response_model']:
            raise ValueError('Le modèle retourné diffère de celui utilisé pour l’index')
        hits = self.index.search(result.vectors[0], top_k)
        return hits, {'usage': result.usage, 'response_model': result.model,
                      'query_vector': result.vectors[0], 'duration_seconds': perf_counter() - start}
