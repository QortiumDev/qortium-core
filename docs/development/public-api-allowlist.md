# Public API allowlist & QDN app access on the preview public network

**Status:** implemented for chat/QDN; public poll builders implemented on a feature branch and pending release/deployment.
**Last updated:** 2026-07-15.
**Owner:** QuickMythril.

This document captures the current behaviour of the Qortium public-API access
control, the gaps that break QDN apps on the public preview network, and the
intended direction for fixing **sending** and **publishing** without ever
handing API keys or private keys to foreign nodes. It exists so the work can be
resumed later without re-doing the investigation.

---

## 1. Problem statement

On mobile (and any desktop Home running in `network` mode, i.e. no local Core),
QDN apps talk to a **public** preview node that the user does not control. Users
report that "chat and other apps don't work on Android."

Crucially: **the public preview network uses *all* previewnet nodes for public
access, not just the two seed VPS nodes.** So it is not enough for the seed
nodes to be configured correctly — the **default user node settings** (what a
normal Home install runs) must also expose whatever the apps legitimately need.
The allowlist was intentionally broken down into individual `METHOD /path`
entries precisely so we can grant exactly the calls we want and nothing more.

Two things must be fixed on the public path:

1. **Sending** (chat, and by extension any transaction) — must work fully.
2. **Publishing** (QDN resource publish) — currently impossible on a public node.

Hard security constraint for both:

> **Never send an API key or a private key to a foreign node.** Signing (and
> proof-of-work) must be done locally in Home. We already do this for keyless
> open-group chat on mobile; the same pattern should be extended.

---

## 2. How "allowed on a public node" is decided (two gates)

A request from a non-local client must pass **both** gates.

### Gate 1 — Core, Jetty layer: `PublicApiAccessHandler`

- `src/main/java/org/qortium/api/PublicApiAccessHandler.java:39` — `isRequestAllowed()`.
- Wired as the **outermost** handler, before routing/CORS/servlets:
  `src/main/java/org/qortium/api/ApiService.java:267`.
- Logic:
  - If the remote IP matches `apiWhitelist` (localhost by default) → allowed (full access).
  - Else if the request carries the node's own API key in `X-API-KEY` and
    `apiKeyRemoteAccessEnabled` (default `true`) → allowed (full access). This is
    the **node-owner remote access** path: an operator who knows their node's API
    key can use it from any IP without whitelisting that IP. Constant-time
    compare; endpoint-level key checks still run afterwards. Does **not** weaken
    the "never send an API key to a *foreign* node" rule — this is for a node the
    caller owns. Note the API port is plain HTTP, so prefer an SSH tunnel/VPN
    when the path crosses untrusted networks.
  - Else if `publicApiWhitelistEnabled == false` → **403** (deny all foreign access).
  - Else allowed **only if** the IP matches `publicApiWhitelist` **and** the
    request's `METHOD /path` matches an entry in `publicApiPaths`
    (`matchesPublicPath`, line 76; supports a trailing `/*` wildcard).
  - Path matching uses `request.getHttpURI().getPath()` — **query string is
    ignored**, so `POST /transactions/process?apiVersion=2` matches the entry
    `POST /transactions/process`.
- This is a **Qortium-specific** addition (not in upstream Qortal).
- Anything not matched gets a hard **403 before reaching any resource class**, so
  per-endpoint `@SecurityRequirement(apiKey)` / `qdnAuthBypass` logic never runs
  for blocked paths.

Settings backing this gate (`src/main/java/org/qortium/settings/Settings.java`):

| Setting | Default (line) | Notes |
|---|---|---|
| `apiWhitelist` | localhost/127.0.0.1/::1 (130) | full-access IPs |
| `publicApiWhitelistEnabled` | `false` (133) | must be `true` to serve any foreign traffic |
| `publicApiWhitelist` | `[]` (134) | preview uses `0.0.0.0/0`, `::/0` |
| `publicApiPaths` | `[]` (135) | the per-call allowlist |
| `apiKeyRemoteAccessEnabled` | `true` | node's own API key bypasses the IP/path gate; file-only (not PATCH-writable) |

Getters at `Settings.java:1666-1675`.

### Gate 2 — Home shell, action layer: `qdn-app-actions.ts`

- Single source of truth: `qortium-home/electron/qdn-app-actions.ts`.
- `QDN_LOCAL_WRITE_ONLY_ACTIONS` = writes that cannot run on a
  public node: `QDN_WRITE_ACTIONS`, `QDN_GROUP_ACTIONS`, `QDN_NAME_ACTIONS`,
  `QDN_PAYMENT_ACTIONS`, `QDN_POLL_ACTIONS`, `QDN_TRUST_ACTIONS`,
  `QDN_LIST_ACTIONS`, `QDN_MINTING_ACTIONS`.
- Poll actions therefore stay out of the static public list but are appended by
  `SHOW_ACTIONS` after Home receives a compatible protocol-v1 response from
  `GET /polls/public/capabilities`.
- `QDN_PUBLIC_NODE_BRIDGE_ACTIONS` (≈ lines 135-137) = everything else.
- `SEND_CHAT_MESSAGE` is **deliberately not** in the write-only set — its keyless
  open-group path signs locally and works against public nodes (comment, lines 120-121).
- Home advertises the filtered list to apps via `SHOW_ACTIONS` when
  `mode === 'network'` (`qortium-home/src/platform.ts:7516`), and exposes
  `IS_USING_PUBLIC_NODE` (`platform.ts:7372`). Well-behaved apps call
  `SHOW_ACTIONS` first and hide unavailable actions (e.g. qortium-chat gates every
  call behind `hasBridgeAction(...)` in `src/coreApi.ts`).

**Implication:** to enable a new public capability we must update **both** gates —
add the exact `METHOD /path` to `publicApiPaths` (Gate 1) and expose the action
only when Home has a keyless local-signing implementation and any required
capability negotiation succeeds (Gate 2).

---

## 3. Where the allowlist lives (config inventory) + the drift bug

All under `qortium-core/preview/`. Public write support consists only of narrow
unsigned builders plus transaction conversion/submission; signing and server-side
MemoryPoW endpoints remain excluded.

| Config file | Used by | GET reads | Chat send | Poll builders | QDN publish |
|---|---|---|---|---|---|
| `settings-preview-seed.json` | seed VPS (apiRestricted) | ✅ | ✅ | ✅ | ✅ unsigned builders only |
| `settings-preview-seed-netcup.json` | seed VPS (netcup) | ✅ | ✅ | ✅ | ✅ unsigned builders only |
| `settings-preview.json` | **default user node** (Home desktop launches this — `qortium-home/electron/core-manager.ts:465`) | ✅ | ✅ | ✅ | ✅ unsigned builders only |
| `settings-preview-local.template.json` | ignored runtime snapshot for local preview nodes | ✅ | ✅ **locally repaired 2026-06-29** | ✅ **locally repaired 2026-07-15** | ✅ **locally repaired 2026-06-29** |
| `settings-preview-local.json` | ignored generated local preview node settings; read at runtime (`core-manager.ts:2216`) | ✅ | ✅ **locally repaired 2026-06-29** | ✅ **locally repaired 2026-07-15** | ✅ **locally repaired 2026-06-29** |

The current GET allowlist (all five files share this set):

```
GET /admin/status, /peers/known, /arbitrary/*, /render/*, /names/*,
    /addresses/*, /blocks/*, /transactions/*, /groups/*, /assets/*,
    /polls/*, /chat/*, /chain-parameters/*, /account-ratings/*,
    /resource-ratings/*, /at/*, /stats/*
```

### Drift bug (fixed 2026-06-29)

This machine's ignored runtime files, `settings-preview-local.template.json` and
`settings-preview-local.json`, are now back in sync with the seed/default configs
for keyless open-group chat sends. They include the three chat-send POSTs added on
**2026-06-19** (commits `cec824321` "allow public open-group chat sends on preview
seeds", `bb3e53816` "match public chat paths on regular preview nodes"):

- `POST /chat/public/build`
- `POST /arbitrary/public/*`
- `POST /transactions/convert`
- `POST /transactions/process`

`PublicApiAccessHandlerTests.testPreviewSettingsExposePublicReadsAndChatSendOnly`
checks the three tracked preview settings files so release/default drift is caught
by tests. The two `settings-preview-local*` files remain intentionally gitignored
runtime files. `MergeSettings` also treats an old no-snapshot `publicApiPaths`
array that is only a subset of the release template as an untouched setting, so
older generated runtime files inherit new default public paths on startup while
custom extra public paths remain preserved. Public QDN publishing is exposed only
through `/arbitrary/public/*` unsigned builders; generic `POST /arbitrary/*`,
`POST /arbitrary/compute`, and `POST /transactions/sign` remain blocked on public
nodes.

---

## 4. Live verification (2026-06-26)

Probed both hardcoded seed nodes
(`qortium-home/src/platform.ts:36-39`: `146.103.42.59:24891`,
`185.207.104.78:24891`). `400` = passed Gate 1 and reached the resource (empty
body rejected); `403` = blocked by Gate 1. Both nodes identical:

| Request | Result | Meaning |
|---|---|---|
| `GET /admin/status` | 200 | allowed |
| `GET /chat/active/{addr}` | 400 | allowed |
| `POST /chat/public/build` | 400 | **allowed** |
| `POST /transactions/process` | 400 | **allowed** |
| `POST /transactions/convert` | 400 | **allowed** |
| `GET /admin/mintingaccounts` | **403** | blocked |
| `POST /arbitrary/WEBSITE/...` (publish) | **403** | blocked |
| `POST /arbitrary/public/WEBSITE/.../base64` | not live-verified yet | intended public unsigned builder |

Conclusion: open-group chat send already works on the **seed** nodes. The
remaining work is (a) make sure the **default user node** config carries the same
(and more), and (b) add publishing.

---

## 5. Current per-app impact on a public node

### Works today (by design)
- All read queries; QDN resource fetch/list/search (`GET /arbitrary/resources/search`,
  covered by `GET /arbitrary/*` — Home uses GET only, no POST-read gap:
  `qortium-home/src/platform.ts:7214-7232`).
- **Open-group chat send** via the keyless path (see §6).
- Read-only apps: `qortium-chain-explorer`, read halves of `walletium` / `qortium-wallet`.

### Blocked today
| App | Needs (blocked) |
|---|---|
| qortium-chat — DMs / private groups | `/chat/private/*` (apiKey). `trySendChatMessageOnNetworkNode` deliberately throws "Direct chat requires a local Core" (`platform.ts:4994`); private groups rejected unless `groupData.isOpen === true` (`platform.ts:5004`, fail-closed). |
| qortium-group-manager | group write actions (CREATE/INVITE/JOIN/KICK/BAN/…) |
| qortium-name-manager | REGISTER/UPDATE/SELL/BUY_NAME |
| qortium-profile-manager, qortium-publish-manager | **PUBLISH_QDN_RESOURCE / DELETE_QDN_RESOURCE** (POST `/arbitrary/*` → 403) |
| walletium / qortium-wallet | SEND_COIN / PAYMENT / TRANSFER_ASSET |
| qortium-minting | START_MINTING + `GET /admin/mintingaccounts` (the latter is try/caught in `qortium-chat/src/coreApi.ts:433-450`, so it degrades gracefully) |

Note: nothing currently calls a blocked endpoint in a way that *hard-crashes* —
write apps are simply read-only on mobile. The likely cause of "chat broken on
Android" reports is **timing**: the chat-send allowlist fix is only from
2026-06-19, so feedback (or a shipped Android build / live node) predating that
would show chat failing. Worth confirming the deployed Android build and live
node versions both postdate 2026-06-19.

---

## 6. The keyless pattern we already have (the template to extend)

Open-group chat send never exposes a key to the foreign node:

1. Home POSTs the unsigned chat tx request to `POST /chat/public/build`
   (`ChatResource.java:1134` `buildPublicChat` — no `@SecurityRequirement`,
   returns Base58 **unsigned** bytes only; explicitly does *not* compute PoW or
   sign). Validation only: `buildChatBytes` → `ChatService.validateForBuild`.
2. Home computes memory-PoW locally — `sendKeylessPublicGroupChatMessage`
   (`qortium-home/src/platform.ts:4859-4896`): `computeChatNonce()` then
   `signChatTransaction()` with a locally-held key.
3. Home submits the signed bytes via `POST /transactions/process?apiVersion=2`.

`POST /chat/compute` (`ChatResource.java:1182`) is apiKey-gated and intentionally
**not** used by this flow (PoW is local). The apiKey-gated `buildChat`
(`ChatResource.java:1128`) is the non-public sibling.

---

## 7. Intent / design goals (to implement later)

1. **Publicnet = every node serves public access.** The default user node config
   (`settings-preview.json`, and whatever Home writes for user nodes) must carry
   the public allowlist, not just the seed VPSes. Keep the allowlist granular
   (per `METHOD /path`).
2. **Fix sending fully** on the public path. Open-group chat already works; audit
   whether anything else users consider "sending" needs a keyless build endpoint.
3. **Fix publishing** on the public path **without keys leaving the device.**
   Mirror the chat pattern: a keyless "build unsigned arbitrary transaction"
   endpoint, with PoW + signing done locally in Home, then submit via
   `POST /transactions/process`.
4. **Never transmit API keys or private keys to foreign nodes.** For `network`
   mode Home already clears the api key (`platform.ts` ~69/816). Any new endpoint
   must require neither.
5. **Local signing in Home is the mechanism.** We already do this for chat on
   mobile; generalise it.

---

## 8. Open design questions / TODO (not yet decided)

- [x] **Drift fix (done 2026-06-29 on this machine):** add the three chat-send
  POSTs to the ignored local runtime files `settings-preview-local.template.json`
  and `settings-preview-local.json` so this local preview node can send chat.
  The tracked source template, `settings-preview.json`, already contained them,
  and `MergeSettings` now repairs old no-snapshot runtime allowlists that only
  lag behind the release template.
- [x] **Default-node allowlist (done 2026-06-29):** tracked preview user/seed
  configs and this machine's ignored local runtime configs include
  `POST /arbitrary/public/*`.
- [x] **Publish build endpoint (done 2026-06-29):** public unsigned QDN
  publish/delete builders live under `/arbitrary/public/*`. Path-based publish,
  generic arbitrary writes, nonce computation, and server-side signing remain
  private/API-key-only.
- [x] **Home Gate 2 changes (done 2026-06-29):** Home advertises QDN write
  actions on public nodes only after routing them through local PoW/signing.
- [x] **Public QDN publish size cap (done 2026-06-29):** public unsigned QDN
  publish builders enforce `publicQdnPublishMaxSize`, defaulting to 100 MiB,
  before building publish transactions.
- [x] **Abuse/rate-limiting (done 2026-07-15):** anonymous remote builders and
  transaction submission now have bounded bodies, per-client token buckets,
  and separate global concurrency ceilings. Expensive QDN work is held to two
  concurrent requests. API-whitelisted and valid API-key callers bypass these
  public limits.
- [ ] **Version check:** confirm the shipped Android build and live nodes are both
  ≥ 2026-06-19 so existing open-group chat actually works for users.

---

## 9. Public poll builders (implemented 2026-07-15, rollout pending)

The original settings-only poll proposal was not sufficient because the normal
`POST /polls/create`, `/vote`, and `/update` resource methods also reject
`apiRestricted` nodes. Core now has dedicated no-key endpoints:

- `GET /polls/public/capabilities` — protocol version, supported Home actions,
  and the active fee-alternative MemoryPoW difficulty.
- `POST /polls/public/create`
- `POST /polls/public/vote`
- `POST /polls/public/update`

The three POST routes share the normal validation and serialization routines and
return unsigned Base58 bytes only. They do not sign, compute MemoryPoW, broadcast,
or write to the repository. Preview settings use exact entries for these three
routes; `/transactions/sign` and `/transactions/mempow/compute` remain blocked.

Home validates every returned poll field against the approved request, requires
zero nonce/fee, computes MemoryPoW locally with a three-minute bound, signs
locally, revalidates the account/node/app context, then uses the existing public
`POST /transactions/process` route. The same hardening verifies public Chat
builder output and the security-critical ARBITRARY resource/method/service/
payment boundary before signing.

Live baseline before rollout (2026-07-15): the Regxa and Netcup seeds returned
HTTP 403 for `POST /polls/vote`, as expected for Core 1.4.2. Public poll support
must not be claimed until the Core change is released and deployed; this API
change does not alter consensus, chain configuration, the database, or peering.

---

## 10. Public-write availability and QDN source attestation (Core 1.5.0)

`PublicApiProtectionHandler` runs after the IP/path access decision and before
Jersey. It affects only anonymous remote calls that reached the public
allowlist; normal API-whitelisted callers and remote callers with the node's
valid API key are exempt. Defaults are deliberately separated by workload:

- lightweight chat, poll, and conversion builders: 256 KiB body, 120 requests
  per minute per remote address with a burst of 30, and 16 active globally;
- signed transaction processing: 256 KiB body, 240 per minute with a burst of
  60, and 32 active globally;
- public QDN build/staged-data work: the existing public publish size plus
  encoding overhead, the builder token bucket, and two active globally.

The settings are writable at runtime so seed operators can tune measured
traffic without a restart. The limiter keys only the direct socket address; it
does not trust client-supplied forwarding headers. Generous bursts reduce the
impact on Tor/I2P users sharing an exit while the global ceilings still bound
node work.

Deployment check (2026-07-15): the public API ports on both the Regxa and
Netcup preview seeds responded directly as Jetty 12.1.11, so Core sees the
actual connecting client in the current topology. An operator who adds a
reverse proxy must preserve that property: do not proxy every request over a
trusted or loopback connection that bypasses Core's public-API gate, and do not
expect Core to trust `X-Forwarded-For`. Either pass connections through without
hiding their source addresses or enforce equivalent body, rate, and concurrency
limits at the proxy edge.

For QDN publish attestation, `GET /arbitrary/public/data/{hash58}` returns the
exact pre-signature artifact whose SHA-256 is present as the transaction's
`DATA_HASH` or `metadataHash`. It accepts only canonical Base58 encoding of 32
bytes, derives the path internally, and serves only the unsigned `data/_misc`
content-addressed store. Because that store is shared with authenticated build
flows, the endpoint additionally requires a ten-minute, in-memory capability
registered only when a public builder returns the corresponding hash. A restart
or expiry requires rebuilding the unsigned transaction. Missing, unregistered,
or non-regular files return 404, a stored hash mismatch returns 409, and
successful responses are raw
`application/octet-stream` bytes with `Content-Length` and `Cache-Control:
no-store`. Signature-keyed QDN data is never exposed by this route.

Home must still independently rehash the response, decrypt the ciphertext with
the secret contained in the unsigned transaction, validate chunk metadata, and
compare the reconstructed plaintext or ZIP tree with the user's selected source
before signing.

---

## 11. Key references

Core:
- `src/main/java/org/qortium/api/PublicApiAccessHandler.java` — Gate 1.
- `src/main/java/org/qortium/api/PublicApiProtectionHandler.java` — public write
  body, rate, and concurrency bounds.
- `src/main/java/org/qortium/api/ApiService.java` — handler wiring.
- `src/main/java/org/qortium/settings/Settings.java` — operator-tunable limits.
- `src/main/java/org/qortium/api/resource/ChatResource.java:1134` (`/public/build`),
  `:1182` (`/compute`), `:275-1010` (apiKey-gated `/private/*`).
- `preview/settings-preview*.json` — the allowlist configs.

Home (`qortium-home`):
- `electron/qdn-app-actions.ts` — Gate 2 action classification.
- `src/platform.ts:36-39` (seed URLs), `:853-875` (URL resolution by mode),
  `:4859-4896` (keyless chat send), `:4983-5031` (`trySendChatMessageOnNetworkNode`),
  `:7372` (`IS_USING_PUBLIC_NODE`), `:7516` (`SHOW_ACTIONS`).
- `electron/core-manager.ts:465` (launches node from `settings-preview.json`),
  `:2216` (reads `settings-preview-local.json`).

Apps: qortium-chat, qortium-help, qortium-trust, qortium-minting,
qortium-group-manager, qortium-name-manager, qortium-profile-manager,
qortium-publish-manager, walletium, qortium-wallet, qortium-chain-explorer.
