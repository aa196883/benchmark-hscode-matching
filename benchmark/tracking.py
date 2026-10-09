"""Configuration MLflow locale indépendante du répertoire de lancement."""
import os
from pathlib import Path

ROOT = Path(__file__).resolve().parent
DEFAULT_URI = 'sqlite:///' + str(ROOT / 'mlflow.db')


def configure(uri=None):
    import mlflow
    uri = uri or os.environ.get('MLFLOW_TRACKING_URI') or DEFAULT_URI
    if not uri.startswith(('sqlite:', 'postgresql:', 'mysql:', 'mssql:', 'http://', 'https://')):
        raise ValueError('MLflow Evaluation Datasets nécessite un backend SQL ou un serveur HTTP avec backend SQL')
    mlflow.set_tracking_uri(uri)
    return uri


def experiment(name):
    import mlflow
    from mlflow.exceptions import MlflowException
    exp_name = f'hs-matching/{name}'
    existing = mlflow.get_experiment_by_name(exp_name)
    if existing is None:
        # Pour un serveur distant, laisser le serveur choisir son stockage d'artifacts.
        location = None
        if mlflow.get_tracking_uri().startswith('sqlite:'):
            from sqlalchemy.engine import make_url
            database = make_url(mlflow.get_tracking_uri()).database
            if database and database != ':memory:':
                location = (Path(database).resolve().parent / 'mlartifacts').as_uri()
        try:
            mlflow.create_experiment(exp_name, artifact_location=location)
        except MlflowException:
            if mlflow.get_experiment_by_name(exp_name) is None:
                raise
        existing = mlflow.get_experiment_by_name(exp_name)
    if existing.lifecycle_stage != 'active':
        raise ValueError(f'Expérience supprimée : {exp_name} ; la restaurer dans MLflow')
    return existing
