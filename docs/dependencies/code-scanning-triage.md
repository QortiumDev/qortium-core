# Code Scanning Triage

This document records the Qortium Core code-scanning triage checkpoint after
the first security-hardening pass on the `main` branch.

The goal is to keep the remaining GitHub CodeQL alerts understandable and
actionable. The remaining count should not be treated as one generic bug list:
some alerts point at intended QDN, local-file, or compatibility behavior, and
some should stay open until a larger design decision is made.

## Current Snapshot

Snapshot date: 2026-06-01

Source: GitHub code scanning, open alerts on `QortiumDev/qortium-core`.

Remaining open CodeQL alerts:

- 26 `java/path-injection` alerts
- 12 `java/xss` alerts
- 1 `java/insecure-trustmanager` alert

These are all high-severity scanner findings, but they do not all represent
the same operational risk in Qortium. The sections below group the findings by
the behavior they protect.

## Triage Principles

- Do not change QDN web-serving behavior only to make a scanner count drop.
- Do not remove local upload, compression, encryption, or validation paths that
  are core to publishing QDN resources without a replacement design.
- Prefer fixing concrete unsafe boundaries over suppressing alerts.
- Prefer documenting intentional behavior before dismissing alerts in GitHub.
- When an alert is dismissed, include a short dismissal comment that points back
  to this document and the specific behavior being accepted.

## Path-Injection Alerts

The remaining path-injection alerts mostly come from local resource publishing,
QDN validation, archive creation, and shared filesystem helpers.

### Local QDN Publishing Paths

Alerts:

- `ArbitraryDataFile`: 47, 48, 49
- `ArbitraryDataWriter`: 65, 66, 68, 69
- `Service`: 67, 70, 71, 72

Triage status: intended behavior, with boundaries already tightened in earlier
hardening work.

QDN publishing accepts a user-selected local file or directory, validates it
against the selected service rules, and then copies, compresses, encrypts, or
splits that resource into Core-managed storage. CodeQL sees the original local
path as user-controlled, which is correct, but the workflow is expected for a
node operator or authenticated local API caller publishing a resource.

These alerts should not be fixed by blocking user-selected local paths. Any
larger change here should be a product/API design change, such as a dedicated
upload-staging area or stricter UI-managed upload model.

Dismissal candidate after review: yes, where the alert is clearly tied to
intentional local QDN publishing.

### ZIP Compression Paths

Alerts:

- `ZipUtils`: 108, 110, 119

Triage status: intended behavior for local QDN archive creation.

ZIP compression reads a user-selected local file or directory and writes it
into an archive for QDN publishing. Earlier hardening added validation for ZIP
entry names and shared-guard extraction paths; the remaining findings are on
the compression-side source path and file reads.

These alerts should not be fixed by preventing local compression. Future work
should focus on preserving safe archive entry names and preventing archive
extraction escapes, not removing local archive creation.

Dismissal candidate after review: yes, if the alert is only the local
compression source path.

### Shared Filesystem Helpers

Alerts:

- `FilesystemUtils`: 94, 95, 96, 97, 102, 103, 104, 105, 106, 107

Triage status: mixed; review case by case before dismissal.

Several alerts are inside helper methods whose purpose is to validate,
normalize, move, inspect, or read filesystem paths. Some helpers are themselves
guardrails used by later hardening work. Other helpers operate on paths that
must already have been validated by their callers.

Before dismissing these alerts, confirm whether the helper is:

- a boundary-check helper used to keep later file access inside a base
  directory
- a low-level file utility that requires caller-side validation
- a cleanup/move helper limited to Core data and temporary directories

Dismissal candidate after review: yes for helper-internal false positives or
intentional low-level utilities; otherwise leave open until the caller boundary
is clear.

### Crypto File Helpers

Alerts:

- `AES`: 73
- `Crypto`: 77

Triage status: low-level utility boundary; caller validation matters.

These helpers encrypt, decrypt, hash, or digest files by path. They are not the
right layer to decide whether a path is allowed. The safer long-term pattern is
for each API or workflow caller to validate the path before it reaches these
helpers.

Dismissal candidate after review: yes if the alert is only the low-level helper
and every reachable caller has an appropriate boundary.

## XSS Alerts

The remaining XSS alerts mostly point at code that intentionally serves web
content, proxied developer content, or downloadable generated content.

### QDN Web Rendering

Alerts:

- `DomainMapResource`: 12, 13
- `GatewayResource`: 14, 15
- `ArbitraryDataRenderer`: 23

Triage status: intended QDN web-serving behavior.

QDN website and app rendering exists to serve user-published web content.
Escaping or forcing these responses to plain text would break the product. The
security boundary should remain around routing, local-loopback restrictions,
content-type handling, origin behavior, and Q-App permissions rather than
escaping the served page body.

Dismissal candidate after review: yes, with a dismissal comment explaining that
QDN web rendering intentionally serves user-published web content.

### Developer Proxy

Alerts:

- `DevProxyServerResource`: 16, 17, 18, 19

Triage status: intended developer-tool behavior with explicit local enablement.

The developer proxy relays local development server content through the Core
API environment so Q-App behavior can be tested. It intentionally writes
proxied HTML and non-HTML responses back to the caller. The proxy is disabled
by default behind `devProxyEnabled`, its start/stop endpoints require the API
key, and its source remains constrained to loopback targets. The default HTML
CSP no longer allows `unsafe-eval`; `devProxyUnsafeEvalEnabled` is available as
an explicit development compatibility setting.

Dismissal candidate after review: yes, with a dismissal comment explaining that
the proxy is a disabled-by-default local developer tool that intentionally
forwards trusted local development content.

### Cross-Chain Ledger CSV

Alerts:

- `CrossChainResource`: 20, 21, 22

Triage status: partially hardened; servlet response sink remains.

The cross-chain ledger endpoint generates CSV from local trade data. Qortium
now escapes ledger string fields before writing them into CSV rows, including
commas, quotes, newlines, and spreadsheet formula prefixes. The remaining
alerts are on the servlet response return/write path, which CodeQL still treats
as a browser-facing sink.

Dismissal candidate after review: yes for the remaining sink alerts if the
generated CSV content and attachment filename boundaries are accepted as
sufficient. Avoid touching the response sink only to add headers, because that
can make existing alerts fail pull-request checks without changing the risk.

## Insecure Trust Manager Alert

Alert:

- `TrustlessSSLSocketFactory`: 11

Triage status: intentional ElectrumX compatibility behavior; needs an explicit
risk decision.

Many ElectrumX servers use self-signed certificates. The current cross-chain
client uses a trust manager that allows those SSL connections while still
enabling modern TLS protocol versions. This is compatibility behavior inherited
from the cross-chain stack, not an accidental use of a permissive trust manager.

Future design options include:

- keeping the current compatibility behavior and documenting it as accepted
  risk
- adding an optional strict-certificate mode
- adding pinned certificate or fingerprint support for configured servers

Dismissal candidate after review: yes only if Qortium accepts the compatibility
risk for the current release line.

## Recommended GitHub Alert Handling

Use this order before dismissing alerts:

1. Confirm the alert still maps to one of the grouped behaviors above.
2. Confirm there is no narrow code fix that preserves expected behavior.
3. If the alert is intentional or accepted risk, dismiss it with a comment that
   names the behavior and references this document.
4. Leave alerts open if they need a future design decision or caller-boundary
   review.

Recommended dismissal reasons:

- Use `false positive` only when the alert is inside a helper that is itself a
  guardrail or when the scanner cannot see an existing validation boundary.
- Use `won't fix` when the alert describes intentional QDN web serving,
  developer proxy behavior, local QDN publishing, or ElectrumX compatibility.
- Do not use `used in tests` for these remaining production alerts.

## Follow-Up Work

The next useful work is triage, not alert-count churn:

- Decide which QDN web-rendering and developer-proxy alerts are accepted
  product behavior.
- Decide whether the ElectrumX trust manager stays compatibility-first or gets
  a stricter optional mode.
- Review `FilesystemUtils` helper alerts case by case to separate helper-level
  false positives from caller-boundary gaps.
- Avoid changing alert-adjacent servlet response lines unless the change fixes
  the underlying behavior, because CodeQL can treat existing alerts on changed
  lines as new pull-request failures.
