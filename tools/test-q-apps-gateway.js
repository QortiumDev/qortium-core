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
  check("render URL is same-origin", url.startsWith(ORIGIN + "/render/APP/Recipes"), url);

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

  console.log("\n=== " + passed + " passed, " + failed + " failed ===");
  process.exit(failed ? 1 : 0);
}

main().catch((e) => {
  console.error("harness error: " + (e && e.stack ? e.stack : e));
  process.exit(1);
});
