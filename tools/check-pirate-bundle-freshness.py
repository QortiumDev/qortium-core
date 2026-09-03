#!/usr/bin/env python3
"""Check whether the pinned Pirate Unified wallet bundle is behind upstream.

This is a non-gating, informational freshness watcher. It never runs as part
of a build or a pull-request check, and it never touches user-facing code or
release artifacts. Its only job is to compare:

  * the RELEASE_TAG Core has pinned in
    src/main/java/org/qortium/controller/PirateUnifiedWalletBundle.java,
  * an "acknowledged upstream version" recorded in
    tools/pirate-bundle-freshness.json (a deliberate, explicit deferral --
    the same pattern Home uses for its i2pd freshness check), and
  * the latest release published by upstream
    (PirateNetwork/Pirate-Unified-Light-Wallet) and by our fork
    (QortiumDev/Pirate-Unified-Light-Wallet).

Exit codes:
  0 -- upstream's latest release is no newer than the greater of the pinned
       tag and the acknowledged version. Nothing new to review.
  1 -- upstream has moved past what Core pins and what has been explicitly
       acknowledged ("UPSTREAM AHEAD"). Lists the steps to catch up.
  2 -- the check itself could not be completed: the pinned tag or the
       acknowledged version could not be parsed (a repo-configuration
       problem), or upstream's latest release could not be fetched/parsed
       (network error, rate limit, unparsable tag). Distinct from 1 so
       automation can tell "upstream is ahead" apart from "we don't know."

The fork (QortiumDev) having no releases, or an unparsable fork tag, is
always tolerated -- it is printed as a note and never changes the exit code.

Python 3 stdlib only (urllib, json, re, argparse) -- no pip dependencies,
so this can run in a minimal GitHub Actions job with no setup step.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import urllib.error
import urllib.request
from pathlib import Path
from typing import NamedTuple, Optional

REPO_ROOT = Path(__file__).resolve().parents[1]
BUNDLE_JAVA_FILE = (
    REPO_ROOT
    / "src"
    / "main"
    / "java"
    / "org"
    / "qortium"
    / "controller"
    / "PirateUnifiedWalletBundle.java"
)
ACK_FILE = REPO_ROOT / "tools" / "pirate-bundle-freshness.json"

UPSTREAM_REPO = "PirateNetwork/Pirate-Unified-Light-Wallet"
FORK_REPO = "QortiumDev/Pirate-Unified-Light-Wallet"

RELEASE_TAG_PATTERN = re.compile(
    r'static\s+final\s+String\s+RELEASE_TAG\s*=\s*"([^"]+)"\s*;'
)

# Matches "v1.2.0", "1.2.0", "v1.1.8-qortium.3", "v1.2.0-rc.1", etc.
VERSION_PATTERN = re.compile(
    r"^v?(\d+)\.(\d+)\.(\d+)(?:-(.+))?$"
)

# Exit codes, named for clarity at call sites.
EXIT_OK = 0
EXIT_UPSTREAM_AHEAD = 1
EXIT_COULD_NOT_DETERMINE = 2


class ParsedVersion(NamedTuple):
    tuple: tuple
    prerelease: Optional[str]
    raw: str


def parse_version(raw: object) -> ParsedVersion:
    """Parse a semver-ish tag into a (major, minor, patch) tuple plus any
    trailing -suffix (prerelease / qortium build metadata), which is kept
    for display but ignored in the base-version comparison.

    `raw` is typed as `object` (rather than `str`) because callers may hand
    this arbitrary JSON-derived or config-derived data (a missing config
    key, a non-string JSON value); those are exactly the malformed inputs
    this function is meant to reject.

    Raises ValueError if `raw` is not a parsable version string."""
    if not isinstance(raw, str):
        raise ValueError(f"Cannot parse version: {raw!r} (not a string)")
    match = VERSION_PATTERN.match(raw.strip())
    if not match:
        raise ValueError(f"Cannot parse version: {raw!r}")
    major, minor, patch, suffix = match.groups()
    return ParsedVersion(
        tuple=(int(major), int(minor), int(patch)),
        prerelease=suffix,
        raw=raw,
    )


def compare_base(left: ParsedVersion, right: ParsedVersion) -> int:
    """Compare only the (major, minor, patch) base tuples. Returns -1, 0, 1."""
    if left.tuple < right.tuple:
        return -1
    if left.tuple > right.tuple:
        return 1
    return 0


def read_release_tag(java_text: str) -> str:
    match = RELEASE_TAG_PATTERN.search(java_text)
    if not match:
        raise ValueError(
            f"Could not find RELEASE_TAG in {BUNDLE_JAVA_FILE}; "
            "the bundle source may have been restructured."
        )
    return match.group(1)


def read_ack_file(path: Path) -> dict:
    with open(path, "r", encoding="utf-8") as handle:
        data = json.load(handle)
    if "acknowledgedUpstreamVersion" not in data:
        raise ValueError(f"{path} is missing acknowledgedUpstreamVersion")
    return data


def github_headers() -> dict:
    headers = {
        "Accept": "application/vnd.github+json",
        "User-Agent": "QortiumCore-pirate-bundle-freshness",
        "X-GitHub-Api-Version": "2022-11-28",
    }
    token = os.environ.get("GITHUB_TOKEN")
    if token:
        headers["Authorization"] = f"Bearer {token}"
    return headers


def fetch_latest_release(repo: str) -> tuple[Optional[dict], Optional[str]]:
    """Fetch the latest release for a GitHub repo.

    Returns (payload, error):
      * (payload, None) on success.
      * (None, None) when the repo genuinely has no releases (404) -- this
        is a normal, tolerated state, not an error.
      * (None, "<message>") for any other failure: non-404 HTTP status,
        network/timeout error, or a response that is not valid JSON.
    """
    url = f"https://api.github.com/repos/{repo}/releases/latest"
    request = urllib.request.Request(url, headers=github_headers())
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            body = response.read().decode("utf-8")
    except urllib.error.HTTPError as exc:
        if exc.code == 404:
            return None, None
        return None, f"GitHub request failed ({exc.code}): {url}"
    except urllib.error.URLError as exc:
        return None, f"GitHub request failed (network error: {exc.reason}): {url}"
    except OSError as exc:
        return None, f"GitHub request failed ({exc}): {url}"

    try:
        return json.loads(body), None
    except json.JSONDecodeError as exc:
        return None, f"GitHub response for {url} was not valid JSON: {exc}"


def load_offline_json(path: str) -> Optional[dict]:
    if not path or path == "-":
        return None
    with open(path, "r", encoding="utf-8") as handle:
        return json.load(handle)


class ReleaseInfo(NamedTuple):
    """The outcome of looking up a repo's latest release.

    status is one of:
      "ok"      -- version is a valid ParsedVersion.
      "missing" -- the repo has no releases (tolerated, e.g. a fresh fork).
      "error"   -- the lookup failed or the tag could not be parsed; `error`
                   holds a human-readable reason. version is always None here.
    """
    version: Optional[ParsedVersion]
    published_at: Optional[str]
    url: Optional[str]
    status: str
    error: Optional[str] = None


def release_info_from_payload(
    payload: Optional[dict], fetch_error: Optional[str] = None
) -> ReleaseInfo:
    if fetch_error is not None:
        return ReleaseInfo(
            version=None, published_at=None, url=None, status="error", error=fetch_error
        )
    if payload is None:
        return ReleaseInfo(version=None, published_at=None, url=None, status="missing")
    tag = payload.get("tag_name")
    if not tag:
        return ReleaseInfo(
            version=None,
            published_at=None,
            url=None,
            status="error",
            error="release payload has no tag_name",
        )
    try:
        version = parse_version(tag)
    except ValueError as exc:
        return ReleaseInfo(
            version=None,
            published_at=None,
            url=None,
            status="error",
            error=f"unparsable tag_name {tag!r}: {exc}",
        )
    return ReleaseInfo(
        version=version,
        published_at=payload.get("published_at"),
        url=payload.get("html_url"),
        status="ok",
    )


def run_check(
    pinned_tag: str,
    ack: dict,
    upstream_release: ReleaseInfo,
    fork_release: ReleaseInfo,
) -> int:
    print("Pirate Unified wallet bundle freshness check")
    print("=============================================")

    # The pinned tag and the acknowledged version are repo configuration, not
    # network input -- if either is unparsable that's our own error, not an
    # upstream-freshness verdict, so it gets its own exit code.
    try:
        pinned = parse_version(pinned_tag)
    except ValueError as exc:
        print(f"REPO ERROR: pinned RELEASE_TAG {pinned_tag!r} is not a parsable version: {exc}")
        return EXIT_COULD_NOT_DETERMINE

    ack_version_raw = ack.get("acknowledgedUpstreamVersion")
    try:
        acknowledged = parse_version(ack_version_raw)
    except ValueError as exc:
        print(
            "REPO ERROR: acknowledgedUpstreamVersion "
            f"{ack_version_raw!r} in "
            f"tools/pirate-bundle-freshness.json is not a parsable version: {exc}"
        )
        return EXIT_COULD_NOT_DETERMINE

    print(f"{'Pinned (Core RELEASE_TAG)':32}: {pinned.raw}")
    print(f"{'Acknowledged upstream version':32}: {acknowledged.raw}")
    note = ack.get("note")
    if note:
        print(f"{'Acknowledgement note':32}: {note}")

    # Extracted into plain local variables (rather than repeatedly reading
    # `.version` off the ReleaseInfo NamedTuple) so the None-ness of each is
    # tracked precisely from this point on -- both for the reader and for
    # static analysis: `status == "ok"` guarantees `.version is not None`,
    # but that invariant isn't expressible on the NamedTuple field itself.
    upstream_version: Optional[ParsedVersion] = None
    if upstream_release.status == "ok":
        assert upstream_release.version is not None
        upstream_version = upstream_release.version
        published = upstream_release.published_at or "unknown date"
        print(
            f"{'Upstream latest release':32}: "
            f"{upstream_version.raw} ({published})"
        )
        if upstream_release.url:
            print(f"{'Upstream release URL':32}: {upstream_release.url}")
    elif upstream_release.status == "missing":
        print(f"{'Upstream latest release':32}: (no releases published)")
    else:
        print(f"{'Upstream latest release':32}: (could not be determined: {upstream_release.error})")

    fork_version: Optional[ParsedVersion] = None
    if fork_release.status == "ok":
        assert fork_release.version is not None
        fork_version = fork_release.version
        published = fork_release.published_at or "unknown date"
        print(
            f"{'Fork (QortiumDev) latest release':32}: "
            f"{fork_version.raw} ({published})"
        )
        if fork_release.url:
            print(f"{'Fork release URL':32}: {fork_release.url}")
    elif fork_release.status == "missing":
        print(f"{'Fork (QortiumDev) latest release':32}: (no releases published)")
    else:
        # Tolerated: printed as a note, never affects the exit code.
        print(
            f"{'Fork (QortiumDev) latest release':32}: "
            f"(could not be determined: {fork_release.error})"
        )

    print()

    # Warn (not fail) when the fork's latest release base version has fallen
    # below the pinned tag -- this indicates a broken/stale fork release feed,
    # not an upstream freshness gap, so it must never affect the exit code.
    if fork_version is not None and compare_base(fork_version, pinned) < 0:
        print(
            "WARNING: the fork's latest published release "
            f"({fork_version.raw}) is behind the pinned tag "
            f"({pinned.raw}). This may indicate a stale or missing fork "
            "release rather than an upstream freshness issue."
        )
        print()

    if upstream_version is None:
        reason = upstream_release.error or "upstream has no releases published"
        print(f"COULD NOT DETERMINE UPSTREAM: {reason}")
        print(
            "The freshness comparison could not run this time. Treating this "
            "as a failed check (rather than a silent pass) so a persistent "
            "outage or rate limit is still noticed."
        )
        return EXIT_COULD_NOT_DETERMINE

    ceiling = pinned if compare_base(pinned, acknowledged) >= 0 else acknowledged
    if compare_base(upstream_version, ceiling) <= 0:
        print(
            f"OK: upstream's latest release ({upstream_version.raw}) is "
            f"covered by the pinned tag ({pinned.raw}) and/or the acknowledged "
            f"version ({acknowledged.raw}). No action needed."
        )
        return EXIT_OK

    print(f"UPSTREAM AHEAD: upstream has released {upstream_version.raw}, "
          f"which is newer than both the pinned bundle ({pinned.raw}) and the "
          f"acknowledged version ({acknowledged.raw}).")
    print()
    print("Next steps:")
    print("  1. Rebase the QortiumDev/Pirate-Unified-Light-Wallet fork onto the")
    print(f"     new upstream release ({upstream_version.raw}).")
    print("  2. Build the JNI artifact bundle from the rebased fork.")
    print("  3. Run the Core compatibility and acceptance harness against the")
    print("     new bundle (see docs/cross-chain/pirate-unified-wallet-acceptance.md).")
    print("  4. Republish the reviewed bundle to QDN and byte-verify it matches")
    print("     what was built.")
    print("  5. Repin RELEASE_TAG, ARTIFACT_FILENAME (size), the SHA-256 in")
    print("     tools/pirate-unified-artifact.properties, and the QDN signature")
    print("     constant in src/main/java/org/qortium/settings/Settings.java.")
    print("  6. Bump acknowledgedUpstreamVersion in")
    print("     tools/pirate-bundle-freshness.json once the repin is merged (or")
    print("     sooner, as an explicit deferral, if the upgrade is deliberately")
    print("     deferred).")
    return EXIT_UPSTREAM_AHEAD


def self_test() -> None:
    """Offline sanity checks for the comparison logic. No network access."""

    # --- tuple parsing ---
    assert parse_version("v1.1.9").tuple == (1, 1, 9)
    assert parse_version("1.1.9").tuple == (1, 1, 9)
    assert parse_version("v1.2.0").tuple == (1, 2, 0)

    # --- prerelease / build-metadata handling ---
    fork_version = parse_version("v1.1.8-qortium.3")
    assert fork_version.tuple == (1, 1, 8)
    assert fork_version.prerelease == "qortium.3"
    rc_version = parse_version("v1.2.0-rc.1")
    assert rc_version.tuple == (1, 2, 0)
    assert rc_version.prerelease == "rc.1"
    # Base-version comparison ignores the suffix.
    assert compare_base(fork_version, parse_version("v1.1.8")) == 0

    # --- ordering ---
    assert compare_base(parse_version("v1.1.9"), parse_version("v1.2.0")) < 0
    assert compare_base(parse_version("v1.2.0"), parse_version("v1.1.9")) > 0
    assert compare_base(parse_version("v1.1.9"), parse_version("v1.1.9")) == 0
    assert compare_base(parse_version("v1.10.0"), parse_version("v1.9.9")) > 0

    # --- unparsable versions raise ValueError ---
    for bad in ("not-a-version", "", "v1.2", None):
        try:
            parse_version(bad)
            raise AssertionError(f"expected ValueError parsing {bad!r}")
        except ValueError:
            pass

    # --- RELEASE_TAG extraction ---
    sample_java = (
        'class PirateUnifiedWalletBundle {\n'
        '\tstatic final String RELEASE_TAG = "v1.1.9";\n'
        '\tstatic final String ARTIFACT_FILENAME = '
        '"pirate-unified-wallet-qortal-jni-artifacts-v1.1.9.zip";\n'
        '}\n'
    )
    assert read_release_tag(sample_java) == "v1.1.9"

    # --- release_info_from_payload: malformed upstream JSON (no tag_name) ---
    malformed_info = release_info_from_payload({"published_at": "2026-01-01T00:00:00Z"})
    assert malformed_info.status == "error"
    assert malformed_info.version is None
    assert malformed_info.error is not None and "tag_name" in malformed_info.error

    # --- release_info_from_payload: unparsable tag_name ---
    unparsable_info = release_info_from_payload({"tag_name": "not-a-version"})
    assert unparsable_info.status == "error"
    assert unparsable_info.version is None

    # --- release_info_from_payload: fetch_error propagates as status "error" ---
    fetch_error_info = release_info_from_payload(None, fetch_error="GitHub request failed (500): x")
    assert fetch_error_info.status == "error"
    assert fetch_error_info.error == "GitHub request failed (500): x"

    # --- release_info_from_payload: missing payload (404) is "missing", not "error" ---
    missing_info = release_info_from_payload(None)
    assert missing_info.status == "missing"
    assert missing_info.error is None

    ok_upstream = ReleaseInfo(
        version=parse_version("v1.2.0"),
        published_at="2026-01-01T00:00:00Z",
        url="https://example.invalid/v1.2.0",
        status="ok",
    )
    ok_fork = ReleaseInfo(
        version=parse_version("v1.1.8-qortium.3"),
        published_at="2025-12-01T00:00:00Z",
        url="https://example.invalid/fork",
        status="ok",
    )

    # --- acknowledged-version override: upstream ahead of pin but covered by
    #     an explicit acknowledgement must pass (exit 0). ---
    pass_code = run_check(
        pinned_tag="v1.1.9",
        ack={"acknowledgedUpstreamVersion": "v1.2.0", "note": "test"},
        upstream_release=ok_upstream,
        fork_release=ok_fork,
    )
    assert pass_code == EXIT_OK, "acknowledged upstream version must not fail the check"

    # --- upstream ahead of both pin and acknowledgement must exit 1. ---
    fail_code = run_check(
        pinned_tag="v1.1.9",
        ack={"acknowledgedUpstreamVersion": "v1.1.9", "note": "test"},
        upstream_release=ok_upstream,
        fork_release=ok_fork,
    )
    assert fail_code == EXIT_UPSTREAM_AHEAD, "un-acknowledged upstream release must exit 1"

    # --- no releases on the fork must be tolerated, not raise or change exit code. ---
    tolerant_code = run_check(
        pinned_tag="v1.1.9",
        ack={"acknowledgedUpstreamVersion": "v1.1.9", "note": "test"},
        upstream_release=ReleaseInfo(
            version=parse_version("v1.1.9"),
            published_at="2025-06-01T00:00:00Z",
            url="https://example.invalid/v1.1.9",
            status="ok",
        ),
        fork_release=ReleaseInfo(version=None, published_at=None, url=None, status="missing"),
    )
    assert tolerant_code == EXIT_OK

    # --- an unparsable/errored fork release must also be tolerated. ---
    fork_error_tolerant_code = run_check(
        pinned_tag="v1.1.9",
        ack={"acknowledgedUpstreamVersion": "v1.1.9", "note": "test"},
        upstream_release=ReleaseInfo(
            version=parse_version("v1.1.9"),
            published_at="2025-06-01T00:00:00Z",
            url="https://example.invalid/v1.1.9",
            status="ok",
        ),
        fork_release=ReleaseInfo(
            version=None, published_at=None, url=None, status="error", error="boom"
        ),
    )
    assert fork_error_tolerant_code == EXIT_OK

    # --- upstream that could not be fetched/parsed must exit 2, not 0 or 1. ---
    could_not_determine_code = run_check(
        pinned_tag="v1.1.9",
        ack={"acknowledgedUpstreamVersion": "v1.1.9", "note": "test"},
        upstream_release=ReleaseInfo(
            version=None,
            published_at=None,
            url=None,
            status="error",
            error="GitHub request failed (network error: timed out): https://example.invalid",
        ),
        fork_release=ok_fork,
    )
    assert could_not_determine_code == EXIT_COULD_NOT_DETERMINE

    # --- upstream with a payload that has no tag_name must also exit 2. ---
    malformed_upstream_code = run_check(
        pinned_tag="v1.1.9",
        ack={"acknowledgedUpstreamVersion": "v1.1.9", "note": "test"},
        upstream_release=release_info_from_payload({"published_at": "2026-01-01T00:00:00Z"}),
        fork_release=ok_fork,
    )
    assert malformed_upstream_code == EXIT_COULD_NOT_DETERMINE

    # --- upstream missing (no releases at all) must also exit 2: we cannot
    #     determine freshness without a comparison point. ---
    upstream_missing_code = run_check(
        pinned_tag="v1.1.9",
        ack={"acknowledgedUpstreamVersion": "v1.1.9", "note": "test"},
        upstream_release=ReleaseInfo(version=None, published_at=None, url=None, status="missing"),
        fork_release=ok_fork,
    )
    assert upstream_missing_code == EXIT_COULD_NOT_DETERMINE

    # --- an unparsable pinned RELEASE_TAG is a repo error: exit 2. ---
    bad_pin_code = run_check(
        pinned_tag="not-a-version",
        ack={"acknowledgedUpstreamVersion": "v1.1.9", "note": "test"},
        upstream_release=ok_upstream,
        fork_release=ok_fork,
    )
    assert bad_pin_code == EXIT_COULD_NOT_DETERMINE

    # --- an unparsable acknowledgedUpstreamVersion is a repo error: exit 2. ---
    bad_ack_code = run_check(
        pinned_tag="v1.1.9",
        ack={"acknowledgedUpstreamVersion": "not-a-version", "note": "test"},
        upstream_release=ok_upstream,
        fork_release=ok_fork,
    )
    assert bad_ack_code == EXIT_COULD_NOT_DETERMINE

    print("All self-tests passed.")


def main() -> int:
    parser = argparse.ArgumentParser(
        description=(
            "Check whether the pinned Pirate Unified wallet bundle release "
            "has fallen behind upstream. Non-gating: informational only."
        )
    )
    parser.add_argument(
        "--self-test",
        action="store_true",
        help="Run offline self-tests of the comparison logic and exit.",
    )
    parser.add_argument(
        "--offline",
        action="store_true",
        help=(
            "Skip live GitHub API calls; use --upstream-json/--fork-json "
            "fixtures instead (for tests)."
        ),
    )
    parser.add_argument(
        "--upstream-json",
        default=None,
        help="Path to a JSON file with a GitHub releases/latest-shaped payload "
        "for the upstream repo (used with --offline).",
    )
    parser.add_argument(
        "--fork-json",
        default=None,
        help="Path to a JSON file with a GitHub releases/latest-shaped payload "
        "for the fork repo (used with --offline). Omit for 'no releases'.",
    )
    parser.add_argument(
        "--bundle-file",
        default=None,
        help="Override the path to PirateUnifiedWalletBundle.java (for tests).",
    )
    parser.add_argument(
        "--ack-file",
        default=None,
        help="Override the path to tools/pirate-bundle-freshness.json (for tests).",
    )
    args = parser.parse_args()

    if args.self_test:
        self_test()
        return EXIT_OK

    bundle_file = Path(args.bundle_file) if args.bundle_file else BUNDLE_JAVA_FILE
    ack_file = Path(args.ack_file) if args.ack_file else ACK_FILE

    java_text = bundle_file.read_text(encoding="utf-8")
    pinned_tag = read_release_tag(java_text)
    ack = read_ack_file(ack_file)

    if args.offline:
        upstream_payload = load_offline_json(args.upstream_json) if args.upstream_json else None
        fork_payload = load_offline_json(args.fork_json) if args.fork_json else None
        upstream_release = release_info_from_payload(upstream_payload)
        fork_release = release_info_from_payload(fork_payload)
    else:
        upstream_payload, upstream_error = fetch_latest_release(UPSTREAM_REPO)
        fork_payload, fork_error = fetch_latest_release(FORK_REPO)
        upstream_release = release_info_from_payload(upstream_payload, upstream_error)
        fork_release = release_info_from_payload(fork_payload, fork_error)

    return run_check(pinned_tag, ack, upstream_release, fork_release)


if __name__ == "__main__":
    sys.exit(main())
