# Generic custody wallet API v1

`/crosschain/wallets/{coin}` is a versioned transport facade for the registered
ARRR and XMR custody adapters. Coin identifiers are exact uppercase values.
Existing coin resource classes retain their parsing, ownership, runtime,
activation, scan and send admission logic. Existing URLs remain compatible.
Bitcoiny already uses shared registered-coin routes under `/crosschain/{blockchain}`;
its Home-local signing and public read model remain separate adapters.

Every generic request requires `X-API-KEY` and a loopback source. These checks
run before body consumption or backend selection. Unknown coins/operations
return 404. No key-export endpoints are registered.

`GET /crosschain/wallets/{coin}/protocol` returns JSON with:

- `contract: "qortium-core-wallet-api-v1"`, `coin`, `sessionHeader: "X-WALLET-SESSION"`;
- `reads` and `writes`: the registered operation names/methods.

These lists describe the transport, not wallet readiness or spending permission.
Coin capability/session/readiness replies remain authoritative. Clients discover
transport before dispatch and must never replay a failed send through another URL.

| Adapter | GET operations | POST operations |
| --- | --- | --- |
| ARRR | session, send-capabilities, send/status/{id} | session, activate, start, stop, status, address, balance, transactions, send, send/readiness, send/lookup/{id} |
| XMR | capabilities, session, status, send/status/{id} | activate, stop, send/prepare, send/commit, send/cancel, send/reconcile |

XMR uses the shared session header for native owner fencing. Its JSON bodies and
response records are unchanged. ARRR status/address/balance/transactions/readiness
and lookup retain their text entropy body; session/activation/send use JSON.
ARRR balance retains `?verified=true|false` (omitted means total). Start/Stop have
no body. ARRR Stop remains explicitly node-scoped; XMR Stop remains owner-scoped.
The generic route does not normalize those different control semantics.

ARRR send uses the existing strict bounded money-body reader. Session JSON rejects
duplicate, unknown, coerced or trailing values without echoing body contents.
Other text bodies are bounded and decoded as strict UTF-8. Existing native errors
remain unchanged. Native responses keep their media types: addresses and exact
atomic balance strings are `text/plain`, structured replies are JSON, and wallet
replies are `Cache-Control: no-store`. Never parse an atomic balance as a float.

## Scan-start policy (version 1)

ARRR session discovery and XMR capabilities advertise `scanStartProtocolVersion: 1`
and `scanModes: ["RESUME", "RESTORE_FROM_HEIGHT", "NEW_AT_CURRENT_TIP"]`.
`RESUME` keeps an existing checkpoint. First-use resume keeps the historical
conservative defaults (ARRR configured birthday; XMR block 0).

XMR activation accepts `scanMode` and an integer `restoreHeight` only for
`RESTORE_FROM_HEIGHT` (0–500,000,000). Legacy activation bodies with an explicit
height remain supported. ARRR `/initialize` (also shared wallet `initialize`)
accepts `initializationMode`, integer `restoreHeight` for historical restore
(1–500,000,000), entropy and the observed `expectedRevision`. ARRR resume uses
ordinary session activation. Both initialization routes strictly reject unknown,
duplicate, coerced and trailing JSON fields before native work.

Current-tip initialization is an assertion that this address has never received
funds. Core records the validated selected height before native creation and
reuses it after a lost reply; retrying never advances the birthday. XMR uses the
last existing block index (`get_info.height - 1`). Existing caches/identities are
never reset or promoted to a different birthday, and missing or inconsistent
paired native/send state remains a recovery error. Legacy XMR checkpoints resume
at their original height and must agree with the native persisted restore height.

Status exposes the saved public `restoreHeight` and `initializationMode`.
XMR `preparation` callbacks below the birthday are display-only chain-history
preparation: they do not advance ordinary scan progress, extend deadlines or make
spending ready. No restore request clears a send journal or unresolved operation.

## Cached read diagnostics

Adapters can add optional, coin-neutral `read` display metadata to status replies.
XMR currently returns `{state, phase, retryAt}` through both its original route
and the generic wallet status route. Older replies can omit it.

- `IDLE`: no current read; phase and retryAt are null.
- `IN_FLIGHT`: the serialized read is running; retryAt is null.
- `OVERDUE`: that same read is still running after its inactivity deadline;
  no concurrent retry starts and retryAt is null.
- `RETRY_SCHEDULED`: a failed read has returned and retryAt gives its wall-clock
  retry time in milliseconds; phase is null.

Working phases are fixed labels: CHECK, DAEMON, SYNC, HISTORY, SAVE and BALANCE.
SYNC includes native downloading, chain preparation and transaction processing;
it does not identify which sub-operation is slow. These are cached diagnostics,
never native getters, error text, session authority or spending readiness.
An old financial snapshot does not establish the current read phase.

An owner-scoped XMR Stop immediately revokes the wallet session and queues close
behind the current read. If Stop waits behind a scan, its close deadline starts
when the serialized close begins. A genuinely stuck read can remain CLOSING;
clients must describe the wait honestly. Actual close, account-switch and send
deadlines, and fatal native/journal protections still apply. Display phase updates
never extend a deadline or restore financial availability.

## Last observed display data

XMR status can include optional `display: { data, updatedAt }`. `data` has the
same wallet fields as the ordinary snapshot, but is the last successfully
completed observation. It remains owner/session fenced and can be present
while the live `wallet` is null. Scan progress is likewise retained through
recoverable errors and overdue reads without refreshing its timestamp.

Display data never establishes spendability, financial freshness, custody or
send/trade readiness. Consumers label its observation time and keep current
read diagnostics alongside it. Changing owners or stopping Core custody clears
the Core display snapshot; a client may keep previously approved selected-account
data in memory for a stopped page, but must clear it on lock, account/node change
or revoked read permission. No native getter is called by these status reads.

### Optional scan timing history

ARRR structured status and XMR cached status can include `scanHistory` with
`identity` and an ordered `samples` array of `{ at, blocks, total }`. `at` is the
backend observation time in epoch milliseconds; counts describe the current
restore-relative XMR scan or native ARRR synchronization range, not a reinterpretation
of absolute chain height. This metadata is optional and provides display estimates
only. It cannot establish financial freshness, wallet ownership or send readiness.

Core retains at most 92 observations in a rolling 30-minute window, using
20-second anchors plus the latest tail. Ordinary cached reads do not add samples.
History resets on owner/range/clock changes and observation gaps above 15 minutes;
Stop, fatal failure and recovery boundaries omit old history. Consumers must verify the
existing wallet owner first, validate the bounded history against current progress,
and treat a retained estimate as approximate rather than a running countdown.
Older versions that omit this field remain compatible.
