#!/usr/bin/env python3
"""Génère artifacts/rag_report.html depuis scripts/rag_descriptions.csv."""
import csv
import os
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
if __package__ in (None, ''):
    sys.path.insert(0, str(ROOT))

from jinja2 import Environment, FileSystemLoader, select_autoescape

from hs_matching.approaches.base import PredictionContext
from hs_matching.approaches.rag import RAG
from hs_matching.catalog import Catalog
from hs_matching.config import load_env
from hs_matching.embedding_index import EmbeddingIndex, EmbeddingRetriever
from hs_matching.providers.openai import ModelConfig, OpenAIProvider
from hs_matching.vectorization.openai import OpenAIVectorizer
from hs_matching.web_presenters import STATUS_LABELS

MODEL = 'gpt-4.1-mini'
RETRIEVAL_K = 20
TOP_K = 5
INPUT_CSV = ROOT / 'scripts/rag_descriptions.csv'
OUTPUT_HTML = ROOT / 'artifacts/rag_report.html'


def read_descriptions():
    with INPUT_CSV.open(encoding='utf-8-sig', newline='') as stream:
        reader = csv.reader(stream, strict=True)
        if next(reader, None) != ['description']:
            raise ValueError('Le CSV doit contenir une seule colonne nommée description.')
        descriptions = []
        for row in reader:
            if not row or (len(row) == 1 and not row[0].strip()):
                continue
            if len(row) != 1:
                raise ValueError(f'CSV ligne {reader.line_num} : une seule colonne attendue ; entourer de guillemets les descriptions contenant une virgule.')
            descriptions.append(row[0])
    if not descriptions:
        raise ValueError('Le CSV ne contient aucune description.')
    return descriptions


def render_report(results):
    env = Environment(loader=FileSystemLoader(ROOT / 'hs_matching/templates'),
                      autoescape=select_autoescape(['html']))
    return env.get_template('rag_report.html').render(
        results=results, model=MODEL, retrieval_k=RETRIEVAL_K, top_k=TOP_K,
        status_labels=STATUS_LABELS,
        app_css=(ROOT / 'hs_matching/static/app.css').read_text(encoding='utf-8'))


def main():
    try:
        descriptions = read_descriptions()
        load_env(ROOT / '.env')
        catalog = Catalog(ROOT / 'data/processed/h6_2022/catalog.jsonl')
        index = EmbeddingIndex(ROOT / 'artifacts/embeddings/h6_2022', catalog)
        if index.manifest['provider'] != 'openai':
            raise ValueError('L’index doit utiliser le fournisseur OpenAI.')
        key = os.environ.get('OPENAI_API_KEY')
        retriever = EmbeddingRetriever(index, OpenAIVectorizer(key, index.config))
        rag = RAG(OpenAIProvider(key), ModelConfig(model=MODEL), retriever, RETRIEVAL_K)
        context = PredictionContext(catalog)
        results = []
        for position, description in enumerate(descriptions, 1):
            print(f'{position}/{len(descriptions)} — {description}', flush=True)
            prediction = rag.predict(description, TOP_K, context)
            results.append({'description': description, 'prediction': prediction.to_dict()})
        html = render_report(results)
        OUTPUT_HTML.parent.mkdir(parents=True, exist_ok=True)
        OUTPUT_HTML.write_text(html, encoding='utf-8')
        print(f'HTML généré : {OUTPUT_HTML}')
        return int(any(result['prediction']['status'] == 'error' for result in results))
    except (OSError, ValueError, csv.Error) as exc:
        print(f'Erreur : {exc}', file=sys.stderr)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
