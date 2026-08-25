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
/bin/sh -n tools/stage-pirate-unified-bundle.sh tools/run-pirate-unified-acceptance.sh \
  tools/run-pirate-unified-packaged-loader-acceptance.sh \
  tools/run-pirate-unified-packaged-lifecycle-acceptance.sh
mvn -DskipTests=false \
  -Dtest='PirateUnifiedArtifactPinTests,PirateUnifiedWalletBundleTests,PirateUnifiedLoopbackLightwalletdTests,LiteWalletJniSurfaceTests,ZcashFamilyWalletControllerQdnTests' \
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

Loopback native host acceptance is a second explicit gate. It loads the mapped
host library through Qortium's serialized native coordinator and uses temporary
storage to exercise deterministic seed derivation, fresh initialization,
birthday height, address/nonempty-key export, zero total and verified balances,
empty transaction listing, same-process persistent reopen, encryption-status
compatibility, two isolated wallet namespaces, and typed invocation.

The opt-in native test also starts a test-only gRPC lightwalletd bound explicitly
to IPv4 loopback. It exposes both service names used by this integration:
`cash.z.wallet.sdk.rpc.CompactTxStreamer` for Core's Java client and
`pirate.wallet.sdk.rpc.CompactTxStreamer` for the pinned native library. The
fixture reports mainnet Sapling activation at height `152855`, a tip at `152858`,
and by default streams four ordered, hash-linked empty compact blocks. Acceptance requires
the native client to request that complete range, reach the fixture tip, stop
reporting active synchronization, and make no transaction, `GetTreeState`, or
other unexpected RPCs. The pinned client makes exactly one optional
`GetSubtreeRoots` capability probe; the fixture records it separately and must
return `UNIMPLEMENTED`. The fake also serves only the exact pre-Ironwood
activation probe at `tip - 30`, with a pre-activation timestamp, so the fixed
historical fixture does not become calendar-dependent near the scheduled
upgrade. The initial asynchronous `sync` response is not acceptance.

This default mode proves real native RPC and compact-block cursor behavior
against a synthetic empty chain. It does not prove canonical chain history,
historical wallet restoration, balances or transactions containing funds, or
production lightwalletd interoperability. Returned seed, key, address, and raw native
responses are not written to receipts or retained logs. The runner sets a
private umask, quarantines raw Maven/native output in its mode-0700 work
directory, scans it and this run's uniquely suffixed Surefire reports for
secret-shaped JSON and shielded addresses, retains only an allowlisted build
summary, and deletes the raw output, deterministic fixture databases, and those
Surefire reports. A signal handler terminates
and reaps the complete Maven/Surefire process group before storage cleanup and
lock release. It starts that group with `setsid`, or with Python's `os.setsid`
as a portable fallback, and refuses to publish a receipt until process-group
termination and secret-capable evidence deletion are both proven:

```sh
tools/run-pirate-unified-acceptance.sh \
  /absolute/path/pirate-unified-wallet-qortal-jni-artifacts-v1.1.6.zip \
  /absolute/path/pirate-unified-v1.1.7 \
  /absolute/new/path/pirate-unified-native-receipt.md \
  --native
```

The receipt runner performs real-bundle validation and writes a new Markdown
receipt plus sanitized build summary. Add `--native` only after native execution
is approved:

```sh
tools/run-pirate-unified-acceptance.sh \
  /absolute/path/pirate-unified-wallet-qortal-jni-artifacts-v1.1.6.zip \
  /absolute/path/pirate-unified-v1.1.7 \
  /absolute/new/path/pirate-unified-receipt.md
```

## Production lightwallet admission acceptance

The production-network gate is separately opt-in and read-only. It uses Core's
Java Pirate lightwallet client without initializing a wallet, deriving an
address, querying balances or transactions, or broadcasting data. For every
configured mainnet endpoint it checks TLS and chain-name admission, compares
the `GetLightdInfo` and `GetLatestBlock` heights, and requests the final two
compact blocks to prove both hashes are nonempty and the reported history is
hash-linked. A pass requires at
least two distinct configured READY endpoints within the configured height
tolerance; this does not establish independent infrastructure. Fully validated
endpoint heights are clustered only after their compact-range checks pass;
temporarily unavailable candidates are recorded rather than silently treated
as healthy.

Run it only from a clean committed Core tree and provide a new absolute receipt
path:

```sh
tools/run-pirate-production-lightwallet-acceptance.sh \
  /absolute/new/path/pirate-production-lightwallet-receipt.md
```

Runtime admission closes the observed shallow-probe case where a server
advertises a height through `GetLightdInfo` but cannot answer `GetLatestBlock`.
That bounded tip check does not itself request compact-block contents; the
separate production acceptance run adds the two-block content and linkage
check described above. Temporary latest-block failures or height disagreement
reject the current attempt but do not permanently exclude that endpoint for
the lifetime of the Core process. Neither check probes the separate
Pirate-native gRPC service, initializes or synchronizes the Unified JNI wallet,
or proves endpoint cutover after Java server selection changes. Those native
interoperability and failover claims require a separate bounded tranche.

## Fresh-install historical restore acceptance

The same loopback fixture has a separate, explicitly selected historical mode.
At height `152856`, after the conservative birthday `152855` and before tip
`152858`, it serves one deterministic compact Sapling note worth `123456789`
arrrtoshis to the public entropy-7 JNI test address. The compact commitment,
ephemeral key, and 52-byte ciphertext were generated offline from the pinned
Pirate Unified `v1.1.7` Rust sources with fixed test randomness. They are test
vectors, not production wallet material. The normal empty-chain fixture remains
unchanged unless historical mode is selected.

Vector provenance is reproducible: tag `v1.1.7` resolves to
`d9f76262c12e2836ab697c20f778761cde65de1a`; its workspace pins
`sapling-crypto` at `62fcf59a4d933244ee6280182c6cd3e5290e8a90`. The generator decodes the
fixture address with `pirate_core::keys::PaymentAddress`, creates a pre-ZIP-212
note appropriate for height `152856` with value `123456789`,
`Rseed::BeforeZip212(jubjub::Fr::from(0x4242))`, an all-zero 512-byte memo, and
`StdRng::seed_from_u64(0x51525449554d)`, then records `note.cmu()`, the
`SaplingDomain` ephemeral key, and the first `COMPACT_NOTE_SIZE` ciphertext
bytes. It was executed with Rust 1.90 in container image digest
`sha256:3f6e6f8d8725a65a2db964bb828850f888d430c68784d661f753144e5d787207`.
The synthetic transaction ID is SHA-256 of
`qortium-pirate-historical-note-v1`.

Run the historical gate only from a clean Core commit:

```sh
tools/run-pirate-unified-historical-restore-acceptance.sh \
  /absolute/path/pirate-unified-wallet-qortal-jni-artifacts-v1.1.6.zip \
  /absolute/path/pirate-unified-v1.1.7 \
  /absolute/new/path/pirate-unified-historical-restore-receipt.md
```

The runner currently accepts only Linux x86_64 and hashes the exact mapped JNI
library for that host. Each Maven process starts from an allowlisted environment:
HOME and XDG roots are fresh, the native block cache and debug log are pinned
inside that process's temporary root, and inherited Pirate wallet/cache/log
overrides are absent.

The runner invokes the opt-in test twice in sequence. Each invocation is a new
JVM/native process with distinct, initially empty HOME, XDG data/cache/config,
temporary, and wallet-storage roots. Both derive the same address from
the same deterministic account entropy, report zero history before scanning,
request the complete conservative-birthday-to-tip compact range, and recover
exactly one confirmed transaction plus the expected total and verified balance.
The test also checks the recovered height, address, and value metadata. A range
request alone is not a pass, and an in-process storage switch is not used as a
second-computer substitute because the native block cache can survive that
switch.

Two development counterexamples define the gate's boundary. A post-ZIP-212
test note at the pre-ZIP-212 fixture height was not valid evidence and was
replaced by the era-correct vector above. A non-isolated run also appeared to
pass after reusing native cache state; clean HOME/XDG process roots reproduced
the zero-balance failure and invalidated that result. Neither failed run is a
success receipt.

Maven runs offline against the already installed local repository. Each process
uses a unique Surefire report suffix; the runner moves only those reports from
the shared build directory into its mode-0700 temporary root. Raw Maven, native,
and quarantined Surefire evidence is scanned for secret-shaped wallet material
and shielded addresses, then deletion is verified before a receipt is written.
This gate is deterministic and unfunded. It
proves fresh-install recognition and balance reporting for a valid synthetic
Sapling note using the pinned JNI library. It does not prove canonical Pirate
mainnet history, production lightwalletd interoperability, witness usability or
spending against a real chain, funded behavior, QDN publication, deployment,
default enablement, or Home behavior.

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

## Packaged lifecycle acceptance

After the loader-only gate passes, the same packaged JAR can run the first
bounded wallet-lifecycle gate:

```sh
tools/run-pirate-unified-packaged-lifecycle-acceptance.sh \
  /absolute/path/qortium-1.7.2.jar \
  /absolute/path/pirate-unified-v1.1.7 \
  /absolute/path/pirate-unified-local-qdn-fixture \
  /absolute/new/path/pirate-unified-packaged-lifecycle-receipt.md
```

This runner starts packaged Core exactly twice in one rootless, loopback-only
network namespace. A standalone form of the test lightwalletd stays up across
both starts on explicit IPv4 loopback. Its Java-compatible service identifies
as `regtest`, while its Pirate native service identifies as `main`; both serve
the same four ordered, empty compact blocks. Pirate regtest server entries use
`127.0.0.1`, not hostname resolution, so the native URI cannot drift to IPv6.

The first start resolves and maps the QDN-derived native library, creates one
unfunded entropy namespace from configured birthday `152855`, reaches the exact
fixture tip, and durably records `MIGRATING` with validated synchronization.
After a graceful shutdown, the second packaged start uses the same disposable
repository and wallet storage, reopens the same namespace and one-way identity,
reaches `READY`, promotes the state to `UNIFIED_READY`, and shuts down
gracefully. Persistent Unified status cannot report ready merely because a sync
command was accepted, and its durable validation requires the exact tip rather
than the legacy two-block lag tolerance. A first wallet selection receives the
normal initialization timeout; steady-state status calls retain the short
bounded timeout.

The fixture must observe Pirate tip and compact-block activity, its optional
subtree capability probe, zero transaction read/send RPCs, and zero unexpected
RPCs. The runner validates the exact start/shutdown count, storage continuity,
network routes and interfaces, and QDN library mapping. It scans raw Core,
native, fixture, API-status, and wallet-state evidence for the request entropy,
API key, seed/key/address-shaped JSON, and shielded addresses, then deletes that
evidence and retains only a sanitized receipt and log.

This proves fresh creation, exact-tip synchronization, persistence, and clean
restart/reopen on unfunded Linux x86_64 local fixtures. Historical recovery is
covered by the separate fresh-install gate above, not by this transaction-free
packaged run. This packaged gate does not prove real legacy
migration, A/B switching during an active sync, disable/re-enable, production
lightwalletd interoperability, funds, QDN publication, deployment, default
enablement, or Home behavior.

## Acceptance matrix

Artifact presence is `STAGED`, not runtime acceptance. Loopback JNI and isolated
packaged-Core results remain separate for every target. FreeBSD is not in the
official five-target artifact; the legacy Linux filename mapping is not
FreeBSD acceptance.

| Target | Artifact | Loopback JNI | Packaged Core |
|---|---:|---:|---:|
| Linux x86_64 | NOT_RUN | NOT_RUN | NOT_RUN |
| Linux aarch64 | NOT_RUN | NOT_RUN | NOT_RUN |
| macOS x86_64 | NOT_RUN | NOT_RUN | NOT_RUN |
| macOS aarch64 | NOT_RUN | NOT_RUN | NOT_RUN |
| Windows x86_64 | NOT_RUN | NOT_RUN | NOT_RUN |

G2 must later cover real legacy migration, A/B account switching during sync,
failover and bad-height servers, disable/re-enable, production-network recovery,
funded balance/spend behavior, and other platforms. G3 separately covers publication by an approved QDN
identity, retrieval by immutable transaction signature, byte comparison,
controlled receive/send, and ARRR HTLC/P2SH fund/redeem/refund recovery.

Receipts must include the artifact and manifest hashes, Core commit and tree
state, a normalized command with sensitive paths redacted, test counts,
host/platform results, sanitized logs, and any counterexamples. Never record entropy,
seed phrases, keys, API keys,
passphrases, or wallet debug responses.
