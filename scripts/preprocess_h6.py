#!/usr/bin/env python3
"""Préparer le catalogue Comtrade H6, sans dépendance externe."""

import argparse
from collections import Counter
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re


SOURCE_URL = "https://comtradeapi.un.org/files/v1/app/reference/H6.json"
WCO_URL = "https://www.wcoomd.org/en/topics/nomenclature/instrument-and-tools/hs-nomenclature-2022-edition/hs-nomenclature-2022-edition.aspx"


def prepare(payload):
    """Valider avant export ; préserver les champs source de chaque ligne."""
    if payload.get("classCode") != "H6" or payload.get("className") != "HS2022":
        raise ValueError("Le fichier doit être le référentiel H6 / HS2022")
    if payload.get("more") is not False:
        raise ValueError("Le fichier ne garantit pas un résultat complet (more)")
    nodes, excluded, seen = {}, [], set()
    for source in payload["results"]:
        code, level = source["id"], source["aggrlevel"]
        if code in seen:
            raise ValueError(f"Code dupliqué : {code}")
        seen.add(code)
        if code == "TOTAL" or level not in (2, 4, 6):
            excluded.append({"reason": "total" if code == "TOTAL" else "unsupported_level", "source": source})
            continue
        if not isinstance(code, str) or not re.fullmatch(r"[0-9]{%d}" % level, code):
            raise ValueError(f"Format de code invalide : {code!r}")
        if source["isLeaf"] not in ("0", "1"):
            raise ValueError(f"isLeaf invalide : {code}")
        text = source["text"]
        prefix = code + " - "
        description = " ".join((text[len(prefix):] if text.startswith(prefix) else text).split())
        if not description:
            raise ValueError(f"Libellé vide : {code}")
        if level == 2 and source["parent"] not in ("TOTAL", "#"):
            raise ValueError(f"Racine invalide : {code}")
        special = not (1 <= int(code[:2]) <= 97) or code[:2] == "77"
        nodes[code] = {
            "code": code, "edition": "2022", "language": "en", "level": level,
            "parent_code": None if level == 2 else source["parent"],
            "description": description, "is_leaf": source["isLeaf"] == "1",
            "is_special": special, "is_candidate": level == 6 and not special,
            "source": source,
        }
    children = Counter()
    for code, node in nodes.items():
        parent = node["parent_code"]
        if parent is not None:
            if parent not in nodes or nodes[parent]["level"] != node["level"] - 2 or parent != code[:-2]:
                raise ValueError(f"Parent absent ou incohérent : {code} → {parent}")
            children[parent] += 1
    for code, node in nodes.items():
        if node["is_leaf"] != (children[code] == 0) or node["is_leaf"] != (node["level"] == 6):
            raise ValueError(f"Feuille incohérente : {code}")
        ancestors = []
        parent = node["parent_code"]
        while parent is not None:
            ancestors.append(parent)
            parent = nodes[parent]["parent_code"]
        node["ancestor_codes"] = ancestors[::-1]
        node["contextual_description"] = " > ".join(
            nodes[item]["description"] for item in node["ancestor_codes"] + [code]
        )
    return [nodes[code] for code in sorted(nodes)], excluded


def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, default=Path("H6.json"))
    parser.add_argument("--output-dir", type=Path, default=Path("data/processed/h6_2022"))
    parser.add_argument("--retrieved-at", help="Date réelle de récupération, si connue (ISO 8601)")
    args = parser.parse_args()
    if args.retrieved_at:
        datetime.fromisoformat(args.retrieved_at)
    raw = args.input.read_bytes()
    rows, excluded = prepare(json.loads(raw))
    candidates = [row for row in rows if row["is_candidate"]]
    outputs = ("catalog.jsonl", "candidates.jsonl", "excluded.json", "manifest.json")
    if args.input.resolve() in {(args.output_dir / name).resolve() for name in outputs}:
        raise ValueError("Le fichier brut ne doit pas être écrasé")
    args.output_dir.mkdir(parents=True, exist_ok=True)
    artifacts = {}
    for name, items in (("catalog.jsonl", rows), ("candidates.jsonl", candidates)):
        content = "".join(json.dumps(row, ensure_ascii=False) + "\n" for row in items).encode("utf-8")
        (args.output_dir / name).write_bytes(content)
        artifacts[name] = {"rows": len(items), "sha256": hashlib.sha256(content).hexdigest()}
    write_json(args.output_dir / "excluded.json", excluded)
    manifest = {
        "schema_version": 1, "edition": "2022", "language": "en",
        "processed_at": datetime.now(timezone.utc).isoformat(),
        "source": {"path": str(args.input), "url": SOURCE_URL, "retrieved_at": args.retrieved_at,
                   "sha256": hashlib.sha256(raw).hexdigest(), "bytes": len(raw),
                   "format": "JSON / results", "reuse_conditions": "not_verified"},
        "script_sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        "artifacts": artifacts,
        "counts_by_level": dict(sorted(Counter(row["level"] for row in rows).items())),
        "excluded_rows": len(excluded),
        "special_codes": [row["code"] for row in rows if row["is_special"]],
        "validation": {"structure": "passed", "wco_reference_url": WCO_URL,
                       "wco_code_by_code_comparison": "not_performed",
                       "coverage": "Comtrade H6; candidate chapters 01–97 except 77"},
        "transformations": ["Keep levels 2, 4, 6; exclude TOTAL", "Root parents become null",
                            "Remove exact code prefix; collapse whitespace; preserve source fields",
                            "Concatenate ancestor descriptions from chapter to subheading",
                            "Exclude chapters outside 01–97 and chapter 77 from candidates"],
    }
    write_json(args.output_dir / "manifest.json", manifest)
    print(f"{len(rows)} nœuds, {len(candidates)} candidats, {len(excluded)} lignes exclues → {args.output_dir}")


if __name__ == "__main__":
    main()
