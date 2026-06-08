# Developer Proxy

The developer proxy is a local QDN app development tool. It relays content from a loopback development server, such as Vite on `127.0.0.1:5173`, through Core's Q-App rendering environment so app authors can test Q-App behavior without publishing every local change.

The proxy is disabled by default. Enable it only on development nodes by setting:

```json
{
  "devProxyEnabled": true
}
```

Starting and stopping the proxy still requires the API key through `/developer/proxy/start` and `/developer/proxy/stop`. The proxy service also binds its target to loopback-only sources and refuses targets that point back at Core's API or proxy ports.

HTML from the local development server is intentionally forwarded as HTML. Do not use the developer proxy as a production content boundary, and only proxy local development servers that you trust.

The default developer-proxy content security policy allows inline script because Q-App bootstrap injection depends on it, but it does not allow `unsafe-eval`. If a legacy development bundle genuinely needs eval-style script execution, set this explicit compatibility option while developing:

```json
{
  "devProxyUnsafeEvalEnabled": true
}
```
