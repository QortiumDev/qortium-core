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

Qortium still inherits the GitHub/JitPack dependency
`com.github.iceburst:altcoinj`. This supports inherited foreign-chain code. If
Qortium keeps that cross-chain implementation, this dependency should be forked
or vendored under Qortium control in a later dedicated change.

## HSQLDB

Qortium uses a locally patched HSQLDB jar from `lib/` so repository code can
share the `org.hsqldb.jdbc` package without sealed-package failures. This is
already effectively vendored, but the patch/build process still needs to be
documented or made reproducible before release.
