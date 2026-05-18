# Dependency Security Review

This note records the first Qortium dependency security review for the
`qortium-6.1.4` baseline.

The review used Maven's resolved runtime dependency tree and the OSV advisory
database. OWASP Dependency-Check was also attempted, but the no-key NVD sync was
rate-limited before it could produce a reliable report.

## First Cleanup Batch

These findings can be handled with narrow dependency maintenance and no planned
API, consensus, transaction, or settings changes:

- Apache Log4j Core `2.23.1` has layout and socket-appender advisories. Qortium's
  current `log4j2.properties` uses pattern layouts and file/console appenders,
  but the dependency should still move to the fixed Log4j 2 line.
- Apache Tika Core `3.1.0` has an XML external entity advisory. Qortium uses
  Tika for MIME type detection around arbitrary data uploads, so this should be
  patched directly.
- Apache Commons Lang `3.17.0` has an uncontrolled-recursion advisory. This is a
  small direct dependency update.
- Swagger's current dependency path brings in a vulnerable Jackson core line.
  Updating the Swagger API dependency moves Jackson onto a fixed line.
- `json-simple` pulls old JUnit into compile scope. Qortium should exclude that
  transitive dependency and keep JUnit only as an explicit test dependency.

## Deferred Security Work

These findings need separate work because they are larger than simple dependency
patches:

- bitcoinj `0.16.3` has a script-verification advisory fixed in bitcoinj
  `0.17.1`, but a dry-run upgrade showed package and API movement for core types
  such as `Coin`, `ECKey`, `Address`, and `Sha256Hash`. This should be a
  dedicated cross-chain compatibility migration or a separate decision to remove
  inherited cross-chain surfaces.
- Netty `4.1.110.Final` is pulled through gRPC and has several HTTP and DoS
  advisories. Updating gRPC alone does not currently land on a Netty version
  that clears every listed advisory, so this needs a separate gRPC/Netty
  dependency-management pass.
- Jetty `10.0.26` has HTTP parsing advisories. Maven Central does not provide a
  newer Jetty 10 line beyond `10.0.26`, so clearing those advisories likely
  requires a Jetty 11 or 12 migration with API, gateway, websocket, TLS, and
  Swagger smoke testing.

## Review Artifacts

The local scan artifacts were generated under `target/`:

- `target/runtime-dependencies.txt`
- `target/osv-vulns.json`
- `target/osv-vuln-details.json`

Those files are build artifacts, not tracked project history. This document is
the durable summary of the review.
