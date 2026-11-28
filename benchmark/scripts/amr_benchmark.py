SYNONYMS = {
    "aac6-ib-cr1": "aac(6')-ib-cr1",
    "aac-6-ib-cr1": "aac(6')-ib-cr1",
    "aac(6')ib-cr1": "aac(6')-ib-cr1",
    "aac6-ib-cr": "aac(6')-ib-cr",
    "aac-6-ib-cr": "aac(6')-ib-cr",
}

FAMILIES = {
    "oxa48": "oxa-48-like",
    "oxa566": "oxa-48-like",
}

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


def normalize(name):
    s = name.strip().lower()
    s = re.sub(r"[’‘`´′]", "'", s)
    s = re.sub(r"\s+", "", s)
    s = s.replace("_", "-")
    s = re.sub(r"^bla(?=[a-z])", "", s)
    s = re.sub(r"(?<=[a-z])-(?=\d+$)", "", s)
    return SYNONYMS.get(s, s)


def family(gene):
    return FAMILIES.get(gene) or re.sub(r"-?\d+$", "", gene)


def load_predictions(path):
    data = json.loads(read_text(path))
    if isinstance(data, dict):
        data = [data]
    return [(r["geneId"], float(r.get("matchPercentage") or 0))
            for r in data if isinstance(r, dict) and r.get("geneId")]


def load_truth(path):
    return [line.strip() for line in read_text(path).splitlines() if line.strip()]


def detect_profile(stem):
    m = re.search(r"[_\-.](strict|relaxed)$", stem, re.I)
    return m.group(1).lower() if m else "unknown"


def sample_name(stem):
    return re.sub(r"[_\-.](strict|relaxed)$", "", stem, flags=re.I)


def div(a, b):
    return a / b if b else 0.0


def calc(P, T):
    tp, fp, fn = len(P & T), len(P - T), len(T - P)
    prec, rec = div(tp, tp + fp), div(tp, tp + fn)
    return dict(n_pred=len(P), n_truth=len(T), TP=tp, FP=fp, FN=fn,
                precision=round(prec, 4), recall=round(rec, 4),
                f1=round(div(2 * prec * rec, prec + rec), 4),
                TP_genes="; ".join(sorted(P & T)),
                FP_genes="; ".join(sorted(P - T)),
                FN_genes="; ".join(sorted(T - P)))


def top1(preds, truth):
    best = {}
    for name, pct in preds:
        g = normalize(name)
        best[g] = max(best.get(g, 0.0), pct)
    hits, parts = 0, []
    for t in sorted(truth):
        if t not in best:
            parts.append(f"{t}: не найден")
            continue
        fam = [g for g in best if family(g) == family(t)]
        rank = 1 + sum(best[g] > best[t] for g in fam)
        ties = sum(best[g] == best[t] for g in fam) - 1
        hits += rank == 1
        parts.append(f"{t}: ранг {rank} из {len(fam)}" + (f" (ничьих: {ties})" if ties else ""))
    return hits, len(truth), "; ".join(parts)


def main():
    ap = argparse.ArgumentParser(description="Gene-level бенчмарк AMR-сервиса")
    ap.add_argument("-p", "--pred", nargs="+", required=True,
                    help="JSON-файлы ответов API (можно маски)")
    ap.add_argument("-t", "--truth", required=True,
                    help="truth.txt: по одному гену на строку")
    ap.add_argument("--family", action="store_true",
                    help="дополнительно считать метрики на уровне семейства гена")
    ap.add_argument("-n", "--name", required=True,
                    help="название файла")
    args = ap.parse_args()

    T = {normalize(g) for g in load_truth(args.truth)}
    files = [f for pat in args.pred for f in (sorted(glob.glob(pat)) or [pat])]
    name = args.name + '.csv'

    rows = []
    for f in files:
        if not Path(f).is_file():
            print(f"[!] файл не найден: {f}", file=sys.stderr)
            continue
        stem = Path(f).stem
        preds = load_predictions(f)
        P = {normalize(g) for g, _ in preds}
        head = dict(sample=sample_name(stem), profile=detect_profile(stem))
        hits, total, ranks = top1(preds, T)
        extra = dict(top1_hits=hits, top1_total=total,
                     top1_rate=round(div(hits, total), 4), truth_ranks=ranks)
        rows.append({**head, "level": "gene", **calc(P, T), **extra})
        if args.family:
            rows.append({**head, "level": "family",
                         **calc({family(g) for g in P}, {family(g) for g in T}),
                         **dict.fromkeys(extra, "")})

    if not rows:
        sys.exit("Нет данных для расчёта")

    cols = ["sample", "profile", "level", "n_pred", "n_truth", "TP", "FP", "FN",
            "precision", "recall", "f1", "top1_rate"]
    table = [[str(r[c]) for c in cols] for r in rows]
    w = [max(len(c), *(len(t[i]) for t in table)) for i, c in enumerate(cols)]
    print("  ".join(c.ljust(x) for c, x in zip(cols, w)))
    for t in table:
        print("  ".join(v.ljust(x) for v, x in zip(t, w)))

    with open(name, "w", newline="", encoding="utf-8-sig") as fh:
        wr = csv.DictWriter(fh, fieldnames=list(rows[0].keys()))
        wr.writeheader()
        wr.writerows(rows)
    print(f"\nРезультат сохранён: {Path(name).resolve()}")


if __name__ == "__main__":
    main()
