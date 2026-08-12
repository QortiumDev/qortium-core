# I2P Fallback Operator Guide

Qortium Core can use I2P as a fallback transport for peers that cannot accept
inbound TCP connections. Direct TCP stays primary: public and port-mapped peers
continue to use normal TCP, while I2P gives NAT'd peers a route to each other
without port forwarding.

Qortium Home is expected to manage `i2pd` for normal desktop users. This guide
is for standalone Core operators who install and run Core manually.

## What Happens Without i2pd

Core does not require `i2pd` to start, sync, or serve direct TCP peers. If I2P is
enabled but no local SAM bridge is available, Core logs one informational message,
keeps direct TCP active, and retries I2P setup in the background. It advertises
I2P capabilities only after the corresponding SAM session is actually up.

Which transports a node uses is controlled by `allowedTransports`, an ordered
list. The default is both, with direct TCP preferred:

```json
{
  "allowedTransports": ["IP", "I2P"]
}
```

If you do not want Core to attempt I2P at all, list only direct TCP:

```json
{
  "allowedTransports": ["IP"]
}
```

To run a node that uses **only** I2P (never binds, dials, or advertises a direct
IP address), list only I2P:

```json
{
  "allowedTransports": ["I2P"]
}
```

> The older `i2pEnabled` / `i2pPreferred` settings are retained only as
> read-only compatibility values derived from `allowedTransports` (e.g.
> "I2P enabled" just means the list contains `I2P`). They are no longer the way
> to configure transports — set `allowedTransports`.

## Required i2pd Setup

Core talks to I2P through SAM on the local machine:

```text
127.0.0.1:7656
```

Do not expose SAM publicly. It should listen on loopback only.

On Debian-family systems, the basic package setup is usually:

```sh
sudo apt install i2pd
sudo systemctl enable --now i2pd
```

Package names, service management, and config paths vary by operating system.
Treat the commands above as an example, not a universal installer.

## Qortium Settings

The default I2P-related Core settings are:

```json
{
  "allowedTransports": ["IP", "I2P"],
  "i2pSamHost": "127.0.0.1",
  "i2pSamPort": 7656,
  "i2pChainKeyFile": "i2p/chain.keys",
  "i2pDataKeyFile": "i2p/data.keys"
}
```

`allowedTransports` is an ordered preference list. Listing `IP` first (the
default) keeps direct TCP primary and uses I2P only as a fallback for peers with
no direct path. Listing `I2P` first prefers I2P even when direct TCP is
available — mainly useful for testing. Listing only `["I2P"]` makes an I2P-only
node, and only `["IP"]` disables I2P entirely.

The key files hold the persistent I2P destinations for the chain and QDN/data
networks. Keep them local to the node and do not commit them.

## Low-bandwidth profile

Core's default networking timeouts assume a normal broadband link. On a very
constrained connection (tens of kbps — dial-up, a saturated satellite/cellular
uplink, etc.) those short timeouts fire before a reply can physically arrive,
so the node loses chain peers every 1-3 minutes ("no ping received") and QDN
and block-sync requests keep retrying instead of completing. None of this
affects consensus, block validity, or wire formats — it only changes how long
a local node waits for a reply and how much it asks for per request.

**As of this change, the node adapts to a slow link automatically, with no
settings changes required:**

- A chain peer is only disconnected after `peerPingFailureThreshold`
  (default 3) *consecutive* missed pings, not the first one. One late PONG on
  a saturated link means slow, not dead — genuinely dead TCP connections are
  already torn down independently by socket errors, and a truly unresponsive
  peer is still removed within roughly `peerPingFailureThreshold` ping
  intervals (~40s each).
- QDN chunk transfer automatically yields to chain sync: while the node is
  not yet caught up with the chain, its per-peer chunk batch size is capped
  at `qdnSyncYieldBatchSize` (default 1) instead of the normal 10-40 range.
  QDN is never fully paused during catch-up — just slowed — so a user
  browsing QDN content while their node is still syncing still sees slow
  progress. Once the node is caught up, normal batching resumes.
- QDN chunk batching self-tunes to the actual link instead of assuming a
  fixed few-second ramp-up. A single feedback window starts at
  `qdnInitialChunkBatchSize`, grows by 1 chunk after any batch interval that
  saw at least one chunk arrive with nothing timing out, and immediately
  halves the moment a tracked chunk request expires. A link that's actually
  fast climbs past the old 40-chunk ceiling's ramp-up delay quickly; a link
  that's actually slow backs off within one interval instead of hammering a
  peer that can't keep up. The sync-yield cap above still applies on top,
  unchanged. Controlled by `qdnAdaptiveBatching` (default true).
- GET_BLOCKS requests during fast sync auto-degrade on a timeout instead of
  wasting the whole 10-second round: the requested block count is halved for
  that peer and retried immediately (down to a floor of 1), so a peer that's
  simply slow gets a request its link can actually complete instead of
  repeatedly missing the same oversized one. A successful response at a
  degraded count lets the next request try double the count again, capped at
  `maxBlocksPerRequest` — the node recovers speed as the link (or the
  request's luck) improves. This is scoped to the current sync attempt only;
  nothing persists across sessions. Controlled by `blocksBatchAutoDegrade`
  (default true).

This means the settings profile below is now needed only for genuinely
extreme links, or to further tune behavior beyond the automatic defaults; a
node on a merely slow (rather than extremely constrained) connection may not
need to touch settings.json at all.

A complete settings.json for a constrained node:

```json
{
  "allowedTransports": ["IP"],
  "maxBlocksPerRequest": 1,
  "peerPingTimeoutMillis": 25000,
  "qdnRequestTimeoutMillis": 180000,
  "qdnInitialChunkBatchSize": 1,
  "qdnMaxChunkBatchSize": 1,
  "singleBlockResponseTimeout": 20000,
  "blocksBatchResponseTimeout": 60000
}
```

- `allowedTransports: ["IP"]` — disables I2P. I2P's tunnel-relay overhead adds
  further latency and jitter on top of an already-thin link, so a constrained
  node gets the most predictable behaviour from direct TCP only.
- `maxBlocksPerRequest: 1` — fast sync normally batches up to 100 blocks into
  one byte-bounded response; capping this at 1 keeps each fast-sync response
  small enough to actually arrive within `blocksBatchResponseTimeout`.
- `peerPingTimeoutMillis: 25000` — how long Core waits for a PING reply before
  counting it as a missed ping (default 4000ms). Chain pings go out every ~40
  seconds; a reply that is simply queued behind other traffic on a slow link
  can easily take longer than the 4-second default, so raising this reduces
  how often healthy peers even register a miss (see `peerPingFailureThreshold`
  below for how many consecutive misses it then takes to disconnect).
- `qdnRequestTimeoutMillis: 180000` — how long QDN keeps a file-list/chunk
  request's bookkeeping entry before treating it as expired and eligible for
  retry (default 12000ms). A single 512 KiB QDN chunk can legitimately take
  minutes on a very slow link, so this needs to be well above the default.
- `qdnInitialChunkBatchSize: 1` and `qdnMaxChunkBatchSize: 1` — how many QDN
  chunks are requested from a peer per batch once the node is caught up with
  the chain (defaults 10 then ramping to 40). Pinning both to 1 avoids
  requesting many chunks in parallel from a link that can barely sustain one
  at a time, on top of the automatic `qdnSyncYieldBatchSize` cap that already
  applies while still syncing.
- `singleBlockResponseTimeout: 20000` — how long slow sync waits for a single
  `GET_BLOCK` reply (default 4000ms, previously the same timeout used for
  chain pings). Raise this alongside `peerPingTimeoutMillis` so a legitimately
  in-flight block isn't abandoned early.
- `blocksBatchResponseTimeout: 60000` — how long fast sync waits for a batched
  `GET_BLOCKS` reply (default 10000ms). With `maxBlocksPerRequest: 1` each
  response is small, but still give it a generous window on a slow link.

Two further fields control the automatic adaptive behavior itself, on top of
the timeouts/batch sizes above:

- `peerPingFailureThreshold: 1` — restores the pre-adaptive instant-disconnect
  behavior (disconnect on the very first missed ping), for an operator who
  wants the old strict dead-peer detection instead of the new default of 3
  consecutive misses. Valid range is 1-10.
- `qdnYieldDuringSync: false` and `qdnSyncYieldBatchSize` — disable, or retune,
  automatic QDN yielding during chain catch-up. `qdnYieldDuringSync` defaults
  to `true`; `qdnSyncYieldBatchSize` defaults to 1 and accepts 1-100. Turning
  yielding off is only useful if you specifically want full-speed QDN transfer
  even while behind on chain sync (accepting that it will further starve chain
  pings/sync on a constrained link) — most operators should leave this alone.
- `qdnAdaptiveBatching: false` — restores the previous fixed, time-based
  chunk-batching ramp exactly (`qdnInitialChunkBatchSize` for the first 5
  seconds of a transfer, then `qdnMaxChunkBatchSize`), instead of the
  self-tuning feedback window described above. Only useful if you want a
  predictable, link-independent batch size rather than one that adapts.
- `blocksBatchAutoDegrade: false` — restores the previous fixed-count,
  single-attempt GET_BLOCKS behavior: one request at `maxBlocksPerRequest`
  blocks per round, and a timeout wastes the whole
  `blocksBatchResponseTimeout` window rather than retrying smaller
  immediately. Only useful if you'd rather tune `maxBlocksPerRequest`
  manually than let the node auto-degrade per peer.

Also consider temporarily setting `qdnEnabled: false` while a node is still
catching up on initial chain sync, on links extreme enough that even the
automatic `qdnSyncYieldBatchSize` cap is too much — this stops QDN chunk
transfers entirely rather than just slowing them, and can be turned back on
once the node is caught up.

## Privacy: What's Exposed (and What Isn't)

A short summary of how I2P protects your IP address, and where it doesn't.

- **Core never connects to the internet over I2P.** It only talks to the local
  SAM bridge (`127.0.0.1:7656`). Everything I2P-related — building tunnels,
  finding peers, carrying your traffic — is done by `i2pd`, not by Core. On an
  I2P-only node, Core makes no direct outbound IP connections at all; its only
  network sockets are to loopback SAM and its own local forwarder.
- **Other Qortium peers see your destination, not your IP.** Over I2P, peers
  identify you by your `.b32.i2p` destination address, which is derived from a
  key and reveals nothing about your IP or location.
- **`i2pd` is the only thing that touches the public internet.** To carry your
  traffic it peers with a rotating set of other I2P routers (on the order of a
  hundred). Those routers, and your ISP, can see that your IP is *running I2P* —
  but not what you are doing. Your actual traffic is wrapped in layered
  ("garlic") encryption and relayed through multiple hops, so no single router
  sees both who you are and what you are sending.
- **A firewalled / NAT'd `i2pd` only dials out.** If your router is behind NAT
  (the common case), it makes outbound connections only and accepts none, which
  further limits exposure.
- **The Core API binds all interfaces but is access-restricted.** By default the
  API is reachable on the machine's interfaces and gated by `apiWhitelist`
  (loopback only by default) plus an API key. For a hardened node you can bind it
  to loopback explicitly with `"bindAddress": "127.0.0.1"`. Keep SAM on loopback
  as well — never expose `127.0.0.1:7656` publicly.

In one line: **Qortium peers see your destination, not your IP; the I2P routers
your `i2pd` talks to see your IP, but not your activity; nothing connects the
two.**

## Verify i2pd

Check that SAM is listening on loopback:

```sh
ss -ltn | grep ':7656'
```

Check Core's application log:

```sh
grep -E "I2P .*fallback reachable|I2P session" qortium.log
```

When the sessions are up, Core logs separate chain and QDN/data I2P destinations.

Connected peer API responses include a `transport` field:

```sh
curl -fsS http://127.0.0.1:24891/peers
curl -fsS http://127.0.0.1:24891/peers/data
```

Reachable public peers should normally show `IP`. Fallback peers that have no
direct TCP path can show `I2P`.

## Reseed Notes

An I2P router needs enough known routers before I2P connectivity is useful. If
normal reseed fails on a hardened or locked-down host, use i2pd's local-file
reseed path instead of repeatedly restarting the router. Place a valid SU3
reseed file on the host and configure i2pd with:

```ini
[reseed]
file = /var/lib/i2pd/i2pseeds.su3
```

Then restart `i2pd` and wait for the router count to climb before judging I2P
fallback connectivity.

## Troubleshooting

- `Connection refused` to `127.0.0.1:7656`: `i2pd` is not running or SAM is not
  enabled on that port. Core will keep using direct TCP.
- `LeaseSet not found`: the remote I2P destination is not currently reachable
  or has not published tunnels yet. This can be normal during cold start. It was
  also previously caused by *your own* node failing to publish its LeaseSet when
  a SAM session was re-opened too quickly for the same destination (leaving a
  "zombie" session with no inbound tunnels). That case is now fixed by a
  per-destination recreate cooldown — if you still see your node unreachable over
  I2P, confirm `i2pd` shows established inbound tunnels for the destination.
- No `I2P` capabilities in peer handshakes: the local SAM session is not up yet,
  or `allowedTransports` does not include `I2P`.
- Slow first connection: I2P tunnel build and destination lookup can take time,
  especially after router startup.
