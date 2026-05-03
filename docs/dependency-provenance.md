# Dependency Provenance

This document tracks nonstandard, forked, or locally patched dependencies that
matter to Qortium's runtime or build reproducibility.

## CIYAM AT

Qortium depends on the CIYAM Automated Transactions virtual machine through a
Qortium-controlled fork:

- Maven coordinate: `com.github.QuickMythril:AT`
- Version pin: `1b731d1`
- Local checkout: `~/git/AT`
- Upstream lineage: `ciyam/AT` -> `catbref/AT` -> `IceBurst/AT` ->
  `QuickMythril/AT`

The pinned commit is the same `IceBurst/AT` `v1.4.3` commit that inherited
Qortal Core already used. Moving the dependency to `QuickMythril/AT` is intended
to be behavior-neutral while giving Qortium ownership of a consensus-critical AT
VM dependency before any Qortium-specific AT changes are made.

The IceBurst `v1.4.3` fork point mainly added JitPack/Maven build support and
dependency updates on top of the Qortal-era `catbref/AT` lineage. The core AT VM
behavior used by Qortium should remain unchanged at this fork point.

## altcoinj

Qortium depends on altcoinj through a Qortium-controlled fork:

- Maven coordinate: `com.github.QuickMythril:altcoinj`
- Version pin: `d7cf6ac`
- Local checkout: `~/git/altcoinj`
- Upstream lineage: `bitcoinj/bitcoinj` -> `dogecoin/libdohj` ->
  `jjos2372/altcoinj` -> `Qortal/altcoinj` -> `IceBurst/altcoinj` ->
  `QuickMythril/altcoinj`

The pinned commit is the same IceBurst commit that inherited Qortal Core already
used for bitcoinj `0.16` compatibility. Moving the dependency to
`QuickMythril/altcoinj` is intended to be behavior-neutral while giving Qortium
ownership of the inherited foreign-chain support dependency.

The Qortal-era altcoinj lineage carries the non-Bitcoin network parameter support
used by Qortium's inherited cross-chain code, including Litecoin, Dogecoin,
DigiByte, Ravencoin, and incomplete Pirate Chain parameters. Any larger update
to current `dogecoin/libdohj` or `bitcoinj` should be treated as a dedicated
cross-chain compatibility project, not as provenance cleanup.

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

## WaifUPnP

Qortium keeps WaifUPnP in the local `lib/` Maven repository:

- Maven coordinate: `com.dosse:WaifUPnP`
- Version pin: `1.3`
- Local jar: `lib/com/dosse/WaifUPnP/1.3/WaifUPnP-1.3.jar`
- Local jar SHA-256:
  `eaca3aebe57e90fc32906cbad960218a4479e2ebb7cecff48e9ee3767ce8b644`
- Local POM SHA-256:
  `87cde7ec6ce795bc3968ae9b1b49ce1f80137f8111ae9751c69ab5a5228bcb84`

This is inherited UPnP support used for local network port mapping. It is not
consensus-critical, but because it is vendored locally instead of resolved from
Maven Central it is tracked here for release reproducibility. A later networking
cleanup can decide whether to keep this jar, replace it with a maintained UPnP
library, or make UPnP support optional/removable.
