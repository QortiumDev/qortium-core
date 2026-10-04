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
