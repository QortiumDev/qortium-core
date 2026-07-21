# Dependency Security Review

This note records the first Qortium dependency security review for the
`qortium-6.1.4` baseline.

The review used Maven's resolved runtime dependency tree and the OSV advisory
database. OWASP Dependency-Check was also attempted, but the no-key NVD sync was
rate-limited before it could produce a reliable report.

## Resolved Direct Dependency Cleanup

These findings were handled with narrow dependency maintenance and no planned
API, consensus, transaction, or settings changes:

- Apache Log4j moved to `2.26.0`.
- Apache Tika Core moved to `3.2.2`.
- Apache Commons Lang moved to `3.20.0`.
- Swagger API moved to `2.2.50`, which moves the inherited Jackson path onto a
  fixed line.
- `json-simple` still provides the simple JSON API Qortium uses, but its old
  JUnit transitive dependency is excluded from compile and runtime scope. JUnit
  remains only as an explicit test dependency.

Qortium also applied a conservative stable maintenance batch after the direct
security fixes:

- Apache Commons Compress: `1.28.0`
- Apache Commons IO: `2.22.0`
- Apache Commons Text: `1.15.0`
- Guava: `33.6.0-jre`
- ICU4J: `78.3`
- java-diff-utils: `4.17`
- json.org: `20251224`
- Maven Build Helper Plugin: `3.6.1`
- Maven Shade Plugin: `3.6.2`
- Swagger UI: `5.32.5`
- XZ: `1.12`

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
OSV scan.

At this checkpoint, GitHub Dependabot alerts and GitHub code-scanning alerts are
clear. The latest local Maven-resolved OSV scan reports no unsuppressed issues,
and the local Grype directory scan reports no vulnerabilities.

Jackson's BOM and resolved databind/core family are now at `2.22.1`, which the
GHSA-5jmj-h7xm-6q6v / CVE-2026-54515 advisory identifies as fixed. The CVE
ignore remains only because `osv-scanner 2.3.8` still flags the fixed Maven
artifact while the GitHub/OSV record lacks `first_patched_version` for this
line. Remove the ignore as soon as the upstream metadata stops flagging
`2.22.1`.

The July 2026 maintenance batch also moves Jetty to `12.1.11`, gRPC to
`1.82.2`, Netty to `4.2.16.Final`, and Bouncy Castle to `1.85`. Bouncy
Castle's official download page and Maven Central identify `1.85` as the
current release, although its GitHub `r1rv85` tag and finalized source release
notes were still lagging at review time. Crypto, TLS, networking, packaging,
and dependency-sensitive regression tests passed against the combined stack.

Qortium now vendors its pinned `com.github.QortiumDev:AT:22f9266` artifact in
the tracked `lib/` Maven repository and has removed the remaining JitPack
repository from the main build. Maven can resolve the full dependency graph from
the repository plus Maven Central, including AT.

OSV's native Maven source resolver does not honor Qortium's checked-in
`file:` repository for the vendored AT artifact, so the reliable full-resolution
scan path is:

```bash
mvn -B -U org.cyclonedx:cyclonedx-maven-plugin:2.9.1:makeBom \
  -DskipTests \
  -DoutputFormat=json \
  -DoutputName=bom.cdx \
  --file pom.xml

osv-scanner scan source -L target/bom.cdx.json
```

The GitHub OSV workflows use the same approach: Maven creates a CycloneDX SBOM
from the resolved dependency graph, then OSV scans that SBOM for vulnerable
packages. The generated SBOM includes the vendored AT artifact.

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
- `target/bom.cdx.json`

Those files are build artifacts, not tracked project history. This document is
the durable summary of the review.
