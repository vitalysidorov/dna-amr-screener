from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from gene_normalize import (
    load_amrfinder_tsv,
    load_plain_list,
    load_rgi_tsv,
    load_screener_json,
    names_to_set,
)


LOADERS = {
    "screener": load_screener_json,
    "amrfinder": load_amrfinder_tsv,
    "rgi": load_rgi_tsv,
    "list": load_plain_list,
}


def metrics(pred: set[str], truth: set[str]) -> dict:
    tp = pred & truth
    fp = pred - truth
    fn = truth - pred
    prec = len(tp) / len(pred) if pred else 0.0
    rec = len(tp) / len(truth) if truth else 0.0
    f1 = (2 * prec * rec / (prec + rec)) if (prec + rec) else 0.0
    return {
        "tp": sorted(tp),
        "fp": sorted(fp),
        "fn": sorted(fn),
        "n_pred": len(pred),
        "n_truth": len(truth),
        "n_tp": len(tp),
        "n_fp": len(fp),
        "n_fn": len(fn),
        "precision": round(prec, 4),
        "recall": round(rec, 4),
        "f1": round(f1, 4),
    }

def main() -> None:
    p = argparse.ArgumentParser(description="Compare AMR gene sets after name normalization")
    p.add_argument("--pred", required=True, help="prediction file (screener JSON / tsv / list)")
    p.add_argument("--truth", required=True, help="ground truth file")
    p.add_argument(
        "--pred-format",
        choices=LOADERS.keys(),
        default="screener",
    )
    p.add_argument(
        "--truth-format",
        choices=LOADERS.keys(),
        default="amrfinder",
    )
    p.add_argument(
        "--level",
        choices=["allele", "family"],
        default="allele",
        help="allele = exact allele after normalize; family = drop allele number",
    )
    p.add_argument("--keep-bla", action="store_true", help="do not strip bla prefix")
    p.add_argument(
        "--keep-point-mutations",
        action="store_true",
        help="do not drop AMRFinder-style point mutations from sets",
    )
    p.add_argument("-o", "--output", default=None, help="write full JSON report to file")
    args = p.parse_args()

    raw_pred = LOADERS[args.pred_format](args.pred)
    raw_truth = LOADERS[args.truth_format](args.truth)

    drop_bla = not args.keep_bla
    skip_pm = not args.keep_point_mutations

    pred = names_to_set(
        raw_pred, level=args.level, drop_bla=drop_bla, skip_point_mutations=skip_pm
    )
    truth = names_to_set(
        raw_truth, level=args.level, drop_bla=drop_bla, skip_point_mutations=skip_pm
    )

    m = metrics(pred, truth)
    summary = {
        "level": args.level,
        "drop_bla": drop_bla,
        "skip_point_mutations": skip_pm,
        "pred_file": args.pred,
        "truth_file": args.truth,
        "precision": m["precision"],
        "recall": m["recall"],
        "f1": m["f1"],
        "n_pred": m["n_pred"],
        "n_truth": m["n_truth"],
        "n_tp": m["n_tp"],
        "n_fp": m["n_fp"],
        "n_fn": m["n_fn"],
        "tp": m["tp"],
        "fp": m["fp"],
        "fn": m["fn"],
        "raw_pred_count": len(raw_pred),
        "raw_truth_count": len(raw_truth),
    }

    print(
        f"level={args.level}  precision={m['precision']:.4f}  "
        f"recall={m['recall']:.4f}  f1={m['f1']:.4f}"
    )
    print(f"pred={m['n_pred']}  truth={m['n_truth']}  tp={m['n_tp']}  fp={m['n_fp']}  fn={m['n_fn']}")
    if m["fp"]:
        print("FP:", ", ".join(m["fp"][:30]) + (" ..." if len(m["fp"]) > 30 else ""))
    if m["fn"]:
        print("FN:", ", ".join(m["fn"][:30]) + (" ..." if len(m["fn"]) > 30 else ""))

    if args.output:
        Path(args.output).write_text(json.dumps(summary, indent=2, ensure_ascii=False), encoding="utf-8")
        print("wrote", args.output)

if __name__ == "__main__":
    # allow running from client/ or repo root
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    main()
