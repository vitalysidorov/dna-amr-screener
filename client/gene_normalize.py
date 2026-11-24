from __future__ import annotations

import re
from typing import Iterable, Set

_TRAILING_JUNK = re.compile(
    r"(?:_partial.*|_blast|_internal.*|\*\*\*.*)$",
    re.IGNORECASE,
)

_POINT_MUT = re.compile(
    r"^.+_"
    r"(?:"
    r"[A-Z]\d+[A-Z]"           # S83L
    r"|\d+[A-Z]"               # 2505A-ish
    r"|[A-Z]?\d+_\w*del"       # N82_Y310del
    r"|\w*ins\w*"              # ins
    r"|.+fs"                   # frameshift
    r"|.+Ter\w*"
    r")$",
    re.IGNORECASE,
)

_PRIME = re.compile(r"[\'\u2032\u00B4`]")
_DBL_PRIME = re.compile(r"[\"\u2033]")

_ALLELE_TAIL = re.compile(
    r"^(?P<family>.+?)(?P<allele>-\d+[a-z]?)$",
    re.IGNORECASE,
)

_BLA_FAMILIES = re.compile(
    r"^bla(?P<body>"
    r"ndm|kpc|oxa|vim|imp|ges|tem|shv|ctx-?m|per|veb|bel|cmy|dha|fox|mox|"
    r"acc|act|mir|lat|sme|imi|nmc|friend|pc|sed|cbla|z|"
    r"[a-z]{2,10}"
    r")",
    re.IGNORECASE,
)


def is_point_mutation(symbol: str) -> bool:
    if not symbol or not symbol.strip():
        return False
    s = symbol.strip()
    if "***" in s:  # disruption marker
        return True
    return bool(_POINT_MUT.match(s.replace(" ", "")))


def _basic_clean(name: str) -> str:
    s = name.strip()
    s = s.split()[0] if s else s
    s = _TRAILING_JUNK.sub("", s)
    s = s.replace("_", "-")
    s = _PRIME.sub("'", s)
    s = _DBL_PRIME.sub("''", s)
    s = re.sub(r"\s+", "", s)
    s = s.lower()
    # частые опечатки/варианты
    s = s.replace("delta", "d")
    return s


def strip_bla_prefix(s: str) -> str:
    if s.startswith("bla-") and len(s) > 4:
        return s[4:]
    if s.startswith("bla") and len(s) > 3 and s[3].isalpha():
        return s[3:]
    return s


def normalize_allele(name: str, *, drop_bla: bool = True) -> str:
    if not name or not str(name).strip():
        return ""
    s = _basic_clean(str(name))
    if drop_bla:
        s = strip_bla_prefix(s)
    s = re.sub(r"^tet([a-z])$", r"tet(\1)", s)
    s = re.sub(r"^erm([a-z])$", r"erm(\1)", s)
    return s


def normalize_family(name: str, *, drop_bla: bool = True) -> str:
    s = normalize_allele(name, drop_bla=drop_bla)
    if not s:
        return ""
    s = re.sub(r"^(mcr)-\d+(?:\.\d+)?$", r"\1", s, flags=re.I)
    m = _ALLELE_TAIL.match(s)
    if m:
        fam = m.group("family")
        if len(fam) >= 3:
            return fam
    m2 = re.match(r"^(van|mec|tet|erm|sul|dfr)([a-z])$", s, re.I)
    if m2:
        return m2.group(1).lower()
    return s


def expand_fusions(name: str) -> Set[str]:
    if not name:
        return set()
    parts = re.split(r"[/|:]", name)
    return {p.strip() for p in parts if p.strip()}


def names_to_set(
    names: Iterable[str],
    *,
    level: str = "allele",
    drop_bla: bool = True,
    skip_point_mutations: bool = True,
) -> Set[str]:
    out: Set[str] = set()
    norm = normalize_allele if level == "allele" else normalize_family
    for raw in names:
        if raw is None:
            continue
        for piece in expand_fusions(str(raw)):
            if skip_point_mutations and is_point_mutation(piece):
                continue
            key = norm(piece, drop_bla=drop_bla)
            if key:
                out.add(key)
    return out

def load_screener_json(path: str) -> Set[str]:
    import json
    from pathlib import Path

    data = json.loads(Path(path).read_text(encoding="utf-8"))
    if isinstance(data, dict) and "genes" in data:
        data = data["genes"]
    if not isinstance(data, list):
        return set()
    names = []
    for row in data:
        if isinstance(row, dict):
            names.append(row.get("geneId") or row.get("gene") or "")
        elif isinstance(row, str):
            names.append(row)
    return set(names)

def load_amrfinder_tsv(path: str) -> Set[str]:
    import csv
    from pathlib import Path

    text = Path(path).read_text(encoding="utf-8", errors="replace")
    lines = text.splitlines()
    if not lines:
        return set()
    reader = csv.DictReader(lines, delimiter="\t")
    names = []
    for row in reader:
        row_n = {re.sub(r"\s+", " ", k.strip()): v for k, v in row.items() if k}
        t = (row_n.get("Type") or row_n.get("type") or "AMR").strip().upper()
        if t and t not in ("AMR", "AMR-SUSCEPTIBLE"):
            if t in ("VIRULENCE", "STRESS"):
                continue
        sym = (
            row_n.get("Element symbol")
            or row_n.get("Gene symbol")
            or row_n.get("gene symbol")
            or ""
        ).strip()
        if sym:
            names.append(sym)
    return set(names)

def load_rgi_tsv(path: str) -> Set[str]:
    import csv
    from pathlib import Path

    text = Path(path).read_text(encoding="utf-8", errors="replace")
    lines = text.splitlines()
    if not lines:
        return set()
    if lines[0].startswith("#"):
        lines[0] = lines[0].lstrip("#").strip()
    reader = csv.DictReader(lines, delimiter="\t")
    names = []
    for row in reader:
        row_n = {k.strip(): v for k, v in row.items() if k}
        cut = (row_n.get("Cut_Off") or row_n.get("Cut-Off") or "").strip().lower()
        if cut and cut not in ("perfect", "strict", ""):
            if cut == "loose":
                continue
        aro = (row_n.get("Best_Hit_ARO") or row_n.get("ARO Term") or "").strip()
        if aro:
            names.append(aro)
    return set(names)

def load_plain_list(path: str) -> Set[str]:
    from pathlib import Path

    names = []
    for line in Path(path).read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        names.append(line.split("\t")[0].strip())
    return set(names)


if __name__ == "__main__":
    demos = [
        "blaNDM-1",
        "NDM-1",
        "bla_NDM_1",
        "blaCTX-M-15",
        "CTX-M-15",
        "aac(6')-Ib-cr",
        "AAC(6')-Ib-cr",
        "mecA",
        "vanA",
        "mcr-1",
        "mcr-1.1",
        "tet(A)",
        "tetA",
        "gyrA_S83L",
        "blaTEM-156",
        "aac(6')-Ie/aph(2'')-Ia",
    ]
    print(f"{'raw':<28} {'allele':<20} {'family':<16} point")
    for d in demos:
        print(f"{d:<28} {normalize_allele(d):<20} {normalize_family(d):<16} {is_point_mutation(d)}")
