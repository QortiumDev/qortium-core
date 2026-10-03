# Experimental Monero wallet reads

This is the first Core integration tranche, not a released XMR feature. It is off
by default, accepts authenticated **loopback** requests only, and exposes no
sending, transaction preparation, relay, trading, or QDN bridge. Home consent,
privileged derivation, account lifecycle integration and Wallet UI are subsequent
work. Do not enable a public/shared node to receive users' wallet secrets.

The adapter holds full wallet keys even though this API only reads. JNI runs
inside Core: a native crash can terminate the JVM, as with the existing ARRR
binding. Java queues cannot isolate a fatal native fault.

## Artifact and platform boundary

Evaluated dependency: `io.github.woodser:monero-java:0.8.58`.

- JAR SHA256: `3ce3768fb108938ff7d9b8b2146abf2af937763e35f30bed708de6bf5d1cd370`.
- Linux x86_64 native SHA256: `d971fda9d8ff04cdc1d1afa2084b35991cd7bfdc78edd2ffd0c38f1f1ef11715`.
- monero-java source: `d7a7427e0965606d855c0496f8027c411a69e275`.
- monero-cpp source: `5ac0d137b6c6052eb5fdd633849180b17cf8fbe3`.
- woodser/monero source: `c76165e5407a27881eb223bb689c5b8d3b0d076d`.
- Official Monero 0.18.5.1 base: `4f92268d7c16741cfb41e5bbe2aa46cc260a9ea5`.

The native fork is 17 commits / 23 files ahead of that official base. The bounded
source review covered the delta's scan, pool, shutdown, TLS and locking changes;
it is not an audit of the entire Monero stack or a reproducible-build proof.
Maven artifact signature verification established the publisher fingerprint
`52FD7C01877CA968C97118D055A10DD48ADEE5EF`; a signature does not establish safety.
Core retains its Jackson BOM and Bouncy Castle jdk18on 1.85; the dependency's old
jdk15on provider is excluded. monero-java is MIT/Apache-2.0; upstream Monero is
BSD-3-Clause. Existing upstream license obligations still apply to distribution.

Only Linux x86_64 has executed acceptance. The native artifact requires glibc
2.30 or newer. Other architectures fail closed even though the upstream JAR
contains some other native targets. Android is not supported by this tranche.
The loader hashes its packaged resource, loads a private absolute temporary
path, and removes the temporary file. It never selects from `java.library.path`
and rejects a prior uncontrolled native load.

Native level zero still logs wallet metadata, so Linux native logging goes to
`/dev/null` with console output disabled. Only monero-java's wallet logger is
suppressed; Core's global logging configuration remains intact. API errors do
not include parser/native diagnostics. Byte buffers are cleared where owned,
but Jackson/JNI create immutable Strings and the native wrapper retains the
password until close. This is **not complete heap erasure**; heap/core dumps
and process inspection remain custody-sensitive.

## Derivation v1

Home, not Core or the QDN app, will obtain the unlocked account seed. The exact
coin seed convention is:

1. Account version 1 uses the **whole** account/master seed, with no nonce
   derivation. Version 2+ uses the existing Home derivation:
   `M = nonce_u32_big_endian || masterSeed || nonce_u32_big_endian`,
   `accountSeed = SHA512(SHA512(M) || M)[0:32]`.
2. `R = reverse(accountSeed)`.
3. `coinSeed = SHA512(R || SHA256(R || UTF8("XMR")))[0:32]`.
4. Core interprets `coinSeed` little-endian and reduces it modulo
   `2^252 + 27742317777372353535851937790883648493` for the spend scalar.
5. The view scalar is the reduction of `Keccak256(spendScalar)` (not SHA3-256).

QortDEX already mixed uppercase `XMR`; its later scalar reduction used an
incorrect subgroup-order constant. This v1 derivation corrects that constant,
so old QortDEX addresses differ. No legacy restoration mode is implemented.
QortDEX's version-1 seed truncation also differs from Home's whole-seed behavior.
Existing BTC/ARRR derivations are not changed.

`src/test/resources/monero/derivation-v1.json` contains only public synthetic
seeds (`00..1f` or `00..3f`). Fixtures cover v1 seed lengths and v2 nonce 0, 1 and
4294967295. Independent Python/libsodium generation, Node hashing, Java reduction
and native address creation must continue to agree before Home exposes addresses.

## Operator configuration

The local experimental settings are `moneroWalletEnabled` (default false) and
`moneroDaemonUri` (no default server). The daemon must use HTTPS, except numeric
loopback HTTP is permitted. Credentials, query strings, fragments and custom
paths are refused. Native daemon trust is explicitly false, including loopback.
This is wallet2 scanning against a Monero daemon; it does not use ARRR's
lightwalletd protocol and may download substantially more scan data.

Wallets are stored beneath `walletsPath/xmr-mainnet-v1/<derived-wallet-id>/`.
Directories are private (0700), wallet files are native-password-encrypted,
and the storage password and directory identity use distinct SHA256 domains
bound to the canonical spend scalar, network and derivation version. Clients
cannot supply filesystem paths. Native-returned spend/view keys must match on
create/reopen. The identity marker pins the original restore height. Missing,
partial, mismatched or symlinked storage is refused, never silently recreated.

Restore height is explicit: use zero when the first receipt height is unknown.
A nonzero height can miss earlier funds; clients must explain this and never
silently substitute today's tip. New wallets first validate the requested
height against the daemon tip in memory before committing storage. The daemon
can lie about its tip; this validation is an input bound, not independent chain
consensus verification. Changing an existing restore height needs a separately
reviewed recovery/rescan flow. A crash between marker and native file creation
leaves a refused partial directory for operator recovery.

## Local API protocol v1

All calls require `X-API-KEY` and a real loopback peer, including capabilities.
Responses have `Cache-Control: no-store`.

| Method and path under `/crosschain/xmr` | Contract |
| --- | --- |
| `GET /capabilities` | Versions, decimals=12, configured enablement, supported platform, mainnet, local custody only, send=false, historyLimit=100. Does not load native code. |
| `GET /session` | Privileged coordination: current sessionId, walletId, state and safe errorCode only. Home can recover the expected revision after its own restart. |
| `POST /activate` | JSON: lowercase hex 32-byte `coinSeed`, `derivationVersion:1`, integer `restoreHeight`, and current `expectedSession` (null initially). Returns a new session immediately while the worker opens/scans. Never send Home's master seed. |
| `GET /wallet` | Requires `X-XMR-SESSION`. Returns state and an owner-scoped snapshot containing receive address, scan/target heights, synced flag, balance/unlocked atomic strings and at most 100 newest history entries. |
| `POST /deactivate` | Requires `X-XMR-SESSION`; revokes access immediately, then closes on the worker. |

The activation reader rejects duplicate/unknown fields, trailing values, invalid
UTF-8, type coercion and bodies over 2048 bytes. A stale session gets HTTP 409
`XMR_SESSION_CHANGED`, never a previous owner's balance or history. Most
operational failures use HTTP 503 and a stable `code`. An uncertain opening/closing failure
or an overdue native operation requires restarting this experimental runtime.
A rejected new-wallet tip check closes its in-memory handle and publishes
ACTIVATION_REJECTED with a safe error code through `/session`; an explicit
corrected activation may then retry using that current session. Existing wallets
do not require a live tip query to reopen.
No input is automatically retried across an uncertain native lifecycle boundary.

Repeated activation of the same identity and restore height returns the current
session without reopening or rescanning. Only one transition can be queued.
Reads consume cached snapshots and do not enqueue native operations, so tab
returns cannot trigger competing balance/history loads. State is one of IDLE,
OPENING, CLOSING, ACTIVATION_REJECTED, SCANNING, READY, UNAVAILABLE, STALE,
RESTART_REQUIRED or STOPPED.
READY requires both daemon and wallet caught up. Snapshot values older than 30
seconds are withheld; timeouts poison the native lane and late results cannot
revive it. No response advertises send support.

The service has one native worker. Sync runs synchronously, cooperatively asking
for a yield after 2048 blocks or two seconds of progress, then reads and saves
with no background native scanner. These are **soft chunk/progress bounds**,
not guaranteed wall-clock interruption: network/native stalls may exceed them.
An observed operation exceeding 90 seconds poisons the lane. Once it returns,
its handle is closed and discarded; a permanently stuck JNI call requires a
Core restart. Shutdown waits only three seconds for the worker and never races
native close against a read. Multi-wallet checkpoints remain separate, but only
the active wallet scans in this tranche.

Pool entries are provisional. The reviewed fork can skip a malformed new pool
body while advancing its cursor; an untrusted daemon can also suppress entries.
Do not infer send finality from pool absence. History timestamps are seconds,
atomic values are decimal strings, and unknown amounts/timestamps stay null.
The current history response is capped; pagination remains future work.

## Verification and remaining gates

Run focused tests normally (native acceptance skips unless explicitly selected):

```
mvn -Dmaven.gitcommitid.nativegit=true -DskipTests=false \
  -Dtest=MoneroKeysTests,MoneroWalletServiceTests,MoneroActivationReaderTests,CrossChainMoneroResourceTests,MoneroApiJerseyTests test
node tools/xmr/verify-derivation.mjs
```

After packaging, exercise the shaded dependency/native combination without a
daemon or wallet files:

```
java --class-path target/qortium-1.8.1.jar tools/xmr/PackagedSmoke.java
```

The isolated acceptance driver validates the official daemon's SHA256, launches
it with `--regtest --offline` on random loopback ports, supplies only the public
fixtures and terminates its child afterward. It also executes the focused suite.
It never starts the Core node or uses configured real wallet data:

```
python3 tools/xmr/run-offline-acceptance.py --monerod /path/to/verified/monerod
```

Before wider enablement: assess dependencies/advisories and release packaging;
execute other supported native targets; test malformed/slow daemons and long
mainnet scans; add Home consent/derivation and lock/account-switch lifecycle;
add Wallet receive/history UI; independently review sends and durable wallet-
scoped recovery. There is no funded/mainnet acceptance or production rollout
claim for this read-only prototype.
