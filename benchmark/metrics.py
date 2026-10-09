"""Mesures déterministes, sans juge LLM ni appel API."""
import re


def token_counts(metadata, approach):
    """Normalise les usages fournisseurs et inclut la récupération pour le RAG."""
    def counts(usage, embedding=False):
        usage = usage if isinstance(usage, dict) else {}
        def count(primary, alternate):
            value = usage.get(primary, usage.get(alternate))
            return value if type(value) is int and value >= 0 else None
        return (count('input_tokens', 'prompt_tokens'),
                0 if embedding else count('output_tokens', 'completion_tokens'))

    input_tokens, output_tokens = counts(metadata.get('usage'), approach == 'embeddings')
    if approach == 'rag':
        retrieval = metadata.get('retrieval') or {}
        retrieval_input, _ = counts(retrieval.get('usage'), embedding=True)
        input_tokens = (input_tokens + retrieval_input
                        if input_tokens is not None and retrieval_input is not None else None)
    return {'input_tokens': input_tokens, 'output_tokens': output_tokens}


def row_metrics(row):
    """Scores d'une ligne ; le même calcul alimente traces et agrégats."""
    answer = row['answer']
    codes = [c['code'] for c in answer['candidates']
             if isinstance(c.get('code'), str) and re.fullmatch(r'[0-9]{6}', c['code'])]
    metrics = {f'{label}_match': answer['status'] in ('ok', 'needs_info') and any(
        code[:width] == row['ground_truth'][:width] for code in codes)
        for label, width in (('chapter', 2), ('heading', 4), ('hs6', 6))}
    metrics.update(response_time=row['response_time'], input_tokens=row['input_tokens'],
                   output_tokens=row['output_tokens'],
                   total_tokens=(row['input_tokens'] + row['output_tokens']
                                 if row['input_tokens'] is not None and row['output_tokens'] is not None else None),
                   candidate_count=len(answer['candidates']), error=answer['status'] == 'error')
    return metrics


def summarize(results):
    count = len(results)
    metrics = {'evaluated_rows': count}
    if not count:
        return metrics
    individual = [row_metrics(row) for row in results]
    for label in ('chapter', 'heading', 'hs6'):
        metrics[f'{label}_accuracy'] = sum(row[f'{label}_match'] for row in individual) / count
    metrics['mean_response_time'] = sum(r['response_time'] for r in results) / count
    for status in ('ok', 'needs_info', 'abstained', 'error'):
        metrics[f'{status}_count'] = sum(r['answer']['status'] == status for r in results)
        metrics[f'{status}_rate'] = metrics[f'{status}_count'] / count
    for name in ('input_tokens', 'output_tokens', 'total_tokens'):
        values = [(r['input_tokens'] + r['output_tokens']
                   if r['input_tokens'] is not None and r['output_tokens'] is not None else None)
                  if name == 'total_tokens' else r[name] for r in results]
        known = [value for value in values if value is not None]
        metrics[f'{name}_measured_rows'] = len(known)
        if known:
            metrics[f'mean_{name}'] = sum(known) / len(known)
            metrics[f'sum_{name}'] = sum(known)
    return metrics
