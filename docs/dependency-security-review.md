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

## Resolved Jetty Work

Jetty `10.0.26` had HTTP parsing advisories and no newer Jetty 10 patch release
in Maven Central. Qortium moved to Jetty `12.1.9` using Jetty's EE8 artifacts so
the API, gateway, proxy, websocket, and HTTP/2 code can stay on the existing
`javax.servlet` surface while running on a maintained Jetty line.

## Jetty and Java 17 Follow-Up Notes

The Jetty migration and Java 17 baseline are complete enough to move on to the
next dependency-security item. The branch builds with Java 17, the README and
start scripts now require Java 17, GitHub Actions runs on Temurin 17, and a
packaged-node smoke test covered the API info endpoint, API documentation route,
and a websocket upgrade.

There are two non-blocking cleanup items to keep visible:

- Jetty 12 EE8 still supports the current `CrossOriginFilter`, but marks it as
  deprecated for future removal. Qortium should replace that CORS setup with a
  small local filter or another Jetty-supported approach in a later API cleanup.
  This does not block the bitcoinj or Netty security work.
- The GitHub workflows now use Java 17, but still use the v3 generation of
  checkout, cache, and setup-java actions. Updating those actions is ordinary CI
  maintenance, not part of the Jetty security fix.

## Resolved Netty Work

Netty `4.1.110.Final` was pulled through gRPC and had several HTTP and DoS
advisories. Qortium moved gRPC to `1.81.0` and pins the Netty family to
`4.1.133.Final` through Netty's Maven BOM so `grpc-netty` and every resolved
Netty module use the same security-patched line.

## Deferred Security Work

These findings need separate work because they are larger than simple dependency
patches:

- bitcoinj `0.16.3` has a script-verification advisory fixed in bitcoinj
  `0.17.1`, but a dry-run upgrade showed package and API movement for core types
  such as `Coin`, `ECKey`, `Address`, and `Sha256Hash`. This should be a
  dedicated cross-chain compatibility migration or a separate decision to remove
  inherited cross-chain surfaces.

## Review Artifacts

The local scan artifacts were generated under `target/`:

- `target/runtime-dependencies.txt`
- `target/osv-vulns.json`
- `target/osv-vuln-details.json`
- `target/runtime-dependencies-after-jetty12.txt`
- `target/osv-vulns-after-jetty12.json`
- `target/runtime-dependencies-after-netty.txt`
- `target/osv-vulns-after-netty.json`

Those files are build artifacts, not tracked project history. This document is
the durable summary of the review.
