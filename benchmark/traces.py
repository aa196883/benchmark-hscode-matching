"""Une trace MLflow native par ligne, avec évaluations déterministes."""
from time import perf_counter

from benchmark.metrics import row_metrics, token_counts
from hs_matching.approaches.base import Prediction

# Ne pas archiver les réponses HTTP brutes, les clés ni les vecteurs volumineux.
DETAIL_FIELDS = ('provider', 'config', 'response_model', 'prompt_version', 'prompt',
                 'rejected_candidates', 'retrieved_candidates', 'generation_duration_seconds',
                 'error_stage', 'refusals', 'abstention_reason')


def persist_trace(trace_id):
    import mlflow
    mlflow.flush_trace_async_logging()
    # Le SDK de tracing peut journaliser une erreur d'export sans la lever.
    # Ne pas annoncer un succès si la trace n'est pas consultable dans MLflow.
    if mlflow.get_trace(trace_id) is None:
        raise RuntimeError(f'Trace MLflow non sauvegardée : {trace_id}')


def predict_row(args, snapshot, row, run_id, approach, context):
    import mlflow
    from mlflow.entities import AssessmentSource, MlflowExperimentLocation

    interrupted = False
    tags = {'dataset.name': snapshot.name, 'dataset.id': snapshot.dataset_id,
            'dataset.version': str(snapshot.version), 'dataset.row_id': str(row['row_id']),
            'approach': args.approach}
    with mlflow.start_span(name=f'{args.approach} / ligne {row["row_id"]}', span_type='CHAIN',
                          trace_destination=MlflowExperimentLocation(snapshot.experiment_id),
                          run_id=run_id) as span:
        if not mlflow.get_active_trace_id():
            raise RuntimeError('Tracing MLflow désactivé ou échantillonné : une trace par ligne est requise')
        span.set_inputs({'description': row['description'], 'row_id': row['row_id']})
        span.set_attributes({'benchmark.top_k': args.top_k, 'benchmark.retrieval_k': args.retrieval_k,
                             'benchmark.requested_model': args.model,
                             'benchmark.catalog_sha256': context.catalog.sha256})
        mlflow.update_current_trace(tags=tags, metadata={'dataset.sha256': snapshot.sha256},
                                    client_request_id=f'{run_id}:{row["row_id"]}',
                                    request_preview=row['description'])
        start = perf_counter()
        try:
            prediction = approach.predict(row['description'], args.top_k, context).to_dict()
        except KeyboardInterrupt:
            interrupted = True
            prediction = Prediction('error', error={'kind': 'interrupted',
                                    'message': 'Prédiction interrompue par l’utilisateur.'}).to_dict()
        except Exception as exc:
            # Éviter que le tracing automatique ne capture un message d'exception sensible.
            prediction = Prediction('error', error={'kind': type(exc).__name__,
                                    'message': 'Erreur interne pendant la prédiction.'}).to_dict()
        elapsed = perf_counter() - start
        metadata = prediction.pop('metadata', {})
        counts = token_counts(metadata, args.approach)
        result = dict(row_id=row['row_id'], trace_id=span.trace_id, description=row['description'],
                      ground_truth=row['hs_code'], response_time=elapsed, **counts, answer=prediction)
        metrics = row_metrics(result)
        details = {key: metadata[key] for key in DETAIL_FIELDS if key in metadata}
        retrieval = metadata.get('retrieval', {})
        if retrieval:
            details['retrieval'] = {key: retrieval[key] for key in ('usage', 'response_model', 'duration_seconds')
                                    if key in retrieval}
        span.set_attributes({'benchmark.details': details, 'benchmark.metrics': metrics,
                             'benchmark.interrupted': interrupted})
        # MLflow complète les compteurs manquants par zéro : ne publier le total
        # natif que lorsque les deux compteurs sont connus.
        if metrics['total_tokens'] is not None:
            span.set_attribute('mlflow.chat.tokenUsage',
                               {key: metrics[key] for key in ('input_tokens', 'output_tokens', 'total_tokens')})
        span.set_outputs(prediction)
        if prediction['status'] == 'error':
            span.set_status('ERROR')
        mlflow.update_current_trace(tags={'prediction.status': 'interrupted' if interrupted else prediction['status']},
            response_preview=prediction['status'] + ' : ' + ', '.join(c['code'] for c in prediction['candidates']))
        source = AssessmentSource(source_type='CODE', source_id='hs-matching/benchmark')
        mlflow.log_expectation(trace_id=span.trace_id, name='hs_code', value=row['hs_code'], source=source)
        # Une interruption n'est pas une prédiction terminée : pas de score de classement.
        for name, value in metrics.items():
            if value is not None and (not interrupted or name == 'response_time'):
                mlflow.log_feedback(trace_id=span.trace_id, name=name, value=value, source=source)
    if interrupted:
        persist_trace(result['trace_id'])
        raise KeyboardInterrupt
    return result
