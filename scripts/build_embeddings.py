#!/usr/bin/env python3
"""Précalcul explicite des embeddings de candidates.jsonl (appels API payants)."""
import argparse
import os
from pathlib import Path
import sys

# Permet python scripts/build_embeddings.py sans installation du package.
if __package__ in (None, ''):
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from hs_matching.config import load_env
from hs_matching.embedding_index import build_index
from hs_matching.providers.openai import ProviderError
from hs_matching.vectorization.base import EmbeddingConfig
from hs_matching.vectorization.openai import OpenAIVectorizer


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input', default='data/processed/h6_2022/candidates.jsonl')
    parser.add_argument('--output-dir', default='artifacts/embeddings/h6_2022')
    parser.add_argument('--env-file', default='.env')
    parser.add_argument('--model', default='text-embedding-3-small')
    parser.add_argument('--dimensions', type=int)
    parser.add_argument('--batch-size', type=int, default=64)
    parser.add_argument('--timeout', type=float, default=60.0)
    args = parser.parse_args(argv)
    try:
        load_env(args.env_file)
        vectorizer = OpenAIVectorizer(os.environ.get('OPENAI_API_KEY'),
                                     EmbeddingConfig(args.model, args.dimensions), args.timeout)
        manifest = build_index(args.input, args.output_dir, vectorizer, args.batch_size,
                               progress=lambda done, total: print(f'{done}/{total} descriptions vectorisées', file=sys.stderr))
    except (OSError, ValueError, KeyError, ProviderError) as exc:
        print(f'Erreur : {exc}', file=sys.stderr)
        return 1
    print(f"{manifest['count']} vecteurs de dimension {manifest['dimensions']} → {args.output_dir}")
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
