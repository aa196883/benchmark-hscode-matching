"""Point d’entrée : python benchmark/benchmark.py --help."""
from pathlib import Path
import sys

if __package__ in (None, ''):
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from benchmark.cli import main

if __name__ == '__main__':
    raise SystemExit(main())
