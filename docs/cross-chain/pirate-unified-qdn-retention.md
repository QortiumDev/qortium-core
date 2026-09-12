# Pirate wallet QDN publication and retention

Core loads the exact transaction signature it pins, not the latest publication
under a name. Every future native bundle must use a unique version-specific
identifier under `ARBITRARY_DATA/QortiumHomeTest`, such as
`pirate-unified-wallet-v1.2.3`. Never overwrite a version identifier. A packaging
correction needs a new identifier suffix and a new reviewed transaction pin.
The current/previous records are in [the release ledger](pirate-unified-qdn-releases.json).

## Supported versions and retirement

The steady-state target is two supported native bundles: current and previous.
This is a support policy, not a hard disk cap or an automatic third-publication
delete rule. Several Core releases can depend on the same bundle. If more than
two bundles are still needed by supported Core releases, retain them until a
support transition permits retirement; never delete solely to reach two.

For each update:

1. Verify the upstream release, publish the new version identifier, and verify
   the exact signature, encrypted payload hash, authenticated ZIP, and every
   file on both seeds. Temporarily holding three bundles is expected.
2. Pin the new signature in Core and its participant profile; update the ledger
   and the seeds' explicit retention lists. Release the replacement stable Core.
3. Keep the previous bundle for at least **30 days after that stable release**,
   and until support is explicitly ended for every Core release using it.
   PR merge, wallet publication, and a prerelease do not start this clock.
4. Before retiring the oldest unsupported bundle, check the ledger and shipped
   release artifacts for references, confirm two independent holders for each
   remaining supported signature, and record the support/upgrade-window decision.
5. Remove the retiring signature from seed retention configuration, apply the
   configuration to the actual running nodes, then publish a DELETE transaction
   for that exact old version identifier. Decode and check the intended service,
   name and identifier before signing. Verify confirmation and later cleanup.

A DELETE is an on-chain tombstone. It does not immediately erase all cached
copies or remove the original transaction from the blockchain. Do not downgrade
or substitute a different native wallet binary when a pinned payload is missing.

## Explicit signature retention

`qdnRetainedSignatures` is an optional JSON array of Base58-encoded 64-byte
transaction signatures, defaulting to `[]`. Entries must be non-null, canonical
(without surrounding whitespace), and unique. Configure it in the active node
settings file and restart the node to apply it. It is not exposed as a live
writable setting through the settings API.

Configured, permitted signatures are exempt from supersession cleanup and
random capacity eviction, preserving their encrypted payload chunks and
metadata. They may still lose reconstructible joined archives or temporary
reader caches, which can be rebuilt from the retained chunks.

Explicit retention overrides follow/view policy, including `storagePolicy=NONE`.
Blocked resources and disabled public/private data classes still take precedence.
Direct operator deletion remains possible; remove a signature from retention
before intentionally retiring it. A later QDN DELETE does not itself cancel an
operator's explicit retention request for historical bytes.

Retention is **not a historical download scheduler**. Provision or explicitly
retrieve each exact signature and verify all of its chunks on each holder.
Ordinary latest-resource prefetch may skip historical transactions, even when
retained. Never use a latest-by-name request as evidence for an older signature.

Capacity still gates new fetches. Retained bytes can exceed a configured cap and
will not be evicted merely to meet it. Keep sufficient disk/capacity headroom
for the new bundle and build workspace; monitor both independent holders.
The tracked seed profiles retain current v1.2.3 and the supported v1.2.1 pin
without enabling ARRR on seeds. Existing installations must update their active
local settings; changing repository templates alone does not change a running node.

## Transition from the generic identifier

Public stable **Core v1.8.0**, and v1.8.0-rc.1, ship a participant profile with
ARRR and Unified enabled and pin the v1.2.1 transaction under
`pirate-unified-wallet`. Core's general default and the seed profiles being
disabled does not make that released participant dependency unused.

Therefore **do not DELETE the generic resource yet**. v1.2.1's original signature
must remain available while those releases are supported. Republishing the same
ZIP under a new name produces a different signature and cannot repair existing
clients that pin the original transaction. The generic v1.2.3 publication from
PR #317 is superseded as the new default by the version-specific publication;
it is preserved as transition history, not another supported runtime version.

Before deploying this PR, exact original generic publication directories were
archived outside QDN cleanup on both seeds. These archival copies protect
recoverability but are not automatically served by QDN. Deploy the retention
change to each seed, apply the active retention settings, and verify or restore
the original signature's chunks before treating live historical availability as
protected. PR creation alone does not provide that runtime guarantee.

Only after the old generic dependencies are explicitly retired may the generic
DELETE be reconsidered. Until then, transition copies can exceed the steady-state
two-bundle target. No automatic deletion is configured by this PR.
