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

If you do not want Core to attempt I2P at all, add this to `settings.json`:

```json
{
  "i2pEnabled": false
}
```

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

The default Core settings are:

```json
{
  "i2pEnabled": true,
  "i2pPreferred": false,
  "i2pSamHost": "127.0.0.1",
  "i2pSamPort": 7656,
  "i2pChainKeyFile": "i2p/chain.keys",
  "i2pDataKeyFile": "i2p/data.keys"
}
```

`i2pPreferred` should normally stay `false`. It is mainly for testing because it
prefers I2P even when direct TCP is available.

The key files hold the persistent I2P destinations for the chain and QDN/data
networks. Keep them local to the node and do not commit them.

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
  or has not published tunnels yet. This can be normal during cold start.
- No `I2P` capabilities in peer handshakes: the local SAM session is not up yet,
  or `i2pEnabled` is `false`.
- Slow first connection: I2P tunnel build and destination lookup can take time,
  especially after router startup.
