#!/usr/bin/env node
/**
 * Regression tests for the read-only qdnRequest bridge in
 * src/main/resources/q-apps/q-apps.js.
 *
 * That bridge is injected into pages served by a PUBLIC gateway node, where
 * there is no signed-in account and no key material. Its contract is that every
 * action resolves to a same-origin GET, so it grants nothing a direct browser
 * fetch of the same endpoints would not.
 *
 * These tests load the bridge with a stubbed window/fetch and assert the parts
 * that are easy to regress silently: which actions are reachable, that request
 * values cannot escape the intended path, and that write/wallet actions stay
 * refused.
 *
 * Standalone by design - Core has no JS test runner. Run with:
 *   node tools/test-q-apps-gateway.js
 */

const fs = require("fs");
const path = require("path");

const SOURCE = path.join(__dirname, "..", "src", "main", "resources", "q-apps", "q-apps.js");
const ORIGIN = "https://gateway.example";

let passed = 0;
let failed = 0;

function check(name, condition, detail) {
  if (condition) {
    passed++;
    console.log("  PASS " + name);
  } else {
    failed++;
    console.log("  FAIL " + name + (detail ? " -> " + detail : ""));
  }
}

function response(options) {
  const status = options.status === undefined ? 200 : options.status;
  const bytes = options.bytes || Buffer.from(options.body || "");
  const headerValues = {};
  for (const [name, value] of Object.entries(options.headers || {}))
    headerValues[name.toLowerCase()] = String(value);
  if (options.json !== undefined) {
    headerValues["content-type"] = "application/json";
  }
  const body = options.json === undefined ? bytes : Buffer.from(JSON.stringify(options.json));
  if (headerValues["content-length"] === undefined)
    headerValues["content-length"] = String(body.byteLength);

  return {
    headers: { get: (name) => headerValues[String(name).toLowerCase()] || null },
    text: () => Promise.resolve(body.toString("utf8")),
    arrayBuffer: () =>
      Promise.resolve(body.buffer.slice(body.byteOffset, body.byteOffset + body.byteLength)),
    ok: status >= 200 && status < 300,
    status: status,
    statusText: options.statusText || "",
  };
}

// Load only the bridge IIFE; the rest of the file needs a full DOM.
function loadBridge() {
  const src = fs.readFileSync(SOURCE, "utf8");
  const start = src.indexOf("(function installReadOnlyQdnBridge()");

  if (start === -1) throw new Error("installReadOnlyQdnBridge not found in " + SOURCE);

  const fetched = [];
  let responder = () => response({ json: { status: "READY" } });

  global.window = { location: { origin: ORIGIN } };
  global.fetch = function (url, options) {
    fetched.push(String(url));
    return Promise.resolve(responder(String(url), options || {}));
  };

  eval(src.slice(start));

  return {
    qdnRequest: global.window.qdnRequest,
    fetched: fetched,
    setResponder: (nextResponder) => {
      responder = nextResponder;
    },
  };
}

async function main() {
  const { qdnRequest, fetched, setResponder } = loadBridge();
  const lastUrl = () => fetched[fetched.length - 1];

  console.log("\n[1] read-only actions resolve to their documented endpoints");
  fetched.length = 0;
  await qdnRequest({ action: "SEARCH_QDN_RESOURCES", service: "APP", limit: 5 });
  check(
    "SEARCH_QDN_RESOURCES hits /arbitrary/resources/search",
    lastUrl().includes("/arbitrary/resources/search?") &&
      lastUrl().includes("service=APP") &&
      lastUrl().includes("limit=5"),
    lastUrl(),
  );

  await qdnRequest({ action: "GET_NAME_DATA", name: "Alice" });
  check("GET_NAME_DATA hits /names/{name}", lastUrl().endsWith("/names/Alice"), lastUrl());

  await qdnRequest({ action: "SEARCH_CHAT_MESSAGES", groupId: 0 });
  check("SEARCH_CHAT_MESSAGES hits /chat/messages", lastUrl().includes("/chat/messages?txGroupId=0"), lastUrl());

  console.log("\n[2] parameters are accepted nested in payload (Qortium Home parity)");
  await qdnRequest({ action: "SEARCH_QDN_RESOURCES", payload: { service: "APP" } });
  check("payload nesting is honoured", lastUrl().includes("service=APP"), lastUrl());

  console.log("\n[3] a gateway has no selected account, so an address is required");
  for (const action of ["GET_BALANCE", "GET_ACCOUNT_DATA", "GET_ACCOUNT_NAMES", "GET_ACTIVE_CHATS"]) {
    try {
      await qdnRequest({ action: action });
      check(action + " without address is refused", false);
    } catch (e) {
      check(action + " without address is refused", /Address is required/.test(e.message), e.message);
    }
  }
  await qdnRequest({ action: "GET_BALANCE", address: "Qabc" });
  check("GET_BALANCE with an address works", lastUrl().endsWith("/addresses/balance/Qabc"), lastUrl());

  console.log("\n[4] request values cannot escape the intended path");
  const injections = [
    ["service traversal", { action: "GET_QDN_RESOURCE_STATUS", service: "../../../admin/settings", name: "x" }],
    ["service query splice", { action: "GET_QDN_RESOURCE_METADATA", service: "APP?evil=1", name: "x" }],
    ["service traversal on fetch", { action: "FETCH_QDN_RESOURCE", service: "../../admin/status", name: "x" }],
    ["pre-encoded traversal", { action: "GET_QDN_RESOURCE_STATUS", service: "..%2f..%2fadmin", name: "x" }],
  ];
  for (const [label, request] of injections) {
    fetched.length = 0;
    try {
      await qdnRequest(request);
      check(label + " is rejected", false, "FETCHED " + lastUrl());
    } catch (e) {
      check(label + " is rejected", /service is invalid/.test(e.message), e.message);
    }
  }

  console.log("\n[5] write, wallet and off-origin actions stay refused");
  const refused = [
    "PUBLISH_QDN_RESOURCE",
    "DELETE_QDN_RESOURCE",
    "JOIN_GROUP",
    "VOTE_ON_POLL",
    "SEND_COIN",
    "START_MINTING",
    "SIGN_TRANSACTION",
    "DECRYPT_DATA",
    "GET_USER_ACCOUNT",
    "GET_USER_WALLET",
    "SEARCH_PRIVATE_GROUP_CHAT_MESSAGES",
    "SEARCH_QORTAL_RESOURCES",
    "GET_QORT_BALANCE",
    "GET_MARKET_PRICES",
    "UPDATE_NODE_SETTINGS",
    "RESTART_NODE",
  ];
  for (const action of refused) {
    try {
      await qdnRequest({ action: action });
      check(action + " is refused", false);
    } catch (e) {
      check(action + " is refused", /not available in read-only gateway mode/.test(e.message), e.message);
    }
  }

  console.log("\n[6] resource URLs stay on the serving origin");
  const url = await qdnRequest({ action: "GET_QDN_RESOURCE_URL", service: "APP", name: "Recipes" });
  check("gateway resource URL is same-origin", url.startsWith(ORIGIN + "/APP/Recipes"), url);

  console.log("\n[7] SHOW_ACTIONS matches what is actually dispatchable");
  const advertised = await qdnRequest({ action: "SHOW_ACTIONS" });
  check("advertises SEARCH_QDN_RESOURCES", advertised.indexOf("SEARCH_QDN_RESOURCES") !== -1);
  check("does not advertise PUBLISH_QDN_RESOURCE", advertised.indexOf("PUBLISH_QDN_RESOURCE") === -1);

  // Anything advertised must actually dispatch. A mismatch here means an app
  // discovered an action and then got a refusal for it.
  const unimplemented = [];
  for (const action of advertised) {
    if (action === "FETCH_NODE_API") continue; // needs a path; covered by its own tests
    try {
      await qdnRequest({ action: action });
    } catch (e) {
      if (/not available in read-only gateway mode/.test(e.message)) unimplemented.push(action);
    }
  }
  check(
    "every advertised action has a handler",
    unimplemented.length === 0,
    unimplemented.join(", "),
  );

  console.log("\n[8] avatar actions preserve Home's public read contract");
  check(
    "advertises both avatar reads",
    advertised.includes("FETCH_ACCOUNT_AVATAR") && advertised.includes("FETCH_GROUP_AVATAR"),
  );

  try {
    await qdnRequest({ action: "FETCH_ACCOUNT_AVATAR" });
    check("account avatar requires an explicit address", false);
  } catch (e) {
    check("account avatar requires an explicit address", /Address is required/.test(e.message), e.message);
  }

  const png = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x00]);
  setResponder((url) => {
    if (url.endsWith("/addresses/Qabc/avatar/info"))
      return response({ json: { service: "THUMBNAIL", name: "Alice", identifier: "avatar-v2" } });
    if (url.endsWith("/addresses/Qabc/avatar"))
      return response({
        bytes: png,
        headers: {
          "content-type": "application/octet-stream",
          "x-qortium-avatar-service": "THUMBNAIL",
          "x-qortium-avatar-name": "Alice",
          "x-qortium-avatar-identifier": "avatar-v2",
        },
      });
    return response({ status: 404 });
  });
  const accountAvatar = await qdnRequest({ action: "FETCH_ACCOUNT_AVATAR", address: "Qabc" });
  check(
    "account pointer avatar returns bounded base64 and descriptor",
    accountAvatar.address === "Qabc" &&
      accountAvatar.encoding === "base64" &&
      accountAvatar.contentType === "image/png" &&
      accountAvatar.body === png.toString("base64") &&
      accountAvatar.source === "POINTER" &&
      accountAvatar.descriptor.identifier === "avatar-v2",
    JSON.stringify(accountAvatar),
  );

  setResponder((url) => {
    if (url.endsWith("/groups/7/avatar/info"))
      return response({ json: { service: "THUMBNAIL", name: "GroupArtist", identifier: "" } });
    if (url.endsWith("/groups/7/avatar"))
      return response({ status: 202, headers: { "retry-after": "2" } });
    return response({ status: 404 });
  });
  const pendingGroup = await qdnRequest({ action: "FETCH_GROUP_AVATAR", payload: { txGroupId: 7 } });
  check(
    "group pointer avatar preserves pending state",
    pendingGroup.groupId === 7 &&
      pendingGroup.status === "PENDING" &&
      pendingGroup.retryAfterSeconds === 2 &&
      pendingGroup.source === "POINTER" &&
      pendingGroup.descriptor.name === "GroupArtist",
    JSON.stringify(pendingGroup),
  );

  try {
    await qdnRequest({ action: "FETCH_GROUP_AVATAR", groupId: 7.5 });
    check("group avatar rejects fractional ids", false);
  } catch (e) {
    check("group avatar rejects fractional ids", /positive integer/.test(e.message), e.message);
  }

  setResponder((url) => {
    if (url.endsWith("/addresses/Qlegacy/avatar/info")) return response({ status: 404 });
    if (url.endsWith("/names/primary/Qlegacy")) return response({ json: { name: "LegacyUser" } });
    if (url.endsWith("/arbitrary/THUMBNAIL/LegacyUser/avatar?async=true"))
      return response({ bytes: png });
    return response({ status: 404 });
  });
  const legacyAccount = await qdnRequest({
    action: "FETCH_ACCOUNT_AVATAR",
    payload: { address: "Qlegacy" },
  });
  check(
    "account legacy fallback runs only when pointer info is missing",
    legacyAccount.source === "LEGACY" &&
      legacyAccount.descriptor === null &&
      legacyAccount.contentType === "image/png",
    JSON.stringify(legacyAccount),
  );

  let legacyWasFetched = false;
  setResponder((url) => {
    if (url.endsWith("/addresses/Qbroken/avatar/info"))
      return response({ json: { service: "THUMBNAIL", name: "Alice", identifier: "missing" } });
    if (url.endsWith("/addresses/Qbroken/avatar")) return response({ status: 404 });
    if (url.includes("/arbitrary/THUMBNAIL/")) legacyWasFetched = true;
    return response({ status: 404 });
  });
  try {
    await qdnRequest({ action: "FETCH_ACCOUNT_AVATAR", address: "Qbroken" });
    check("an unavailable explicit pointer fails closed", false);
  } catch (e) {
    check(
      "an unavailable explicit pointer fails closed",
      /Avatar request failed with HTTP 404/.test(e.message) && !legacyWasFetched,
      e.message,
    );
  }

  setResponder((url) => {
    if (url.endsWith("/groups/8/avatar/info"))
      return response({ json: { service: "THUMBNAIL", name: "GroupArtist", identifier: "large" } });
    if (url.endsWith("/groups/8/avatar"))
      return response({ bytes: png, headers: { "content-length": "600000" } });
    return response({ status: 404 });
  });
  try {
    await qdnRequest({ action: "FETCH_GROUP_AVATAR", groupId: 8 });
    check("avatar response limits are enforced before buffering", false);
  } catch (e) {
    check(
      "avatar response limits are enforced before buffering",
      /exceeded the 512000 byte limit/.test(e.message),
      e.message,
    );
  }

  let oversizedLegacyFallbackFetched = false;
  setResponder((url) => {
    if (url.endsWith("/addresses/Qlegacylarge/avatar/info")) return response({ status: 404 });
    if (url.endsWith("/names/primary/Qlegacylarge"))
      return response({ json: { name: "LegacyLarge" } });
    if (url.endsWith("/arbitrary/THUMBNAIL/LegacyLarge/avatar?async=true"))
      return response({ bytes: png, headers: { "content-length": "600000" } });
    if (url.endsWith("/arbitrary/THUMBNAIL/LegacyLarge/qortal_avatar?async=true")) {
      oversizedLegacyFallbackFetched = true;
      return response({ bytes: png });
    }
    return response({ status: 404 });
  });
  const legacyAfterOversize = await qdnRequest({
    action: "FETCH_ACCOUNT_AVATAR",
    address: "Qlegacylarge",
  });
  check(
    "account legacy fallback skips an advertised oversized candidate",
    oversizedLegacyFallbackFetched &&
      legacyAfterOversize.source === "LEGACY" &&
      legacyAfterOversize.contentType === "image/png",
    JSON.stringify(legacyAfterOversize),
  );

  console.log("\n[9] gateway identity and node reads are honest and same-origin");
  check(
    "advertises the four additive gateway reads",
    ["GET_HOST_INFO", "GET_MINTING_STATUS", "GET_NODE_INFO", "GET_QDN_RESOURCE_STREAM_URL"].every(
      (action) => advertised.includes(action),
    ),
  );

  const hostInfo = await qdnRequest({ action: "GET_HOST_INFO" });
  check(
    "host info identifies the read-only gateway without claiming Home compatibility",
    hostInfo.hostName === "qortium-gateway" &&
      hostInfo.hostVersion === "1.2.0" &&
      hostInfo.platform === "gateway" &&
      hostInfo.platformVersion === "0.0" &&
      hostInfo.protocol === "qdnRequest" &&
      hostInfo.network === "qortium" &&
      hostInfo.readOnly === true &&
      hostInfo.route.configuredKind === "public" &&
      hostInfo.route.reachable === true &&
      hostInfo.route.revision === "qortium-gateway-read-only-v3",
    JSON.stringify(hostInfo),
  );

  setResponder((url) => {
    if (url.endsWith("/admin/info"))
      return response({ json: { buildVersion: "qortium-test", nodeId: "NodeId" } });
    if (url.endsWith("/admin/status"))
      return response({ json: { height: 123, syncPhase: "SYNCED" } });
    return response({ status: 404 });
  });
  const nodeInfo = await qdnRequest({ action: "GET_NODE_INFO" });
  check(
    "node info resolves through the gateway admin route",
    nodeInfo.buildVersion === "qortium-test" && lastUrl().endsWith("/admin/info"),
    JSON.stringify(nodeInfo),
  );
  const nodeStatus = await qdnRequest({ action: "GET_NODE_STATUS" });
  check(
    "node status resolves through the gateway admin route",
    nodeStatus.height === 123 && lastUrl().endsWith("/admin/status"),
    JSON.stringify(nodeStatus),
  );

  console.log("\n[10] gateway minting status matches Home's public-node contract");
  try {
    await qdnRequest({ action: "GET_MINTING_STATUS" });
    check("minting status requires an explicit address", false);
  } catch (e) {
    check("minting status requires an explicit address", /Address is required/.test(e.message), e.message);
  }
  try {
    await qdnRequest({ action: "GET_MINTING_STATUS", address: "Qabc" });
    check("minting status validates the explicit address", false);
  } catch (e) {
    check("minting status validates the explicit address", /Address is invalid/.test(e.message), e.message);
  }

  const mintingAddress = "QgV4s3xnzLhVBEJxcYui4u4q11yhUHsd9v";
  setResponder((url) => {
    if (url.includes("/addresses/rewardshares?"))
      return response({
        json: [{ mintingAccount: mintingAddress, recipient: mintingAddress }],
      });
    return response({ status: 404 });
  });
  const mintingStatus = await qdnRequest({
    action: "GET_MINTING_STATUS",
    payload: { address: mintingAddress },
  });
  check(
    "minting status reads only the public self-share and withholds node-local state",
    mintingStatus.address === mintingAddress &&
      mintingStatus.hasRewardShare === true &&
      mintingStatus.isMinting === null &&
      mintingStatus.keyOnNode === null &&
      mintingStatus.nodeMintingPossible === null &&
      lastUrl().includes("minters=" + mintingAddress) &&
      lastUrl().includes("recipients=" + mintingAddress) &&
      !lastUrl().includes("mintingaccounts"),
    JSON.stringify(mintingStatus),
  );

  console.log("\n[11] stream URLs preserve Home's media boundary");
  setResponder((url) => {
    if (url.includes("/arbitrary/resource/status/VIDEO/Alice/demo"))
      return response({ json: { status: "READY" } });
    return response({ status: 404 });
  });
  const streamUrl = await qdnRequest({
    action: "GET_QDN_RESOURCE_STREAM_URL",
    service: "VIDEO",
    name: "Alice",
    identifier: "demo",
    path: "clips/intro.mp4?download=false",
  });
  check(
    "stream URL is same-origin and keeps the exact resource path",
    streamUrl === ORIGIN + "/VIDEO/Alice/demo/clips/intro.mp4?download=false",
    streamUrl,
  );

  for (const [label, request, message] of [
    [
      "stream action refuses executable app services",
      { action: "GET_QDN_RESOURCE_STREAM_URL", service: "APP", name: "Alice" },
      /only supports image, audio, video/,
    ],
    [
      "stream action refuses dot path segments",
      { action: "GET_QDN_RESOURCE_STREAM_URL", service: "VIDEO", name: "Alice", path: "../admin/info" },
      /cannot contain \. or \.\./,
    ],
    [
      "stream action refuses backslash paths",
      { action: "GET_QDN_RESOURCE_STREAM_URL", service: "VIDEO", name: "Alice", path: "..\\admin" },
      /cannot contain backslashes/,
    ],
  ]) {
    try {
      await qdnRequest(request);
      check(label, false);
    } catch (e) {
      check(label, message.test(e.message), e.message);
    }
  }

  console.log("\n[12] asset reads preserve Home's validated Core paths");
  check(
    "advertises the three asset reads",
    ["GET_ASSET_INFO", "GET_ASSET_BALANCES", "GET_ASSET_TRANSFERS"].every(
      (action) => advertised.includes(action),
    ),
  );

  setResponder((url) => response({ json: { url: url } }));
  await qdnRequest({ action: "GET_ASSET_INFO", assetId: 5, assetName: "ignored" });
  check("asset id wins over asset name", lastUrl().endsWith("/assets/info?assetId=5"), lastUrl());
  await qdnRequest({ action: "GET_ASSET_INFO", payload: { assetName: "MY ASSET/ONE" } });
  check(
    "asset names are encoded from nested payloads",
    lastUrl().endsWith("/assets/info?assetName=MY%20ASSET%2FONE"),
    lastUrl(),
  );
  await qdnRequest({
    action: "GET_ASSET_BALANCES",
    address: mintingAddress,
    assetId: "5",
    excludeZero: false,
    limit: 0,
  });
  check(
    "asset balance filters stay bounded to their documented query",
    lastUrl().endsWith(
      "/assets/balances?address=" + mintingAddress + "&assetid=5&excludeZero=false&limit=0",
    ),
    lastUrl(),
  );
  await qdnRequest({
    action: "GET_ASSET_TRANSFERS",
    payload: { assetId: 5, address: mintingAddress, limit: 20, reverse: false },
  });
  check(
    "asset transfer filters preserve Home's path contract",
    lastUrl().endsWith(
      "/assets/transfers/5?address=" + mintingAddress + "&limit=20&reverse=false",
    ),
    lastUrl(),
  );

  for (const [label, request, message] of [
    ["asset info requires a selector", { action: "GET_ASSET_INFO" }, /either assetId or assetName/],
    [
      "asset balances require a filter",
      { action: "GET_ASSET_BALANCES" },
      /either an address or an assetId/,
    ],
    [
      "malformed asset ids cannot widen a balance query",
      { action: "GET_ASSET_BALANCES", address: mintingAddress, assetId: "invalid" },
      /non-negative safe integer/,
    ],
    [
      "asset transfers reject fractional ids",
      { action: "GET_ASSET_TRANSFERS", assetId: 1.5 },
      /non-negative safe integer/,
    ],
    [
      "asset reads validate addresses before path construction",
      { action: "GET_ASSET_BALANCES", address: "../admin" },
      /Address is invalid/,
    ],
  ]) {
    try {
      await qdnRequest(request);
      check(label, false);
    } catch (e) {
      check(label, message.test(e.message), e.message);
    }
  }

  console.log("\n[13] identity resolution is validated, ordered and concurrency-bounded");
  check("advertises RESOLVE_IDENTITIES", advertised.includes("RESOLVE_IDENTITIES"));
  const identityAlice = "Q" + "A".repeat(24);
  const identityBob = "Q" + "B".repeat(24);
  setResponder((url) => {
    if (url.endsWith("/names/primary/" + identityAlice))
      return response({ json: { name: "Alice", owner: identityAlice } });
    if (url.endsWith("/names/primary/" + identityBob))
      return response({ json: { name: null, owner: identityBob } });
    if (url.endsWith("/names/address/" + identityBob + "?limit=0"))
      return response({ json: [{ name: "Bob", owner: identityBob }] });
    return response({ status: 404 });
  });
  const identities = await qdnRequest({
    action: "RESOLVE_IDENTITIES",
    payload: { addresses: [identityAlice, identityBob, identityAlice] },
  });
  check(
    "identity resolution de-duplicates while preserving first-seen order",
    identities.length === 2 &&
      identities[0].address === identityAlice &&
      identities[0].name === "Alice" &&
      identities[1].address === identityBob &&
      identities[1].name === "Bob",
    JSON.stringify(identities),
  );
  check(
    "identity avatar hints remain same-origin and explicitly legacy",
    identities[0].avatarSrc ===
      ORIGIN + "/arbitrary/THUMBNAIL/Alice/avatar?async=true" &&
      identities[0].avatarContract === "LEGACY_NAMED_THUMBNAIL",
    JSON.stringify(identities[0]),
  );

  const identityAlphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
  const concurrentAddresses = Array.from(
    { length: 12 },
    (_value, index) => "Q" + "A".repeat(22) + identityAlphabet[index] + "B",
  );
  let activeIdentityReads = 0;
  let maximumIdentityReads = 0;
  setResponder((url) => {
    if (url.includes("/names/primary/")) {
      activeIdentityReads++;
      maximumIdentityReads = Math.max(maximumIdentityReads, activeIdentityReads);
      return new Promise((resolve) =>
        setTimeout(() => {
          activeIdentityReads--;
          resolve(response({ json: { name: "Resolved" } }));
        }, 2),
      );
    }
    return response({ status: 404 });
  });
  const concurrentIdentities = await qdnRequest({
    action: "RESOLVE_IDENTITIES",
    addresses: concurrentAddresses,
  });
  check(
    "identity fan-out uses at most eight concurrent node reads",
    concurrentIdentities.length === concurrentAddresses.length && maximumIdentityReads === 8,
    "maximum concurrency " + maximumIdentityReads,
  );

  try {
    await qdnRequest({ action: "RESOLVE_IDENTITIES", addresses: ["Q0invalid"] });
    check("identity resolution validates every address", false);
  } catch (e) {
    check("identity resolution validates every address", /Address is invalid/.test(e.message), e.message);
  }

  const tooManyAddresses = Array.from(
    { length: 501 },
    (_value, index) =>
      "Q" +
      "A".repeat(20) +
      identityAlphabet[Math.floor(index / identityAlphabet.length)] +
      identityAlphabet[index % identityAlphabet.length],
  );
  try {
    await qdnRequest({ action: "RESOLVE_IDENTITIES", addresses: tooManyAddresses });
    check("identity resolution enforces the 500 unique-address limit", false);
  } catch (e) {
    check(
      "identity resolution enforces the 500 unique-address limit",
      /at most 500 addresses/.test(e.message),
      e.message,
    );
  }

  console.log("\n[14] cross-chain reads stay passive, strict and gateway-honest");
  check(
    "advertises all four passive cross-chain reads",
    [
      "GET_CROSSCHAIN_BLOCKCHAINS",
      "GET_CROSSCHAIN_SERVER_INFO",
      "GET_FOREIGN_FEE",
      "GET_SERVER_CONNECTION_HISTORY",
    ].every((action) => advertised.includes(action)),
  );

  const coreBlockchains = [
    { name: "BITCOIN", currencyCode: "BTC", walletEnabled: true },
    { name: "PIRATECHAIN", currencyCode: "ARRR", walletEnabled: false },
  ];
  setResponder((url) => {
    if (url.endsWith("/crosschain/blockchains")) return response({ json: coreBlockchains });
    return response({ status: 404 });
  });
  const discoveredBlockchains = await qdnRequest({ action: "GET_CROSSCHAIN_BLOCKCHAINS" });
  check(
    "blockchain discovery returns Core metadata without a synthetic QORT or Home-wallet row",
    discoveredBlockchains.length === 2 &&
      discoveredBlockchains[0].currencyCode === "BTC" &&
      discoveredBlockchains[1].currencyCode === "ARRR" &&
      !discoveredBlockchains.some((blockchain) => blockchain.currencyCode === "QORT"),
    JSON.stringify(discoveredBlockchains),
  );

  const servers = [{ hostName: "electrum.example", port: 50002 }];
  setResponder((url) => {
    if (url.endsWith("/crosschain/arrr/serverinfos"))
      return response({ json: { servers: servers, currentServer: null } });
    if (url.endsWith("/crosschain/ltc/serverconnectionhistory"))
      return response({ json: [{ address: "electrum.example", success: true }] });
    return response({ status: 404 });
  });
  const serverInfo = await qdnRequest({
    action: "GET_CROSSCHAIN_SERVER_INFO",
    payload: { blockchain: "PirateChain" },
  });
  check(
    "server info accepts Home's ARRR alias and unwraps the server array",
    Array.isArray(serverInfo) &&
      serverInfo.length === 1 &&
      serverInfo[0].hostName === "electrum.example" &&
      lastUrl().endsWith("/crosschain/arrr/serverinfos"),
    JSON.stringify(serverInfo),
  );
  const history = await qdnRequest({
    action: "GET_SERVER_CONNECTION_HISTORY",
    coin: "Litecoin",
  });
  check(
    "connection history uses the normalized same-origin Core path",
    history.length === 1 &&
      history[0].success === true &&
      lastUrl().endsWith("/crosschain/ltc/serverconnectionhistory"),
    JSON.stringify(history),
  );

  setResponder((url) => {
    if (url.endsWith("/crosschain/btc/feekb")) return response({ body: "1001" });
    if (url.endsWith("/crosschain/arrr/feerequired")) return response({ body: "12000" });
    return response({ status: 404 });
  });
  const tradeFee = await qdnRequest({ action: "GET_FOREIGN_FEE", coin: "Bitcoin", type: "TRADE" });
  check(
    "fee-per-KB reads preserve the raw value and round the per-byte fee up",
    tradeFee.fee === "0.00000002" &&
      tradeFee.feePerKb === "1001" &&
      lastUrl().endsWith("/crosschain/btc/feekb"),
    JSON.stringify(tradeFee),
  );
  const requiredFee = await qdnRequest({
    action: "GET_FOREIGN_FEE",
    payload: { coin: "ARRR", feeType: "FEECEILING" },
  });
  check(
    "fee-required reads keep Core's atomic-unit value unchanged",
    requiredFee.fee === "12000" &&
      requiredFee.feePerKb === undefined &&
      lastUrl().endsWith("/crosschain/arrr/feerequired"),
    JSON.stringify(requiredFee),
  );

  for (const [label, request, message] of [
    [
      "cross-chain paths reject traversal instead of encoding it",
      { action: "GET_CROSSCHAIN_SERVER_INFO", coin: "../admin" },
      /coin must be one of/,
    ],
    [
      "cross-chain paths reject unsupported Core-only coin aliases",
      { action: "GET_SERVER_CONNECTION_HISTORY", coin: "BCH" },
      /coin must be one of/,
    ],
    [
      "foreign fee reads reject unknown fee modes",
      { action: "GET_FOREIGN_FEE", coin: "BTC", type: "withdraw" },
      /type must be TRADE/,
    ],
  ]) {
    const fetchCount = fetched.length;
    try {
      await qdnRequest(request);
      check(label, false);
    } catch (e) {
      check(label, message.test(e.message) && fetched.length === fetchCount, e.message);
    }
  }

  setResponder(() => response({ body: "0" }));
  try {
    await qdnRequest({ action: "GET_FOREIGN_FEE", coin: "BTC" });
    check("zero foreign fees are rejected before an app can under-fee a trade", false);
  } catch (e) {
    check(
      "zero foreign fees are rejected before an app can under-fee a trade",
      /must be greater than zero/.test(e.message),
      e.message,
    );
  }

  console.log("\n=== " + passed + " passed, " + failed + " failed ===");
  process.exit(failed ? 1 : 0);
}

main().catch((e) => {
  console.error("harness error: " + (e && e.stack ? e.stack : e));
  process.exit(1);
});
