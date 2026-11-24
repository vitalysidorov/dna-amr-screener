from __future__ import annotations
from pathlib import Path

import argparse
import hashlib
import json
import sys
import uuid
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

def send_file(
    url: str,
    file_path: str,
    file_hash: str,
    session_id: str,
    kmer_prefix: str,
    profile: str,
    finalize: bool,
    lsh_headers: dict[str, str],
    cert: tuple[str, str],
    verify: str | bool,
) -> requests.Response:
    params = {
        "sessionId": session_id,
        "kmerPrefix": kmer_prefix,
        "profile": profile,
        "finalize": str(finalize).lower(),
    }
    headers = {
        "Content-Type": "application/octet-stream",
        "X-File-Hash": file_hash,
        **lsh_headers,
    }

    label = "R1 (accumulate)" if not finalize else "R2 (finalize)"
    print(f"→ {label}: {Path(file_path).name}  [prefix={kmer_prefix}, profile={profile}]")

    return requests.post(
        url,
        params=params,
        headers=headers,
        data=chunk_generator(file_path),
        cert=cert,
        verify=verify,
        timeout=(30, 3600),
    )

def main() -> None:
    parser = argparse.ArgumentParser(
        description="Paired-end mTLS client for dna-amr-screener (/api/v1/analyze)"
    )
    parser.add_argument("r1", help="path to R1 FASTQ")
    parser.add_argument("r2", help="path to R2 FASTQ")
    parser.add_argument(
        "--profile",
        choices=["strict", "relaxed"],
        default="strict",
        help="detection profile (default: strict)",
    )
    parser.add_argument(
        "--kmer-prefix",
        default="k21",
        help="index prefix: k21, k17, or custom (default: k21)",
    )
    parser.add_argument("--url", default="https://localhost:8443/api/v1/analyze", help="analyze URL")
    parser.add_argument("--session-id", default=None, help="sessionId (default: random UUID)")
    parser.add_argument("--cert", type=Path, required=True, help="client certificate (PEM)")
    parser.add_argument("--key", type=Path, required=True, help="client private key (PEM)")
    parser.add_argument(
        "--cacert",
        type=Path,
        default=None,
        help="CA bundle to verify server cert (default: system CAs)",
    )
    parser.add_argument("--kmer-size", type=int, default=None, help="X-LSH-Kmer-Size (custom indexes)")
    parser.add_argument("--num-hashes", type=int, default=None, help="X-LSH-Num-Hashes")
    parser.add_argument("--bands", type=int, default=None, help="X-LSH-Bands")
    parser.add_argument("--rows-per-band", type=int, default=None, help="X-LSH-Rows-Per-Band")
    args = parser.parse_args()

    r1_path = Path(args.r1)
    r2_path = Path(args.r2)
    if not r1_path.is_file():
        print(f"r1 not found: {r1_path}", file=sys.stderr)
        sys.exit(1)
    if not r2_path.is_file():
        print(f"r2 not found: {r2_path}", file=sys.stderr)
        sys.exit(1)
    if not args.cert.is_file() or not args.key.is_file():
        print("cert/key not found", file=sys.stderr)
        sys.exit(1)

    kmer_prefix = args.kmer_prefix.lower()
    if kmer_prefix not in ("k17", "k21") and any(
        v is None for v in (args.kmer_size, args.num_hashes, args.bands, args.rows_per_band)
    ):
        print(
            f"custom prefix '{kmer_prefix}' requires --kmer-size --num-hashes --bands --rows-per-band",
            file=sys.stderr,
        )
        sys.exit(1)

    lsh_headers = build_lsh_headers(
        args.kmer_size, args.num_hashes, args.bands, args.rows_per_band
    )
    session_id = args.session_id or str(uuid.uuid4())
    cert = (str(args.cert), str(args.key))
    verify: str | bool = str(args.cacert) if args.cacert else True
    print(f"sessionId={session_id}")

    try:
        resp1 = send_file(
            url=args.url,
            file_path=str(r1_path),
            file_hash=get_file_hash(str(r1_path)),
            session_id=session_id,
            kmer_prefix=kmer_prefix,
            profile=args.profile,
            finalize=False,
            lsh_headers=lsh_headers,
            cert=cert,
            verify=verify,
        )
        if resp1.status_code != 200:
            print(f"R1 error ({resp1.status_code}):", file=sys.stderr)
            print(resp1.text, file=sys.stderr)
            sys.exit(1)
        print(f"R1: {resp1.status_code} — data accumulated")

        resp2 = send_file(
            url=args.url,
            file_path=str(r2_path),
            file_hash=get_file_hash(str(r2_path)),
            session_id=session_id,
            kmer_prefix=kmer_prefix,
            profile=args.profile,
            finalize=True,
            lsh_headers=lsh_headers,
            cert=cert,
            verify=verify,
        )
        if resp2.status_code != 200:
            print(f"R2 error ({resp2.status_code}):", file=sys.stderr)
            print(resp2.text, file=sys.stderr)
            sys.exit(1)

        print(f"R2: {resp2.status_code}")
        print("result:\n")
        try:
            print(json.dumps(resp2.json(), indent=2, ensure_ascii=False))
        except json.JSONDecodeError:
            print(resp2.text)

    except requests.exceptions.RequestException as e:
        print(f"ERROR: {e}", file=sys.stderr)
        sys.exit(1)

if __name__ == "__main__":
    main()
