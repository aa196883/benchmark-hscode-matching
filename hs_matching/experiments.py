"""Exécution et sauvegarde partagées par les interfaces."""
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import subprocess
from uuid import uuid4

from hs_matching.approaches.base import Prediction
from hs_matching.approaches.registry import create_approach


def run_prediction(query, top_k, context, configs, provider, approach='llm_direct', *, retriever=None, retrieval_k=20):
    predictions = []
    for config in configs:
        try:
            if approach == 'embeddings' and (retriever is None or config != retriever.index.config):
                raise ValueError('Configuration incompatible avec le retriever')
            dependencies = {'retriever': retriever} if approach == 'embeddings' else {'provider': provider, 'config': config}
            if approach == 'rag':
                dependencies.update(retriever=retriever, retrieval_k=retrieval_k)
            prediction = create_approach(approach, **dependencies).predict(query, top_k, context)
        except Exception as exc:
            # Une erreur inattendue ne doit pas empêcher les autres modèles de répondre.
            prediction = Prediction(status='error', metadata={'approach': approach, 'config': config.to_dict()},
                                    error={'kind': type(exc).__name__, 'message': 'Erreur interne pendant la prédiction.'})
        predictions.append(prediction.to_dict())
    root = Path(__file__).resolve().parents[1]
    try:
        revision = subprocess.run(['git', 'rev-parse', 'HEAD'], cwd=root, capture_output=True, text=True, check=True).stdout.strip()
        dirty = bool(subprocess.run(['git', 'status', '--porcelain'], cwd=root, capture_output=True, text=True, check=True).stdout)
    except (OSError, subprocess.CalledProcessError):
        revision, dirty = None, None
    sources = {str(p.relative_to(root)): hashlib.sha256(p.read_bytes()).hexdigest()
               for p in sorted((root / 'hs_matching').rglob('*.py'))}
    return {'schema_version': 1, 'run_id': uuid4().hex,
            'created_at': datetime.now(timezone.utc).isoformat(), 'query': query, 'top_k': top_k,
            'catalog': {'path': str(context.catalog.path), 'sha256': context.catalog.sha256},
            'code': {'git_revision': revision, 'dirty': dirty, 'source_sha256': sources}, 'predictions': predictions}


def save_run(run, directory='runs'):
    directory = Path(directory)
    directory.mkdir(parents=True, exist_ok=True)
    path = directory / (run['run_id'] + '.json')
    with path.open('x', encoding='utf-8') as stream:
        json.dump(run, stream, ensure_ascii=False, indent=2)
        stream.write('\n')
    return path
