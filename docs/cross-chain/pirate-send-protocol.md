# ARRR send protocol v2

ARRR sends require the enabled Unified wallet backend, an explicitly active owner, a fresh READY snapshot and known verified funds. The fee is fixed at **10,000 atomic units (0.0001 ARRR)**. Recipients are canonical Sapling addresses. Amounts use exact decimal text with at most eight fractional digits; memos are limited to 512 UTF-8 bytes.

All endpoints require the node's API authorization. Wallet entropy is spending authority. It belongs only in an authenticated request body to a trusted custody node; never put it in a URL, log, app response or journal.

- `GET /crosschain/arrr/sendcontract`: protocol version, current Pirate network, fixed fee and input limits.
- `POST /crosschain/arrr/sendreadiness`: plaintext wallet entropy; returns `sendAllowed`, network and any blocking operation ID. It never activates or switches a wallet.
- `POST /crosschain/arrr/send`: JSON `entropy58`, `receivingAddress`, `arrrAmount`, optional `memo`, required canonical UUID `idempotencyKey`, and optional `expectedNetwork`. A network mismatch is rejected before admission. Clients should always pin it. Fee overrides are refused.
- `GET /crosschain/arrr/send/{operationId}`: durable operation status without invoking the native wallet.
- `POST /crosschain/arrr/sendlookup/{idempotencyKey}`: plaintext wallet entropy; recovers the same operation after a lost HTTP response. This only reads the journal and never retries the send.

A new admitted operation returns HTTP 202. An identical request returns the same operation, including after restart or while the wallet is disabled; it never invokes native send twice. A changed payment with the same wallet/key conflicts. Terminal duplicate requests return HTTP 200. Responses bind `operationId`, `idempotencyKey`, `walletIdentityHash` and `network`; they contain no recipient, memo or entropy. Typed errors 1205–1208 distinguish not-ready, not-found, conflict and unavailable storage.

| State | Meaning | Wallet spending blocked? |
| --- | --- | --- |
| ACCEPTED | Durable reservation; native send has not begun | Yes |
| NATIVE_STARTED | Durable boundary recorded immediately before native invocation | Yes |
| BROADCAST | Native returned a valid 64-hex transaction ID, durably saved; confirmation is still pending | No |
| FAILED | Proven failure before native invocation | No |
| UNRESOLVED | Native may have broadcast; outcome is unknown | Yes |

**Never retry an unresolved payment with a new key.** It blocks only that wallet's sends and trade funding. Other wallets can spend once the shared native worker is idle and healthy; a degraded worker requires restart. Null-seed trade redemption/refund remains separate. A late valid native transaction ID can upgrade UNRESOLVED to BROADCAST. Stashi v1.2.5 does not expose a durable prebroadcast operation-to-transaction binding: an unknown send without a later transaction ID can remain blocked indefinitely. History resemblance is not proof that a reservation is safe to clear.

The journal lives at `<walletsPath>/PirateChain/send-operations/<network>`, outside the recoverable native `unified` directory. Each checksummed operation file is authoritative. Writes require file and directory synchronization plus atomic replacement. A private persistence worker protects transaction-ID writes from native timeout interrupts. A process lock prevents competing writers; shutdown stops admissions and retains ownership until process exit so late callbacks cannot race a reopened journal. Changing the runtime path or network fails closed. Corruption blocks the affected wallet; uncertain operation-ID lookups fail storage rather than falsely returning not-found.

Restart never replays a payment. ACCEPTED becomes FAILED; NATIVE_STARTED becomes UNRESOLVED. Do not delete, rename or replace this journal to unblock spending: that destroys duplicate-safety evidence.

The in-lane preflight checks both wallet-wide verified balance and the input address's spendable key group, including internal change, repair state and witness epoch. These are necessary checks, not proof of exact native note selection. Every failure after native invocation remains uncertain unless a valid transaction ID was captured.
