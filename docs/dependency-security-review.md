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

The two non-blocking cleanup items from the migration were later resolved:

- Qortium replaced Jetty's deprecated `CrossOriginFilter` with a small local
  servlet filter while preserving the existing permissive CORS behavior.
- The GitHub workflows now use Java 17 and current major versions of checkout,
  cache, and setup-java.

## Resolved Netty Work

Netty `4.1.110.Final` was pulled through gRPC and had several HTTP and DoS
advisories. Qortium moved gRPC to `1.81.0` and pins the Netty family to
`4.1.133.Final` through Netty's Maven BOM so `grpc-netty` and every resolved
Netty module use the same security-patched line.

## Resolved Bitcoinj Work

bitcoinj `0.16.3` had a script-verification advisory fixed in bitcoinj
`0.17.1`. Qortium moved to bitcoinj `0.17.1` and updated the cross-chain code
for bitcoinj's relocated base, crypto, address, script, transaction, and network
parameter APIs while preserving the existing Bitcoiny HTLC, transaction-builder,
deterministic-wallet, and Pirate Chain compatibility behavior.

The upgrade also removes bitcoinj's old transitive OkHttp `3.14.9` and Okio
`1.17.2` runtime path. The refreshed OSV batch query for the resolved runtime
tree produced no vulnerable dependency entries.

## Current Security Monitoring Status

The follow-up security automation is active on GitHub. Dependabot watches Maven
and GitHub Actions dependencies, Dependabot security updates are enabled, pull
requests get OSV comparison scanning, and the active branch gets a scheduled
OSV source scan.

At this checkpoint, GitHub Dependabot alerts and GitHub code-scanning alerts are
clear. The latest local source scan with `osv-scanner scan source -r
--no-resolve .` also reports no issues, and the local Grype directory scan
reports no vulnerabilities.

Qortium's latest stable maintenance batch moved these dependencies and build
plugins onto current supported baselines:

- Log4j family: `2.26.0`
- jsoup: `1.22.2`
- Apache Commons Net: `3.13.0`
- Maven Jar Plugin: `3.5.0`
- Maven Surefire Plugin: `3.5.5`

The local OSV source scan currently uses `--no-resolve` because full Maven
resolution still trips over the inherited
`com.github.QuickMythril:AT:1b731d1` dependency, which is not available from a
normal Maven repository. The GitHub OSV workflow remains useful because it
compares dependency manifests and lockfile-style inputs without requiring that
local full-resolution path to succeed.

## Review Artifacts

The local scan artifacts were generated under `target/`:

- `target/runtime-dependencies.txt`
- `target/osv-vulns.json`
- `target/osv-vuln-details.json`
- `target/runtime-dependencies-after-jetty12.txt`
- `target/osv-vulns-after-jetty12.json`
- `target/runtime-dependencies-after-netty.txt`
- `target/osv-vulns-after-netty.json`
- `target/runtime-dependencies-after-bitcoinj.txt`
- `target/osv-vulns-after-bitcoinj.json`

Those files are build artifacts, not tracked project history. This document is
the durable summary of the review.
