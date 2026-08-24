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

Offline native host acceptance is a second explicit gate. It loads the mapped
host library through Qortium's serialized native coordinator and uses temporary
storage to exercise deterministic seed derivation, fresh initialization,
birthday height, address/nonempty-key export, zero total and verified balances,
empty transaction listing, same-process persistent reopen, encryption-status
compatibility, two isolated wallet namespaces, typed invocation, and
local-loopback sync/cancel command compatibility. Returned seed, key, address,
and raw native responses are not written to receipts or logs. The deterministic
fixture databases are removed on normal runner exit:

```sh
tools/run-pirate-unified-acceptance.sh \
  /absolute/path/pirate-unified-wallet-qortal-jni-artifacts-v1.1.6.zip \
  /absolute/path/pirate-unified-v1.1.7 \
  /absolute/new/path/pirate-unified-native-receipt.md \
  --native
```

The receipt runner performs real-bundle validation and writes a new Markdown
receipt plus full log. Add `--native` only after native execution is approved:

```sh
tools/run-pirate-unified-acceptance.sh \
  /absolute/path/pirate-unified-wallet-qortal-jni-artifacts-v1.1.6.zip \
  /absolute/path/pirate-unified-v1.1.7 \
  /absolute/new/path/pirate-unified-receipt.md
```

## Disposable local-QDN fixture

Packaged-Core acceptance first needs a repository-backed resource that can be
resolved through the production `TRANSACTION_DATA` reader without publishing
anything. Prepare that fixture from an already validated staged bundle:

```sh
tools/prepare-pirate-unified-local-qdn-fixture.sh \
  /absolute/path/pirate-unified-v1.1.7 \
  /absolute/new/path/pirate-unified-local-qdn-fixture
```

The script refuses to overwrite its output. Its opt-in test uses Core's real
QDN writer to compress, split, and describe the bundle, assigns a deterministic
64-byte placeholder signature, and saves the `ARBITRARY_DATA` row directly into
a disposable on-disk repository. It does not compute a transaction nonce, use a
production/operator private key, sign, import, broadcast, confirm, or mint. The
test chain uses its normal deterministic bootstrap identities. The generated row
has no block height and is absent from the unconfirmed transaction pool.

Before retaining the fixture, the test closes and reopens the repository,
resolves the placeholder signature through the production pinned-resource
loader with missing-file network requests explicitly disabled, validates the
rebuilt bundle, and byte-compares every output file to the staged source.
`fixture.properties` records the placeholder signature, relative repository/data
paths, source-manifest hash, synthetic transaction state, and production-startup
readiness of the derived arbitrary-resource cache. Cache readiness is asserted
again after the retained repository is closed and reopened.

This is a **local fixture**, not a QDN publication or peer-retrieval result. It
does not load native code or start Core. A later packaged-Core runner must use a
fail-closed no-egress sandbox and report local-QDN resolution, cache installation,
native loading, and wallet lifecycle as separate results.

## Packaged loader acceptance

On Linux x86_64, a separately packaged Core JAR can exercise the production
loader against that fixture without network egress or wallet creation:

```sh
tools/run-pirate-unified-packaged-loader-acceptance.sh \
  /absolute/path/qortium-1.7.2.jar \
  /absolute/path/pirate-unified-v1.1.7 \
  /absolute/path/pirate-unified-local-qdn-fixture \
  /absolute/new/path/pirate-unified-packaged-loader-receipt.md
```

The runner refuses to overwrite its receipt or log, copies the retained
repository/data fixture into disposable storage, and starts the JAR in a
rootless network namespace containing only loopback. Core uses a restricted,
loopback-only API with ephemeral request files, a lite test-chain profile,
Pirate `TEST3` (which has no configured lightwallet servers), no seed or fixed
peers, and all non-ARRR wallets disabled. The namespace route/interface checks
are mandatory; the fixture's completeness is not treated as an egress control.

After the API is ready, the runner sends exactly one entropy-bearing
`POST /crosschain/arrr/syncstatus`. On an unloaded controller this only schedules
the asynchronous native-library load. The runner never repeats the request:
doing so after the load would initialize a wallet. Instead it waits for the
expected QDN-signature cache pathname to appear in `/proc/<pid>/maps`, then
requires exact staged/cache inventory and SHA-256 equality and confirms that
`wallets/PirateChain/unified` was not created. It sends SIGTERM only after the
mapping is observed and requires Core's graceful-shutdown confirmation.

This advances local-QDN resolution, cache installation, and packaged native
loading for Linux x86_64. It does not prove publication, retrieval from a peer,
wallet creation or reopening, synchronization, migration, or funded-wallet
behavior. Those lifecycle claims remain G2 work.

## Acceptance matrix

Artifact presence is `STAGED`, not runtime acceptance. Offline JNI and isolated
packaged-Core results remain separate for every target. FreeBSD is not in the
official five-target artifact; the legacy Linux filename mapping is not
FreeBSD acceptance.

| Target | Artifact | Offline JNI | Packaged Core |
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
state, a normalized command with sensitive paths redacted, test counts,
host/platform results, logs, and any counterexamples. Never record entropy,
seed phrases, keys, API keys,
passphrases, or wallet debug responses.
