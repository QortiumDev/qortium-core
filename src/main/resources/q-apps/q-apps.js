let customQDNHistoryPaths = []; // Array to track visited paths
let currentIndex = -1; // Index to track the current position in the history
let isManualNavigation = true; // Flag to control when to add new paths. set to false when navigating through a back/forward call

function resetVariables() {
  let customQDNHistoryPaths = [];
  let currentIndex = -1;
  let isManualNavigation = true;
}

function getNameAfterService(url) {
  try {
    const parsedUrl = new URL(url);
    const pathParts = parsedUrl.pathname.split("/");

    // Find the index of "WEBSITE" or "APP" and get the next part
    const serviceIndex = pathParts.findIndex(
      (part) => part === "WEBSITE" || part === "APP",
    );

    if (serviceIndex !== -1 && pathParts[serviceIndex + 1]) {
      return pathParts[serviceIndex + 1];
    } else {
      return null; // Return null if "WEBSITE" or "APP" is not found or has no following part
    }
  } catch (error) {
    console.error("Invalid URL provided:", error);
    return null;
  }
}

function parseUrl(url) {
  try {
    const parsedUrl = new URL(url);

    // Check if isManualNavigation query exists and is set to "false"
    const isManual = parsedUrl.searchParams.get("isManualNavigation");

    if (isManual !== null && isManual == "false") {
      isManualNavigation = false;
      // Optional: handle this condition if needed (e.g., return or adjust the response)
    }

    // Remove host UI, identifier, and time queries if they exist
    parsedUrl.searchParams.delete("theme");
    parsedUrl.searchParams.delete("lang");
    parsedUrl.searchParams.delete("textSize");
    parsedUrl.searchParams.delete("accent");
    parsedUrl.searchParams.delete("uiStyle");
    parsedUrl.searchParams.delete("identifier");
    parsedUrl.searchParams.delete("time");
    parsedUrl.searchParams.delete("isManualNavigation");
    // Extract the pathname and remove the prefix if it matches "render/APP" or "render/WEBSITE"
    const path = parsedUrl.pathname.replace(
      /^\/render\/(APP|WEBSITE)\/[^/]+/,
      "",
    );

    // Combine the path with remaining query params (if any)
    return path + parsedUrl.search;
  } catch (error) {
    console.error("Invalid URL provided:", error);
    return null;
  }
}

// Tell the client to open a new tab. Done when an app is linking to another app
function openNewTab(data) {
  window.parent.postMessage(
    {
      action: "SET_TAB",
      requestedHandler: "UI",
      payload: data,
    },
    "*",
  );
}
// sends navigation information to the client in order to manage back/forward navigation
function sendNavigationInfoToParent(isDOMContentLoaded) {
  window.parent.postMessage(
    {
      action: "NAVIGATION_HISTORY",
      requestedHandler: "UI",
      payload: {
        customQDNHistoryPaths,
        currentIndex,
        isDOMContentLoaded: isDOMContentLoaded ? true : false,
      },
    },
    "*",
  );
}

function handleQDNResourceDisplayed(pathurl, isDOMContentLoaded) {
  // make sure that an empty string the root path
  if (pathurl?.startsWith("/render/hash/")) return;
  const path = pathurl || "/";
  if (!isManualNavigation) {
    isManualNavigation = true;
    // If the navigation is automatic (back/forward), do not add new entries
    return;
  }

  // If it's a new path, add it to the history array and adjust the index
  if (customQDNHistoryPaths[currentIndex] !== path) {
    customQDNHistoryPaths = customQDNHistoryPaths.slice(0, currentIndex + 1);

    // Add the new path and move the index to the new position
    customQDNHistoryPaths.push(path);
    currentIndex = customQDNHistoryPaths.length - 1;
    sendNavigationInfoToParent(isDOMContentLoaded);
  } else {
    currentIndex = customQDNHistoryPaths.length - 1;
    sendNavigationInfoToParent(isDOMContentLoaded);
  }

  // Reset isManualNavigation after handling
  isManualNavigation = true;
}

// Request deduplication cache
const requestCache = new Map();
const REQUEST_CACHE_TTL = 5000; // 5 seconds

// Pending request tracking for cleanup
const pendingMessageChannels = new Map();

// Request queue to limit concurrent requests
const MAX_CONCURRENT_REQUESTS = 30;
let activeRequestCount = 0;
const requestQueue = [];

// Set to true to deduplicate identical in-flight qdnRequest calls
const ENABLE_REQUEST_DEDUPLICATION = false;

// Debug logging (set to true to enable)
const DEBUG_REQUESTS = false;

function debugLog(...args) {
  if (DEBUG_REQUESTS) {
    console.log("[q-apps.js]", ...args);
  }
}

function httpGet(url) {
  // Check cache first
  const cached = requestCache.get(url);
  if (cached && Date.now() - cached.timestamp < REQUEST_CACHE_TTL) {
    return cached.data;
  }

  var request = new XMLHttpRequest();
  request.open("GET", url, false);
  request.send(null);

  // Cache the response
  requestCache.set(url, {
    data: request.responseText,
    timestamp: Date.now(),
  });

  return request.responseText;
}

// Async request deduplication
const pendingAsyncRequests = new Map();

function httpGetAsyncWithEvent(event, url) {
  // Check if same request is already pending
  if (pendingAsyncRequests.has(url)) {
    // Attach to existing request instead of creating new one
    pendingAsyncRequests
      .get(url)
      .then((responseText) => {
        handleResponse(event, responseText);
      })
      .catch((error) => {
        let res = {};
        res.error = error;
        handleResponse(event, JSON.stringify(res));
      });
    return;
  }

  // Create new request and cache the promise
  const requestPromise = fetch(url)
    .then((response) => response.text())
    .then((responseText) => {
      // Remove from pending cache
      pendingAsyncRequests.delete(url);

      if (responseText == null) {
        // Pass to parent (UI), in case they can fulfil this request
        event.data.requestedHandler = "UI";
        parent.postMessage(event.data, "*", [event.ports[0]]);
        return null;
      }

      return responseText;
    });

  // Store the promise for deduplication
  pendingAsyncRequests.set(url, requestPromise);

  requestPromise
    .then((responseText) => {
      if (responseText !== null) {
        handleResponse(event, responseText);
      }
    })
    .catch((error) => {
      // Remove from pending cache on error
      pendingAsyncRequests.delete(url);
      let res = {};
      res.error = error;
      handleResponse(event, JSON.stringify(res));
    });
}

function handleResponse(event, response) {
  if (event == null) {
    return;
  }

  // Handle empty or missing responses
  if (response == null || response.length == 0) {
    response = '{"error": "Empty response"}';
  }

  // Parse response
  let responseObj;
  try {
    responseObj = JSON.parse(response);
  } catch (e) {
    // Not all responses will be JSON
    responseObj = response;
  }

  // GET_QDN_RESOURCE_URL has custom handling
  const data = event.data;
  if (data.action == "GET_QDN_RESOURCE_URL") {
    if (
      responseObj == null ||
      responseObj.status == null ||
      responseObj.status == "NOT_PUBLISHED"
    ) {
      responseObj = {};
      responseObj.error = "Resource does not exist";
    } else {
      responseObj = buildResourceUrl(
        data.service,
        data.name,
        data.identifier,
        data.path,
        false,
      );
    }
  }

  // Respond to app
  if (responseObj.error != null) {
    event.ports[0].postMessage({
      result: null,
      error: responseObj,
    });
  } else {
    event.ports[0].postMessage({
      result: responseObj,
      error: null,
    });
  }
}

function encodePathSegment(value) {
  return encodeURIComponent(value == null ? "" : String(value));
}

function encodeResourcePath(path) {
  return String(path)
    .split("/")
    .map((segment) => encodeURIComponent(segment))
    .join("/");
}

function encodeQueryString(query) {
  if (query == null || query === "") {
    return "";
  }

  return String(query)
    .split("&")
    .map((part) => {
      const equalsIndex = part.indexOf("=");
      if (equalsIndex === -1) {
        return encodeURIComponent(part);
      }

      return (
        encodeURIComponent(part.slice(0, equalsIndex)) +
        "=" +
        encodeURIComponent(part.slice(equalsIndex + 1))
      );
    })
    .join("&");
}

function appendResourcePath(url, path) {
  if (path == null || path === "") {
    return url;
  }

  const rawPath = String(path);
  const fragmentIndex = rawPath.indexOf("#");
  const pathAndQuery =
    fragmentIndex === -1 ? rawPath : rawPath.slice(0, fragmentIndex);
  const fragment = fragmentIndex === -1 ? "" : rawPath.slice(fragmentIndex + 1);
  const queryIndex = pathAndQuery.indexOf("?");
  const pathname =
    queryIndex === -1 ? pathAndQuery : pathAndQuery.slice(0, queryIndex);
  const query = queryIndex === -1 ? "" : pathAndQuery.slice(queryIndex + 1);
  const encodedPath = encodeResourcePath(pathname);

  if (encodedPath !== "") {
    url = url.concat((encodedPath.startsWith("/") ? "" : "/") + encodedPath);
  }
  if (query !== "") {
    url = url.concat("?" + encodeQueryString(query));
  }
  if (fragment !== "") {
    url = url.concat("#" + encodeURIComponent(fragment));
  }

  return url;
}

function appendQueryParam(url, key, value) {
  if (value == null) {
    return url;
  }

  const fragmentIndex = url.indexOf("#");
  const baseUrl = fragmentIndex === -1 ? url : url.slice(0, fragmentIndex);
  const fragment = fragmentIndex === -1 ? "" : url.slice(fragmentIndex);
  const queryPrefix = baseUrl.includes("?") ? "&" : "?";
  return (
    baseUrl +
    queryPrefix +
    encodeURIComponent(key) +
    "=" +
    encodeURIComponent(String(value)) +
    fragment
  );
}

function navigateToResource(service, name, identifier, path) {
  const resourceUrl = buildResourceUrl(service, name, identifier, path, true);
  const targetUrl = new URL(resourceUrl, window.location.origin);
  if (targetUrl.origin !== window.location.origin) {
    throw new Error("QDN navigation must stay on the current origin");
  }

  window.location.assign(
    targetUrl.pathname + targetUrl.search + targetUrl.hash,
  );
}

function buildResourceUrl(service, name, identifier, path, isLink) {
  const encodedService = encodePathSegment(service || "WEBSITE");
  const encodedName = encodePathSegment(name);
  const encodedIdentifier =
    identifier != null ? encodePathSegment(identifier) : null;
  let url;

  if (isLink == false) {
    // If this URL isn't being used as a link, then we need to fetch the data
    // synchronously, instead of showing the loading screen.
    url = "/arbitrary/" + encodedService + "/" + encodedName;
    if (encodedIdentifier != null) url = url.concat("/" + encodedIdentifier);
    url = appendQueryParam(url, "filepath", path);
  } else if (_qdnContext == "render") {
    url = "/render/" + encodedService + "/" + encodedName;
    url = appendResourcePath(url, path);
    url = appendQueryParam(url, "identifier", identifier);
  } else if (_qdnContext == "gateway") {
    url = "/" + encodedService + "/" + encodedName;
    if (encodedIdentifier != null) url = url.concat("/" + encodedIdentifier);
    url = appendResourcePath(url, path);
  } else {
    // domainMap only serves websites right now
    url = "/" + encodedName;
    url = appendResourcePath(url, path);
  }

  if (isLink) {
    url = appendQueryParam(url, "theme", _qdnTheme);
    url = appendQueryParam(url, "lang", _qdnLang);
    url = appendQueryParam(url, "textSize", _qdnTextSize);
    url = appendQueryParam(url, "accent", _qdnAccent);
    url = appendQueryParam(url, "uiStyle", _qdnUiStyle);
  }
  return url;
}

function extractComponents(url) {
  if (!url.startsWith("qdn://")) {
    return null;
  }

  url = url.replace(/^(qdn\:\/\/)/, "");
  if (url.includes("/")) {
    let parts = url.split("/");
    const service = parts[0].toUpperCase();
    parts.shift();
    const name = parts[0];
    parts.shift();
    let identifier;

    if (parts.length > 0) {
      identifier = parts[0]; // Do not shift yet
      // Check if a resource exists with this service, name and identifier combination
      const url =
        "/arbitrary/resource/status/" +
        encodePathSegment(service) +
        "/" +
        encodePathSegment(name) +
        "/" +
        encodePathSegment(identifier);
      const response = httpGet(url);
      const responseObj = JSON.parse(response);
      if (responseObj.totalChunkCount > 0) {
        // Identifier exists, so don't include it in the path
        parts.shift();
      } else {
        identifier = null;
      }
    }

    const path = parts.join("/");

    const components = {};
    components["service"] = service;
    components["name"] = name;
    components["identifier"] = identifier;
    components["path"] = path;
    return components;
  }

  return null;
}

function convertToResourceUrl(url, isLink) {
  if (!url.startsWith("qdn://")) {
    return null;
  }
  const c = extractComponents(url);
  if (c == null) {
    return null;
  }

  return buildResourceUrl(c.service, c.name, c.identifier, c.path, isLink);
}

window.addEventListener(
  "message",
  async (event) => {
    if (event == null || event.data == null || event.data.length == 0) {
      return;
    }
    if (event.data.action == null) {
      // This could be a response from the UI
      handleResponse(event, event.data);
    }
    if (
      event.data.requestedHandler != null &&
      event.data.requestedHandler === "UI"
    ) {
      // This request was destined for the UI, so ignore it
      return;
    }

    let url;
    let data = event.data;
    let identifier;
    switch (data.action) {
      case "GET_ACCOUNT_DATA":
        return httpGetAsyncWithEvent(event, "/addresses/" + data.address);

      case "GET_ACCOUNT_NAMES":
        return httpGetAsyncWithEvent(event, "/names/address/" + data.address);

      case "SEARCH_NAMES":
        url = "/names/search?";
        if (data.query != null) url = url.concat("&query=" + data.query);
        if (data.prefix != null)
          url = url.concat("&prefix=" + new Boolean(data.prefix).toString());
        if (data.limit != null) url = url.concat("&limit=" + data.limit);
        if (data.offset != null) url = url.concat("&offset=" + data.offset);
        if (data.reverse != null)
          url = url.concat("&reverse=" + new Boolean(data.reverse).toString());
        return httpGetAsyncWithEvent(event, url);

      case "GET_NAME_DATA":
        return httpGetAsyncWithEvent(event, "/names/" + data.name);

      case "GET_QDN_RESOURCE_URL":
        // Check status first; URL is built and returned automatically after status check
        url = "/arbitrary/resource/status/" + data.service + "/" + data.name;
        if (data.identifier != null) url = url.concat("/" + data.identifier);
        return httpGetAsyncWithEvent(event, url);

      case "LINK_TO_QDN_RESOURCE":
        if (data.service == null) data.service = "WEBSITE"; // Default to WEBSITE

        const nameOfCurrentApp = getNameAfterService(window.location.href);
        // Check to see if the link is an external app. If it is, request that the client opens a new tab instead of manipulating the window's history stack.
        if (nameOfCurrentApp !== data.name) {
          // Attempt to open a new tab and wait for a response
          const navigationPromise = new Promise((resolve, reject) => {
            function handleMessage(event) {
              if (
                event.data?.action === "SET_TAB_SUCCESS" &&
                event.data.payload?.name === data.name
              ) {
                window.removeEventListener("message", handleMessage);
                resolve();
              }
            }

            window.addEventListener("message", handleMessage);

            // Send the message to the parent window
            openNewTab({
              name: data.name,
              service: data.service,
              identifier: data.identifier,
              path: data.path,
            });

            // Set a timeout to reject the promise if no response is received within 200ms
            setTimeout(() => {
              window.removeEventListener("message", handleMessage);
              reject(new Error("No response within 200ms"));
            }, 200);
          });

          // Handle the promise, and if it times out, fall back to the else block
          navigationPromise
            .then(() => {
              console.log("Tab opened successfully");
            })
            .catch(() => {
              console.warn("No response, proceeding with window.location");
              navigateToResource(
                data.service,
                data.name,
                data.identifier,
                data.path,
              );
            });
        } else {
          navigateToResource(
            data.service,
            data.name,
            data.identifier,
            data.path,
          );
        }
        return;

      case "LIST_QDN_RESOURCES":
        url = "/arbitrary/resources?";
        if (data.service != null) url = url.concat("&service=" + data.service);
        if (data.name != null) url = url.concat("&name=" + data.name);
        if (data.identifier != null)
          url = url.concat("&identifier=" + data.identifier);
        if (data.default != null)
          url = url.concat("&default=" + new Boolean(data.default).toString());
        if (data.includeStatus != null)
          url = url.concat(
            "&includestatus=" + new Boolean(data.includeStatus).toString(),
          );
        if (data.includeMetadata != null)
          url = url.concat(
            "&includemetadata=" + new Boolean(data.includeMetadata).toString(),
          );
        if (data.nameListFilter != null)
          url = url.concat("&namefilter=" + data.nameListFilter);
        if (data.followedOnly != null)
          url = url.concat(
            "&followedonly=" + new Boolean(data.followedOnly).toString(),
          );
        if (data.excludeBlocked != null)
          url = url.concat(
            "&excludeblocked=" + new Boolean(data.excludeBlocked).toString(),
          );
        if (data.limit != null) url = url.concat("&limit=" + data.limit);
        if (data.offset != null) url = url.concat("&offset=" + data.offset);
        if (data.reverse != null)
          url = url.concat("&reverse=" + new Boolean(data.reverse).toString());
        return httpGetAsyncWithEvent(event, url);

      case "SEARCH_QDN_RESOURCES":
        url = "/arbitrary/resources/search?";
        if (data.service != null) url = url.concat("&service=" + data.service);
        if (data.query != null) url = url.concat("&query=" + data.query);
        if (data.identifier != null)
          url = url.concat("&identifier=" + data.identifier);
        if (data.name != null) url = url.concat("&name=" + data.name);
        if (data.names != null)
          data.names.forEach((x, i) => (url = url.concat("&name=" + x)));
        if (data.keywords != null)
          data.keywords.forEach((x, i) => (url = url.concat("&keywords=" + x)));
        if (data.title != null) url = url.concat("&title=" + data.title);
        if (data.description != null)
          url = url.concat("&description=" + data.description);
        if (data.prefix != null)
          url = url.concat("&prefix=" + new Boolean(data.prefix).toString());
        if (data.exactMatchNames != null)
          url = url.concat(
            "&exactmatchnames=" + new Boolean(data.exactMatchNames).toString(),
          );
        if (data.default != null)
          url = url.concat("&default=" + new Boolean(data.default).toString());
        if (data.mode != null) url = url.concat("&mode=" + data.mode);
        if (data.minLevel != null)
          url = url.concat("&minlevel=" + data.minLevel);
        if (data.includeStatus != null)
          url = url.concat(
            "&includestatus=" + new Boolean(data.includeStatus).toString(),
          );
        if (data.includeMetadata != null)
          url = url.concat(
            "&includemetadata=" + new Boolean(data.includeMetadata).toString(),
          );
        if (data.nameListFilter != null)
          url = url.concat("&namefilter=" + data.nameListFilter);
        if (data.followedOnly != null)
          url = url.concat(
            "&followedonly=" + new Boolean(data.followedOnly).toString(),
          );
        if (data.excludeBlocked != null)
          url = url.concat(
            "&excludeblocked=" + new Boolean(data.excludeBlocked).toString(),
          );
        if (data.before != null) url = url.concat("&before=" + data.before);
        if (data.after != null) url = url.concat("&after=" + data.after);
        if (data.limit != null) url = url.concat("&limit=" + data.limit);
        if (data.offset != null) url = url.concat("&offset=" + data.offset);
        if (data.reverse != null)
          url = url.concat("&reverse=" + new Boolean(data.reverse).toString());
        return httpGetAsyncWithEvent(event, url);

      case "FETCH_QDN_RESOURCE":
        url = "/arbitrary/" + data.service + "/" + data.name;
        if (data.identifier != null) url = url.concat("/" + data.identifier);
        url = url.concat("?");
        if (data.filepath != null)
          url = url.concat("&filepath=" + data.filepath);
        if (data.rebuild != null)
          url = url.concat("&rebuild=" + new Boolean(data.rebuild).toString());
        if (data.encoding != null)
          url = url.concat("&encoding=" + data.encoding);
        return httpGetAsyncWithEvent(event, url);

      case "GET_QDN_RESOURCE_STATUS":
        url = "/arbitrary/resource/status/" + data.service + "/" + data.name;
        if (data.identifier != null) url = url.concat("/" + data.identifier);
        url = url.concat("?");
        if (data.build != null)
          url = url.concat("&build=" + new Boolean(data.build).toString());
        return httpGetAsyncWithEvent(event, url);

      case "GET_QDN_RESOURCE_PROPERTIES":
        identifier = data.identifier != null ? data.identifier : "default";
        url =
          "/arbitrary/resource/properties/" +
          data.service +
          "/" +
          data.name +
          "/" +
          identifier;
        return httpGetAsyncWithEvent(event, url);

      case "GET_QDN_RESOURCE_METADATA":
        identifier = data.identifier != null ? data.identifier : "default";
        url =
          "/arbitrary/metadata/" +
          data.service +
          "/" +
          data.name +
          "/" +
          identifier;
        return httpGetAsyncWithEvent(event, url);

      case "SEARCH_CHAT_MESSAGES":
        url = "/chat/messages?";
        if (data.before != null) url = url.concat("&before=" + data.before);
        if (data.after != null) url = url.concat("&after=" + data.after);
        if (data.txGroupId != null)
          url = url.concat("&txGroupId=" + data.txGroupId);
        if (data.involving != null)
          data.involving.forEach(
            (x, i) => (url = url.concat("&involving=" + x)),
          );
        if (data.reference != null)
          url = url.concat("&reference=" + data.reference);
        if (data.chatReference != null)
          url = url.concat("&chatreference=" + data.chatReference);
        if (data.hasChatReference != null)
          url = url.concat(
            "&haschatreference=" +
              new Boolean(data.hasChatReference).toString(),
          );
        if (data.encoding != null)
          url = url.concat("&encoding=" + data.encoding);
        if (data.limit != null) url = url.concat("&limit=" + data.limit);
        if (data.offset != null) url = url.concat("&offset=" + data.offset);
        if (data.reverse != null)
          url = url.concat("&reverse=" + new Boolean(data.reverse).toString());
        return httpGetAsyncWithEvent(event, url);

      case "LIST_GROUPS":
        url = "/groups?";
        if (data.limit != null) url = url.concat("&limit=" + data.limit);
        if (data.offset != null) url = url.concat("&offset=" + data.offset);
        if (data.reverse != null)
          url = url.concat("&reverse=" + new Boolean(data.reverse).toString());
        return httpGetAsyncWithEvent(event, url);

      case "GET_BALANCE":
        url = "/addresses/balance/" + data.address;
        if (data.assetId != null) url = url.concat("&assetId=" + data.assetId);
        return httpGetAsyncWithEvent(event, url);

      case "GET_AT":
        url = "/at/" + data.atAddress;
        return httpGetAsyncWithEvent(event, url);

      case "GET_AT_DATA":
        url = "/at/" + data.atAddress + "/data";
        return httpGetAsyncWithEvent(event, url);

      case "LIST_ATS":
        url = "/at/byfunction/" + data.codeHash58 + "?";
        if (data.isExecutable != null)
          url = url.concat("&isExecutable=" + data.isExecutable);
        if (data.limit != null) url = url.concat("&limit=" + data.limit);
        if (data.offset != null) url = url.concat("&offset=" + data.offset);
        if (data.reverse != null)
          url = url.concat("&reverse=" + new Boolean(data.reverse).toString());
        return httpGetAsyncWithEvent(event, url);

      case "FETCH_BLOCK":
        if (data.signature != null) {
          url = "/blocks/signature/" + data.signature;
        } else if (data.height != null) {
          url = "/blocks/byheight/" + data.height;
        }
        url = url.concat("?");
        if (data.includeOnlineSignatures != null)
          url = url.concat(
            "&includeOnlineSignatures=" + data.includeOnlineSignatures,
          );
        return httpGetAsyncWithEvent(event, url);

      case "FETCH_BLOCK_RANGE":
        url = "/blocks/range/" + data.height + "?";
        if (data.count != null) url = url.concat("&count=" + data.count);
        if (data.reverse != null) url = url.concat("&reverse=" + data.reverse);
        if (data.includeOnlineSignatures != null)
          url = url.concat(
            "&includeOnlineSignatures=" + data.includeOnlineSignatures,
          );
        return httpGetAsyncWithEvent(event, url);

      case "SEARCH_TRANSACTIONS":
        url = "/transactions/search?";
        if (data.startBlock != null)
          url = url.concat("&startBlock=" + data.startBlock);
        if (data.blockLimit != null)
          url = url.concat("&blockLimit=" + data.blockLimit);
        if (data.txGroupId != null)
          url = url.concat("&txGroupId=" + data.txGroupId);
        if (data.txType != null)
          data.txType.forEach((x, i) => (url = url.concat("&txType=" + x)));
        if (data.address != null) url = url.concat("&address=" + data.address);
        if (data.confirmationStatus != null)
          url = url.concat("&confirmationStatus=" + data.confirmationStatus);
        if (data.limit != null) url = url.concat("&limit=" + data.limit);
        if (data.offset != null) url = url.concat("&offset=" + data.offset);
        if (data.reverse != null)
          url = url.concat("&reverse=" + new Boolean(data.reverse).toString());
        return httpGetAsyncWithEvent(event, url);

      case "GET_PRICE":
        url = "/crosschain/price/" + data.blockchain + "?";
        if (data.maxtrades != null)
          url = url.concat("&maxtrades=" + data.maxtrades);
        if (data.inverse != null) url = url.concat("&inverse=" + data.inverse);
        return httpGetAsyncWithEvent(event, url);

      case "PERFORMING_NON_MANUAL":
        isManualNavigation = false;
        currentIndex = data.currentIndex;
        return;

      default:
        // Pass to parent (UI), in case they can fulfil this request
        event.data.requestedHandler = "UI";
        parent.postMessage(event.data, "*", [event.ports[0]]);

        return;
    }
  },
  false,
);

/**
 * Listen for and intercept all link click events
 */
function interceptClickEvent(e) {
  var target = e.target || e.srcElement;
  if (target.tagName !== "A") {
    target = target.closest("A");
  }
  if (target == null || target.getAttribute("href") == null) {
    return;
  }
  let href = target.getAttribute("href");
  if (href.startsWith("qdn://")) {
    const c = extractComponents(href);
    if (c != null) {
      qdnRequest({
        action: "LINK_TO_QDN_RESOURCE",
        service: c.service,
        name: c.name,
        identifier: c.identifier,
        path: c.path,
      });
    }
    e.preventDefault();
  } else if (
    href.startsWith("http://") ||
    href.startsWith("https://") ||
    href.startsWith("//")
  ) {
    // Block external links
    e.preventDefault();
  }
}
if (document.addEventListener) {
  document.addEventListener("click", interceptClickEvent);
} else if (document.attachEvent) {
  document.attachEvent("onclick", interceptClickEvent);
}

/**
 * Intercept image loads from the DOM
 */
document.addEventListener("DOMContentLoaded", () => {
  const imgElements = document.querySelectorAll("img");
  imgElements.forEach((img) => {
    let url = img.src;
    const newUrl = convertToResourceUrl(url, false);
    if (newUrl != null) {
      document.querySelector("img").src = newUrl;
    }
  });
});

/**
 * Intercept img src updates
 */
document.addEventListener("DOMContentLoaded", () => {
  const imgElements = document.querySelectorAll("img");
  imgElements.forEach((img) => {
    let observer = new MutationObserver((changes) => {
      changes.forEach((change) => {
        if (change.attributeName.includes("src")) {
          const newUrl = convertToResourceUrl(img.src, false);
          if (newUrl != null) {
            document.querySelector("img").src = newUrl;
          }
        }
      });
    });
    observer.observe(img, { attributes: true });
  });
});

const awaitTimeout = (timeout, reason) =>
  new Promise((resolve, reject) =>
    setTimeout(
      () => (reason === undefined ? resolve() : reject(reason)),
      timeout,
    ),
  );

function getDefaultTimeout(action) {
  if (action != null) {
    // Some actions need longer default timeouts, especially those that create transactions
    switch (action) {
      case "GET_USER_ACCOUNT":
      case "SAVE_FILE":
      case "SIGN_TRANSACTION":
      case "DECRYPT_DATA":
        // User may take a long time to accept/deny the popup
        return 60 * 60 * 1000;

      case "SEARCH_QDN_RESOURCES":
        // Searching for data can be slow, especially when metadata and statuses are also being included
        return 30 * 1000;

      case "FETCH_QDN_RESOURCE":
        // Fetching data can take a while, especially if the status hasn't been checked first
        return 60 * 1000;

      case "PUBLISH_QDN_RESOURCE":
      case "PUBLISH_MULTIPLE_QDN_RESOURCES":
        // Publishing could take a very long time on slow system, due to the proof-of-work computation
        return 60 * 60 * 1000;

      case "SEND_CHAT_MESSAGE":
        // Chat messages rely on PoW computations, so allow extra time
        return 60 * 1000;

      case "CREATE_TRADE_BUY_ORDER":
      case "CREATE_TRADE_SELL_ORDER":
      case "CANCEL_TRADE_SELL_ORDER":
      case "VOTE_ON_POLL":
      case "CREATE_POLL":
      case "UPDATE_POLL":
      case "JOIN_GROUP":
      case "DEPLOY_AT":
      case "SEND_COIN":
        // Allow extra time for other actions that create transactions, even if there is no PoW
        return 5 * 60 * 1000;

      case "GET_WALLET_BALANCE":
        // Getting a wallet balance can take a while, if there are many transactions
        return 2 * 60 * 1000;

      default:
        break;
    }
  }
  return 30 * 1000;
}

/**
 * Process queued requests when a slot becomes available
 */
function processRequestQueue() {
  while (
    activeRequestCount < MAX_CONCURRENT_REQUESTS &&
    requestQueue.length > 0
  ) {
    const queuedRequest = requestQueue.shift();
    queuedRequest.execute();
  }
}

/**
 * Execute a request immediately (bypasses queue)
 * @param {object} request - The request payload
 * @param {number} [effectiveTimeoutMs] - Timeout used for this request (for cleanup); if omitted, cleanup uses getDefaultTimeout(request.action)
 */
function executeQdnRequestImmediate(request, effectiveTimeoutMs) {
  return new Promise((res, rej) => {
    const channel = new MessageChannel();
    const requestId = Math.random().toString(36).substring(2, 15) + Date.now();

    // Track this channel for cleanup (effectiveTimeoutMs used so cleanup respects qdnRequest / qdnRequestWithTimeout timeouts)
    pendingMessageChannels.set(requestId, {
      channel: channel,
      request: request,
      timestamp: Date.now(),
      effectiveTimeoutMs: effectiveTimeoutMs,
    });

    channel.port1.onmessage = ({ data }) => {
      channel.port1.close();
      pendingMessageChannels.delete(requestId);
      activeRequestCount--;
      processRequestQueue(); // Process next queued request

      if (data.error) {
        rej(data.error);
      } else {
        res(data.result);
      }
    };

    // Handle port closure/errors
    channel.port1.onmessageerror = () => {
      channel.port1.close();
      pendingMessageChannels.delete(requestId);
      activeRequestCount--;
      processRequestQueue();
      rej(new Error("MessageChannel error"));
    };

    window.postMessage(request, "*", [channel.port2]);
  });
}

/**
 * Make a QDN app request with no timeout
 * @param {object} request - The request payload
 * @param {number} [effectiveTimeoutMs] - Timeout used for this request (for orphan cleanup only); if omitted, cleanup uses getDefaultTimeout(request.action)
 */
const qdnRequestWithNoTimeout = (request, effectiveTimeoutMs) => {
  return new Promise((res, rej) => {
    const executeRequest = () => {
      activeRequestCount++;
      executeQdnRequestImmediate(request, effectiveTimeoutMs)
        .then(res)
        .catch(rej);
    };

    // If under concurrent limit, execute immediately
    if (activeRequestCount < MAX_CONCURRENT_REQUESTS) {
      executeRequest();
    } else {
      // Queue the request
      requestQueue.push({ execute: executeRequest });
    }
  });
};

// Pending QDN request deduplication
const pendingQdnRequests = new Map();

/**
 * Create a unique key for request deduplication
 */
function getRequestKey(request) {
  // Create a stable key from the request object
  const keyObj = {
    action: request.action,
    // Include key parameters that make requests unique
    service: request.service,
    name: request.name,
    identifier: request.identifier,
    path: request.path,
    address: request.address,
    // For search/query requests
    query: request.query,
    limit: request.limit,
    offset: request.offset,
  };
  return JSON.stringify(keyObj);
}

/**
 * Make a QDN app request with the default timeout (10 seconds)
 */
const qdnRequest = (request) => {
  // Check if identical request is already pending
  if (ENABLE_REQUEST_DEDUPLICATION) {
    const requestKey = getRequestKey(request);
    if (pendingQdnRequests.has(requestKey)) {
      debugLog("Request deduplication hit for:", request.action);
      // Return the existing promise instead of creating a new request
      return pendingQdnRequests.get(requestKey);
    }
  }

  debugLog(
    "New request:",
    request.action,
    "Queue size:",
    requestQueue.length,
    "Active:",
    activeRequestCount,
    request,
  );

  // Create new request promise
  const defaultTimeout = getDefaultTimeout(request.action);
  const requestPromise = Promise.race([
    qdnRequestWithNoTimeout(request, defaultTimeout),
    awaitTimeout(defaultTimeout, "The request timed out"),
  ])
    .then((result) => {
      debugLog("Request completed:", request.action);
      return result;
    })
    .catch((error) => {
      debugLog("Request failed:", request.action, error);
      throw error;
    })
    .finally(() => {
      // Remove from pending cache when done (success or failure)
      if (ENABLE_REQUEST_DEDUPLICATION) {
        pendingQdnRequests.delete(getRequestKey(request));
      }
    });

  // Store in pending cache
  if (ENABLE_REQUEST_DEDUPLICATION) {
    pendingQdnRequests.set(getRequestKey(request), requestPromise);
  }

  return requestPromise;
};

/**
 * Make a QDN app request with a custom timeout, specified in milliseconds
 */
const qdnRequestWithTimeout = (request, timeout) => {
  // Check if identical request is already pending
  if (ENABLE_REQUEST_DEDUPLICATION) {
    const requestKey = getRequestKey(request);
    if (pendingQdnRequests.has(requestKey)) {
      // Return the existing promise instead of creating a new request
      return pendingQdnRequests.get(requestKey);
    }
  }

  // Create new request promise
  const requestPromise = Promise.race([
    qdnRequestWithNoTimeout(request, timeout),
    awaitTimeout(timeout, "The request timed out"),
  ]).finally(() => {
    // Remove from pending cache when done (success or failure)
    if (ENABLE_REQUEST_DEDUPLICATION) {
      pendingQdnRequests.delete(getRequestKey(request));
    }
  });

  // Store in pending cache
  if (ENABLE_REQUEST_DEDUPLICATION) {
    pendingQdnRequests.set(getRequestKey(request), requestPromise);
  }

  return requestPromise;
};

// Clean up channels this long after the request's timeout would have fired
const CLEANUP_BUFFER_MS = 5000;

/**
 * Cleanup orphaned MessageChannels that have been waiting too long.
 * Uses each request's effective timeout (from qdnRequest or qdnRequestWithTimeout) so long-running requests are not closed early.
 */
function cleanupOrphanedChannels() {
  const now = Date.now();
  let cleanedCount = 0;

  for (const [requestId, data] of pendingMessageChannels.entries()) {
    const maxAge =
      (data.effectiveTimeoutMs != null
        ? data.effectiveTimeoutMs
        : getDefaultTimeout(data.request.action)) + CLEANUP_BUFFER_MS;
    if (now - data.timestamp > maxAge) {
      console.warn(
        "Cleaning up orphaned MessageChannel for request:",
        data.request.action,
        "Age:",
        Math.round((now - data.timestamp) / 1000),
        "seconds",
      );
      try {
        data.channel.port1.close();
      } catch (e) {
        // Port may already be closed
      }
      pendingMessageChannels.delete(requestId);
      cleanedCount++;
    }
  }

  // Cleanup old cache entries
  let cacheCleanedCount = 0;
  for (const [url, cached] of requestCache.entries()) {
    if (now - cached.timestamp > REQUEST_CACHE_TTL * 2) {
      requestCache.delete(url);
      cacheCleanedCount++;
    }
  }

  if (cleanedCount > 0 || cacheCleanedCount > 0) {
    debugLog(
      "Cleanup complete. Channels:",
      cleanedCount,
      "Cache entries:",
      cacheCleanedCount,
    );
    debugLog(
      "Stats - Pending channels:",
      pendingMessageChannels.size,
      "Active requests:",
      activeRequestCount,
      "Queued:",
      requestQueue.length,
    );
  }
}

// Run cleanup every 30 seconds
setInterval(cleanupOrphanedChannels, 30000);

/**
 * Send current page details to UI
 */
document.addEventListener("DOMContentLoaded", (event) => {
  resetVariables();
  qdnRequest({
    action: "QDN_RESOURCE_DISPLAYED",
    service: _qdnService,
    name: _qdnName,
    identifier: _qdnIdentifier,
    path: _qdnPath,
  });
  // send to the client the first path when the app loads.
  const firstPath = parseUrl(window?.location?.href || "");
  handleQDNResourceDisplayed(firstPath, true);
  // Increment counter when page fully loads
});

/**
 * Handle app navigation
 */
navigation.addEventListener("navigate", (event) => {
  const url = new URL(event.destination.url);

  let fullpath = url.pathname + url.hash;
  const processedPath = fullpath.startsWith(_qdnBase)
    ? fullpath.slice(_qdnBase.length)
    : fullpath;
  qdnRequest({
    action: "QDN_RESOURCE_DISPLAYED",
    service: _qdnService,
    name: _qdnName,
    identifier: _qdnIdentifier,
    path: processedPath,
  });

  // Put a timeout so that the DOMContentLoaded listener's logic executes before the navigate listener
  setTimeout(() => {
    handleQDNResourceDisplayed(processedPath);
  }, 100);
});

/**
 * Cleanup on page unload
 */
window.addEventListener("beforeunload", () => {
  // Close all pending MessageChannels
  for (const [requestId, data] of pendingMessageChannels.entries()) {
    try {
      data.channel.port1.close();
    } catch (e) {
      // Port may already be closed
    }
  }
  pendingMessageChannels.clear();

  // Clear all caches
  requestCache.clear();
  pendingAsyncRequests.clear();
  pendingQdnRequests.clear();

  // Clear request queue
  requestQueue.length = 0;
  activeRequestCount = 0;
});

/**
 * Read-only qdnRequest bridge for browser / gateway contexts.
 *
 * Bundled QDN apps detect a host bridge by checking
 * `typeof window.qdnRequest === "function"` and, when it is absent, fall back
 * to fetching a hard-coded local node URL such as http://127.0.0.1:24891.
 * That address does not exist inside a plain browser (or on an appliance
 * browser pointed at a public gateway), so those apps silently lose every
 * node read. Qortium Home injects a full bridge through its preload; over
 * /render or the public gateway there is none.
 *
 * This installs a minimal, read-only bridge that services the node-read
 * actions apps depend on by fetching the SAME ORIGIN that served the app.
 * Same-origin keeps every call inside the page CSP (connect-src 'self') and,
 * on an access-controlled node, inside that node's own public-API allowlist:
 * it grants nothing a direct browser fetch of those endpoints would not.
 * Write / wallet / signing actions are rejected — those require Qortium Home.
 *
 * Guarded so it never clobbers a real Home bridge, which sets
 * window.qdnRequest before page scripts run.
 */
(function installReadOnlyQdnBridge() {
  if (typeof window === "undefined") return;
  if (typeof window.qdnRequest === "function") return; // real Home bridge present

  // Every action here resolves to a GET against this same origin, so the set
  // stays bounded by the serving node's own publicApiPaths allowlist. Actions
  // needing the logged-in account, signing, decryption, Home-local state, or a
  // second origin are deliberately absent: the *_QORTAL_* twins target a
  // separately configured Qortal node and GET_MARKET_PRICES calls an external
  // price API, neither of which exists at this origin.
  const READ_ONLY_ACTIONS = [
    "FETCH_NODE_API",
    "FETCH_ACCOUNT_AVATAR",
    "FETCH_GROUP_AVATAR",
    "FETCH_QDN_RESOURCE",
    "GET_ACCOUNT_DATA",
    "GET_ACCOUNT_GROUPS",
    "GET_ACCOUNT_GROUP_JOIN_REQUESTS",
    "GET_ACCOUNT_NAMES",
    "GET_ACCOUNT_RATING",
    "GET_ACTIVE_CHATS",
    "GET_ADMIN_GROUP_JOIN_REQUESTS",
    "GET_BALANCE",
    "GET_GROUP",
    "GET_GROUP_BANS",
    "GET_GROUP_JOIN_REQUESTS",
    "GET_GROUP_KICKS",
    "GET_GROUP_MEMBERS",
    "GET_MEMBER_BANS",
    "GET_MEMBER_KICKS",
    "GET_NAME_DATA",
    "GET_NODE_STATUS",
    "GET_QDN_RESOURCE_METADATA",
    "GET_QDN_RESOURCE_PROPERTIES",
    "GET_QDN_RESOURCE_STATUS",
    "GET_QDN_RESOURCE_URL",
    "GET_RESOURCE_RATING",
    "IS_USING_PUBLIC_NODE",
    "LIST_GROUPS",
    "LIST_QDN_RESOURCES",
    "SEARCH_CHAT_MESSAGES",
    "SEARCH_GROUPS",
    "SEARCH_QDN_RESOURCES",
    "SHOW_ACTIONS",
    "WHICH_UI",
  ];

  function sanitizeReadPath(path) {
    if (typeof path !== "string" || !path.startsWith("/") || path.startsWith("//"))
      throw new Error("Node API paths must start with a single /.");
    if (/[\x00-\x1F]/.test(path))
      throw new Error("Node API path contains invalid control characters.");
    const parsed = new URL(path, window.location.origin);
    return parsed.pathname + parsed.search;
  }

  function readOnlyMethod(method) {
    const normalized =
      typeof method === "string" && method.trim() ? method.trim().toUpperCase() : "GET";
    if (normalized !== "GET" && normalized !== "HEAD")
      throw new Error("Only GET and HEAD node API requests are supported in read-only mode.");
    return normalized;
  }

  function parseResponseBody(body, contentType) {
    if (!body) return null;
    if ((contentType || "").toLowerCase().indexOf("json") !== -1 || /^[\s]*[[{]/.test(body)) {
      try {
        return JSON.parse(body);
      } catch (e) {
        return body;
      }
    }
    return body;
  }

  function fetchNodeApi(request) {
    let method;
    let apiPath;
    try {
      method = readOnlyMethod(request && request.method);
      apiPath = sanitizeReadPath(request && request.path);
    } catch (e) {
      return Promise.reject(e);
    }

    // Abort a stalled read instead of hanging forever: on a slow or dropping
    // link (e.g. an appliance browser) a fetch with no timeout can leave the
    // app waiting indefinitely rather than failing and recovering.
    const controller = typeof AbortController !== "undefined" ? new AbortController() : null;
    const timeoutMs =
      request && typeof request.timeout === "number" && request.timeout > 0
        ? request.timeout
        : 30000;
    const timer = controller ? setTimeout(() => controller.abort(), timeoutMs) : null;
    const options = { method: method };
    if (controller) options.signal = controller.signal;

    const maxBytes =
      request && typeof request.maxBytes === "number" ? request.maxBytes : 0;

    return fetch(window.location.origin + apiPath, options).then((response) => {
      const contentType = response.headers.get("content-type") || "";
      const rawLength = response.headers.get("content-length");
      const contentLength = rawLength != null ? Number(rawLength) : undefined;

      // Reject an advertised oversized response before buffering it when the
      // server provides a useful Content-Length. Keep the decoded byte check
      // below because compressed/chunked responses can omit or understate it.
      if (maxBytes > 0 && isFinite(contentLength) && contentLength > maxBytes)
        throw new Error("Node API response exceeded the " + maxBytes + " byte limit.");

      const bodyPromise = method === "HEAD" ? Promise.resolve("") : response.text();
      return bodyPromise.then((body) => {
        const byteLength = new TextEncoder().encode(body).byteLength;
        if (maxBytes > 0 && byteLength > maxBytes)
          throw new Error("Node API response exceeded the " + maxBytes + " byte limit.");
        return {
          body: body,
          contentLength: isFinite(contentLength) ? contentLength : byteLength,
          contentType: contentType,
          data: parseResponseBody(body, contentType),
          ok: response.ok,
          status: response.status,
          statusText: response.statusText,
        };
      });
    }).finally(() => {
      // Keep the abort timer armed until the response body has finished. A
      // slow link can deliver headers and then stall while response.text() is
      // still consuming the body.
      if (timer) clearTimeout(timer);
    });
  }

  // --- Request value readers -------------------------------------------------
  // Home accepts each parameter either at the top level or inside `payload`,
  // and apps rely on both spellings. Mirror that lookup order exactly so the
  // same app code works against Home and against a gateway.

  function getRequestValue(request, key) {
    const payload = request && typeof request.payload === "object" && request.payload !== null
      ? request.payload
      : {};
    const fromPayload = payload[key];
    return fromPayload !== undefined && fromPayload !== null ? fromPayload : request[key];
  }

  function getString(value) {
    if (typeof value === "string") return value.trim();
    if (typeof value === "number" && isFinite(value)) return String(value);
    return "";
  }

  function getInteger(value) {
    if (typeof value === "number" && isFinite(value)) return Math.trunc(value);
    if (typeof value === "string" && value.trim()) {
      const parsed = Number(value.trim());
      if (isFinite(parsed)) return Math.trunc(parsed);
    }
    return undefined;
  }

  function getBoolean(value) {
    if (typeof value === "boolean") return value;
    if (value === "true") return true;
    if (value === "false") return false;
    return undefined;
  }

  function requiredString(request, key, label) {
    const value = getString(getRequestValue(request, key));
    if (!value) throw new Error(label + " is required.");
    return value;
  }

  // A gateway has no logged-in account, so the actions Home defaults to the
  // selected address must demand an explicit one. Without this they would
  // silently resolve to "undefined" and return a confusing node error rather
  // than saying what the app actually got wrong.
  function requiredAddress(request, label) {
    return requiredString(request, "address", label || "Address");
  }

  function requiredGroupId(request, minimumValue) {
    const groupId = getInteger(
      getRequestValue(request, "groupId") !== undefined
        ? getRequestValue(request, "groupId")
        : getRequestValue(request, "txGroupId"),
    );
    if (typeof groupId !== "number" || groupId < minimumValue)
      throw new Error(
        minimumValue > 0
          ? "Group id must be a positive integer."
          : "Group id must be a non-negative integer.",
      );
    return groupId;
  }

  function appendQueryValue(queryParams, key, value) {
    if (Array.isArray(value)) {
      for (const item of value) appendQueryValue(queryParams, key, item);
      return;
    }
    if (typeof value === "boolean" || typeof value === "number") {
      queryParams.append(key, String(value));
      return;
    }
    const stringValue = getString(value);
    if (stringValue) queryParams.append(key, stringValue);
  }

  function appendFields(queryParams, request, queryFields) {
    for (const requestKey of Object.keys(queryFields))
      appendQueryValue(queryParams, queryFields[requestKey], getRequestValue(request, requestKey));
  }

  function withQuery(basePath, queryParams) {
    const queryString = queryParams.toString();
    return queryString ? basePath + "?" + queryString : basePath;
  }

  // --- Fetch helpers ---------------------------------------------------------

  function fetchPayload(request, apiPath) {
    return fetchNodeApi({
      action: "FETCH_NODE_API",
      path: apiPath,
      maxBytes: getRequestValue(request, "maxBytes"),
      timeout: getRequestValue(request, "timeout"),
    }).then((result) => {
      if (!result.ok)
        throw new Error(
          result.body || "Qortium node request failed with HTTP " + result.status + ".",
        );
      return result.data;
    });
  }

  function fetchOptionalPayload(request, apiPath, notFoundValue) {
    return fetchNodeApi({
      action: "FETCH_NODE_API",
      path: apiPath,
      maxBytes: getRequestValue(request, "maxBytes"),
      timeout: getRequestValue(request, "timeout"),
    }).then((result) => {
      if (result.status === 404) return notFoundValue;
      if (!result.ok)
        throw new Error(
          result.body || "Qortium node request failed with HTTP " + result.status + ".",
        );
      return result.data;
    });
  }

  // --- Account / group avatars ---------------------------------------------

  const AVATAR_MAX_BYTES = 500 * 1024;

  function avatarMaxBytes(request) {
    const value = getRequestValue(request, "maxBytes");
    const requested =
      typeof value === "number" && isFinite(value)
        ? Math.floor(value)
        : typeof value === "string" && /^\d+$/.test(value.trim())
          ? Number(value.trim())
          : AVATAR_MAX_BYTES;
    return Math.max(1, Math.min(requested, AVATAR_MAX_BYTES));
  }

  function avatarDescriptor(value) {
    if (!value || typeof value !== "object") return null;
    const service = getString(value.service);
    const name = getString(value.name);
    if (!service || !name) return null;
    return {
      service: service,
      name: name,
      identifier:
        value.identifier === undefined || value.identifier === null ? "" : String(value.identifier),
    };
  }

  function avatarDescriptorFromHeaders(headers) {
    return avatarDescriptor({
      service: headers.get("x-qortium-avatar-service"),
      name: headers.get("x-qortium-avatar-name"),
      identifier: headers.get("x-qortium-avatar-identifier"),
    });
  }

  function avatarContentType(bytes) {
    function startsWith(signature, offset) {
      const start = offset || 0;
      return signature.every((byte, index) => bytes[start + index] === byte);
    }

    if (startsWith([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]))
      return "image/png";
    if (startsWith([0xff, 0xd8, 0xff])) return "image/jpeg";
    if (startsWith([0x47, 0x49, 0x46, 0x38])) return "image/gif";
    if (startsWith([0x42, 0x4d])) return "image/bmp";
    if (
      startsWith([0x52, 0x49, 0x46, 0x46]) &&
      startsWith([0x57, 0x45, 0x42, 0x50], 8)
    )
      return "image/webp";
    return null;
  }

  function bytesToBase64(bytes) {
    let binary = "";
    const chunkSize = 0x8000;
    for (let offset = 0; offset < bytes.length; offset += chunkSize)
      binary += String.fromCharCode.apply(null, bytes.subarray(offset, offset + chunkSize));
    return btoa(binary);
  }

  function retryAfterSeconds(value) {
    if (!value) return null;
    if (/^\d+$/.test(value.trim())) return Number(value.trim());
    const retryAt = Date.parse(value);
    return isFinite(retryAt) ? Math.max(0, Math.ceil((retryAt - Date.now()) / 1000)) : null;
  }

  function fetchAvatarResponse(request, apiPath, maxBytes) {
    let safePath;
    try {
      safePath = sanitizeReadPath(apiPath);
    } catch (e) {
      return Promise.reject(e);
    }

    const controller = typeof AbortController !== "undefined" ? new AbortController() : null;
    const timeoutMs =
      request && typeof getRequestValue(request, "timeout") === "number" &&
      getRequestValue(request, "timeout") > 0
        ? getRequestValue(request, "timeout")
        : 30000;
    const timer = controller ? setTimeout(() => controller.abort(), timeoutMs) : null;
    const options = { method: "GET" };
    if (controller) options.signal = controller.signal;

    return fetch(window.location.origin + safePath, options)
      .then((response) => {
        const rawLength = response.headers.get("content-length");
        const contentLength = rawLength == null ? undefined : Number(rawLength);

        if (!response.ok || response.status === 202) {
          if (response.body && typeof response.body.cancel === "function")
            Promise.resolve(response.body.cancel()).catch(() => undefined);
          return {
            bytes: null,
            contentLength: isFinite(contentLength) ? contentLength : undefined,
            headers: response.headers,
            ok: response.ok,
            status: response.status,
          };
        }

        // Let the account legacy fallback skip an advertised oversized first
        // candidate without buffering it, just as Home does. Callers decide
        // whether an advertised oversize is terminal for their route.
        if (maxBytes > 0 && isFinite(contentLength) && contentLength > maxBytes) {
          if (response.body && typeof response.body.cancel === "function")
            Promise.resolve(response.body.cancel()).catch(() => undefined);
          return {
            bytes: null,
            contentLength: contentLength,
            headers: response.headers,
            ok: true,
            status: response.status,
            tooLarge: true,
            maxBytes: maxBytes,
          };
        }

        return response.arrayBuffer().then((buffer) => {
          const bytes = new Uint8Array(buffer);
          if (bytes.byteLength > maxBytes)
            throw new Error("Avatar exceeded the " + maxBytes + " byte limit.");
          return {
            bytes: bytes,
            contentLength: isFinite(contentLength) ? contentLength : bytes.byteLength,
            headers: response.headers,
            ok: true,
            status: response.status,
          };
        });
      })
      .finally(() => {
        if (timer) clearTimeout(timer);
      });
  }

  function avatarPending(target, source, descriptor, retryAfter) {
    return Object.assign({}, target, {
      status: "PENDING",
      retryAfterSeconds: retryAfterSeconds(retryAfter),
      source: source,
      descriptor: descriptor,
    });
  }

  function avatarReady(target, source, descriptor, response) {
    if (response.tooLarge)
      throw new Error("Avatar exceeded the " + response.maxBytes + " byte limit.");
    const contentType = avatarContentType(response.bytes);
    if (!contentType) throw new Error("Avatar was not a supported image.");
    return Object.assign({}, target, {
      body: bytesToBase64(response.bytes),
      encoding: "base64",
      contentType: contentType,
      contentLength: response.contentLength,
      source: source,
      descriptor: descriptor,
    });
  }

  function buildAvatarResourcePath(name, identifier) {
    return (
      "/arbitrary/THUMBNAIL/" +
      encodeURIComponent(name) +
      "/" +
      encodeURIComponent(identifier) +
      "?async=true"
    );
  }

  function fetchAvatarMetadata(request, apiPath) {
    return fetchNodeApi({
      action: "FETCH_NODE_API",
      path: apiPath,
      maxBytes: 65536,
      timeout: getRequestValue(request, "timeout"),
    }).then((result) => {
      if (result.status === 404) return null;
      if (!result.ok)
        throw new Error(
          result.body || "Qortium node request failed with HTTP " + result.status + ".",
        );
      return result.data;
    });
  }

  function fetchLegacyAccountAvatar(request, address, maxBytes) {
    return fetchAvatarMetadata(
      request,
      "/names/primary/" + encodeURIComponent(address),
    ).then((primaryName) => {
      const name = primaryName && typeof primaryName === "object"
        ? getString(primaryName.name)
        : "";
      if (!name) throw new Error("Account avatar is not set.");

      const identifiers = ["avatar", "qortal_avatar"];
      function tryIdentifier(index) {
        if (index >= identifiers.length) throw new Error("Account avatar is not set.");
        return fetchAvatarResponse(
          request,
          buildAvatarResourcePath(name, identifiers[index]),
          maxBytes,
        ).then((response) => {
          if (response.status === 202)
            return avatarPending(
              { address: address },
              "LEGACY",
              null,
              response.headers.get("retry-after"),
            );
          if (!response.ok) return tryIdentifier(index + 1);
          if (response.tooLarge) return tryIdentifier(index + 1);
          try {
            return avatarReady({ address: address }, "LEGACY", null, response);
          } catch (e) {
            return tryIdentifier(index + 1);
          }
        });
      }
      return tryIdentifier(0);
    });
  }

  function fetchLegacyGroupAvatar(request, groupId, maxBytes) {
    return fetchAvatarMetadata(
      request,
      "/groups/" + encodeURIComponent(String(groupId)),
    ).then((group) => {
      const ownerName =
        group && typeof group === "object" ? getString(group.ownerPrimaryName) : "";
      if (!ownerName) throw new Error("Group avatar is not set.");
      return fetchAvatarResponse(
        request,
        buildAvatarResourcePath(ownerName, "qortal_group_avatar_" + groupId),
        maxBytes,
      ).then((response) => {
        if (response.status === 202)
          return avatarPending(
            { groupId: groupId },
            "LEGACY",
            null,
            response.headers.get("retry-after"),
          );
        if (!response.ok) throw new Error("Group avatar is not set.");
        return avatarReady({ groupId: groupId }, "LEGACY", null, response);
      });
    });
  }

  function fetchPointerAvatar(request, target, infoPath, avatarPath, legacyFetch) {
    const maxBytes = avatarMaxBytes(request);
    return fetchNodeApi({
      action: "FETCH_NODE_API",
      path: infoPath,
      maxBytes: 65536,
      timeout: getRequestValue(request, "timeout"),
    }).then((infoResponse) => {
      if (infoResponse.status === 404) return legacyFetch(maxBytes);
      if (!infoResponse.ok)
        throw new Error("Avatar pointer lookup failed with HTTP " + infoResponse.status + ".");
      const pointerDescriptor = avatarDescriptor(infoResponse.data);
      if (!pointerDescriptor) throw new Error("Avatar pointer metadata was invalid.");

      return fetchAvatarResponse(request, avatarPath, maxBytes).then((response) => {
        const descriptor = avatarDescriptorFromHeaders(response.headers) || pointerDescriptor;
        if (response.status === 202)
          return avatarPending(
            target,
            "POINTER",
            descriptor,
            response.headers.get("retry-after"),
          );
        if (!response.ok)
          throw new Error("Avatar request failed with HTTP " + response.status + ".");
        return avatarReady(target, "POINTER", descriptor, response);
      });
    });
  }

  function fetchAccountAvatar(request) {
    const address = requiredAddress(request);
    return fetchPointerAvatar(
      request,
      { address: address },
      "/addresses/" + encodeURIComponent(address) + "/avatar/info",
      "/addresses/" + encodeURIComponent(address) + "/avatar",
      (maxBytes) => fetchLegacyAccountAvatar(request, address, maxBytes),
    );
  }

  function fetchGroupAvatar(request) {
    const rawGroupId =
      getRequestValue(request, "groupId") !== undefined
        ? getRequestValue(request, "groupId")
        : getRequestValue(request, "txGroupId");
    const groupId =
      typeof rawGroupId === "number" && Number.isSafeInteger(rawGroupId)
        ? rawGroupId
        : typeof rawGroupId === "string" && /^-?\d+$/.test(rawGroupId.trim())
          ? Number(rawGroupId.trim())
          : undefined;
    if (!Number.isSafeInteger(groupId) || groupId < 1)
      throw new Error("Group id must be a positive integer.");
    return fetchPointerAvatar(
      request,
      { groupId: groupId },
      "/groups/" + encodeURIComponent(String(groupId)) + "/avatar/info",
      "/groups/" + encodeURIComponent(String(groupId)) + "/avatar",
      (maxBytes) => fetchLegacyGroupAvatar(request, groupId, maxBytes),
    );
  }

  // --- QDN resource paths ----------------------------------------------------

  // A QDN service is a bare enum-style token that gets concatenated into the
  // request path, so validate its shape rather than relying on encoding alone.
  // Unvalidated, a value such as "../admin" is normalized by sanitizeReadPath()
  // into an entirely different endpoint family, and one containing "?" splices
  // an attacker-controlled query onto the request.
  function getServiceToken(value) {
    const service = getString(value).toUpperCase();
    if (!service) return "";
    if (!/^[A-Z0-9_]+$/.test(service)) throw new Error("QDN resource service is invalid.");
    return service;
  }

  function getResourceRequest(request) {
    const service = getServiceToken(getRequestValue(request, "service"));
    const name = getString(getRequestValue(request, "name"));
    const identifier = getString(getRequestValue(request, "identifier"));
    const resourcePath =
      getString(getRequestValue(request, "path")) || getString(getRequestValue(request, "filepath"));

    if (!service) throw new Error("QDN resource service is required.");
    if (!name) throw new Error("QDN resource name is required.");

    return {
      service: service,
      name: name,
      identifier: identifier || undefined,
      path: resourcePath,
    };
  }

  function resourceIdentifierPath(resource) {
    return resource.identifier ? "/" + encodeURIComponent(resource.identifier) : "";
  }

  function buildResourcesPath(request, pathBase) {
    const queryParams = new URLSearchParams();
    appendFields(queryParams, request, {
      default: "default",
      description: "description",
      exactMatchNames: "exactmatchnames",
      excludeBlocked: "excludeblocked",
      followedOnly: "followedonly",
      identifier: "identifier",
      includeMetadata: "includemetadata",
      includeStatus: "includestatus",
      keywords: "keywords",
      limit: "limit",
      mode: "mode",
      name: "name",
      nameListFilter: "namefilter",
      names: "name",
      offset: "offset",
      prefix: "prefix",
      query: "query",
      reverse: "reverse",
      service: "service",
      title: "title",
    });
    return withQuery(pathBase, queryParams);
  }

  function buildResourceStatusPath(request) {
    const resource = getResourceRequest(request);
    const queryParams = new URLSearchParams();
    const build = getBoolean(getRequestValue(request, "build"));
    if (typeof build === "boolean") queryParams.set("build", String(build));
    return withQuery(
      "/arbitrary/resource/status/" +
        encodeURIComponent(resource.service) +
        "/" +
        encodeURIComponent(resource.name) +
        resourceIdentifierPath(resource),
      queryParams,
    );
  }

  function buildResourcePropertiesPath(request) {
    const resource = getResourceRequest(request);
    return (
      "/arbitrary/resource/properties/" +
      encodeURIComponent(resource.service) +
      "/" +
      encodeURIComponent(resource.name) +
      "/" +
      encodeURIComponent(resource.identifier || "default")
    );
  }

  function buildResourceMetadataPath(request) {
    const resource = getResourceRequest(request);
    return (
      "/arbitrary/metadata/" +
      encodeURIComponent(resource.service) +
      "/" +
      encodeURIComponent(resource.name) +
      "/" +
      encodeURIComponent(resource.identifier || "default")
    );
  }

  function buildFetchResourcePath(request) {
    const resource = getResourceRequest(request);
    const queryParams = new URLSearchParams();
    if (resource.path) queryParams.set("filepath", resource.path);
    for (const key of ["encoding", "rebuild", "async"]) {
      const value = getRequestValue(request, key);
      if (typeof value === "boolean" || typeof value === "number" || typeof value === "string")
        queryParams.set(key, String(value));
    }
    return withQuery(
      "/arbitrary/" +
        encodeURIComponent(resource.service) +
        "/" +
        encodeURIComponent(resource.name) +
        resourceIdentifierPath(resource),
      queryParams,
    );
  }

  function encodeResourcePath(resourcePath) {
    return resourcePath
      .split("/")
      .filter(Boolean)
      .map((segment) => encodeURIComponent(segment))
      .join("/");
  }

  // Home resolves this against its configured node; on a gateway the serving
  // origin IS the node, so the render URL is same-origin by construction.
  function getResourceUrl(request) {
    const resource = getResourceRequest(request);
    return fetchPayload(request, buildResourceStatusPath(request)).then((status) => {
      if (!status || typeof status !== "object" || !status.status || status.status === "NOT_PUBLISHED")
        throw new Error("Resource does not exist.");

      const rawPath = resource.path || "";
      const queryIndex = rawPath.indexOf("?");
      const pathOnly = queryIndex === -1 ? rawPath : rawPath.slice(0, queryIndex);
      const queryString = queryIndex === -1 ? "" : rawPath.slice(queryIndex + 1);
      const encodedPath = encodeResourcePath(pathOnly);

      return withQuery(
        window.location.origin +
          "/render/" +
          encodeURIComponent(resource.service) +
          "/" +
          encodeURIComponent(resource.name) +
          resourceIdentifierPath(resource) +
          (encodedPath ? "/" + encodedPath : ""),
        new URLSearchParams(queryString),
      );
    });
  }

  // --- Group / chat paths ----------------------------------------------------

  function buildGroupsPath(request) {
    const queryParams = new URLSearchParams();
    appendFields(queryParams, request, { limit: "limit", offset: "offset", reverse: "reverse" });
    return withQuery("/groups", queryParams);
  }

  function buildSearchGroupsPath(request) {
    const queryParams = new URLSearchParams();
    appendFields(queryParams, request, {
      limit: "limit",
      offset: "offset",
      prefixOnly: "prefixOnly",
      query: "query",
      reverse: "reverse",
      visibility: "visibility",
    });
    return withQuery("/groups/search", queryParams);
  }

  function buildGroupMembersPath(request) {
    const groupId = requiredGroupId(request, 1);
    const queryParams = new URLSearchParams();
    appendFields(queryParams, request, {
      limit: "limit",
      offset: "offset",
      onlyAdmins: "onlyAdmins",
      reverse: "reverse",
    });
    return withQuery("/groups/members/" + encodeURIComponent(String(groupId)), queryParams);
  }

  function buildGroupKicksPath(request) {
    const groupId = requiredGroupId(request, 1);
    const queryParams = new URLSearchParams();
    appendFields(queryParams, request, {
      address: "address",
      before: "before",
      after: "after",
      limit: "limit",
      offset: "offset",
      reverse: "reverse",
    });
    return withQuery("/groups/kicks/" + encodeURIComponent(String(groupId)), queryParams);
  }

  function buildMemberKicksPath(request) {
    const queryParams = new URLSearchParams({ address: requiredAddress(request) });
    appendFields(queryParams, request, {
      groupId: "groupId",
      before: "before",
      after: "after",
      limit: "limit",
      offset: "offset",
      reverse: "reverse",
    });
    return "/groups/kicks/member?" + queryParams.toString();
  }

  function buildMemberBansPath(request) {
    const queryParams = new URLSearchParams({ address: requiredAddress(request) });
    appendFields(queryParams, request, { limit: "limit", offset: "offset", reverse: "reverse" });
    return "/groups/bans/member?" + queryParams.toString();
  }

  function buildAccountGroupsPath(request) {
    const address = requiredAddress(request);
    const queryParams = new URLSearchParams();
    appendFields(queryParams, request, { adminOnly: "adminOnly", ownerOnly: "ownerOnly" });
    return withQuery("/groups/member/" + encodeURIComponent(address), queryParams);
  }

  function buildSearchChatMessagesPath(request) {
    const queryParams = new URLSearchParams();
    const groupId = getInteger(
      getRequestValue(request, "groupId") !== undefined
        ? getRequestValue(request, "groupId")
        : getRequestValue(request, "txGroupId"),
    );
    if (typeof groupId === "number") {
      if (groupId < 0) throw new Error("Group id must be a non-negative integer.");
      queryParams.set("txGroupId", String(groupId));
    }
    appendFields(queryParams, request, {
      after: "after",
      before: "before",
      chatReference: "chatreference",
      encoding: "encoding",
      hasChatReference: "haschatreference",
      involving: "involving",
      limit: "limit",
      offset: "offset",
      reverse: "reverse",
      sender: "sender",
    });
    return "/chat/messages?" + queryParams.toString();
  }

  function buildActiveChatsPath(request) {
    const address = requiredAddress(request);
    const queryParams = new URLSearchParams();
    appendFields(queryParams, request, {
      encoding: "encoding",
      hasChatReference: "haschatreference",
    });
    return withQuery("/chat/active/" + encodeURIComponent(address), queryParams);
  }

  // --- Ratings ---------------------------------------------------------------

  function normalizeRatingSummary(summary) {
    if (summary === null || summary === undefined) return null;
    if (Array.isArray(summary) && summary.length === 0) return null;
    if (typeof summary === "object" && Object.keys(summary).length === 0) return null;
    return summary;
  }

  // `rater` defaults to the selected account in Home; a gateway must be given
  // one explicitly.
  function getResourceRating(request) {
    const service = getServiceToken(getRequestValue(request, "service"));
    const name = requiredString(request, "name", "QDN resource name");
    const identifier = getString(getRequestValue(request, "identifier")) || "default";
    const rater = requiredString(request, "rater", "Rater address");
    if (!service) throw new Error("QDN resource service is required.");

    const summaryQuery = new URLSearchParams({
      service: service,
      name: name,
      identifier: identifier,
    });
    const ratingQuery = new URLSearchParams({
      service: service,
      name: name,
      identifier: identifier,
      rater: rater,
    });

    return Promise.all([
      fetchOptionalPayload(request, "/resource-ratings/summary?" + summaryQuery.toString(), null),
      fetchOptionalPayload(request, "/resource-ratings/rating?" + ratingQuery.toString(), null),
    ]).then((results) => ({
      action: "GET_RESOURCE_RATING",
      service: service,
      name: name,
      identifier: identifier,
      rater: rater,
      summary: normalizeRatingSummary(results[0]),
      rating: results[1] === undefined ? null : results[1],
    }));
  }

  function getAccountRating(request) {
    const target = requiredString(request, "target", "Target address");
    const category = getString(getRequestValue(request, "category"));
    const rater = requiredString(request, "rater", "Rater address");

    const summaryQuery = new URLSearchParams({ target: target });
    const ratingQuery = new URLSearchParams({ target: target, rater: rater });
    if (category) {
      summaryQuery.set("category", category);
      ratingQuery.set("category", category);
    }

    return Promise.all([
      fetchOptionalPayload(request, "/account-ratings/summary?" + summaryQuery.toString(), null),
      fetchOptionalPayload(request, "/account-ratings?" + ratingQuery.toString(), []),
    ]).then((results) => ({
      action: "GET_ACCOUNT_RATING",
      target: target,
      category: category,
      rater: rater,
      summary: normalizeRatingSummary(results[0]),
      ratings: Array.isArray(results[1]) ? results[1] : [],
    }));
  }

  // --- Dispatch --------------------------------------------------------------

  // Each entry returns either a node API path (fetched and unwrapped) or a
  // promise, so adding a plain read stays a one-line change.
  const PATH_ACTIONS = {
    FETCH_QDN_RESOURCE: buildFetchResourcePath,
    GET_ACCOUNT_DATA: (request) => "/addresses/" + encodeURIComponent(requiredAddress(request)),
    GET_ACCOUNT_GROUPS: buildAccountGroupsPath,
    GET_ACCOUNT_GROUP_JOIN_REQUESTS: (request) =>
      "/groups/joinrequests/address/" + encodeURIComponent(requiredAddress(request)),
    GET_ACCOUNT_NAMES: (request) =>
      "/names/address/" + encodeURIComponent(requiredAddress(request)),
    GET_ACTIVE_CHATS: buildActiveChatsPath,
    GET_ADMIN_GROUP_JOIN_REQUESTS: (request) =>
      "/groups/joinrequests/admin/" + encodeURIComponent(requiredAddress(request)),
    GET_BALANCE: (request) =>
      "/addresses/balance/" + encodeURIComponent(requiredAddress(request)),
    GET_GROUP: (request) => "/groups/" + encodeURIComponent(String(requiredGroupId(request, 1))),
    GET_GROUP_BANS: (request) =>
      "/groups/bans/" + encodeURIComponent(String(requiredGroupId(request, 1))),
    GET_GROUP_JOIN_REQUESTS: (request) =>
      "/groups/joinrequests/" + encodeURIComponent(String(requiredGroupId(request, 1))),
    GET_GROUP_KICKS: buildGroupKicksPath,
    GET_GROUP_MEMBERS: buildGroupMembersPath,
    GET_MEMBER_BANS: buildMemberBansPath,
    GET_MEMBER_KICKS: buildMemberKicksPath,
    GET_NAME_DATA: (request) =>
      "/names/" + encodeURIComponent(requiredString(request, "name", "Name")),
    GET_NODE_STATUS: () => "/admin/status",
    GET_QDN_RESOURCE_METADATA: buildResourceMetadataPath,
    GET_QDN_RESOURCE_PROPERTIES: buildResourcePropertiesPath,
    GET_QDN_RESOURCE_STATUS: buildResourceStatusPath,
    LIST_GROUPS: buildGroupsPath,
    LIST_QDN_RESOURCES: (request) => buildResourcesPath(request, "/arbitrary/resources"),
    SEARCH_CHAT_MESSAGES: buildSearchChatMessagesPath,
    SEARCH_GROUPS: buildSearchGroupsPath,
    SEARCH_QDN_RESOURCES: (request) =>
      buildResourcesPath(request, "/arbitrary/resources/search"),
  };

  const PROMISE_ACTIONS = {
    FETCH_NODE_API: fetchNodeApi,
    FETCH_ACCOUNT_AVATAR: fetchAccountAvatar,
    FETCH_GROUP_AVATAR: fetchGroupAvatar,
    GET_ACCOUNT_RATING: getAccountRating,
    GET_QDN_RESOURCE_URL: getResourceUrl,
    GET_RESOURCE_RATING: getResourceRating,
    IS_USING_PUBLIC_NODE: () => Promise.resolve(true),
    SHOW_ACTIONS: () => Promise.resolve(READ_ONLY_ACTIONS.slice()),
    WHICH_UI: () => Promise.resolve("QORTIUM_GATEWAY"),
  };

  function handleReadOnlyRequest(request) {
    if (!request || typeof request.action !== "string")
      return Promise.reject(new Error("QDN requests must include an action."));

    const action = request.action.toUpperCase();

    try {
      if (Object.prototype.hasOwnProperty.call(PROMISE_ACTIONS, action))
        return Promise.resolve(PROMISE_ACTIONS[action](request));

      if (Object.prototype.hasOwnProperty.call(PATH_ACTIONS, action))
        return fetchPayload(request, PATH_ACTIONS[action](request));
    } catch (e) {
      // Parameter validation throws synchronously; surface it as a rejection so
      // callers only ever have one failure path to handle.
      return Promise.reject(e);
    }

    return Promise.reject(
      new Error(
        request.action +
          " is not available in read-only gateway mode. Interactive features require the Qortium Home app.",
      ),
    );
  }

  window.qdnRequest = function (request) {
    return handleReadOnlyRequest(request);
  };
})();
