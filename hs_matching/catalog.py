import hashlib
import json
from pathlib import Path
import re


class Catalog:
    def __init__(self, path: str | Path):
        self.path = Path(path)
        raw = self.path.read_bytes()
        self.sha256 = hashlib.sha256(raw).hexdigest()
        self.rows = {}
        text = raw.decode('utf-8')
        decoder = json.JSONDecoder()
        offset = 0
        whitespace_pattern = re.compile(r'\s*')
        # Accepte JSONL et les objets JSON successifs remis en forme à la main.
        while offset < len(text):
            offset = whitespace_pattern.match(text, offset).end()
            if offset == len(text):
                break
            row, offset = decoder.raw_decode(text, offset)
            code = row['code']
            if not isinstance(code, str) or code in self.rows:
                raise ValueError('Catalogue : code invalide ou dupliqué')
            if row['edition'] != '2022' or row['language'] != 'en':
                raise ValueError('Catalogue attendu : HS 2022 anglais')
            if not isinstance(row['description'], str) or not row['description'].strip():
                raise ValueError('Catalogue : description invalide')
            self.rows[code] = row
        if not self.rows:
            raise ValueError('Catalogue vide')

    def rejection_reason(self, code: str) -> str | None:
        if not isinstance(code, str) or not re.fullmatch(r'[0-9]{6}', code):
            return 'invalid_format'
        row = self.rows.get(code)
        if row is None:
            return 'unknown_code'
        if row['level'] != 6 or row['is_candidate'] is not True or row.get('is_special', False):
            return 'ineligible_code'
        return None
