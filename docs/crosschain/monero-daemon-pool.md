# Monero scan servers

XMR scans stay local to Core. A configured remote daemon supplies chain data and never receives wallet keys. Settings can name a primary and explicit fallback servers:

```json
{
  "moneroDaemonUri": "https://xmr-node.cakewallet.com:18081",
  "moneroDaemonUris": ["https://node.monero.fail"]
}
```

This is an example, not an automatic default or provider endorsement. Core contacts only the operator's configured pool. The existing `moneroDaemonUri` stays first; `moneroDaemonUris` appends fallbacks (or supplies the entire pool if the legacy field is absent). Duplicates are removed; at most eight distinct endpoints are allowed in total. No public discovery runs at startup. Changes require a Core restart in this version. Disabled XMR does not initialize the wallet service or make network requests.

Endpoints require HTTPS, or numeric loopback HTTP for an operator's local daemon. Credentials, paths other than `/`, queries and fragments are refused. Before connecting a new read provider, a key-free public metadata request checks mainnet, synchronization and a height at least as high as the local checkpoint. These Java requests verify certificate/hostname normally, never follow redirects, cap response bodies at 64 KiB and wait at most five seconds including the body. Offline regtest exceptions are internal test seams, never API/settings controlled.

A working connection remains selected. A failed scan/daemon read clears financial availability and cools that provider down for 10 seconds, doubling successive failures up to five minutes. The next scheduled read can connect a configured eligible backup using the same native wallet handle. When every provider is cooling down, Core performs no native or metadata reads until the earliest retry. Other ordinary wallet read errors also back off. Passive API polling and repeat activation of the same account cannot bypass the delay. Stop cancels future reads; switching accounts clears account-specific read delays but preserves shared provider health.

Connection changes happen only around reads on Core's one wallet worker. Send preparation, reconciliation and broadcast never choose an alternative provider or retry automatically. A send queued behind a failing read checks availability again before any native or journal admission work. Existing unknown-send holds, journal rules, quote expiry and owner/session fences remain in force.

A first-use wallet checks one native daemon height after the bounded metadata selection pass. A failed native admission is rejected without a durable wallet checkpoint; it cools that provider down so a later explicit activation can choose a fallback. Core does not loop native admission requests or reset partial wallet files.

The existing JNI transport is unchanged. `MoneroWalletFull.setDaemonConnection` forwards URI/credentials/proxy/trust, not Java `MoneroRpcConnection` SSL/timeout flags. The metadata probe's five-second bound does not make native sync cancellable or bound every native request. Core retains its single-worker soft overdue-read state and recovery after a complete successful read; it never starts a concurrent replacement native call. No native library repin is included.

Authenticated, loopback-only `/crosschain/wallets/XMR/status` adds `readRetryAt` (Unix milliseconds) and cached `servers` metadata. `/crosschain/wallets/XMR/servers` exposes that same key-free metadata with the current wallet-session header. Status polling performs no backend/native calls. Errors use fixed `XMR_DAEMON_UNAVAILABLE` or `XMR_WALLET_READ_UNAVAILABLE` categories, without native exception text. This first PR implements automatic configured read failover; explicit app provider selection and shared Home/Wallet display are separate follow-ups through the generic wallet interfaces. Apps cannot submit daemon URLs.
