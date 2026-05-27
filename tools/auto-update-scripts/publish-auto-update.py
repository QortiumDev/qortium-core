#!/usr/bin/env python3

import argparse
import getpass
import hashlib
import json
import os
import subprocess
import sys
import time
from pathlib import Path
from urllib.parse import quote

import requests


QDN_UPDATE_NAME = "qortium"
QDN_UPDATE_SERVICE = "AUTO_UPDATE_BINARY"
QDN_UPDATE_PATH = "qortium.update"
AUTO_UPDATE_SERVICE_ID = 1
AUTO_UPDATE_MANIFEST_MAGIC = bytes([0x51, 0x41, 0x55, 0x31])  # QAU1
SIGNATURE_HEX_LENGTH = 128
XOR_VALUE = 0x5a


def abort(message):
    sys.exit(f"ERROR: {message}")


def run(cmd, cwd=None):
    result = subprocess.run(cmd, cwd=cwd, capture_output=True, text=True)
    if result.returncode != 0:
        abort(f"Command failed: {' '.join(cmd)}\n{result.stderr.strip()}")
    return result.stdout.strip()


def get_project_name():
    pom = Path("pom.xml")
    if not pom.exists():
        abort("pom.xml not found")

    for line in pom.read_text(encoding="utf-8").splitlines():
        if "<artifactId>" in line:
            return line.strip().split(">")[1].split("<")[0]

    abort("artifactId not found in pom.xml")


def commit_exists(commit_hash):
    result = subprocess.run(["git", "cat-file", "-e", f"{commit_hash}^{{commit}}"],
                            capture_output=True, text=True)
    return result.returncode == 0


def resolve_commit(commit_hash):
    if not commit_hash:
        commit_hash = "HEAD"

    if not commit_exists(commit_hash):
        abort(f"Commit hash '{commit_hash}' does not exist")

    full_hash = run(["git", "rev-parse", f"{commit_hash}^{{commit}}"])
    timestamp = int(run(["git", "show", "--no-patch", "--format=%ct", full_hash])) * 1000
    return full_hash, timestamp


def decoded_update_sha256_hex(path):
    sha256 = hashlib.sha256()
    with path.open("rb") as update_file:
        for chunk in iter(lambda: update_file.read(1024 * 1024), b""):
            decoded = bytearray(chunk)
            for i in range(len(decoded)):
                decoded[i] ^= XOR_VALUE

            sha256.update(decoded)
    return sha256.hexdigest()


def api_key_from_default_locations():
    candidates = [
        Path("preview") / "apikey.txt",
        Path("apikey.txt"),
        Path.home() / "qortium" / "apikey.txt",
    ]

    for candidate in candidates:
        if candidate.exists():
            return candidate.read_text(encoding="utf-8").strip()

    return None


class QortiumApi:
    def __init__(self, host, port, api_key=None):
        self.base_url = f"http://{host}:{port}"
        self.api_key = api_key

    def headers(self, content_type="text/plain", api_key=False, extra=None):
        headers = {"Content-Type": content_type}
        if api_key and self.api_key:
            headers["X-API-KEY"] = self.api_key
        if extra:
            headers.update(extra)
        return headers

    def request(self, method, path, *, data=None, content_type="text/plain", api_key=False, timeout=60):
        url = f"{self.base_url}{path}"
        response = requests.request(method, url, data=data,
                                    headers=self.headers(content_type, api_key),
                                    timeout=timeout)
        if not response.ok:
            abort(f"{method} {path} failed with HTTP {response.status_code}: {response.text.strip()}")
        text = response.text.strip()
        if '"error"' in text.lower():
            abort(f"{method} {path} failed: {text}")
        return text

    def get_public_key(self, private_key):
        return self.request("POST", "/utils/publickey", data=private_key)

    def from_base58(self, base58_value):
        return self.request("POST", "/utils/frombase58", data=base58_value)

    def to_base58(self, hex_value):
        return self.request("GET", f"/utils/tobase58/{hex_value}")

    def address_from_public_key(self, public_key):
        return self.request("GET", f"/addresses/convert/{public_key}")

    def last_reference(self, address):
        return self.request("GET", f"/addresses/lastreference/{address}")

    def build_qdn_upload(self, qdn_name, identifier, update_file, fee):
        service = quote(QDN_UPDATE_SERVICE, safe="")
        name = quote(qdn_name, safe="")
        identifier = quote(identifier, safe="")
        path = f"/arbitrary/{service}/{name}/{identifier}?fee={fee}"
        return self.request("POST", path, data=str(update_file.resolve()), api_key=True, timeout=600)

    def compute_nonce(self, unsigned_tx):
        return self.request("POST", "/arbitrary/compute", data=unsigned_tx, api_key=True, timeout=600)

    def sign_transaction(self, private_key, unsigned_tx):
        payload = json.dumps({"privateKey": private_key, "transactionBytes": unsigned_tx})
        return self.request("POST", "/transactions/sign", data=payload, content_type="application/json")

    def process_transaction(self, signed_tx):
        result = self.request("POST", "/transactions/process", data=signed_tx)
        if result != "true":
            abort(f"Transaction submission failed: {result}")

    def signature_hex_from_signed_transaction(self, signed_tx):
        tx_hex = self.from_base58(signed_tx)
        if len(tx_hex) < SIGNATURE_HEX_LENGTH:
            abort("Signed transaction is too short to contain a signature")
        return tx_hex[-SIGNATURE_HEX_LENGTH:]


def build_manifest_hex(timestamp, commit_hash, update_hash, binary_signature_hex):
    if len(commit_hash) != 40:
        abort("Commit hash must be the full 20-byte Git hash")
    if len(update_hash) != 64:
        abort("Decoded update JAR SHA-256 must be 32 bytes")
    if len(binary_signature_hex) != SIGNATURE_HEX_LENGTH:
        abort("Binary transaction signature must be 64 bytes")

    payload = bytearray()
    payload.extend(AUTO_UPDATE_MANIFEST_MAGIC)
    payload.extend(timestamp.to_bytes(8, byteorder="big", signed=False))
    payload.extend(bytes.fromhex(commit_hash))
    payload.extend(bytes.fromhex(update_hash))
    payload.append(64)
    payload.extend(bytes.fromhex(binary_signature_hex))
    return payload.hex()


def build_manifest_transaction_hex(api, private_key, tx_group_id, manifest_hex, fee):
    public_key = api.get_public_key(private_key)
    public_key_hex = api.from_base58(public_key)
    address = api.address_from_public_key(public_key)
    reference = api.last_reference(address)
    reference_hex = api.from_base58(reference)

    data_length = len(manifest_hex) // 2
    raw_tx_parts = [
        "0000000a",                              # type 10 ARBITRARY
        f"{int(time.time() * 1000):016x}",       # current timestamp
        f"{tx_group_id:08x}",                    # dev group ID
        reference_hex,
        public_key_hex,
        "00000000",                              # nonce, filled by /arbitrary/compute
        "00000000",                              # name length
        "00000000",                              # identifier length
        "00000000",                              # method PUT
        "00000000",                              # secret length
        "00000000",                              # compression NONE
        "00000000",                              # payments count
        f"{AUTO_UPDATE_SERVICE_ID:08x}",
        "01",                                    # RAW_DATA
        f"{data_length:08x}",
        manifest_hex,
        f"{data_length:08x}",                    # repeated raw data length
        "00000000",                              # metadata hash length
        f"{fee:016x}",
    ]
    return "".join(raw_tx_parts)


def countdown(message, enabled=True):
    if not enabled:
        return

    print(message)
    for remaining in range(5, 0, -1):
        print(f"{remaining}...", end="\r", flush=True)
        time.sleep(1)
    print(" " * 8, end="\r")


def parse_args():
    parser = argparse.ArgumentParser(description="Publish a Qortium auto-update through QDN")
    parser.add_argument("arg1", nargs="?", help="Private key OR commit hash")
    parser.add_argument("arg2", nargs="?", help="Commit hash if arg1 was the private key")
    parser.add_argument("--preview", action="store_true", help="Use preview defaults: port 24891, tx group 1, and zero fees")
    parser.add_argument("--host", default="localhost", help="API host")
    parser.add_argument("--port", type=int, help="API port")
    parser.add_argument("--api-key", default=os.environ.get("QORTIUM_API_KEY"), help="API key for restricted API calls")
    parser.add_argument("--qdn-name", default=QDN_UPDATE_NAME, help="QDN name that owns the update binary resource")
    parser.add_argument("--identifier", help="QDN identifier for the update binary, defaults to full commit hash")
    parser.add_argument("--tx-group-id", type=int, help="Development group transaction group ID")
    parser.add_argument("--binary-fee", type=int, help="Fee for the QDN binary transaction in atomic units")
    parser.add_argument("--manifest-fee", type=int, help="Fee for the AUTO_UPDATE manifest transaction in atomic units")
    parser.add_argument("--dry-run", action="store_true", help="Show what would be published without creating transactions")
    parser.add_argument("--yes", action="store_true", help="Submit without the five second cancellation pause")
    args = parser.parse_args()

    if args.preview:
        args.port = 24891 if args.port is None else args.port
        args.tx_group_id = 1 if args.tx_group_id is None else args.tx_group_id
        args.binary_fee = 0 if args.binary_fee is None else args.binary_fee
        args.manifest_fee = 0 if args.manifest_fee is None else args.manifest_fee
    else:
        args.port = 14891 if args.port is None else args.port
        args.tx_group_id = 1 if args.tx_group_id is None else args.tx_group_id
        args.binary_fee = 0 if args.binary_fee is None else args.binary_fee
        args.manifest_fee = 0 if args.manifest_fee is None else args.manifest_fee

    return args


def resolve_private_key_and_commit(args):
    if args.dry_run and not args.arg2:
        if args.arg1 and commit_exists(args.arg1):
            return None, args.arg1
        return args.arg1, None

    if args.arg1 and args.arg2:
        return args.arg1, args.arg2
    if args.arg1:
        if commit_exists(args.arg1):
            return getpass.getpass("Enter your Base58 private key: "), args.arg1
        return args.arg1, None

    return getpass.getpass("Enter your Base58 private key: "), None


def main():
    args = parse_args()

    git_root = run(["git", "rev-parse", "--show-toplevel"])
    os.chdir(git_root)

    private_key, commit_arg = resolve_private_key_and_commit(args)
    project = get_project_name()
    commit_hash, timestamp = resolve_commit(commit_arg)
    identifier = args.identifier or commit_hash
    update_file = Path(f"{project}.update")

    if not update_file.is_file():
        abort(f"{update_file} not found. Run build-auto-update.sh first.")

    update_hash = decoded_update_sha256_hex(update_file)

    print(f"Project:        {project}")
    print(f"Commit:         {commit_hash}")
    print(f"Build time ms:  {timestamp}")
    print(f"Update file:    {update_file.resolve()}")
    print(f"Decoded JAR SHA-256: {update_hash}")
    print(f"QDN binary:     {QDN_UPDATE_SERVICE}/{args.qdn_name}/{identifier}")
    print(f"Manifest group: {args.tx_group_id}")
    print(f"API endpoint:   {args.host}:{args.port}")
    print(f"Fees:           binary={args.binary_fee}, manifest={args.manifest_fee}")

    if args.dry_run:
        print("\nDry run: no QDN binary or AUTO_UPDATE manifest transaction was built or submitted.")
        print("The real run will pin the signed QDN binary transaction signature into the manifest.")
        return

    api_key = args.api_key or api_key_from_default_locations()
    api = QortiumApi(args.host, args.port, api_key)

    print("\nBuilding QDN binary transaction...")
    qdn_unsigned_tx = api.build_qdn_upload(args.qdn_name, identifier, update_file, args.binary_fee)
    qdn_unsigned_tx = api.compute_nonce(qdn_unsigned_tx)
    qdn_signed_tx = api.sign_transaction(private_key, qdn_unsigned_tx)
    qdn_signature_hex = api.signature_hex_from_signed_transaction(qdn_signed_tx)
    qdn_signature58 = api.to_base58(qdn_signature_hex)
    print(f"QDN binary transaction signature: {qdn_signature58}")

    manifest_hex = build_manifest_hex(timestamp, commit_hash, update_hash, qdn_signature_hex)
    print(f"Manifest payload bytes: {len(manifest_hex) // 2}")

    print("Building AUTO_UPDATE manifest transaction...")
    manifest_tx_hex = build_manifest_transaction_hex(api, private_key, args.tx_group_id, manifest_hex, args.manifest_fee)
    manifest_unsigned_tx = api.to_base58(manifest_tx_hex)
    manifest_unsigned_tx = api.compute_nonce(manifest_unsigned_tx)
    manifest_signed_tx = api.sign_transaction(private_key, manifest_unsigned_tx)
    manifest_signature_hex = api.signature_hex_from_signed_transaction(manifest_signed_tx)
    manifest_signature58 = api.to_base58(manifest_signature_hex)
    print(f"AUTO_UPDATE manifest transaction signature: {manifest_signature58}")

    countdown("\nSubmitting both transactions in 5 seconds. Press CTRL+C to cancel.", not args.yes)
    api.process_transaction(qdn_signed_tx)
    api.process_transaction(manifest_signed_tx)

    print("\nSubmitted successfully.")
    print("The AUTO_UPDATE manifest must still be approved by the configured development group.")


if __name__ == "__main__":
    main()
