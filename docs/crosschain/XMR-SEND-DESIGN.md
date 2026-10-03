# XMR send and recovery design

Status: reviewed implementation target with an internal journal/state-machine
foundation and internal native integration; **not an implemented send API**.
Baseline: receive/read protocol v1, Core `fa8817f71`, Home `f75c14c3`,
Wallet `bd0c8d5b`. Date: 2026-10-03. Sending remains unavailable.

## User-visible flow

The initial flow sends one exact amount to one mainnet standard address or
subaddress from account index zero. No integrated addresses, payment IDs,
OpenAlias/URI resolution, sweep/max, split transactions, custom unlock times,
caller-chosen inputs/rings/fees, multiple destinations or trade funding.
Amounts are canonical positive decimal atomic strings, at most uint64; Home
converts an ordinary decimal XMR input with at most 12 fractional digits using
integer arithmetic. Exponents, floating-point coercion and silent rounding are
refused. Validate amount + fee for overflow and affordability independently.

1. The user enters an address and amount and chooses Review send. Home requires
   the selected account unlocked, current local custody and explicit permission
   to prepare this request. Passive reads never prepare, sign or open prompts.
2. Core constructs a signed transaction with relay explicitly false. The
   native wallet determines its fee with NORMAL priority. Core returns only a
   validated quote, not transaction bytes or native metadata.
3. Home shows the destination, amount, actual fee, total debit, source account
   and local node in a one-request approval. A high fee remains visible even
   when larger than the amount. This is not ARRR's fixed fee. No remembered or
   session-wide send approval and no automatic fee increases.
4. Approving authorizes that exact stored transaction. Declining, expiry,
   locking, changing account/node or closing the originating context invalidates
   an undispatched quote. Rebuilding requires a new quote and approval.
5. Wallet follows status automatically. The conservative initial version keeps
   this wallet's next send disabled until the exact transaction has at least ten
   confirmations and the native wallet reports it unlocked. Other wallets can
   spend when the native worker is idle. A transport timeout after commit means
   outcome unknown, not failed. It offers status/recovery rather than another
   Send action. Confirmed messages can be dismissed without deleting recovery
   evidence; a page reload cannot repeat preparation or relay.

A proposed quote lasts 120 seconds; this is a product bound, not a native or
consensus guarantee. Core enforces wall-clock expiry plus a monotonic in-process
lease. Restart expires all uncommitted quotes regardless of clock movement.

## What the pinned native stack actually provides

Source pins remain monero-java 0.8.58 (`d7a7427e`) and monero-cpp (`5ac0d137`).
The bounded probe uses the pinned packaged Core JAR/native library and Monero
0.18.5.1 offline regtest with public synthetic keys; no real XMR is involved.

- `MoneroWalletFull.createTxs(config)` passes the configuration to JNI.
  `monero_wallet_full::create_txs` uses wallet2 `create_transactions_2`, supports
  `can_split=false`, and sets relay only when explicitly requested.
- `fill_response` produces the hash, fee, full transaction and pending-transaction
  metadata before relay. `commit_tx` is conditional on relay for a normal full
  wallet. The metadata includes transaction secret material; never expose or
  log it, nor the full native response/configuration.
- `relay_txs` deserializes pending-transaction metadata and calls `commit_tx`.
  It is not a harmless lookup. Its generic error does not identify whether the
  daemon accepted the transaction before the failure. Do not classify its
  exceptions as definitely-not-sent.
- Native preparation leaves unlocked balance unchanged and does not insert the
  candidate in wallet history. It is **not an input reservation**.
- The probe prepared one transaction, observed no pool entry, reopened the
  encrypted sender wallet, relayed the retained metadata with the same hash,
  mined confirmation, and verified the recipient's exact amount.
- Input key images are available and native freeze survives reopen. This is
  feasibility evidence only: initial reservation uses Core's durable wallet-wide
  spending guard, avoiding partial-freeze/thaw recovery as another authority.
- A deliberate identical-metadata replay was accepted with the same hash and
  left one pool transaction; this is a bounded regtest observation, not an
  automatic retry policy.
- The probe did not restart the JVM, inject power loss, test unknown relay
  outcomes, or prove mainnet fee/decoy behavior. Those are implementation gates.

Source references:
- [Pinned Java adapter](https://github.com/woodser/monero-java/blob/d7a7427e0965606d855c0496f8027c411a69e275/src/main/java/monero/wallet/MoneroWalletFull.java), createTxs / relayTxs.
- [Pinned C++ wallet](https://github.com/woodser/monero-cpp/blob/5ac0d137b6c6052eb5fdd633849180b17cf8fbe3/src/wallet/monero_wallet_full.cpp), fill_response / create_txs / relay_txs.
- [Monero wallet API reference](https://docs.getmonero.org/rpc-library/wallet-rpc/#transfer) documents the analogous do-not-relay and transaction metadata fields; JNI source and native tests govern this integration, not an assumed RPC equivalence.

## Authority and storage

Core remains the sole native-wallet owner. All native sync, preparation, relay,
exact-hash reconciliation, save and close calls share its existing single worker.
There is no second signing thread or background native sync. The REST response
accepts an operation and returns promptly; waiting clients do not occupy the
native lane or hold HTTP calls through a long scan/sign/relay.

One durable active operation per derived wallet identity is the reservation.
Every future spending path, including trading, must acquire this same guard.
An unresolved operation blocks that wallet's spends, not other wallets once the
native worker is idle. A hung JNI operation still blocks the entire native lane
and may require restarting Core; never start another native call concurrently.
Reads may expose fresh cached snapshots with the spending hold explicitly shown.

Use a process-lifetime exclusive lock on the managed wallet storage root before
any wallet activation/open, scan or journal access, including read-only API
activation, once send-capable code is present. Cooperating Core processes must not race the same cache/journal. The lock does
not retrofit enforcement into older binaries or third-party wallet tools. Copies of the same keys outside that root are outside this
coordination guarantee; observing another spend must fail/reconcile, not silently
change the prepared transaction's inputs.

Journal records are wallet/network/derivation-bound, versioned, authenticated and
encrypted with a new domain-separated key derived from the canonical spend
scalar. Never reuse the native wallet password directly as the encryption key.
Use a reviewed AEAD format with random nonce and authenticated schema version,
wallet identity, network, operation ID and sequence. Owned byte buffers are wiped;
immutable Java/native copies still prevent a complete heap-erasure guarantee.
Native metadata is a relay-capable secret; Home/QDN never receive it, tx private
keys, input key images, wallet identifiers or Core session authority.

Use private nonsymlink regular files, same-directory temporary writes, file
fsync, atomic replacement and parent-directory fsync. Treat write, fsync, rename,
authentication, schema or size failures as a spending hold. Retain the last
recoverable record and never silently recreate a damaged journal. Do not derive
paths from request strings. A wallet whose journal cannot be authenticated stays
blocked; unrelated wallets can proceed only if the root's integrity is known.
Do not promise rollback resistance against an administrator restoring old files.

The persisted request binds operation ID, canonical request digest, recipient,
amount, priority, network and wallet. The prepared record additionally binds
exact fee/total, txid, artifact digest and encrypted metadata, plus input key images for exact conflict/recovery checks. Persist a claim
**before** native preparation; persist the complete validated candidate before
returning a quote. No plaintext signing artifact is written to a log or journal.
Initial design needs no persistent native freeze: the journal is the only spend
admission path. Adding alternate spend APIs without this guard is forbidden.

## State and crash semantics

| State | Durable meaning | Recovery or allowed next action |
| --- | --- | --- |
| PREPARING | Wallet guard claimed; no relay is possible on this code path | Await native result. After process restart, expire safely; never auto-recreate a transaction. |
| PREPARED | Validated artifact durably stored; not yet authorized for relay | Commit exact quote or cancel. Restart/session loss expires it before any new spend. |
| CANCELLED / EXPIRED / PREPARE_FAILED | Native preparation cannot still publish a candidate; relay never admitted | Release wallet guard; retain operation tombstone. |
| RELAYING | Commit accepted and fsynced before entering relay JNI | On any missing/ambiguous result or restart become UNKNOWN. Cancellation cannot promise to stop this state. |
| BROADCAST | Native relay returned the exact stored hash | Save wallet and reconcile exact hash; absence alone does not clear the guard. |
| UNKNOWN | Relay may have happened, including a crash before the actual call | Read-only reconciliation; no automatic retransmission or new preparation. |
| CONFIRMED_WAIT | Exact stored hash observed confirmed but below the release policy | Keep the wallet guard; continue scanning. |
| CONFIRMED | At least ten confirmations and native unlocked state observed for the exact hash | Release spend guard; retain record and confirmation evidence for reorg checks. |

An interrupted PREPARING task may still return while Core is alive. Cancellation
marks its lease invalid, but the wallet/native slot is released only after that
task has exited and its artifact has been discarded. Late results cannot revive
cancelled/expired operations. The same restriction applies to service timeouts.

Home must validate the app/tab/account/permission origin lease. Core must
validate its session, operation state, quote digest and expiry, then atomically
claim relay against cancellation/activation. Core does not infer an app origin
from a caller-provided string. The durable
RELAYING claim is the commit linearization point. Lock/switch can immediately
revoke client access but cannot undo an already accepted relay. Closing a native
handle waits for its worker; lifecycle transitions must not close it in flight.
Refactor the current transition model explicitly rather than allowing send work
to bypass it. A failure to durably write RELAYING means zero relay calls.

Once RELAYING is durable, exactly one native invocation is admitted in the live
process. Duplicate commits return the same operation status. Restart never
replays this invocation. If persistence fails after native success, the earlier
RELAYING record remains UNKNOWN. A different returned hash is also UNKNOWN and
holds spending; never accept or display it as the approved transaction.

Reconciliation runs normal wallet sync with daemon trust still false, then
queries the exact stored txid from local wallet state on the native worker, not
the 100-entry UI history. `scanTxs()` is not part of this baseline: targeted scans
can reveal a txid, detach/reprocess history, or be refused for an untrusted daemon.
Any later use requires separate privacy/state acceptance. Require a current scan; pool absence, a timeout, a native
failed flag or an untrusted daemon's missing response is not proof of non-send.
An initial UNKNOWN has no "clear and retry" action. A future explicit identical-
artifact retransmission/recovery tool needs separate review and approval; it
must never construct a replacement payment implicitly. Ten confirmations is a conservative product policy, not irreversible finality.
Reconcile all retained confirmed records in the worker before admitting another
prepare or commit. If rollback is observed, mark the affected records uncertain
and hold the wallet. Cancel/discard any newer pre-relay candidate only after its
worker exits; if a newer operation already reached RELAYING, preserve both
records without retransmission and report wallet recovery required. A rollback
hold can therefore contain multiple historical operations even though only one
new operation may be admitted at a time. `/send/active` reports this aggregate
hold and the current operation; exact operation lookups retain earlier evidence.
Deep reorgs after release or delayed/dishonest daemon observations remain residual
risk: this policy cannot retroactively undo an accepted relay or prove network
finality. Test both rollback before release and after a newer relay is admitted.

For CANCELLED, EXPIRED and PREPARE_FAILED, atomically replace the record with a
minimal tombstone before releasing the guard: keep operation/request/artifact
digests, state and audit times, but omit metadata, full hex, transaction private
keys and input details. If replacement/fsync fails, retain the hold. There is no
claim that filesystem deletion securely erases previous physical bytes.

Tombstones survive UI dismissal and restart. Reusing an operation ID with an
identical payload returns its recorded status; using it with a different payload
fails. Never delete an old ID in a way that lets a delayed request create a new
payment. Bound retention with a documented admission refusal or archived
idempotency index, not silent eviction. New Home profiles must rediscover Core's
active wallet operation before admitting a send.

## Proposed interfaces (not yet available)

All Core endpoints remain authenticated real-loopback-only, `no-store`, and
owner-session scoped. Use the strict body reader: duplicate/unknown fields,
trailing JSON, invalid UTF-8, oversized bodies, numeric coercion and arbitrary
native options are refused. Preparation uses the pinned address decoder for checksum/network/type checks
and native construction inside the worker, then checks exact destination/amount
readback. Java-only decoding does not replace native transfer validation. All readiness checks are repeated in-lane.
The candidate must be exactly one transaction, have a valid hash and bounded
artifacts (initial caps: 2 MiB metadata, 512 KiB full transaction hex; reject
rather than truncate), positive fee, exact amount/destination, unlocked input coverage and
no unsupported payment ID/unlock policy. Failure discards it without relay.

| Method under `/crosschain/xmr` | Proposed request and result |
| --- | --- |
| POST `/send/prepare` | `{operationId,address,amountAtomic}` plus session header; 202 with operation status after durable claim. Operation ID is a Home-generated random UUID persisted before dispatch. |
| GET `/send/active` | Current wallet's active/uncertain operation or null; bounded, never another wallet's details. |
| GET `/send/{operationId}` | Owner-scoped status and safe validated quote when ready. Reads do not relay. |
| POST `/send/{operationId}/commit` | `{quoteDigest}` plus the active session; commits only the exact prepared artifact and original live lease. |
| POST `/send/{operationId}/cancel` | Cancels only a provably pre-relay operation; same-session CAS rules. |

A restarted Home may read status using newly established same-wallet custody,
but cannot commit an old quote. Core session loss expires pre-relay operations;
post-relay records remain recoverable. A different wallet cannot inspect them.
Only the private coordination API exposes operation IDs; QDN gets a Home-scoped
handle bound to its app, tab, account, permission epoch and node binding.

Core capability negotiation will use read protocol v2 with
`sendProtocolVersion:1` only once the complete send implementation is qualified.
Current Home deliberately requires read protocol 1 and `send:false`; update that
compatibility predicate with explicit v1-read/v2-send cases and downgrade tests.
No intermediate commit advertises send or changes the existing protocol.

Proposed Home actions: PREPARE_XMR_SEND, COMMIT_XMR_SEND, CANCEL_XMR_SEND,
GET_XMR_SEND_STATUS. Generic SEND_COIN remains excluded for XMR. All are desktop,
trusted-local and account-scoped; all mutations require live context checks.
Preparing uses a foreground single-request permission; committing always has
its own exact-quote approval. Polls reuse only the relevant read permission.
Home persists its request handle before first dispatch, reconciles lost prepare
responses by ID, and never creates a new ID in a retry handler. Read failure
cannot be converted to "no active operation". Node changes cannot erase a hold
on the previous node; restoration/recovery uses that exact trusted route.

Wallet keeps exact atomic strings internally, serializes status reads and scopes
components by account/context revision. It shows preparation, approval, relay,
unknown and confirmed states distinctly, and checks status automatically when
opened and before a send. Dismissal hides only terminal presentation. Tab return,
unlock, reload and React remount cannot create, commit or clear an operation.

## Implementation sequence and gates

1. **Core journal and state-machine tranche:** strict immutable request/quote
   contracts, encrypted durable per-wallet operation store, idempotency and
   crash-state tests with a fake backend. No native relay API and send=false.
2. **Core native tranche:** add non-relay prepare, validated exact-artifact relay,
   targeted tx lookup and guarded lifecycle integration. Offline synthetic
   acceptance must inject lost responses, daemon loss, pre/post-fsync crashes,
   late results, same-ID retries and wallet switching. Send stays unadvertised
   until reviewed end to end.
3. **Home/Wallet tranche:** capability negotiation, custody/quote approvals,
   durable operation handles, automatic status, exact decimal UI and account/
   tab race tests. Packaged tests must use the complete Wallet app as well as
   real Home/Core boundaries. No app-accessible transaction material.
4. **Qualification/release:** full Controller lifecycle, slow/malformed daemon,
   mainnet scan and platform matrix; separately authorized owner-operated funded
   acceptance, publication and installed-runtime updates.

Required adversarial tests include two concurrent prepares; duplicate commit;
request ID collision with changed payload; expired quote; account/node/permission
change around every await; cancel while queued/preparing/relay-claimed; uint64
and 12-decimal bounds; wrong-network/integrated addresses; split/native mismatch;
journal corruption/symlink/ENOSPC/fsync failure; native hang/crash; JVM restart
before and after every journal/native boundary; transaction outside UI history;
reorg after confirmation; another wallet spending after the worker returns;
and every unsupported capability/version failing closed. No real-wallet test
or mainnet broadcast is part of these automated gates.

## Journal/state-machine implementation checkpoint (2026-10-03)

Package-private `MoneroSendContracts`, `MoneroSendJournal` and
`MoneroSendMachine` implemented tranche 1. The native checkpoint below supersedes
its original unwired state; there is still no app/API send caller.
The key helper derives an independent domain-separated journal key from the
existing XMR spend material; it does not change address derivation.

The Linux-only storage foundation uses a private root, a retained process lock,
per-wallet AES-GCM snapshots with wallet/network/schema/sequence binding,
strict bounded JSON, new nonces, same-directory temporary files, file fsync,
atomic replacement and directory fsync. A failed mutation poisons that journal
instance until reopen. An existing wallet directory without a valid ledger is
never treated as a new empty wallet. Valid pre-rename leftovers are discarded
only after authenticating the durable main ledger. No rollback protection is
claimed against an administrator restoring an older valid encrypted snapshot,
nor against a malicious process with the same user's filesystem authority.

A durable PREPARING or RELAYING transition precedes the corresponding opaque
worker receipt. Relay extraction is one-use. Cancellation/expiry preserves its
first reason and the hold until preparation completes. Startup expires
pre-relay work, marks interrupted relays uncertain, and requires reconciliation
of retained confirmations before another admission. Pre-relay terminal records
omit request and signed artifact material. All IDs/tombstones are retained;
4096 records and a 16 MiB snapshot bound fail closed instead of evicting history.
This is a bounded experimental store, not an indefinite production archive.

Reconciliation has a separate opaque one-use receipt, minted immediately before
ordinary sync. Preparation/relay and sync cannot be admitted concurrently by
one machine. Session changes invalidate sync receipts. A completed reconciliation
permits one admission within two monotonic seconds; prepare and commit require
separate fresh checks when confirmed history exists. The trusted adapter
must build the observation map from that receipt's actual sync and exact local
wallet lookup. Receipt identity fences stale callbacks; this pure protocol
cannot prove that a caller supplied truthful chain observations.

At the journal-only checkpoint, tranche 2 still required root lock acquisition before *all* native wallet
access including reads; a global worker/lifecycle gate held even after a timeout;
actual native validation of address checksum/network, balance and selected
inputs; non-relay preparation and exact stored-artifact relay; current local
observation production; and safe shutdown/reopen. Request address validation in
this foundation is syntax only and is not a send-acceptance gate. The caller
must stop/join native work before closing a journal/root. Same-JVM per-wallet
reopen must likewise never race a native worker.

Tests use synthetic contracts and fake completion events, injected storage
exceptions, process-lock contention and actual JVM termination at each commit
write barrier. They demonstrate software ordering and process recovery on the
local filesystem, not hardware power-loss durability, a real full disk, JNI hang
containment, chain acceptance, other platforms or mainnet sends. Java-owned key
and serialization byte buffers are wiped and closed ledgers are dereferenced;
immutable Java/Jackson strings cannot be reliably erased from memory.

## Internal native integration checkpoint (2026-10-03)

The service now uses a package-private send backend/coordinator on its existing
single worker. There are no new REST routes, bridge actions or advertised send
capabilities. Default-off configuration remains unchanged. Package-private
prepare/commit/reconcile futures are bounded to one queued send operation;
cancelling a future does not cancel native work or undo relay admission.

The factory acquires and retains `<network namespace>/.coordination/.send.lock`
before journal or native wallet access. Per-wallet journals live below that
coordination directory, separate from the existing native cache layout. Journal
authentication and startup normalization precede native open. Temporary derived
journal keys are wiped. Wallet switches durably revoke pre-relay work before
publishing the new session, then serialize native completion, close and open.
A queued final shutdown closes the factory/root only after native completion;
the caller's three-second shutdown wait is not proof of worker termination.
Uncertain native close retains the root until process exit.

Each native cache has a `send-journal-v1` marker paired with an authenticated
`nativePaired` journal bit. After adoption, either missing side fails closed.
An unpaired empty journal can adopt a pristine/legacy read-only cache: force
native cache files and directory, atomically persist and fsync the pair bit,
then create/fsync the native marker and directory before exposing the backend.
A crash after the pair bit but before the marker requires operator recovery.
No send admission is possible before adoption completes. This detects partial
loss, not restoration/deletion of both storage trees or older binaries that do
not honor the new process lock. Journals bind the actual mainnet/regtest
namespace in AEAD associated data; the test-only regtest flag remains inaccessible
through settings and APIs.

Preparation performs ordinary untrusted sync, validates the mainnet checksum
and address form, requires readiness/account-zero unlocked balance, then calls
native createTxs with NORMAL priority, canSplit=false and relay=false. It checks
the single returned destination, amount, fee, unlock time, relay/confirmation
flags, hash, metadata, full bytes and input key images. Native outputs must show
every selected input available and unlocked. Commit repeats readiness/input/
balance checks after fresh reconciliation, then durably claims relay under the
service's current-session monitor. Exactly the stored metadata reaches relay.

A missing/mismatched relay result or post-relay save failure is UNKNOWN. No
rebuild, retry or alternate-daemon relay is attempted. Deadline failure records
uncertainty before allowing a late result to complete; the native lane remains
occupied until the call returns. A returned error can permit another wallet
after cleanup, while the affected wallet retains its journal hold. Local exact
hash lookups after ordinary sync distinguish pool presence, confirmation depth
and unlocked status; they do not depend on the capped UI history.

Acceptance uses public fixtures and freshly mined offline regtest rewards through
the actual Core service/JNI path. It checks prepared quotes, an intentionally
lost relay response, no duplicate relay, pool/confirmation recovery and exact
recipient balance. Child JVMs halt immediately before and after native relay,
then new services reopen UNKNOWN without granting retry. Separate tests cover
journal loss, missing native files, four adoption write barriers, cross-network
journal transplant, account switches, cancellation, native timeouts and lock
retention after shutdown. These are process/fault-injection tests, not hardware
power-loss qualification, mainnet funded acceptance or a full Controller test.

Next: expose strictly authenticated owner-scoped Core request/response contracts
and lifecycle/status routes, then Home exact-quote approval and durable handles,
Wallet UI and packaged end-to-end acceptance. Keep send=false until the complete
route passes security review and offline acceptance. Full Controller/mainnet
scan, additional platforms, release and owner-operated funded acceptance remain
separate gates.


## Core API checkpoint (2026-10-03)

The local operator API now wraps the existing journal/native worker. It does
not change protocol-v1 discovery or `send=false`; Home/Wallet support and
reviewed activation of advertised send capability are a later tranche.
All routes require the API key, numeric loopback transport, enabled XMR
runtime, and the active `X-XMR-SESSION` token. An operation belongs to the
wallet, not the current UI session; reopening the same wallet can read its
retained operation, while another wallet cannot. Home must keep its own
app/account/tab-bound operation handles and must not expose Core identifiers
or authority to arbitrary apps.

| Method and suffix under `/crosschain/xmr` | Strict request |
| --- | --- |
| POST `/send/prepare` | `operationId`, `address`, `amountAtomic` |
| POST `/send/commit` | `operationId`, `quoteDigest` |
| POST `/send/cancel` | `operationId` |
| POST `/send/reconcile` | `operationId` |
| GET `/send/status/{operationId}` | No body |

Every request field is a string. IDs are canonical UUIDs; digests lowercase
64-character hex; amounts canonical positive uint64 atomic strings (12 XMR
decimals); address syntax is 95-character Base58, then native checksum/network
validation before preparation. Unknown/duplicate fields, null/coercion, trailing
JSON, malformed UTF-8 and bodies above 2048 bytes are rejected after auth and
ownership checks. Only preparation accepts recipient/amount; commit binds the
stored exact quote. Native NORMAL priority determines the fee, not ARRR's fee.

A 200 response contains only operationId, state, quoteDigest, address,
amountAtomic, feeAtomic, txid, walletHeld, expiresAt (Unix milliseconds),
confirmations and unlocked. Signed bytes, native metadata, key images, private
keys and journal internals are never response fields. Quotes expire under both
wall and monotonic clocks; state, rather than expiry alone, determines validity.
`walletHeld` aggregates retained unresolved operations including reorg holds.

The request waits at most two seconds for worker completion; it never cancels
native work on timeout or client disconnect. After timeout, 202
`XMR_SEND_PENDING` with `durable:true` means the operation already exists in the
journal. A prepare still queued or reconciling before journal admission instead
returns 503 `XMR_SEND_ADMISSION_PENDING` with `durable:false`. Both include the
operationId and `statusRequired:true`: keep that ID, query status and do not
resubmit automatically. Before admission, status/cancel can return 409
`XMR_SEND_NOT_READY`; no durable cancellation or acceptance is promised, and a
process loss can leave no record. A client can revoke the custody session to
prevent still-queued preparation from being admitted. Preparing never relays.

409 errors are redacted owner/admission errors, not retry authorization. 503
`XMR_RESTART_REQUIRED` retains the existing fail-closed lifecycle semantics.
Unexpected worker failure yields `XMR_SEND_STATUS_REQUIRED`, never native text.
UNKNOWN remains a durable wallet hold: no automatic rebuild/rebroadcast/new ID.
GET status reads the journal only; POST reconcile performs ordinary serialized
sync and exact local-hash observation. Neither route grants relay authority.
