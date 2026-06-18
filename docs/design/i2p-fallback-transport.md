# Design: I2P Fallback Transport for Qortium Core

Status: **Draft / proposal** (2026-06-18)
Author: prepared with QuickMythril
Scope: Qortium Core networking (`org.qortium.network`), chain network + QDN data network

---

## 1. Problem & goal

On Previewnet every node connects only to the two public seed nodes: home routers
don't open inbound ports (UPnP fails — no IGD / CGNAT), so no ordinary node is
inbound-reachable, and the QDN data network (which has no peer-exchange of its own)
collapses to the seeds. UPnP, `minOutboundPeers`, and the reachability-advertise fix
(PR #45) help only nodes whose router cooperates; they cannot connect two NAT'd peers
to each other.

**Goal:** give NAT'd nodes a way to reach each other with no port forwarding, no new
seed/relay infrastructure, and no central coordinator — for **both** the chain network
(`Network.java`) and the QDN data network (`NetworkData.java`).

**Validated approach (spike, 2026-06-18):** an I2P overlay gives every node a routable
`.b32.i2p` address and carries data between two NAT'd nodes with zero open ports. A real
cross-NAT transfer succeeded (2 MiB, hash-verified). Throughput is modest and
warmup-sensitive (~28 Kbps avg, ~72–127 Kbps physical peaks, semi-warm), which is why
I2P is proposed as a **fallback transport**, not a replacement for direct TCP.

## 2. Principles (non-negotiables for this design)

1. **Direct TCP stays primary.** Public / port-mapped nodes keep using fast TCP. I2P
   carries only the traffic that otherwise has *no path* (NAT'd ↔ NAT'd).
2. **No new infrastructure.** No gateways, no relays, no seeds beyond what exists.
   Reachability comes from I2P itself, not from community transport nodes.
3. **Minimal, additive change.** Reuse Qortium's existing handshake, message, Peer, and
   selector machinery. Abstract only the connection-establishment and addressing seams.
4. **Decentralized.** Every node is equally reachable over I2P; no node is special.
5. **Incremental & reversible.** Ship behind a setting, data network first. There is no
   mainnet yet, so it is enabled across the board for testing; we un-adopt simply by not
   merging the branch if it doesn't pan out.

## 3. Key enabling insight: SAM turns I2P into SocketChannels

i2pd exposes the **SAM v3** API on `127.0.0.1:7656`. With SAM:

- `STREAM CONNECT ID=<sid> DESTINATION=<peer-b32>` — after the bridge replies
  `STREAM STATUS RESULT=OK`, **the same TCP socket becomes a transparent data pipe to the
  remote destination.** I.e. an outbound I2P connection is just a `SocketChannel` to
  `127.0.0.1:7656` that we hand to the normal peer machinery.
- `STREAM FORWARD ID=<sid> PORT=<localPort>` — the bridge opens a local TCP connection to
  `localhost:<localPort>` for **each inbound** I2P stream (prefixing the remote
  destination as the first line in SAM ≥3.2). So inbound I2P peers arrive as ordinary
  inbound `SocketChannel`s on a `ServerSocketChannel` we already know how to accept.

**Consequence:** the entire data path (selector loop, read/write tasks, handshake, message
framing, Peer state machine) is **reused unchanged**. New code is confined to: a SAM
session manager, an outbound "connect-over-SAM" helper, an inbound forward listener, and
I2P-aware addressing. This is the difference between this proposal (~hundreds of LOC) and
the Qortal `reticulum` branch (~7k LOC, see §11).

## 4. Architecture overview

```
                 ┌─────────────────────── Qortium Core (one node) ───────────────────────┐
                 │                                                                        │
   chain peers   │   Network.java (24892)            NetworkData.java (24894)             │
   (TCP or I2P)  │     selector / accept / Peer        selector / accept / Peer           │
                 │            │      ▲                        │      ▲                     │
                 │  connect() │      │ accept                 │      │ accept              │
                 │            ▼      │                        ▼      │                     │
                 │      ┌───────────────────── PeerTransport seam ─────────────────────┐  │
                 │      │  TCP path: SocketChannel.open()+connect (existing)            │  │
                 │      │  I2P path: I2PTransport.connect(dest) / forward-accept (new)  │  │
                 │      └───────────────────────────────┬───────────────────────────────┘ │
                 │                                       │ SAM v3 (control + stream sockets)│
                 └───────────────────────────────────────┼────────────────────────────────┘
                                                          ▼
                                              i2pd  (127.0.0.1:7656 SAM)
                                                          │
                                                   I2P network (no ports)
```

- **Per network, one I2P destination** (one SAM session): the chain network advertises its
  `.b32.i2p`, the data network advertises its own. (Two destinations mirror the two TCP
  ports; keeps the existing "separate networks" model intact.)
- A peer is reachable by **TCP and/or I2P**; the local node picks per-peer (see §5.5).

## 5. Component design

References below are to the current code, mapped by exploration on 2026-06-18.

### 5.1 I2P client library — decision

Three options were evaluated:

| Option | What | Pros | Cons |
|---|---|---|---|
| **A. Thin in-house SAM v3 client** (recommended for core) | ~300–500 LOC speaking SAM over `java.nio` to an external i2pd | zero new Maven deps; matches Qortium's thin-networking style; this is effectively what the spike validated | we maintain the SAM code; **requires i2pd present on the node** |
| B. `net.i2p:i2p` 2.5.1 | official lib; can **embed a Java I2P router in-process** | no external i2pd needed → far better UX for Qortium Home users; battle-tested | +~5 MB; full router in-process (RAM/CPU); heavier dep |
| C. Java RNS port (`jschulthess/reticulum-network-stack`) | full Reticulum stack | matches Qortal prior art | RNS has no working I2P interface (only an `i2p_tunneled` flag); pulls a large stack we'd use only as a socket wrapper |

**Recommendation:** implement transport against a small internal `I2PStreamProvider`
interface so the *backing* is swappable, and start with **(A) thin SAM client + external
i2pd** for core/dev. Treat **(B) embedded `net.i2p`** as the likely production answer for
Qortium Home (see §6 — the deployment/UX question dominates). The transport seam (§5.3)
is identical either way.

### 5.2 Addressing — `PeerAddress`

Today `PeerAddress` is a `host:port` string pair (`PeerAddress.java:18-167`).
`fromString()` (`:101-122`) validates IP *literals* via `InetAddresses.forString()` but
lets hostnames through; `toSocketAddress()` (`:138-148`) then does a DNS `InetSocketAddress`
lookup — which would **block/fail** on a `.b32.i2p` host.

Changes:
- Add a transport discriminator to `PeerAddress`: `enum Kind { IP, I2P }` (or `isI2P()`).
- For I2P, `host` holds the destination (`<52-char-b32>.b32.i2p`, ~60 chars), `port`
  unused (store `0` or the network's logical port for display).
- `fromString()` recognizes a `.b32.i2p` suffix → `Kind.I2P`, **skips** `InetAddresses`
  validation and never calls DNS.
- `toSocketAddress()` throws/`assert` for `Kind.I2P` (must not be called; the connect path
  branches before this — see §5.3).
- `equals()`/`toString()` already string-based, work for b32 (case-insensitive compare is
  fine; b32 is lowercase).

A `.b32.i2p` address is ~60 bytes, well within the `PEERS` message's 1-byte length field
(255) — so peer-exchange needs no wire change (§5.6).

### 5.3 Connection establishment — the transport seam

This is the only place the data path forks. Keep it narrow.

**Outbound** — `Peer.connect(int network)` (`Peer.java:714-751`) currently does
`SocketChannel.open()` + `socket().connect(resolvedAddress, CONNECT_TIMEOUT)` (`:721-723`).
Branch at the top:
```java
SocketChannel connect(int network) {
    PeerAddress addr = this.peerData.getAddress();
    SocketChannel channel = addr.isI2P()
        ? I2PTransport.forNetwork(network).connect(addr.getHost())  // SAM STREAM CONNECT
        : connectTcp(addr);                                         // existing path
    if (channel == null) return null;
    sharedSetup(network);   // unchanged: register with selector, non-blocking
    return channel;
}
```
`I2PTransport.connect(dest)` opens a `SocketChannel` to the SAM bridge, performs the
(blocking) `HELLO` + `STREAM CONNECT ID=<sid> DESTINATION=<dest> SILENT=false` handshake,
reads `STREAM STATUS RESULT=OK`, sets the channel non-blocking, and returns it. From that
point it is an ordinary data channel — `Network.connectPeer` (`Network.java:1568-1601`) and
the selector loop are unchanged.

**Inbound** — in addition to the existing `ServerSocketChannel` on 24892/24894
(`Network.java:297-302`, `NetworkData.java:281-285`), bind a **loopback**
`ServerSocketChannel` on an ephemeral local port and issue SAM `STREAM FORWARD
ID=<sid> PORT=<thatPort>`. i2pd then dials that local port for each inbound I2P stream.
Register it in the same selector; the accept path (`Network.java:1030-1038` →
`ChannelAcceptTask`, `ChannelAcceptTask.java:50,70`) is reused, with one variant: for the
forward listener, read SAM's leading destination line first and construct an I2P
`PeerAddress` instead of `PeerAddress.fromSocket()` (`:70`). The inbound `Peer` constructor
(`Peer.java:245-263`) caches `resolvedAddress` from the socket (`:256`) — for I2P that's
loopback and meaningless, so store the parsed I2P `PeerAddress` as the peer's address
instead.

**`I2PTransport` / SAM session manager (new, `org.qortium.network.i2p`):**
- Holds the persistent **control socket** to SAM and the `SESSION CREATE STYLE=STREAM`
  (one per network). The session's destination keys are loaded from / saved to disk so the
  node's `.b32.i2p` is **stable across restarts** (the spike confirmed RNS does this; we do
  it explicitly).
- Exposes: `String localDestination()` (our b32), `SocketChannel connect(String dest)`,
  and starts the `STREAM FORWARD` listener feeding the network's selector.
- Reconnect/health: re-establish the SAM session if the control socket drops (i2pd restart);
  surface "session up" state for reachability (§5.7).

### 5.4 Handshake — advertise our I2P destination(s)

Capabilities ride in the HELLO handshake as a free-form `Map<String,Object>`
(`Handshake.buildHelloCapabilities()`, `Handshake.java:558-583`; `HelloMessage`
`HelloMessage.java:17-21`; read via `Peer.getPeerCapability()` `:302-304`). This is exactly
how `"QDN":24894` is advertised — so we add string capabilities, no wire-format change:

- `"I2P"` → this node's **chain** destination b32 (when the chain I2P session is up).
- `"I2P_QDN"` → this node's **data** destination b32 (when the data I2P session is up).

Two keys because chain and data use separate destinations. A node advertises a key only
when that I2P session is up; absence = "no I2P here".

### 5.5 Transport selection / fallback policy

When choosing how to reach a known peer:
1. If the peer is **directly IP-connectable** (we have a routable `IP:port` for it and it
   advertises inbound reachability) → **TCP** (fast path, unchanged).
2. Else if the peer advertised an I2P destination **and** local `i2pEnabled` and our I2P
   session is up → **I2P** (fallback).
3. Else → unreachable (as today).

Optional `i2pPreferred` setting forces I2P even when IP is available (useful for testing
and for privacy-conscious operators). The peer is *not* hard "transport-locked" (unlike the
Qortal branch); the local node decides per attempt, so a peer that gains a public IP later
is reached directly without re-handshake gymnastics.

### 5.6 Peer-exchange propagation

`PeersMessage` serializes addresses as `host:port` UTF-8 strings with a 1-byte length
prefix (`PeersMessage.java:19-48,61-83`). A `.b32.i2p` address fits (~60 < 255). A node that
is **not** directly IP-reachable advertises its I2P address in its self-entry so others can
reach it; `PeerAddress.fromString()` round-trips it via the `.b32.i2p` suffix detection
(§5.2). No new message type required for v1. (If we later want explicit transport tagging in
peer-exchange, `PeersV2Message` is the place — cf. how Qortal added `peerMetaType`.)

### 5.7 Reachability — `InboundReachability`

`canAcceptInbound()` (`InboundReachability.java:25`) currently gates *IP* direct-
connectability (`portMapped || hasConfiguredExternalAddress() || recentInboundHandshake`).
I2P reachability is independent: **if our I2P session is up, we are inbound-reachable over
I2P with no port mapping at all.** So:
- Track `i2pSessionUp` and expose `canAcceptInboundI2P()`.
- Advertise the `"I2P"`/`"I2P_QDN"` capability whenever the corresponding session is up,
  independently of `canAcceptInbound()` (IP).
- A node behind CGNAT with failed UPnP advertises *no* IP reachability but *does* advertise
  I2P — exactly the case we need to fix.

### 5.8 NetworkData (QDN) specifics

`NetworkData.addPeer()` (`NetworkData.java:2290-2347`) and `getConnectablePeer()`
(`:1223-1303`) learn data peers by taking the chain peer's `"QDN"` port capability and
**reusing the chain peer's host** (`:2319`) to build the data address. For I2P this is wrong
— the chain and data destinations differ. Fix:
- If the chain peer advertised `"I2P_QDN"`, build an **I2P** `PeerAddress` from that b32
  (ignore host:port reuse).
- Prefer a direct-IP QDN address when the peer is IP-connectable; else use `I2P_QDN`.

This is the change that makes cross-NAT **QDN fetch** work — the original symptom.

### 5.9 Settings (`Settings.java`)

New keys (defaults chosen for safe, opt-in rollout):
- `i2pEnabled` (bool, default `true` — no mainnet exists yet, so enable across the board for
  testing; gate it off per-node by setting `false`)
- `i2pPreferred` (bool, default `false`) — force I2P even when IP available
- `i2pSamHost` (default `127.0.0.1`), `i2pSamPort` (default `7656`)
- `i2pChainKeyFile`, `i2pDataKeyFile` (paths for persistent destination keys; default under
  the node data dir)
- `i2pEmbeddedRouter` (bool, default `false`) — if true and built with `net.i2p`, run an
  in-process router instead of requiring external i2pd (see §6)

## 6. Deployment / UX decision (must resolve before production)

The transport works with either an **external i2pd** or an **embedded Java router**. The
deciding factor is *who runs the node*:

- **Core / VPS / developers:** external i2pd is fine (and is what the spike used). Thin SAM
  client, zero deps. Document the i2pd install + the **local-file reseed** workaround for
  locked-down/Tor-centric hosts (see `~/AGENTS/notes.md` 2026-06-18 — default reseed servers
  were blocked on the Kicksecure dev box; `reseed.diva.exchange` su3 + `[reseed] file=` fixed
  it).
- **Qortium Home end-users (the whole point — ordinary NAT'd users):** requiring a manual
  i2pd install defeats the purpose. Either **Qortium Home bundles per-platform i2pd binaries**
  (like it already manages the Core), or Core **embeds `net.i2p`** so I2P "just works". This
  is a real fork in the road and should be decided in Phase 2a; my lean is embedded
  `net.i2p` for Home, external i2pd for headless/VPS, behind the same `I2PStreamProvider`
  seam so Core code doesn't care.

## 7. Phasing

- **Phase 2a — Data network over I2P (prove it in-Java).**
  SAM session manager + outbound connect + `STREAM FORWARD` inbound, wired into
  `NetworkData`. Advertise `"I2P_QDN"`, fix `addPeer`/`getConnectablePeer` (§5.8). Goal: two
  NAT'd nodes fetch QDN data from each other over I2P with no ports. (Lower risk: cannot fork
  consensus.) Resolve the §6 library/deployment decision here.
- **Phase 2b — Chain network over I2P.**
  Advertise `"I2P"`, add the chain I2P session + forward listener, transport selection (§5.5),
  peer-exchange propagation (§5.6). Goal: NAT'd nodes maintain chain peers beyond the seeds.
- **Phase 2c — Reachability, settings, rollout.**
  `InboundReachability` I2P mode, preview config defaults (`i2pEnabled:true`), packaging
  (§9), `MergeSettings` keys, seed handling.

## 8. Testing strategy

- **Unit:** SAM v3 client against a mock/fake SAM bridge (handshake parsing, framing,
  reconnect); `PeerAddress` I2P parsing/round-trip; transport-selection decision table.
- **Integration (in-JVM):** two Core instances on one host, I2P-only, exchanging blocks /
  fetching a QDN resource over a live i2pd — the Java analogue of the spike (which already
  proved the network mechanics with `rncp`).
- **Cross-NAT:** the two-machine setup from `~/reticulum-spike/CROSS-NAT-SETUP.md`, but with
  Core instead of `rncp`.
- **Regression:** existing TCP-only tests must pass unchanged with `i2pEnabled:false`.

## 9. Rollout

- Bundle decision per §6 into `package-release.sh` (ship i2pd binary, or `net.i2p` jar, or
  document external i2pd).
- Preview configs (`settings-preview.json`, seeds) gain `i2pEnabled` etc.; existing testers
  pick them up via the `MergeSettings` 3-way merge (as the UPnP/minOutbound defaults did),
  unless an operator overrode the key.
- Seeds keep public IPs (TCP primary) and additionally run I2P, so they can also serve
  unreachable peers over I2P during transition.
- No mainnet exists yet, so `i2pEnabled:true` across the board (preview/testnet/defaults);
  the whole effort lives on a branch and simply isn't merged if it doesn't pan out.

## 10. Risks & mitigations

| Risk | Mitigation |
|---|---|
| I2P throughput modest (~tens of Kbps–low Mbps) | It's a **fallback**; TCP carries reachable peers. QDN already fetches chunks from multiple peers in parallel, so slow I2P links add capacity, not a bottleneck. |
| i2pd not present on user machines | §6 — bundle i2pd with Home or embed `net.i2p`. |
| i2pd reseed blocked on hardened/locked-down networks | Document local-file reseed (`reseed.diva.exchange` su3); embedded router can ship a seed set. |
| SAM session lifecycle (i2pd restart drops session) | Session manager reconnects; peers re-handshake; b32 persists via saved keys. |
| Destination-key file is sensitive | Store under node data dir with restrictive perms; never commit; rotate-able. |
| Added attack surface / privacy | Gated by `i2pEnabled` (settable off per node); no mainnet yet, so on across the board for testing; document threat model. |
| Cold-start latency (I2P tunnels take minutes) | Acceptable for a fallback; persist destination + known-peer hints to warm faster. |

## 11. Alternative considered: full Reticulum (Qortal `reticulum` branch)

The closest prior art (Qortal `reticulum` branch, `~/reticulum/repos/Qortal/qortal`) takes a
**maximal** approach: full Java RNS (`jschulthess/reticulum-network-stack` via JitPack), a
parallel `RNS.java` (~1,978 LOC), `Peer`/`PeerAddress` refactored to interfaces +
reflection factories (`@PeerCtor`/`@PeerAddressCtor`), `IPPeer`/`ReticulumPeer`, and
**transport-locked** peers — **57 files, +7,276/−2,107 LOC**. Its shipped config relies on
**Backbone/TCP gateway servers** (`reticulumBackboneGatewayServers`,
`reticulumTcpGatewayServers`), i.e. reachable RNS transport nodes — which **reintroduces the
relay-infrastructure dependency this design avoids** (RNS routes through transport nodes; it
does not hole-punch).

**Why not adopt it wholesale:** (a) it's a large, invasive rework on a codebase that has
diverged from Qortium; (b) its decentralization depends on community gateways we don't want
to run; (c) it gives RNS features (mesh routing, LXMF) we don't need for the NAT problem.

**What to borrow:** the *structural* ideas if/when we want true multi-transport — the
`PeerAddress` interface split and a peer/transport factory are cleaner than a `Kind` enum at
scale. If a future requirement is "general Reticulum mesh" (not just NAT traversal),
revisit this branch as the base. For *now*, the thin SAM-fallback design (§3–§9) solves the
actual problem with ~1–2 orders of magnitude less change.

Reusable references (reticulum branch): `RNS.java`, `ReticulumPeer.java`,
`ReticulumPeerAddress.java`, `PeerFactory.java`/`PeerAddressFactory.java`,
`reticulum_config_template.jinja`.

## 12. Decisions (locked 2026-06-18)

1. **Library/deployment:** build against a swappable `I2PStreamProvider` seam; **start with a
   thin SAM client + external i2pd** for dev/testing. Embedded `net.i2p` for Qortium Home
   remains a later option behind the same seam (revisit during Phase 2a packaging).
2. **`PeerAddress`:** minimal **`Kind` enum** (`IP`/`I2P`), not the interface split.
3. **I2P destinations:** **two** — separate chain and data destinations, mirroring the two
   networks/ports.
4. **Posture:** no mainnet exists yet → **enabled across the board** (`i2pEnabled` default
   `true`). The work lives on branch `i2p-fallback-transport` and is simply not merged if it
   doesn't pan out.

---

### Appendix A — primary code anchors (as of 2026-06-18)
- Outbound: `Network.connectPeer` `Network.java:1568-1601`; `Peer.connect` `Peer.java:714-751`
- Inbound: accept `Network.java:1030-1038`; `ChannelAcceptTask.java:50,70`; `Peer(SocketChannel,int)` `Peer.java:245-263`
- Listen sockets: `Network.java:297-302`, `NetworkData.java:281-285`
- Addressing: `PeerAddress.java` `fromString:101-122`, `toSocketAddress:138-148`
- Handshake/caps: `Handshake.buildHelloCapabilities` `Handshake.java:558-583`; `HelloMessage.java:17-21`; `Peer.getPeerCapability` `Peer.java:302-304`
- Peer-exchange: `GetPeersMessage.java`; `PeersMessage.java:19-48,61-83`
- QDN peer learning: `Network.onHandshakeCompleted` `Network.java:2074-2282` (→2216-2217); `NetworkData.addPeer` `NetworkData.java:2290-2347` (host reuse at 2319); `NetworkData.getConnectablePeer` `NetworkData.java:1223-1303`
- Reachability: `InboundReachability.java:25` (`canAcceptInbound`), `:40-42` (`hasConfiguredExternalAddress`)

---

## 13. Implementation status & handoff (2026-06-18)

**Branch:** `i2p-fallback-transport` in `/home/user/git/qortium`, created off
`work/restore-feature-triggers` — **rebase onto `main` before any PR.** Changes are
**uncommitted** in the working tree (operator reviews with meld, then commits).

**Done — Phase 2a pieces 1-2 (seam + SAM client + address parsing):**
- `src/main/java/org/qortium/network/i2p/I2PStreamProvider.java` — the transport seam (SAM
  now; embedded `net.i2p` possible later behind the same interface).
- `src/main/java/org/qortium/network/i2p/SamSession.java` — thin SAM v3 client (persistent
  Ed25519 destination, `.b32.i2p` derivation, `connect()`, inbound `STREAM FORWARD`,
  `PING`/`PONG` keepalive). Compiles clean. SAM exchange + b32 derivation validated against
  the live i2pd (SAM v3.1). `SESSION CREATE`/`STREAM CONNECT` are slow (tunnel build) → 120 s
  setup timeouts baked in.
- `src/test/java/org/qortium/network/i2p/SamSessionIntegrationTests.java` — opt-in live i2pd
  tests (`-Dqortium.runLiveI2PTests=true`) that bring up two SAM sessions, forward inbound
  streams over loopback, exchange bytes over I2P, verify the forwarded remote destination, and
  check that an invalid `.b32.i2p` destination fails.
- `src/main/java/org/qortium/network/PeerAddress.java` — now carries `Kind { IP, I2P }`,
  recognizes valid `.b32.i2p` hosts, keeps I2P ports at `0`, skips IP/DNS validation for I2P,
  and rejects socket/DNS conversion for I2P addresses.
- `src/test/java/org/qortium/network/PeerAddressTests.java` and
  `src/test/java/org/qortium/network/message/PeersMessageTests.java` — cover IP/DNS
  compatibility, `.b32.i2p` normalization/rejection, no-DNS socket conversion, and PEERS
  message round-trip for I2P addresses.

**Validated:** I2P gives NAT-free cross-NAT reachability — a cross-NAT transfer was received
intact; throughput ~28 Kbps avg / 72–127 Kbps physical peaks (semi-warm) → **fallback-grade,
not primary**. Hence I2P is a fallback; direct TCP stays primary.

**Environment (this dev box is Kicksecure — read `~/AGENTS/` first):** i2pd installed +
running, SAM on `127.0.0.1:7656`, console `http://127.0.0.1:7070`, `bandwidth = X`. **i2pd
reseed on this box works ONLY via the local-file method** (`[reseed] file =
/var/lib/i2pd/i2pseeds.su3`); default/URL/Tor-proxy reseed all fail here — do not revert it.
`apt` / `/etc/i2pd` edits / `systemctl` need the `sysmaint` account. `rns 1.3.5` (pipx) and
spike artifacts are in `~/reticulum-spike/` (incl. `CROSS-NAT-SETUP.md` for a second machine)
and `~/.reticulum-a|-b`. Reference clones (Java RNS, qortal reticulum branch, markqvist
Python) are in `~/reticulum/repos/`.

**Next steps, in order:**
1. **Wire into `NetworkData`** (§5.3–§5.8): data SAM-session bring-up; advertise `I2P_QDN`
   capability in `Handshake.buildHelloCapabilities` (`Handshake.java:558-583`); `STREAM
   FORWARD` listener feeding the data selector; branch `Peer.connect()` (`Peer.java:714`) on
   `addr.isI2P()`; fix `NetworkData.addPeer`/`getConnectablePeer` to use `I2P_QDN` instead of
   reusing the chain host.
2. **Settings** keys (§5.9). 3. **Tests** (§8). Then Phase 2b (chain network), 2c (rollout).

**Gotchas:** `pkill -f <pattern>` matches the running shell when the pattern is in its own
command line (kill by filtered `ps` instead). i2pd must be `active` for any I2P test —
check the console `Routers` is in the hundreds. SAM stream ops are slow on a cold router.
