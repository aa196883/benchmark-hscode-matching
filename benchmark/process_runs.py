"""Agrégation des traces de benchmark et rapport Markdown."""
from collections import defaultdict
import glob
import json
import math
from pathlib import Path
import re
import sys


def group_runs(patterns):
    """Charge chaque fichier une seule fois et regroupe par modèle / approche."""
    groups = defaultdict(list)
    seen = set()
    for pattern in patterns:
        paths = sorted(glob.glob(str(pattern)))
        if not paths:
            raise ValueError(f'Aucun run trouvé : {pattern}')
        for filename in paths:
            path = Path(filename).resolve()
            if path in seen:
                continue
            seen.add(path)
            with path.open(encoding='utf-8') as stream:
                run = json.load(stream)
            if not isinstance(run, dict):
                raise ValueError(f'{path} : objet JSON requis')
            if not isinstance(run.get('model'), str) or not isinstance(run.get('approach'), str) or not run['approach']:
                raise ValueError(f'{path} : model et approach doivent être des chaînes, approach non vide')
            if not isinstance(run.get('results'), list):
                raise ValueError(f'{path} : results doit être une liste')
            if 'dataset_size' in run and run['dataset_size'] != len(run['results']):
                print(f'Attention : {path} : run partiel, analyse des {len(run["results"])} résultats présents.', file=sys.stderr)
            groups[run['model'], run['approach']].append((path, run['results']))
    return groups


def summarize(groups):
    summaries = {}
    for key, runs in sorted(groups.items()):
        count = 0
        matches = [0, 0, 0]
        times = []
        for path, results in runs:
            for number, result in enumerate(results, 1):
                where = f'{path}, résultat {number}'
                if not isinstance(result, dict):
                    raise ValueError(f'{where} : objet requis')
                truth = result.get('ground_truth')
                if not isinstance(truth, str) or re.fullmatch(r'[0-9]{6}', truth) is None:
                    raise ValueError(f'{where} : ground_truth doit être un code de six chiffres')
                duration = result.get('response_time')
                if type(duration) not in (int, float) or not math.isfinite(duration) or duration < 0:
                    raise ValueError(f'{where} : response_time doit être un nombre fini positif ou nul')
                answer = result.get('answer')
                if not isinstance(answer, dict) or not isinstance(answer.get('candidates'), list):
                    raise ValueError(f'{where} : answer.candidates doit être une liste')
                codes = []
                # Les abstentions et erreurs restent dans le dénominateur.
                if answer.get('status') in ('ok', 'needs_info'):
                    for candidate in answer['candidates']:
                        code = candidate.get('code') if isinstance(candidate, dict) else None
                        if isinstance(code, str) and re.fullmatch(r'[0-9]{6}', code):
                            codes.append(code)
                for i, length in enumerate((2, 4, 6)):
                    matches[i] += any(code[:length] == truth[:length] for code in codes)
                count += 1
                times.append(duration)
        summaries[key] = {'count': count, 'matches': matches,
                          'mean_time': math.fsum(times) / count if count else None}
    return summaries


def markdown(summaries):
    def escape(value):
        return value.replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;').replace('|', '&#124;').replace('\n', ' ').replace('\r', ' ')

    lines = ['| Modèle / approche | Chapitre (2 chiffres) | Position (4 chiffres) | Sous-position (6 chiffres) | Temps moyen (s) |',
             '| --- | ---: | ---: | ---: | ---: |']
    for (model, approach), summary in summaries.items():
        count = summary['count']
        fractions = [f'{n}/{count} ({n / count:.2%})' if count else 'N/A (0 résultat)' for n in summary['matches']]
        duration = f'{summary["mean_time"]:.3f}' if count else 'N/A'
        label = f'{escape(model) if model else "(sans modèle)"} / {escape(approach)}'
        lines.append('| ' + ' | '.join([label, *fractions, duration]) + ' |')
    return '\n'.join(lines) + '\n'


def process_runs(patterns, output):
    report = markdown(summarize(group_runs(patterns)))
    output = Path(output)
    # Évite de remplacer accidentellement une trace d'entrée.
    if output.suffix.lower() != '.md':
        raise ValueError('--output doit avoir une extension .md')
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(report, encoding='utf-8')
    return output
