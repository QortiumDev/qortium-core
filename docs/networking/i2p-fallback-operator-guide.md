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
