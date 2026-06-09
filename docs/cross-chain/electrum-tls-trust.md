# ElectrumX TLS Trust Model

This note describes how Qortium Core decides whether to trust the TLS
certificate of an SSL ElectrumX server, and the migration path for existing
server lists and operator configuration.

## Background

Cross-chain support (BTC, LTC, DOGE, DGB, RVN, and other BTC-like chains) talks
to ElectrumX servers over TLS. The public ElectrumX network is decentralized and
overwhelmingly uses **self-signed** certificates, and most servers are reached by
raw IP address. Classic certificate-authority (CA) validation does not fit this
model well:

- self-signed servers have no CA chain to validate, and
- IP-based servers cannot use hostname verification.

This is why the broader Electrum ecosystem pins certificates instead of relying
on CAs. Qortium follows the same approach.

Earlier hardening removed the inherited all-trusting TLS socket factory and made
SSL connections validate against the JVM default trust store. That closed the
"trust everything" hole, but, with the bundled server list carrying no pins, it
also rejected most self-signed servers — a connectivity regression. The trust
model below resolves that without returning to blind trust.

## Trust decision

For every SSL ElectrumX connection, Core decides trust in this order:

1. **Explicit pin (always authoritative).** If the server entry carries a
   `certificateSha256Fingerprint` (from the generated list or operator settings),
   Core pins the connection to that fingerprint. The handshake fails closed if the
   server's leaf certificate does not match.
2. **Trust mode (for servers with no explicit pin).** Controlled by the
   `electrumTlsTrustMode` setting:
   - `STRICT` — require a publicly trusted certificate chain (JVM default trust
     store). Self-signed servers are rejected.
   - `PINNED_ONLY` — refuse any server that has no known pin. Most restrictive.
   - `TOFU` *(default)* — trust on first use. On the first connection Core records
     the server's leaf certificate SHA-256 fingerprint, then pins to it for every
     later connection. A changed certificate is rejected rather than silently
     re-trusted.

Pinning checks the **leaf** certificate's SHA-256 fingerprint. A fingerprint may
be written with or without colons and in any case; Core normalizes it to 64
lower-case hex characters.

## Trust on first use (TOFU)

In `TOFU` mode, recorded fingerprints are persisted to:

```
<lists path>/electrum-tls-fingerprints.json
```

The first connection to an unpinned server captures its leaf certificate with a
trust manager that records the certificate and then aborts the handshake, stores
the fingerprint, and immediately reconnects pinned to it. Subsequent connections
pin directly to the stored fingerprint.

TOFU's only exposure window is a man-in-the-middle present at the very first
connection (the same limitation as SSH known-hosts). Shipping pins in the bundled
server list removes even that window for the default servers.

## Failure behavior

Trust failures fail closed:

- a fingerprint mismatch aborts the TLS handshake,
- `PINNED_ONLY` refuses unpinned servers before connecting, and
- a failed connection is recorded against the server and another server is tried.

No trust failure silently downgrades to an unauthenticated connection.

## Defense in depth

Pinning binds a connection to a specific server certificate, but a single
self-signed server is still only as trustworthy as its operator. Core already
maintains several scored ElectrumX connections per coin and rotates away from
servers that misbehave or respond slowly.

On top of that, the chain tip is corroborated across servers. `getCurrentHeight()`
samples a bounded subset of connected ElectrumX servers and returns the **median**
reading, so a single server lying in either direction cannot skew the
height-based refund/locktime decisions that bound cross-chain trade safety. When
at least three readings are available, servers whose height disagrees beyond a
small tolerance are penalized so the pool drifts away from them. This check is
fail-safe: it never throws or stalls on disagreement, and it falls back to a
single-server read when too few servers are connected to corroborate.

Other reads are bounded by independent safeguards: revealed HTLC secrets are
cryptographically verified, and redeem/refund transactions are validated by the
foreign chain's own consensus on broadcast. Corroborating HTLC funding status
across servers before acting is a possible future hardening step.

## Generating pins

The Electrum server refresh tool (see
[electrum-server-refresh.md](electrum-server-refresh.md)) captures each reachable
SSL server's current leaf fingerprint and writes it into the generated list as
`certificateSha256Fingerprint`. Capture uses the same record-then-abort trust
manager, so the tool never trusts an unverified certificate, and self-signed
servers are pinned and kept instead of being dropped by strict verification.

Regenerate and commit the bundled list to ship explicit pins:

```
tools/refresh-electrum-servers
```

## Migration path

- **Bundled/generated lists** — regenerate with the refresh tool so every SSL
  entry ships a pin. Until then, `TOFU` keeps these servers usable by pinning on
  first use.
- **Operator-configured servers** — set `certificateSha256Fingerprint` on a
  Bitcoiny server entry to pin it explicitly. Entries without a fingerprint follow
  the active `electrumTlsTrustMode`. Configured fingerprints are validated as
  64-character SHA-256 hex.
- **Existing deployments** — no configuration change is required. The default
  `TOFU` mode restores self-signed connectivity automatically; operators who want
  stricter behavior can set `STRICT` or `PINNED_ONLY`.

## CodeQL

The remaining `java/insecure-trustmanager` alert is on the pinned trust manager.
It is a false positive: the manager throws on any fingerprint mismatch and on a
missing certificate, so it fails closed. It does not delegate to the platform
PKIX validator only because it intentionally cannot for self-signed ElectrumX
servers. See [code-scanning-triage.md](../dependencies/code-scanning-triage.md).
