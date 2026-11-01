import argparse
import json
import sys
import uuid
import requests
import hashlib
import os
from pathlib import Path


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

def send_file(url: str, file_path: str, file_hash: str, cert: tuple[str, str],
              verify: str | bool) -> requests.Response:
    headers = {
        "Content-Type": "application/octet-stream",

        'X-File-Hash': file_hash,
        'X-LSH-Kmer-Size': '15',
        'X-LSH-Num-Hashes': '100',
        'X-LSH-Bands': '20',
        'X-LSH-Rows-Per-Band': '5'
    }

    response = requests.post(
        url,
        headers=headers,
        data=chunk_generator(file_path),
        cert=cert,
        verify=verify,
        timeout=300,
    )
    return response

def main():
    parser = argparse.ArgumentParser(description="client for update db dna-amr-screener")
    parser.add_argument("endpoint", choices=["seed", "switch", "status", "inactive",
                                             "activate/blue", "activate/green"], help="endpoint")
    parser.add_argument("--path", type=Path, required=False, help="path to db file")
    parser.add_argument("--cert", type=Path, required=True, help="client.crt")
    parser.add_argument("--key", type=Path, required=True, help="client.key")
    args = parser.parse_args()

    base_url = "http://localhost:8080/api/card/"
    cert = (str(args.cert), str(args.key))
    verify: str | bool = str(args.cacert) if args.cacert else True

    try:
        if args.endpoint == "seed":
            path = Path(args.path)
            if not path.exists():
                print(f"file not found: {path}", file=sys.stderr)
                sys.exit(1)
            resp = send_file(url=str(base_url + args.endpoint), file_path=str(path), file_hash=get_file_hash(str(path)),
                             cert=cert, verify=verify)
            if resp.status_code != 200:
                print(f"error ({resp2.status_code}):", file=sys.stderr)
                print(resp.text, file=sys.stderr)
                sys.exit(1)

        elif args.endpoint == "switch":
            resp = requests.post(str(base_url + args.endpoint), cert=cert, verify=verify, timeout=10)

        elif args.endpoint == "inactive":
            resp = requests.delete(str(base_url + args.endpoint), cert=cert, verify=verify, timeout=10)

        elif args.endpoint == "activate/blue":
            resp = requests.post(str(base_url + args.endpoint), cert=cert, verify=verify, timeout=10)

        elif args.endpoint == "activate/green":
            resp = requests.post(str(base_url + args.endpoint), cert=cert, verify=verify, timeout=10)

        elif args.endpoint == "status":
            resp = requests.get(str(base_url + args.endpoint), cert=cert, verify=verify, timeout=5)

        print(f"resp: {resp.status_code}")

        try:
            data = resp.json()
            print(json.dumps(data, indent=2, ensure_ascii=False))
        except json.JSONDecodeError:
            print(resp.text)

    except requests.exceptions.RequestException as e:
        print(f"ERROR: {e}", file=sys.stderr)
        sys.exit(1)

if __name__ == "__main__":
    main()