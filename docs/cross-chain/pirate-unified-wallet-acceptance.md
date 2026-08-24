# Pirate Unified wallet artifact acceptance

This is the reproducible C7 harness for the default-off Pirate Unified wallet.
It validates an official local artifact without adding an arbitrary production
library path. Downloads, native execution, QDN publication, signing, funded
transactions, deployment, and default enablement remain separate decisions.

## Pinned official artifact

- Release tag: `v1.1.7`
- Asset filename: `pirate-unified-wallet-qortal-jni-artifacts-v1.1.6.zip`
- Size: `353764001` bytes
- SHA-256: `27773b37510ac5f6e9a594e1ae8a98e8b3b0dc9069506776314ba6719341f299`
- URL: `https://github.com/PirateNetwork/Pirate-Unified-Light-Wallet/releases/download/v1.1.7/pirate-unified-wallet-qortal-jni-artifacts-v1.1.6.zip`

The `v1.1.7` release tag and `v1.1.6` asset filename intentionally differ. The
official release reused the unchanged Qortal JNI developer artifact, retaining
its source-version filename. The pin file is
`tools/pirate-unified-artifact.properties`.

## Staged bundle contract

Run the staging script only after an operator has separately obtained the exact
archive:

```sh
tools/stage-pirate-unified-bundle.sh \
  /absolute/path/pirate-unified-wallet-qortal-jni-artifacts-v1.1.6.zip \
  /absolute/new/path/pirate-unified-v1.1.7
```

The script verifies the archive size and SHA-256, refuses to overwrite output,
and extracts exactly these seven payloads:

- `librust-linux-x86_64.so`
- `librust-linux-aarch64.so`
- `librust-macos-x86_64.dylib`
- `librust-macos-aarch64.dylib`
- `librust-windows-x86_64.dll`
- `LICENSE-qortal-jni.txt`
- `qortal-handoff.md`

The upstream `LiteWalletJni.java` is deliberately not copied into the runtime
bundle because Qortium carries its adapted declaration surface in source; an
always-on reflection test pins all twelve native declarations. The whole ZIP
hash still authenticates that official source file as part of the release
artifact.

It then creates `QORTIUM-MANIFEST.txt` with the exact release provenance and a
byte size plus SHA-256 for every payload. Core requires that exact inventory,
rejects symlinks and non-regular entries, rebuilds the selected QDN transaction
payload against its transaction data hash, and requires the cached manifest to
match that authenticated source. It rechecks the selected native library inside
the serialized native lane immediately before loading it. Cache publication
also fails closed if the filesystem cannot atomically rename the completed
sibling staging directory. Legacy wallet bundles remain compatible and do not
require this manifest.

This protects against incomplete copies and ordinary cache corruption. It does
not claim to defeat an actively hostile process running as the same OS user
during QDN-source verification, cache verification, or the final pathname handoff
to `System.load`.

## Validation levels

Always-on tests use synthetic bytes and never load native code:

```sh
/bin/sh -n tools/stage-pirate-unified-bundle.sh tools/run-pirate-unified-acceptance.sh
mvn -DskipTests=false \
  -Dtest='PirateUnifiedArtifactPinTests,PirateUnifiedWalletBundleTests,LiteWalletJniSurfaceTests,ZcashFamilyWalletControllerQdnTests' \
  test
```

After staging is separately approved, validate the real inventory without
executing it. This reads the original ZIP, rechecks its pinned size and SHA-256,
and byte-compares every staged payload to the matching archive entry:

```sh
mvn -DskipTests=false \
  -Dqortium.runPirateUnifiedArtifactAcceptanceTests=true \
  -Dqortium.pirateUnifiedArtifactPath=/absolute/path/pirate-unified-wallet-qortal-jni-artifacts-v1.1.6.zip \
  -Dqortium.pirateUnifiedBundlePath=/absolute/path/pirate-unified-v1.1.7 \
  -Dtest=PirateUnifiedArtifactAcceptanceTests \
  test
```

Native host smoke is a second explicit gate. It loads the mapped host library
through Qortium's serialized native coordinator and performs one deterministic
entropy-to-seed call without recording the returned seed:

```sh
mvn -DskipTests=false \
  -Dqortium.runPirateUnifiedNativeSmokeTests=true \
  -Dqortium.pirateUnifiedArtifactPath=/absolute/path/pirate-unified-wallet-qortal-jni-artifacts-v1.1.6.zip \
  -Dqortium.pirateUnifiedBundlePath=/absolute/path/pirate-unified-v1.1.7 \
  -Dtest=PirateUnifiedNativeSmokeTests \
  test
```

The receipt runner performs real-bundle validation and writes a new Markdown
receipt plus full log. Add `--native` only after native execution is approved:

```sh
tools/run-pirate-unified-acceptance.sh \
  /absolute/path/pirate-unified-wallet-qortal-jni-artifacts-v1.1.6.zip \
  /absolute/path/pirate-unified-v1.1.7 \
  /absolute/new/path/pirate-unified-receipt.md
```

## Acceptance matrix

Artifact presence is `STAGED`, not runtime acceptance. Each target remains
`NOT_RUN` until its JNI smoke and isolated packaged-Core workflow run on that
platform. FreeBSD is not in the official five-target artifact; the legacy Linux
filename mapping is not FreeBSD acceptance.

| Target | Artifact | JNI smoke | Packaged Core |
|---|---:|---:|---:|
| Linux x86_64 | NOT_RUN | NOT_RUN | NOT_RUN |
| Linux aarch64 | NOT_RUN | NOT_RUN | NOT_RUN |
| macOS x86_64 | NOT_RUN | NOT_RUN | NOT_RUN |
| macOS aarch64 | NOT_RUN | NOT_RUN | NOT_RUN |
| Windows x86_64 | NOT_RUN | NOT_RUN | NOT_RUN |

G2 must later cover fresh wallet, historical restore, migration and restart,
A/B account switching during sync, failover and bad-height servers,
disable/re-enable, total and verified balances, address/key/seed/transaction
listing, and packaged Core. G3 separately covers publication by an approved QDN
identity, retrieval by immutable transaction signature, byte comparison,
controlled receive/send, and ARRR HTLC/P2SH fund/redeem/refund recovery.

Receipts must include the artifact and manifest hashes, Core commit and tree
state, exact commands, test counts, host/platform results, logs, and any
counterexamples. Never record entropy, seed phrases, keys, API keys,
passphrases, or wallet debug responses.
