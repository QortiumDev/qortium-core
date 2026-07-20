#!/usr/bin/env python3
"""Prune superseded AUTO_UPDATE_BINARY payloads from QDN.

Each auto-update publish leaves a ~76 MB AUTO_UPDATE_BINARY resource on QDN
forever, and every node that follows the publishing name downloads all of
them. This prunes the ones no longer referenced by an approved manifest.

SAFETY MODEL
------------
An approved AUTO_UPDATE manifest pins its binary by transaction signature
(AutoUpdate.java resolves it via ResourceIdType.TRANSACTION_DATA), so the
live payload is "whatever the currently-approved manifest points at" --
NOT simply the newest one. A newer payload may exist whose manifest is
still PENDING approval, in which case the newest payload is not yet live
and the previous one still is.

This script therefore:
  * refuses to run unless /admin/update reports an APPROVED manifest with a
    resolvable binarySignature;
  * always excludes that pinned signature;
  * keeps --keep additional recent payloads as rollback margin;
  * is dry-run by default.

Deleting a payload that an approved manifest still pins would stall that
update for any node that has not already fetched it. The pinned hash and
signature mean it cannot be substituted, only made unavailable -- so the
failure mode is denial of service, not code injection.

Run this AFTER a new manifest is approved, never as part of publishing:
at publish time the new manifest is still PENDING and the PREVIOUS payload
is the live one.
"""

import argparse
import json
import sys
from pathlib import Path
from urllib.parse import quote

import requests

QDN_UPDATE_NAME = "qortium"
QDN_UPDATE_SERVICE = "AUTO_UPDATE_BINARY"
DEFAULT_KEEP = 2


def abort(message):
    sys.exit(f"ERROR: {message}")


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

    def headers(self, content_type="text/plain", api_key=False):
        headers = {"Content-Type": content_type}
        if api_key and self.api_key:
            headers["X-API-KEY"] = self.api_key
        return headers

    def request(self, method, path, *, data=None, content_type="text/plain",
                api_key=False, timeout=60):
        url = f"{self.base_url}{path}"
        response = requests.request(method, url, data=data,
                                    headers=self.headers(content_type, api_key),
                                    timeout=timeout)
        if not response.ok:
            abort(f"{method} {path} failed with HTTP {response.status_code}: "
                  f"{response.text.strip()}")
        text = response.text.strip()
        if '"error"' in text.lower():
            abort(f"{method} {path} failed: {text}")
        return text

    def get_json(self, path, *, api_key=False, timeout=60):
        return json.loads(self.request("GET", path, api_key=api_key, timeout=timeout))

    def update_status(self):
        return self.get_json("/admin/update", api_key=True)

    def list_binaries(self, qdn_name):
        service = quote(QDN_UPDATE_SERVICE, safe="")
        resources = self.get_json(
            f"/arbitrary/resources?service={service}&limit=0&includemetadata=false",
            timeout=120)
        return [r for r in resources if r.get("name") == qdn_name]

    def build_delete(self, qdn_name, identifier, fee):
        service = quote(QDN_UPDATE_SERVICE, safe="")
        name = quote(qdn_name, safe="")
        ident = quote(identifier, safe="")
        path = f"/arbitrary/resource/{service}/{name}/{ident}/delete?fee={fee}"
        return self.request("POST", path, api_key=True, timeout=120)

    def compute_nonce(self, unsigned_tx):
        return self.request("POST", "/arbitrary/compute", data=unsigned_tx,
                            api_key=True, timeout=600)

    def sign_transaction(self, private_key, unsigned_tx):
        payload = json.dumps({"privateKey": private_key,
                              "transactionBytes": unsigned_tx})
        return self.request("POST", "/transactions/sign", data=payload,
                            content_type="application/json")

    def process_transaction(self, signed_tx):
        result = self.request("POST", "/transactions/process", data=signed_tx)
        if result != "true":
            abort(f"Transaction submission failed: {result}")

    def delete_local(self, qdn_name, identifier):
        service = quote(QDN_UPDATE_SERVICE, safe="")
        name = quote(qdn_name, safe="")
        ident = quote(identifier, safe="")
        path = f"/arbitrary/resource/{service}/{name}/{ident}"
        return self.request("DELETE", path, api_key=True, timeout=120)


def resolve_pinned_signature(api, allow_unapproved):
    """Return the binary signature pinned by the currently-approved manifest."""
    status = api.update_status()

    approval = status.get("manifestApprovalStatus")
    pinned = status.get("binarySignature")

    if approval != "APPROVED":
        message = (f"manifestApprovalStatus is {approval!r}, not APPROVED -- "
                   "the live payload cannot be determined safely")
        if not allow_unapproved:
            abort(message + ". Refusing to prune. Re-run after approval, or "
                            "pass --i-know-the-pinned-binary to override.")
        print(f"WARNING: {message}", file=sys.stderr)

    if not pinned:
        abort("/admin/update reported no binarySignature -- refusing to prune "
              "because the live payload cannot be identified")

    return pinned, status


def select_prunable(binaries, pinned_signature, keep):
    """Newest-first ordering; exclude the pinned payload and `keep` others."""
    ordered = sorted(binaries, key=lambda r: r.get("created") or 0, reverse=True)

    pinned = [r for r in ordered if r.get("latestSignature") == pinned_signature]
    if not pinned:
        abort(f"pinned binary signature {pinned_signature} was not found among "
              f"the {len(ordered)} payload(s) for this name -- refusing to "
              "prune against an unverifiable baseline")

    # Retain the pinned payload, plus the `keep` most-recent payloads that are
    # not the pinned one. Keyed on signature rather than list position so this
    # stays correct when the pinned payload is not the newest -- which happens
    # whenever a newer payload's manifest is still awaiting approval.
    retained = {pinned_signature}
    for resource in ordered:
        if len(retained) >= keep + 1:
            break
        retained.add(resource.get("latestSignature"))

    kept = [r for r in ordered if r.get("latestSignature") in retained]
    prunable = [r for r in ordered if r.get("latestSignature") not in retained]
    return kept, prunable


def describe(resource, pinned_signature):
    size_mb = (resource.get("size") or 0) / 1e6
    marker = "  <-- PINNED (live)" if resource.get("latestSignature") == pinned_signature else ""
    return f"  {resource['identifier'][:16]}...  {size_mb:6.1f} MB{marker}"


def parse_args():
    parser = argparse.ArgumentParser(
        description="Prune superseded AUTO_UPDATE_BINARY payloads from QDN")
    parser.add_argument("--preview", action="store_true",
                        help="Use preview defaults: port 24891 and zero fees")
    parser.add_argument("--host", default="localhost", help="API host")
    parser.add_argument("--port", type=int, help="API port")
    parser.add_argument("--api-key", help="API key for restricted API calls")
    parser.add_argument("--qdn-name", default=QDN_UPDATE_NAME,
                        help="QDN name that owns the update binaries")
    parser.add_argument("--keep", type=int, default=DEFAULT_KEEP,
                        help=f"Extra recent payloads to retain beyond the pinned "
                             f"one, as rollback margin (default {DEFAULT_KEEP})")
    parser.add_argument("--fee", type=int,
                        help="Fee per delete transaction in atomic units")
    parser.add_argument("--also-delete-local", action="store_true",
                        help="Also drop this node's local copies immediately "
                             "instead of waiting for the cleanup manager")
    parser.add_argument("--i-know-the-pinned-binary", action="store_true",
                        help="Proceed even if the manifest is not APPROVED "
                             "(still requires a binarySignature)")
    parser.add_argument("--yes", action="store_true",
                        help="Actually submit the delete transactions")
    return parser.parse_args()


def main():
    args = parse_args()

    if args.preview:
        if args.port is None:
            args.port = 24891
        if args.fee is None:
            args.fee = 0
    if args.port is None:
        args.port = 12391
    if args.fee is None:
        args.fee = 0

    if args.keep < 0:
        abort("--keep must be zero or greater")

    api_key = args.api_key or api_key_from_default_locations()
    api = QortiumApi(args.host, args.port, api_key)

    pinned_signature, status = resolve_pinned_signature(
        api, args.i_know_the_pinned_binary)

    binaries = api.list_binaries(args.qdn_name)
    if not binaries:
        print(f"No {QDN_UPDATE_SERVICE} payloads found for name {args.qdn_name!r}")
        return

    kept, prunable = select_prunable(binaries, pinned_signature, args.keep)

    print(f"Node:            {api.base_url}")
    print(f"QDN name:        {args.qdn_name}")
    print(f"Manifest status: {status.get('manifestApprovalStatus')} "
          f"(height {status.get('manifestApprovalHeight')})")
    print(f"Pinned binary:   {pinned_signature}")
    print()
    print(f"Retaining {len(kept)} payload(s):")
    for resource in kept:
        print(describe(resource, pinned_signature))

    if not prunable:
        print("\nNothing to prune.")
        return

    reclaimed = sum(r.get("size") or 0 for r in prunable) / 1e6
    print(f"\nPruning {len(prunable)} payload(s), reclaiming ~{reclaimed:.1f} MB "
          f"network-wide:")
    for resource in prunable:
        print(describe(resource, pinned_signature))

    if not args.yes:
        print("\nDry run -- nothing submitted. Re-run with --yes to delete.")
        return

    import getpass
    private_key = getpass.getpass(
        f"Private key for the owner of {args.qdn_name!r}: ").strip()
    if not private_key:
        abort("No private key supplied")

    for resource in prunable:
        identifier = resource["identifier"]
        print(f"Deleting {identifier[:16]}... ", end="", flush=True)

        unsigned = api.build_delete(args.qdn_name, identifier, args.fee)
        computed = api.compute_nonce(unsigned)
        signed = api.sign_transaction(private_key, computed)
        api.process_transaction(signed)
        print("submitted", end="")

        if args.also_delete_local:
            api.delete_local(args.qdn_name, identifier)
            print(" (local copy dropped)", end="")
        print()

    print(f"\nSubmitted {len(prunable)} delete transaction(s). Other nodes drop "
          "their copies via ArbitraryDataCleanupManager, which refreshes its "
          "transaction list every 6 hours.")


if __name__ == "__main__":
    main()
