import argparse
import csv
import io
import sys
from pathlib import Path

SYMBOL_COLS = ("element symbol", "gene symbol")
TYPE_COLS = ("element type", "type")


def read_text(path):
    raw = Path(path).read_bytes()
    if raw.startswith((b"\xff\xfe", b"\xfe\xff")):
        return raw.decode("utf-16")
    if raw.startswith(b"\xef\xbb\xbf"):
        return raw.decode("utf-8-sig")
    try:
        return raw.decode("utf-8")
    except UnicodeDecodeError:
        return raw.decode("cp1251")


def find_col(header, candidates):
    low = {h.strip().lower(): h for h in header}
    for c in candidates:
        if c in low:
            return low[c]
    return None


def main():
    ap = argparse.ArgumentParser(description="AMRFinderPlus TSV -> truth.txt")
    ap.add_argument("input", help="TSV-файл с выводом AMRFinderPlus")
    ap.add_argument("-o", "--out", default="truth.txt", help="выходной файл (по умолчанию truth.txt)")
    ap.add_argument("--all", action="store_true",
                    help="брать все типы элементов, а не только AMR")
    args = ap.parse_args()

    reader = csv.DictReader(io.StringIO(read_text(args.input)), delimiter="\t")
    if not reader.fieldnames:
        sys.exit("Пустой входной файл")
    sym_col = find_col(reader.fieldnames, SYMBOL_COLS)
    if not sym_col:
        sys.exit(f"Не найдена колонка с символом гена ({' / '.join(SYMBOL_COLS)}). "
                 f"Колонки в файле: {reader.fieldnames}")
    type_col = find_col(reader.fieldnames, TYPE_COLS)

    genes = []
    for row in reader:
        gene = (row.get(sym_col) or "").strip()
        if not gene or gene.upper() == "NA":
            continue
        if type_col and not args.all and (row.get(type_col) or "").strip().upper() != "AMR":
            continue
        if gene not in genes:
            genes.append(gene)

    Path(args.out).write_text("\n".join(genes) + ("\n" if genes else ""), encoding="utf-8")
    print(f"Генов: {len(genes)} -> {Path(args.out).resolve()}")
    for g in genes:
        print(" ", g)


if __name__ == "__main__":
    main()
