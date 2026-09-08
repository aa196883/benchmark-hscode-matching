"""GUI locale Flask/Jinja ; calculs séquentiels via le moteur commun."""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import secrets
from time import perf_counter
from uuid import uuid4

from flask import Flask, abort, redirect, render_template, request, send_file, session, url_for

from hs_matching.approaches.base import Prediction
from hs_matching.config import load_env
from hs_matching.demo import DEMO_QUERY, demo_run
from hs_matching.experiments import save_run
from hs_matching.providers.openai import ModelConfig, ProviderError
from hs_matching.web_presenters import APPROACHES, present
from hs_matching.web_runner import WebRunner


def create_app(config=None, runner=None):
    app = Flask(__name__)
    app.config.from_mapping(SECRET_KEY=secrets.token_hex(32), DEMO=False,
        CATALOG_PATH='data/processed/h6_2022/catalog.jsonl', INDEX_PATH='artifacts/embeddings/h6_2022',
        RUNS_DIR='runs/web', ENV_FILE='.env', MAX_CONTENT_LENGTH=64 * 1024,
        SESSION_COOKIE_HTTPONLY=True, SESSION_COOKIE_SAMESITE='Lax')
    app.config.update(config or {})
    if not app.config['DEMO']:
        load_env(app.config['ENV_FILE'])
    engine = runner or (demo_run if app.config['DEMO'] else WebRunner(app.config['CATALOG_PATH'], app.config['INDEX_PATH']))

    def defaults():
        return {'query': DEMO_QUERY if app.config['DEMO'] else '', 'approaches': list(APPROACHES),
                'top_k': 3, 'retrieval_k': 20, 'model': os.environ.get('OPENAI_MODEL', 'gpt-4.1-mini'),
                'max_output_tokens': 2048, 'temperature': '', 'reasoning_effort': '', 'timeout': 60, 'scenario': 'standard'}

    def page(options, comparison=None, error=None):
        session.setdefault('csrf', secrets.token_urlsafe(24))
        return render_template('index.html', options=options, approaches=APPROACHES, comparison=comparison,
            columns=[present(run) for run in comparison['runs']] if comparison else [],
            error=error, demo=app.config['DEMO'])

    def read_comparison(run_id):
        if len(run_id) != 32 or any(c not in '0123456789abcdef' for c in run_id):
            abort(404)
        path = Path(app.config['RUNS_DIR']) / (run_id + '.json')
        if not path.is_file():
            abort(404)
        return path, json.loads(path.read_text(encoding='utf-8'))

    @app.route('/', methods=['GET', 'POST'])
    def index():
        if request.method == 'GET':
            return page(defaults())
        if not secrets.compare_digest(session.get('csrf', ''), request.form.get('csrf', '')) or 'csrf' not in session:
            return page(defaults(), error='La session a expiré. Relancez la comparaison.'), 400
        options = {**defaults(), **{k: request.form.get(k, '') for k in defaults() if k != 'approaches'}}
        selected = request.form.getlist('approaches')
        options['approaches'] = [a for a in APPROACHES if a in selected]
        try:
            if not selected or any(a not in APPROACHES for a in selected):
                raise ValueError('Sélectionnez au moins une approche disponible.')
            if not options['query'].strip() or len(options['query']) > 12000:
                raise ValueError('Saisissez une description de 1 à 12 000 caractères.')
            for field in ('top_k', 'retrieval_k', 'max_output_tokens'):
                options[field] = int(options[field])
            if not 1 <= options['top_k'] <= 50:
                raise ValueError('Le nombre de résultats doit être compris entre 1 et 50.')
            if 'rag' in selected and not options['top_k'] <= options['retrieval_k'] <= 200:
                raise ValueError('Le nombre de voisins RAG doit être au moins égal au nombre de résultats, et au plus égal à 200.')
            options['timeout'] = float(options['timeout'])
            options['temperature'] = float(options['temperature']) if options['temperature'] else None
            options['reasoning_effort'] = options['reasoning_effort'].strip() or None
            ModelConfig(model=options['model'], max_output_tokens=options['max_output_tokens'],
                        timeout=options['timeout'], temperature=options['temperature'])
        except (ValueError, TypeError) as exc:
            return page(options, error=str(exc)), 400
        comparison = {'schema_version': 1, 'run_id': uuid4().hex, 'created_at': datetime.now(timezone.utc).isoformat(),
                      'options': options, 'demo': app.config['DEMO'], 'runs': []}
        for approach in options['approaches']:
            start = perf_counter()
            try:
                run = engine(approach, options)
            except Exception as exc:
                if isinstance(exc, ProviderError):
                    error = exc.details
                elif isinstance(exc, FileNotFoundError):
                    error = {'kind': 'missing_data', 'message': 'Catalogue ou index introuvable. Vérifiez les données et le précalcul des embeddings.'}
                elif isinstance(exc, (ValueError, ImportError)):
                    error = {'kind': 'configuration', 'message': str(exc)}
                else:
                    error = {'kind': 'internal_error', 'message': 'Cette approche a rencontré une erreur interne.'}
                run = {'predictions': [Prediction('error', metadata={'approach': approach,
                       'config': {'model': options['model'] if approach != 'embeddings' else 'Indisponible'}}, error=error).to_dict()]}
            elapsed = perf_counter() - start
            metadata = run['predictions'][0]['metadata']
            if not app.config['DEMO'] or 'total_duration_seconds' not in metadata:
                metadata['total_duration_seconds'] = elapsed
            comparison['runs'].append(run)
        try:
            save_run(comparison, app.config['RUNS_DIR'])
        except OSError:
            comparison['saved'] = False
            return page(options, comparison, error='Résultats calculés, mais leur sauvegarde a échoué. Vérifiez le dossier des runs.'), 500
        return redirect(url_for('comparison', run_id=comparison['run_id']))

    @app.get('/comparisons/<run_id>')
    def comparison(run_id):
        _, result = read_comparison(run_id)
        return page(result['options'], result)

    @app.get('/comparisons/<run_id>/download')
    def download(run_id):
        path, _ = read_comparison(run_id)
        return send_file(path.resolve(), as_attachment=True, download_name=f'hs-comparison-{run_id}.json', mimetype='application/json')

    return app


def main():
    parser = argparse.ArgumentParser(description='Interface locale de comparaison HS')
    parser.add_argument('--demo', action='store_true', help='Données simulées, sans appel API')
    parser.add_argument('--port', type=int, default=5000)
    parser.add_argument('--catalog', default='data/processed/h6_2022/catalog.jsonl')
    parser.add_argument('--index', default='artifacts/embeddings/h6_2022')
    parser.add_argument('--runs-dir')
    parser.add_argument('--env-file', default='.env')
    args = parser.parse_args()
    app = create_app({'DEMO': args.demo, 'CATALOG_PATH': args.catalog, 'INDEX_PATH': args.index,
                      'RUNS_DIR': args.runs_dir or ('runs/demo' if args.demo else 'runs/web'), 'ENV_FILE': args.env_file})
    app.run(host='127.0.0.1', port=args.port, debug=False, threaded=False)


if __name__ == '__main__':
    main()
