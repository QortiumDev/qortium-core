# Dependency Provenance

This document tracks nonstandard, forked, or locally patched dependencies that
matter to Qortium's runtime or build reproducibility.

## CIYAM AT

Qortium depends on the CIYAM Automated Transactions virtual machine through the
independent Qortium-controlled `QortiumDev/AT` repository. That repository
preserves the complete inherited Git history without remaining in GitHub's fork
network.

- Maven coordinate: `com.github.QortiumDev:AT`
- Version pin: `22f9266`
- Local checkout: `~/qortium/git/AT`
- Local jar: `lib/com/github/QortiumDev/AT/22f9266/AT-22f9266.jar`
- Local POM: `lib/com/github/QortiumDev/AT/22f9266/AT-22f9266.pom`
- Pinned commit:
  `22f9266b48de86b7f5db0bd0d680ab5f2d59f659`
- Local jar SHA-256:
  `f94f7d41fe547a8a5a0b1387b31edd266cc6cf62ed06401dcc1ce618a26ed259`
- Local POM SHA-256:
  `6c354d4fb3aa7f993034d5d195c80010f43dad1c0b2e2a371faf210880518936`
- Upstream lineage: `ciyam/AT` -> `catbref/AT` -> `IceBurst/AT` ->
  `QortiumDev/AT`

The pinned commit is the canonical merge on `QortiumDev/AT` containing the
payout-balance correctness changes used by this Core branch. Stock payout
opcodes now debit the VM balance by the amount the host actually emitted, and a
host platform API can debit that same balance when it pays the configured
working asset by another route. Core activates the matching behavior at its
configured payout-solvency height; the library itself is pinned exactly so all
nodes execute the same VM semantics.

The IceBurst `v1.4.3` fork point mainly added JitPack/Maven build support and
dependency updates on top of the Qortal-era `catbref/AT` lineage. The core AT VM
history and original authorship remain intact in the independent repository.
`QortiumDev/AT` carries a standardized MIT license and an `AUTHORS.md` lineage
record; both notices are also packaged under `META-INF` in the runtime jar.

Qortium vendors the pinned AT jar and a small local Maven POM under `lib/` so
builds and dependency scanners can resolve this consensus-critical dependency
without depending on JitPack availability. The local POM intentionally omits the
plugin and test dependencies from the JitPack-generated POM because the runtime
AT classes imported by Qortium use only JDK classes.

## bitcoinj

Qortium currently depends directly on upstream bitcoinj for the remaining
Bitcoin-like transaction signing and compatibility boundary:

- Maven coordinate: `org.bitcoinj:bitcoinj-core`
- Version pin: `0.17.1`

The shared BTC-like parameter, address, script, wallet-scan, raw-transaction,
UTXO, and ACCT identity paths have moved into Qortium code. bitcoinj remains in
use for transaction construction/signing and a few compatibility types. The
`0.17.1` baseline keeps that boundary on bitcoinj's patched security line.
Removing that final dependency boundary should be treated as a dedicated
crosschain signing project, not as provenance cleanup.

## HSQLDB

Qortium uses a locally patched HSQLDB jar from `lib/`:

- Maven coordinate: `org.hsqldb:hsqldb`
- Version pin: `2.7.4`
- Local jar: `lib/org/hsqldb/hsqldb/2.7.4/hsqldb.jar`
- Upstream jar SHA-256:
  `5fab2bb4384ac06b762638c8fa2740c944b8d080e4796c0c6c2af8b90dd4e5ad`
- Patched jar SHA-256:
  `d26b2294f9f16ea54b72f87ca1e2b32e8da1a66b2995ab41385ff926e518c069`
- Verification script: `tools/rebuild-hsqldb-jar.sh`

The patch changes only the HSQLDB manifest seal line:

```diff
-Sealed: true
+Sealed: false
```

This is needed because Qortium adds local classes in the `org.hsqldb.jdbc`
package for HSQLDB pool monitoring. Maven installs this patched jar during the
`validate` phase so fresh workspaces use the unsealed jar for compile and test
classpaths.

Run `tools/rebuild-hsqldb-jar.sh --verify` to download the official Maven
Central jar, verify its checksum, verify the checked-in patched jar checksum,
and confirm the extracted jar contents differ only by the manifest seal line.

Run `tools/rebuild-hsqldb-jar.sh --output /tmp/hsqldb.jar` to produce a freshly
patched jar for inspection. The generated jar is verified by extracted content;
its byte-for-byte checksum can differ because ZIP metadata is not normalized by
the basic rebuild path.

## Retired Dependencies

Qortium previously depended on `com.github.QuickMythril:altcoinj:d7cf6ac`, an
inherited fork lineage from `bitcoinj/bitcoinj` -> `dogecoin/libdohj` ->
`jjos2372/altcoinj` -> `Qortal/altcoinj` -> `IceBurst/altcoinj` ->
`QuickMythril/altcoinj`. That dependency carried inherited non-Bitcoin network
parameter support for Litecoin, Dogecoin, DigiByte, Ravencoin, and incomplete
Pirate Chain parameters. Qortium removed the altcoinj/libdohj dependency after
moving registered BTC-like coins and PirateChain onto shared static parameters.

Qortium previously carried `com.dosse:WaifUPnP:1.3` in the local `lib/` Maven
repository for automatic router port mapping. That inherited jar was replaced
with the maintained Maven Central dependency `org.jupnp:org.jupnp`, so UPnP is
no longer a locally vendored dependency.
