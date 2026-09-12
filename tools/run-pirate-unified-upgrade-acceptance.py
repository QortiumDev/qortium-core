#!/usr/bin/env python3
"""Opt-in three-JVM disposable encrypted-wallet release upgrade acceptance."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import signal
import subprocess
import tempfile
import time
import uuid
import xml.etree.ElementTree as ET


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("old-artifact", "old-bundle", "artifact", "bundle", "receipt"):
        parser.add_argument("--" + name, required=True, type=Path)
    parser.add_argument("--native", required=True, action="store_true")
    args = parser.parse_args()
    for path in vars(args).values():
        if isinstance(path, Path) and not path.is_absolute():
            parser.error("All paths must be absolute")
    if args.receipt.exists():
        parser.error("Receipt already exists")
    if os.uname().sysname != "Linux" or os.uname().machine != "x86_64":
        parser.error("Acceptance is scoped to Linux x86_64")
    repo = Path(__file__).resolve().parent.parent
    def git(*arguments):
        return subprocess.check_output(["git", *arguments], cwd=repo, text=True).strip()
    head = git("rev-parse", "HEAD")
    initial_tree = git("status", "--porcelain")
    maven_repo = Path.home() / ".m2/repository"
    if not maven_repo.is_dir():
        parser.error("Offline Maven cache missing")
    args.receipt.parent.mkdir(parents=True, exist_ok=True)
    suffix = "pirate-upgrade-" + uuid.uuid4().hex
    report_dir = repo / "target/surefire-reports"
    phase_results = []
    started = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
    try:
        with tempfile.TemporaryDirectory(prefix=".pirate-upgrade-", dir=args.receipt.parent) as temporary:
            root = Path(temporary)
            (root / "java-home").mkdir()
            (root / "tmp").mkdir()
            os.chmod(root, 0o700)
            # Do not inherit unrelated wallet/transport settings or Java options.
            # All native storage/cache/config paths and Java's home are explicit.
            env = {"PATH": os.environ.get("PATH", os.defpath), "LANG": "C.UTF-8"}
            for variable, relative in {
                "XDG_DATA_HOME": "xdg-data", "XDG_CONFIG_HOME": "xdg-config",
                "XDG_CACHE_HOME": "xdg-cache", "PIRATE_WALLET_DB_DIR": "native-fallback",
                "PIRATE_BLOCK_CACHE_DIR": "block-cache",
            }.items():
                directory = root / relative
                directory.mkdir()
                env[variable] = str(directory)
            env["PIRATE_WALLET_DB_PATH"] = str(root / "native-fallback/wallet.db")
            env["PIRATE_DEBUG_LOG_PATH"] = str(root / "native-debug.log")
            env["TMPDIR"] = str(root / "tmp")
            for phase in ("create", "upgrade", "reopen"):
                artifact = args.old_artifact if phase == "create" else args.artifact
                bundle = args.old_bundle if phase == "create" else args.bundle
                phase_suffix = suffix + "-" + phase
                command = ["mvn", "--offline", "-Dstyle.color=never", "-DskipJUnitTests=false",
                           "-Dmaven.repo.local=" + str(maven_repo),
                           "-Dmaven.gitcommitid.nativegit=true",
                           "-Duser.home=" + str(root / "java-home"),
                           "-Djava.io.tmpdir=" + str(root / "tmp"),
                           "-Dsurefire.reportNameSuffix=" + phase_suffix,
                           "-Dtest=PirateUnifiedUpgradeAcceptanceTests",
                           "-Dqortium.runPirateUnifiedUpgradeAcceptanceTests=true",
                           "-Dqortium.pirateUpgradePhase=" + phase,
                           "-Dqortium.pirateUnifiedArtifactPath=" + str(artifact),
                           "-Dqortium.pirateUnifiedBundlePath=" + str(bundle),
                           "-Dqortium.pirateUpgradeStoragePath=" + str(root / "storage"), "test"]
                with (root / (phase + ".log")).open("wb") as log:
                    process = subprocess.Popen(command, cwd=repo, env=env, stdout=log,
                                               stderr=subprocess.STDOUT, start_new_session=True)
                    try:
                        code = process.wait(timeout=600)
                    except subprocess.TimeoutExpired:
                        os.killpg(process.pid, signal.SIGTERM)
                        try:
                            process.wait(timeout=15)
                        except subprocess.TimeoutExpired:
                            os.killpg(process.pid, signal.SIGKILL)
                            process.wait()
                        raise RuntimeError("Native upgrade phase timed out: " + phase)
                report = report_dir / ("TEST-org.qortium.controller.PirateUnifiedUpgradeAcceptanceTests-" + phase_suffix + ".xml")
                if code != 0 or not report.is_file():
                    raise RuntimeError("Native upgrade phase failed (private logs removed): " + phase)
                suite = ET.parse(report).getroot()
                expected = {"tests": "1", "failures": "0", "errors": "0", "skipped": "0"}
                if any(suite.get(key) != value for key, value in expected.items()):
                    # Retain only failure classification, never raw native responses or wallet material.
                    raise RuntimeError("Native upgrade report did not pass: " + phase)
                phase_results.append(phase)
    finally:
        for report in report_dir.glob("*" + suffix + "*"):
            if report.is_file():
                report.unlink()
    if head != git("rev-parse", "HEAD") or initial_tree != git("status", "--porcelain"):
        raise RuntimeError("Core source state changed during acceptance")
    def sha256(path):
        with path.open("rb") as stream:
            return hashlib.file_digest(stream, "sha256").hexdigest()
    receipt = {
        "result": "PASS", "coreCommit": head,
        "tree": "unchanged; " + ("clean" if not initial_tree else "includes pending changes"),
        "host": "Linux x86_64", "startedUtc": started,
        "oldArtifactSha256": sha256(args.old_artifact), "artifactSha256": sha256(args.artifact),
        "phases": phase_results, "tests": 3, "failures": 0, "errors": 0, "skipped": 0,
        "boundary": "Three separate JVMs: old native creates and syncs a synthetic-note wallet; new native opens and reopens its encrypted storage without seed restore or history rescan. Identity, exported key digest, key group, birthday, transaction and balance persist. SQLCipher schema version is not queried; schema 41-to-42 is source-derived. No real wallet, public endpoint, transaction broadcast, downgrade, QDN publication or deployment proof. Temporary wallet state, logs and unique reports removed."
    }
    with args.receipt.open("x") as output:
        json.dump(receipt, output, indent=2)
        output.write("\n")
    print("PASS receipt=" + str(args.receipt) + " phases=create,upgrade,reopen")


if __name__ == "__main__":
    main()
