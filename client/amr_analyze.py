import argparse
import json
import sys
import uuid
import hashlib
import os
from pathlib import Path

import requests


def send_file(url: str, file_path: str, file_hash: str, session_id: str,
              profile: str, finalize: bool) -> requests.Response:
    params = {
        "sessionId": session_id,
        "profile": profile,
        "finalize": str(finalize).lower(),
    }
    headers = {
        "Content-Type": "application/octet-stream",

        'X-File-Hash': file_hash,
        'X-LSH-Kmer-Size': '15',
        'X-LSH-Num-Hashes': '100',
        'X-LSH-Bands': '20',
        'X-LSH-Rows-Per-Band': '5'
    }

    print(f"{'→ R1 (accumulate)' if not finalize else '→ R2 (finalize)'} : {Path(file_path).name}")

    response = requests.post(
        url,
        params=params,
        headers=headers,
        data=chunk_generator(file_path),
        timeout=3600,
    )
    return response

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
    return f"{file_path}_{hasher.hexdigest()}"

def main():
    parser = argparse.ArgumentParser(description="paired-end client for dna-amr-screener")
    parser.add_argument("r1", help="path to r1 fastq")
    parser.add_argument("r2", help="path to r2 fastq")
    parser.add_argument("--profile", choices=["strict", "relaxed"], help="detection profile")
    parser.add_argument("--url", default="http://localhost:8080/api/v1/analyze", help="URL")
    parser.add_argument("--session-id", default=None, help="sessionId")
    args = parser.parse_args()

    r1_path = Path(args.r1)
    r2_path = Path(args.r2)

    if not r1_path.exists():
        print(f"r1 not found: {r1_path}", file=sys.stderr)
        sys.exit(1)
    if not r2_path.exists():
        print(f"r2 not found: {r2_path}", file=sys.stderr)
        sys.exit(1)

    session_id = args.session_id or str(uuid.uuid4())

    try:
        resp1 = send_file(args.url, str(r1_path), get_file_hash(str(r1_path)), session_id, args.profile, finalize=False)
        if resp1.status_code != 200:
            print(f"R1 error ({resp1.status_code}):", file=sys.stderr)
            print(resp1.text, file=sys.stderr)
            sys.exit(1)
        print(f"R1: {resp1.status_code} — data is accumulated")

        resp2 = send_file(args.url, str(r2_path), get_file_hash(str(r2_path)), session_id, args.profile, finalize=True)
        if resp2.status_code != 200:
            print(f"R2 error ({resp2.status_code}):", file=sys.stderr)
            print(resp2.text, file=sys.stderr)
            sys.exit(1)

        print(f"R2: {resp2.status_code}")
        print("result:\n")

        try:
            data = resp2.json()
            print(json.dumps(data, indent=2, ensure_ascii=False))
        except json.JSONDecodeError:
            print(resp2.text)

    except requests.exceptions.RequestException as e:
        print(f"ERROR: {e}", file=sys.stderr)
        sys.exit(1)

if __name__ == "__main__":
    main()