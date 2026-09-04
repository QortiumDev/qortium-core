# Pirate Unified wallet artifact acceptance

This is the reproducible C7 harness for the default-off Pirate Unified wallet.
It validates the pinned local artifact without adding an arbitrary production
library path. Downloads, native execution, QDN publication, signing, funded
transactions, deployment, and default enablement remain separate decisions.

## Pinned artifact

- Release tag: `v1.2.0`
- Asset filename: `pirate-unified-wallet-qortal-jni-artifacts-v1.2.0.zip`
- Size: `363186519` bytes
- SHA-256: `646fd1ba49641b8b4d5859355fc78d6aba92bb858ab1fc75a388e27ab462f76c`
- URL: `https://github.com/PirateNetwork/Pirate-Unified-Light-Wallet/releases/download/v1.2.0/pirate-unified-wallet-qortal-jni-artifacts-v1.2.0.zip`

Provenance: this is the official PirateNetwork `v1.2.0` release artifact
(upstream renamed the product "Stashi Wallet"; the repository path is
unchanged), built from commit `92041eef6abf9d5f6adf2ce05111b67080a5ea75` by
release workflow run
[`33566264516`](https://github.com/PirateNetwork/Pirate-Unified-Light-Wallet/actions/runs/33566264516).
It keeps the note-based recovery of the full 88-bit Sapling diversifier cursor
introduced in `v1.1.9` and adds wallet-scoped signing envelopes and broadcasts,
an explicit spendability/session contract, a witness repair-readiness fix, and
in-memory Sapling parameters. Core's JNI adapter surface is unchanged between
`v1.1.9` and `v1.2.0`: all twelve loaded symbols, every request Core issues,
and every response field Core reads keep their names and semantics, and the
storage schema migrations are additive and forward-only. The release ZIP and
checksum manifest signatures verify under Pirate Unified's published signing
fingerprint `E4FB2399AECCF9B9447DED472CE65343401553A6`, the same key that
signed `v1.1.9`.

The exact release payloads are:

| File | Bytes | SHA-256 |
| --- | ---: | --- |
| `LICENSE-qortal-jni.txt` | 1081 | `5ee51e5067dd67cbf94640328494418539c708c7ec420e8a30c808866b384ab0` |
| `librust-linux-aarch64.so` | 111740672 | `4e8e06396c4563578d5a6d7bce1d58810e0292d80b1870ed635b24b61e313c4a` |
| `librust-linux-x86_64.so` | 114279720 | `472e858b5893858b65156a7357f5db7a8bd7bd3e6c94a8692ed5f85e6e35019a` |
| `librust-macos-aarch64.dylib` | 100081376 | `1e0002f3fbb4d9492f9b01763ef2e7866f37ecaad58ec0e719f6c866f91a3d5c` |
| `librust-macos-x86_64.dylib` | 102730196 | `43b260e33d6c149123d193015ee3e880d4c50fb8e57723acf5e2504979923e15` |
| `librust-windows-x86_64.dll` | 104588800 | `0d397a3f3c3e993b2a4f000273509ec7afcb946ce6682bf17a12f802d02a0092` |
| `qortal-handoff.md` | 11406 | `bb10a948da37d2d95b3d90522af11b4732e1073537d7f611e5573ae9d507756f` |
| `LiteWalletJni.java` | 1551 | `2aa1768c72a5d900367b0f068c6f430230d03d63f9df2ab773004c049fe9091e` |

Superseded Qortium fork artifacts remain retained for provenance:
`v1.1.8-qortium.3` (size `362743273`, SHA-256
`a06bb575929e38b8d6062f0220a71a0a88c25f95a8c90c324a73c1b6950ee0ca`),
`v1.1.8-qortium.2` (size
`362680463`, SHA-256
`243fe3da010924c63a3509dcc3d01f681d1430fdd0391102b6ae11a78b31d803`) carried
an incomplete first version of the fix that missed the dominant
string-wrapped cancellation path; `v1.1.8-qortium.1` (size `362642330`,
SHA-256 `3824ea8535fae411d1a20381051aaa6867e89071e3cb2bd49a8264ec3cebe357`)
contains the upstream bug unfixed; and before them the official
`v1.1.7`/`v1.1.6` artifact (size `353764001`, SHA-256
`27773b37510ac5f6e9a594e1ae8a98e8b3b0dc9069506776314ba6719341f299`)
predates the verified spending-key import. The pin file is
`tools/pirate-unified-artifact.properties`.

## Upstream freshness watcher

A scheduled, non-gating check (`.github/workflows/pirate-bundle-freshness.yml`,
running `tools/check-pirate-bundle-freshness.py`) compares the pinned
`RELEASE_TAG` above against the latest releases published by upstream
(`PirateNetwork/Pirate-Unified-Light-Wallet`) and by our fork
(`QortiumDev/Pirate-Unified-Light-Wallet`). It runs weekly via
`workflow_dispatch`/`schedule` only -- never on pull requests, never as part
of a build, and it has no effect on users. When upstream is ahead of both the
pin and the acknowledged version, the scheduled run fails (shows red) and the
job opens or updates a single tracking issue labeled `upstream-freshness`; it
never blocks a build or a release.

`tools/pirate-bundle-freshness.json` holds an `acknowledgedUpstreamVersion`,
the same deliberate-deferral pattern Home uses for its i2pd freshness check.
It lets maintainers see and consciously accept a known upstream gap (for
example, while a fork rebase or acceptance pass for the new release is in
progress) without the scheduled job going red every week for something
already being worked on. Bump it only when a newer upstream release has been
reviewed and either the pin is updated to match, or the gap is knowingly
deferred with an updated `note` explaining why.

Run it locally at any time with:

```
python3 tools/check-pirate-bundle-freshness.py
```

Exit code `0` means the pin (or an explicit acknowledgement) already covers
the latest upstream release; exit code `1` means upstream has moved and lists
the repin steps (rebase fork, build bundle, run the acceptance harness above,
republish to QDN byte-exact, repin `RELEASE_TAG`/size/SHA-256/QDN signature,
bump the acknowledged version). `python3 tools/check-pirate-bundle-freshness.py
--self-test` runs the comparison logic offline against fixtures with no
network access.

## Published QDN bundle

The reviewed official v1.2.0 runtime bundle is published on Qortium Previewnet
as `ARBITRARY_DATA/QortiumHomeTest/pirate-unified-wallet` at immutable
transaction signature
`3FEsot2pkqPNwtHKuLGTsaRwMTRpK6Kv6dUVp3mps1BHKoCEq5j3QgwJDjMZRwe2ftpvZouLTqG42JphjfAFVQe3`.
It confirmed at height `116682` with fee zero and MemoryPoW nonce `2674`. This
supersedes the v1.1.9 publication at
`3QtMvKDTMUG6V48SKPCwMTPgiqNYdaCwyXfpssfuGD13d7ZL31kk48cuRpuzxy8qnSGg4qgZKEUJ8zYJ7UDQ9aBk`
(height `111063`), which remains immutable on chain but is no longer pinned.

Regxa and Netcup independently reached `READY` at 694/694 chunks. On both
nodes, the signature-keyed encrypted transaction payload matched the on-chain
data hash, and every file in the authenticated/decrypted bundle matched the
reviewed staged artifact. Core pins this transaction as the default bundle
source while retaining `pirateChainWalletUnified=false`; carrying the pin does
not load the library, start a wallet, or enable ARRR.

## Closed gate: legacy address reads after a recovery

Core reads Unified balances through the typed `get_balance` request, which does
not build a per-address breakdown. Two other paths still call the legacy
address lookup, which does: Unified wallet initialization and validated-sync
recording both resolve the wallet's legacy Sapling receive address for the
migration identity check.

The superseded `v1.1.8-qortium.3` artifact handled an imported address by
walking the wallet's own key through 4096 sequential addresses. That could
exceed Core's native-lane timeout during either identity path and meant that a
restart could re-enter the same stall.

Official `v1.1.9` removes that sequential scan. Its note ownership repair
recovers and persists the exact full Sapling diversifier cursor instead. The
request's 32-bit `address_index` remains legacy response/display metadata, not
an ownership boundary or derivation cursor; native acceptance requires a retry
with different metadata to remain idempotent with the same verified key group.
The native recovery acceptance now requires both Core identity paths to finish in
under 15 seconds after a foreign-key recovery: `recordValidatedSync`, followed
by a new `PirateWallet` object initializing over the same persistent registry.
It also requires the recovered balance to survive that initialization and the
validated namespace to promote to `UNIFIED_READY`. The identity check itself
is unchanged and must still agree with the legacy-compatible receive address.

## Staged bundle contract

Run the staging script only after an operator has separately obtained the exact
archive:

```sh
tools/stage-pirate-unified-bundle.sh \
  /absolute/path/pirate-unified-wallet-qortal-jni-artifacts-v1.2.0.zip \
  /absolute/new/path/pirate-unified-v1.2.0
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
hash still authenticates that upstream source file as part of the release
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
  tools/run-pirate-unified-packaged-lifecycle-acceptance.sh \
  tools/run-pirate-legacy-v8-inspection-acceptance.sh \
  tools/run-pirate-unified-packaged-real-legacy-migration-acceptance.sh
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
  -Dqortium.pirateUnifiedArtifactPath=/absolute/path/pirate-unified-wallet-qortal-jni-artifacts-v1.2.0.zip \
  -Dqortium.pirateUnifiedBundlePath=/absolute/path/pirate-unified-v1.2.0 \
  -Dtest=PirateUnifiedArtifactAcceptanceTests \
  test
```

Loopback native host acceptance is a second explicit gate. It loads the mapped
host library through Qortium's serialized native coordinator and uses temporary
storage to exercise deterministic seed derivation, fresh initialization,
birthday height, address/nonempty-key export, zero total and verified balances,
empty transaction listing, same-process persistent reopen, encryption-status
compatibility, two isolated wallet namespaces, and typed invocation.

The opt-in native test also starts test-only gRPC lightwalletd servers bound explicitly
to IPv4 loopback. Each exposes both service names used by this integration:
`cash.z.wallet.sdk.rpc.CompactTxStreamer` for Core's Java client and
`pirate.wallet.sdk.rpc.CompactTxStreamer` for the pinned native library. The
default fixture reports mainnet Sapling activation at height `152855`, a tip at
`152858`, and streams four ordered, hash-linked empty compact blocks. Acceptance requires
the native client to request that complete range, reach the fixture tip, stop
reporting active synchronization, and make no transaction, `GetTreeState`, or
other unexpected RPCs. The pinned client can make an optional
`GetSubtreeRoots` capability probe; each fixture records it separately and must
return `UNIMPLEMENTED`. The fake also serves only the exact pre-Ironwood
activation probe at `tip - 30`, with a pre-activation timestamp, so the fixed
historical fixture does not become calendar-dependent near the scheduled
upgrade. The initial asynchronous `sync` response is not acceptance. After
synchronizing the first endpoint, the test probes a Java-compatible endpoint
whose Pirate service deliberately reports the wrong chain and confirms that it
receives no sync traffic; the Core fake-adapter contract separately requires
this result to fail closed. It then probes a valid second endpoint whose tip is
four blocks newer,
requires an acknowledged cancellation, typed endpoint mutation, normalized
readback, and consensus-branch validation, starts a fresh sync, and proves the
first server's native RPC counters do not advance after the cutover barrier.
Core holds the Java endpoint selection stable from native preparation through
the corresponding sync or wallet operation, so an API-requested or automatic
Java failover cannot race the process-global native context onto an older endpoint.
Native endpoint preparation also keeps transient reconciliation separate from
proved endpoint rejection. A missing native response, failed cancellation or
endpoint-mutation acknowledgement, readback mismatch, wallet-context change, or
storage failure retains the current Java selection and returns to the existing
controller cadence; it does not rotate within the same preparation call. This
provides one controller-paced attempt at a time without an inner retry loop. An
explicit chain, height, TLS, or consensus mismatch is endpoint-specific evidence
and still enters the bounded pass through remaining configured servers. Unit
coverage uses two configured candidates to require that B survives one
transient cancellation failure and is persisted on the next call, while a
wrong-chain B is rejected and replaced by A.
Reconfiguring the same encrypted registry within that host process must retain
the second endpoint, wallet ID, and address. Cold Core restart is not claimed by
this host-level test.

This default mode proves real native RPC, compact-block cursor behavior, and
same-wallet A-to-B endpoint mutation/persistence against synthetic empty chains.
It does not prove canonical chain history,
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
  /absolute/path/pirate-unified-wallet-qortal-jni-artifacts-v1.2.0.zip \
  /absolute/path/pirate-unified-v1.2.0 \
  /absolute/new/path/pirate-unified-native-receipt.md \
  --native
```

The receipt runner performs real-bundle validation and writes a new Markdown
receipt plus sanitized build summary. Add `--native` only after native execution
is approved:

```sh
tools/run-pirate-unified-acceptance.sh \
  /absolute/path/pirate-unified-wallet-qortal-jni-artifacts-v1.2.0.zip \
  /absolute/path/pirate-unified-v1.2.0 \
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
the lifetime of the Core process. Neither production check probes the separate
Pirate-native gRPC service, initializes or synchronizes the Unified JNI wallet,
or proves production endpoint cutover after Java server selection changes. The
synthetic loopback native gate above covers the pinned ABI and deterministic
cutover primitives, while unit tests cover Core's fail-closed composition.

## Production native admission acceptance

The first production-native gate is separately and doubly opt-in, read-only,
wallet-free, and currently accepted only on Linux x86_64. It validates the
pinned artifact and staged bundle before loading the Rust/JNI library,
selects Direct transport for that disposable native process without opening a
wallet, while quarantining every native storage, cache, and log path below the
disposable run directory. No wallet storage may be created or written. It then
makes one bounded pass over every Pirate mainnet endpoint hardcoded in Core.
Each candidate must first pass the
existing Java TLS, chain, latest-block, and compact-history checks. The pinned
native `test_node` path must then report
`main`, TLS with direct transport, a positive latest height, and agreement with
the Java-validated height. A pass requires at least two configured endpoints in
a compatible native-height cluster; temporarily unavailable candidates remain
visible in the receipt. This endpoint count does not establish infrastructure
independence.

Run it only from a clean committed Core tree with the retained pinned artifact,
staged bundle, a new absolute receipt path, and the explicit `--native` marker:

```sh
tools/run-pirate-production-native-interoperability-acceptance.sh \
  /absolute/path/pirate-unified-wallet-qortal-jni-artifacts-v1.2.0.zip \
  /absolute/path/pirate-unified-v1.2.0 \
  /absolute/new/path/pirate-production-native-receipt.md \
  --native
```

The same run includes the deterministic endpoint-retention and bounded-fallback
regressions, but it does not turn a live server into a failure fixture or claim
that a particular production outage was observed. It never initializes or
persists a wallet, derives an address, synchronizes
history, queries a balance or transaction, mutates a wallet endpoint, signs or
broadcasts data, moves funds, publishes to QDN, deploys Core, enables Unified by
default, or changes Home. Raw native and Maven evidence is secret-scanned and
deleted before the allowlisted receipt is published atomically. Production
wallet initialization, synchronization, and cutover remain separate future
gates.

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
  /absolute/path/pirate-unified-wallet-qortal-jni-artifacts-v1.2.0.zip \
  /absolute/path/pirate-unified-v1.2.0 \
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
  /absolute/path/pirate-unified-v1.2.0 \
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
  /absolute/path/pirate-unified-v1.2.0 \
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
  /absolute/path/pirate-unified-v1.2.0 \
  /absolute/path/pirate-unified-local-qdn-fixture \
  /absolute/new/path/pirate-unified-packaged-lifecycle-receipt.md
```

After that single-endpoint lifecycle passes, the same command accepts an
explicit cold-restart endpoint gate by adding `--cutover` after the new receipt
path. The option does not change the original invocation.

For a wallet that the caller explicitly confirms has no historical receipts,
add `--known-new` instead. This mode calls the authenticated, loopback-only
`POST /crosschain/arrr/initialize` operation with
`NEW_AT_CURRENT_TIP`, requires the returned and durable birthday to equal the
fixture tip `152858`, stops and restarts only the wallet controller while Core's
admin API remains healthy, and then repeats the exact initialization request in
a second packaged Core process. The namespace, one-way identity hash,
wallet-address hash, initialization policy, and birthday must remain exact
through both recovery boundaries.

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

The `--known-new` fixture contract is deliberately different: it requires at
least one validated tip request and exactly zero compact-block ranges, unary
compact blocks, scanned blocks, subtree probes, transaction RPCs, and unexpected
RPCs. That zero-history pattern is the runtime proof that the explicit current
tip birthday avoided the conservative historical scan.

In `--cutover` mode, fixed regtest endpoints A (`127.0.0.1:9067`, tip `152858`)
and B (`127.0.0.1:9068`, tip `152862`) remain live across both Core processes.
Process one first reaches A, then Java selects B and a wallet operation
synchronously applies and persists B in the native wallet. Each endpoint must
serve native retrieval of its exact tip, either as a tip-ending range or as an
exact-tip unary block. The Java selection API shares the native-operation lease,
while native application first cancels any asynchronous A synchronization before
mutating and reading back B. A live A-native-RPC barrier is therefore established
after native B application succeeds and must remain unchanged through the
subsequent B-readiness observation, both graceful shutdowns, and process two.
The second Core process must reopen persisted B before native use, produce new
B-native evidence, and retain the exact namespace, one-way identity hash, and
wallet-address hash.

This proves fresh creation, exact-tip synchronization, persistence, and clean
restart/reopen on unfunded Linux x86_64 local fixtures. In `--known-new` mode it
also proves wallet-only disable/re-enable and current-tip initialization against
the deterministic fixture. Historical recovery is
covered by the separate fresh-install gate above, not by this transaction-free
packaged run. This packaged gate does not prove real legacy
migration, transient endpoint-failure recovery, switching during an active
sync, production
lightwalletd interoperability, funds, QDN publication, deployment, default
enablement, or Home behavior.

## Inspect-only legacy-v8 wallet acceptance

Before connecting arbitrary external wallet files to Core's verified recovery
endpoint, a narrower opt-in Linux x86_64 gate proves that the exact reviewed
legacy library can inspect the three protected, unfunded serialization-v8
fixtures without starting Core or importing a key:

```sh
tools/run-pirate-legacy-v8-inspection-acceptance.sh \
  /absolute/path/qortium.jar \
  /absolute/path/reviewed-legacy-bundle \
  /absolute/path/protected-v8-fixture-directory \
  /absolute/new/path/pirate-legacy-v8-inspection-receipt.md
```

The reviewed legacy bundle is a flat directory containing the exact pinned
Linux x86_64 JNI library, `coinparams.json`, `saplingoutput_base64`, and
`saplingspend_base64`. Their hashes are enforced before native loading. The
fixture directory remains private and contains the two encrypted and one
unencrypted fixture metadata/file pairs described by the wallet-import test
plan; none are shipped with Core.

The runner pins the exact metadata and wallet hashes for all three fixtures,
snapshots every executable input into its private work directory, and compiles
the standalone inspector and test lightwalletd against the snapshotted packaged
JAR with the newly compiled helper classes first on the classpath. It then
creates a rootless network namespace with only IPv4 loopback, copies each
source wallet into a private disposable directory, and uses one JVM/native
instance per file. For the Qortal-derived encrypted test fixtures, an
acceptance-only helper derives the known fixture password from protected
metadata and writes it to an inherited file descriptor. Passwords, entropy,
wallet bytes, keys, and addresses are never command arguments, environment
values, receipt fields, or ordinary stdout.

Each successful inspection must recover the wallet's own conservative birthday
and exactly one nonempty mainnet Sapling spending-key candidate associated with
the source-reviewed v8 default row. Serialization v8 rebuilds that row's
address from the stored full viewing key and does not persist the later
diversified-address list. The inspector requires the export to match the
wallet's shielded-address set and fails closed on duplicate or ambiguous key
groups. It emits only an address hash, pool, count,
`suggestedAddressIndex=0`, and `selectionBasis=legacy-v8-default-row`; it does
not persist or emit the spending key outside transient JVM memory. Those fields
do not prove ownership, index, or spendability—the later Core verified-import
boundary does. A separate wrong-password JVM must return the dedicated
rejection status without creating a result or changing its copied wallet.

Acceptance also requires every protected source and disposable copy to remain
byte-identical, zero transaction or unexpected fixture RPCs, zero network
egress, and a passing scan of all raw outputs for fixture entropy,
seed/key/address fields, shielded addresses, spending-key prefixes, or the
exact protected fixture secrets. Exact-secret matching uses a mode-0600 pattern
file rather than secret-bearing process arguments. Raw evidence and temporary
password files are deleted before the sanitized receipt and log are retained.

This is an inspect-only proof, not an end-user importer or a mutation-free
simulation of Core's verified import. It does not call
`POST /crosschain/arrr/recovery/import`, mutate a Unified registry, accept a
live user-selected password from a terminal, prove funded recovery, or parse a
full-node BerkeleyDB `wallet.dat`. Full-node support remains limited to a later
adapter for explicit `z_exportkey`/`z_exportwallet` text exports.

## Packaged real-v8 legacy migration acceptance

The lifecycle gate uses a newly created synthetic wallet. A stricter opt-in
Linux x86_64 gate can instead exercise the complete Core migration state
machine with one protected, unfunded, encrypted Qortal-format serialization-v8
fixture:

```sh
tools/run-pirate-unified-packaged-real-legacy-migration-acceptance.sh \
  /absolute/path/qortium.jar \
  /absolute/path/pirate-unified-v1.2.0 \
  /absolute/path/pirate-unified-local-qdn-fixture \
  /absolute/path/reviewed-legacy-bundle \
  /absolute/path/protected-v8-metadata.json \
  /absolute/new/path/pirate-unified-packaged-real-v8-migration-receipt.md
```

This runner is deliberately fixture-gated rather than suitable for arbitrary
wallet files. It requires metadata identifying an encrypted, unfunded version-8
fixture; exact reviewed hashes for the legacy Linux JNI, coin parameters, and
Sapling parameter files; the authenticated staged bundle; and the
disposable local-QDN resource. Secret material is read from a protected local
file, never accepted on the command line, and copied only into a mode-0700
temporary runtime beside the new receipt. The source wallet is hash-checked
before and after the run and is never opened in place.

Inside a rootless loopback-only network namespace, the runner starts the same
packaged Core JAR three times. The legacy phase must map the reviewed legacy JNI,
log an actual serialization-v8 decode, preserve the wallet file, reach `READY`,
and persist `LEGACY` without a Unified registry. The first Unified phase must
resolve and map the pinned bundle through the local-QDN fixture, retain the same namespace,
one-way identity hash, and wallet-address hash, synchronize to the deterministic
fixture, and persist validated `MIGRATING`. A third clean process must reopen the
same registry and promote it to `UNIFIED_READY`. All three processes must shut
down gracefully, and the loopback fixture must record compact-block and subtree
activity with zero transaction or unexpected RPCs.

Raw Core, JNI, API, fixture, and wallet-state evidence is scanned for the
fixture entropy, ephemeral API key, seed/key/address-shaped JSON, and shielded
addresses, then deleted. The retained receipt contains only hashes, one-way
continuity results, counters, and sanitized diagnostics. A pass proves this one
protected Qortal-format v8 fixture migrates through the packaged Core state
machine on Linux x86_64. It does not prove arbitrary legacy/full-node/Unified
wallet import, password handling, funded behavior, production-network history,
other platforms, QDN publication, deployment, or Home behavior.

## Acceptance matrix

Artifact presence is `STAGED`, not runtime acceptance. Loopback JNI and isolated
packaged-Core results remain separate for every target. FreeBSD is not in the
pinned five-target artifact; the legacy Linux filename mapping is not
FreeBSD acceptance.

| Target | Artifact | Loopback JNI | Packaged Core |
|---|---:|---:|---:|
| Linux x86_64 | NOT_RUN | NOT_RUN | NOT_RUN |
| Linux aarch64 | NOT_RUN | NOT_RUN | NOT_RUN |
| macOS x86_64 | NOT_RUN | NOT_RUN | NOT_RUN |
| macOS aarch64 | NOT_RUN | NOT_RUN | NOT_RUN |
| Windows x86_64 | NOT_RUN | NOT_RUN | NOT_RUN |

G2 must later cover additional legacy fixtures, A/B account switching during
sync, failover and bad-height servers, production-network recovery,
funded balance/spend behavior, and other platforms. G3 separately covers publication by an approved QDN
identity, retrieval by immutable transaction signature, byte comparison,
controlled receive/send, and ARRR HTLC/P2SH fund/redeem/refund recovery.

Receipts must include the artifact and manifest hashes, Core commit and tree
state, a normalized command with sensitive paths redacted, test counts,
host/platform results, sanitized logs, and any counterexamples. Never record entropy,
seed phrases, keys, API keys,
passphrases, or wallet debug responses.
