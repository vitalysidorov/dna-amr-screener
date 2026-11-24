from __future__ import annotations
from pathlib import Path

import argparse
import hashlib
import json
import sys
import requests


def chunk_generator(file_path: str, chunk_size: int = 64 * 1024):
    with open(file_path, "rb") as f:
        while True:
            chunk = f.read(chunk_size)
            if not chunk:
                break
            yield chunk

def get_file_hash(file_path: str) -> str:
    hasher = hashlib.md5()
    with open(file_path, "rb") as f:
        hasher.update(f.read(1024 * 1024 * 5))
    return f"{Path(file_path).name}_{hasher.hexdigest()}"

def build_lsh_headers(
    kmer_size: int | None,
    num_hashes: int | None,
    bands: int | None,
    rows_per_band: int | None,
) -> dict[str, str]:
    values = (kmer_size, num_hashes, bands, rows_per_band)
    if all(v is None for v in values):
        return {}
    if any(v is None for v in values):
        raise SystemExit(
            "for custom LSH params provide all of: "
            "--kmer-size --num-hashes --bands --rows-per-band"
        )
    return {
        "X-LSH-Kmer-Size": str(kmer_size),
        "X-LSH-Num-Hashes": str(num_hashes),
        "X-LSH-Bands": str(bands),
        "X-LSH-Rows-Per-Band": str(rows_per_band),
    }

def print_response(resp: requests.Response) -> None:
    print(f"resp: {resp.status_code}")
    try:
        print(json.dumps(resp.json(), indent=2, ensure_ascii=False))
    except json.JSONDecodeError:
        print(resp.text)
    if resp.status_code >= 400:
        sys.exit(1)

def main() -> None:
    parser = argparse.ArgumentParser(description="Client for /api/card endpoints")
    parser.add_argument(
        "endpoint",
        choices=["seed", "switch", "status", "inactive", "activate/blue", "activate/green", "newIndex"],
        help="card API action",
    )
    parser.add_argument(
        "--kmer-prefix",
        default="k21",
        help="index prefix (required by server for all card ops; default: k21)",
    )
    parser.add_argument("--path", default=None, help="FASTA path (seed only)")
    parser.add_argument("--url", default="http://localhost:8080/api/card", help="card API base URL")
    parser.add_argument("--kmer-size", type=int, default=None, help="X-LSH-Kmer-Size (custom / newIndex)")
    parser.add_argument("--num-hashes", type=int, default=None, help="X-LSH-Num-Hashes")
    parser.add_argument("--bands", type=int, default=None, help="X-LSH-Bands")
    parser.add_argument("--rows-per-band", type=int, default=None, help="X-LSH-Rows-Per-Band")
    args = parser.parse_args()

    base = args.url.rstrip("/") + "/"
    kmer_prefix = args.kmer_prefix.lower()
    params = {"kmerPrefix": kmer_prefix}
    lsh_headers = build_lsh_headers(
        args.kmer_size, args.num_hashes, args.bands, args.rows_per_band
    )

    try:
        if args.endpoint == "seed":
            if not args.path:
                print("--path is required for seed", file=sys.stderr)
                sys.exit(1)
            path = Path(args.path)
            if not path.is_file():
                print(f"file not found: {path}", file=sys.stderr)
                sys.exit(1)
            if kmer_prefix not in ("k17", "k21") and not lsh_headers:
                print(
                    f"custom prefix '{kmer_prefix}' requires LSH headers for seed",
                    file=sys.stderr,
                )
                sys.exit(1)
            headers = {
                "Content-Type": "application/octet-stream",
                "X-File-Hash": get_file_hash(str(path)),
                **lsh_headers,
            }
            resp = requests.post(
                base + "seed",
                params=params,
                headers=headers,
                data=chunk_generator(str(path)),
                timeout=3600,
            )

        elif args.endpoint == "newIndex":
            # Server indexes bundled CARD.fasta under the new prefix; LSH headers are required.
            if any(v is None for v in (args.kmer_size, args.num_hashes, args.bands, args.rows_per_band)):
                print(
                    "newIndex requires --kmer-size --num-hashes --bands --rows-per-band",
                    file=sys.stderr,
                )
                sys.exit(1)
            headers = {**lsh_headers}
            resp = requests.post(
                base + "newIndex",
                params=params,
                headers=headers,
                timeout=3600,
            )

        elif args.endpoint == "switch":
            resp = requests.post(base + "switch", params=params, timeout=30)

        elif args.endpoint == "inactive":
            resp = requests.delete(base + "inactive", params=params, timeout=60)

        elif args.endpoint == "activate/blue":
            resp = requests.post(base + "activate/blue", params=params, timeout=30)

        elif args.endpoint == "activate/green":
            resp = requests.post(base + "activate/green", params=params, timeout=30)

        elif args.endpoint == "status":
            resp = requests.get(base + "status", params=params, timeout=15)

        else:
            print(f"unknown endpoint: {args.endpoint}", file=sys.stderr)
            sys.exit(1)

        print_response(resp)

    except requests.exceptions.RequestException as e:
        print(f"ERROR: {e}", file=sys.stderr)
        sys.exit(1)

if __name__ == "__main__":
    main()
