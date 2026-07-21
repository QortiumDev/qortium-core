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

// Load only the bridge IIFE; the rest of the file needs a full DOM.
function loadBridge() {
  const src = fs.readFileSync(SOURCE, "utf8");
  const start = src.indexOf("(function installReadOnlyQdnBridge()");

  if (start === -1) throw new Error("installReadOnlyQdnBridge not found in " + SOURCE);

  const fetched = [];

  global.window = { location: { origin: ORIGIN } };
  global.fetch = function (url) {
    fetched.push(String(url));
    return Promise.resolve({
      headers: { get: (header) => (header === "content-type" ? "application/json" : null) },
      text: () => Promise.resolve(JSON.stringify({ status: "READY" })),
      ok: true,
      status: 200,
      statusText: "OK",
    });
  };

  eval(src.slice(start));

  return { qdnRequest: global.window.qdnRequest, fetched: fetched };
}

async function main() {
  const { qdnRequest, fetched } = loadBridge();
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

  console.log("\n=== " + passed + " passed, " + failed + " failed ===");
  process.exit(failed ? 1 : 0);
}

main().catch((e) => {
  console.error("harness error: " + (e && e.stack ? e.stack : e));
  process.exit(1);
});
