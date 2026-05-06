# Electrum Server Refresh

Qortium ships built-in Electrum server seeds in `BitcoinyServers` and also loads a generated resource from:

`src/main/resources/crosschain/electrum-servers.json`

At runtime, Qortium uses the generated resource when it contains entries for the selected coin/network. SSL servers are preferred; TCP servers are used only when no SSL servers are available. If the resource is missing, invalid, or has no entries for that coin/network, the built-in seeds remain the fallback with the same SSL preference.

## Refreshing The List

Run:

```bash
tools/refresh-electrum-servers
```

The refresh tool:

- starts with the built-in BTC, LTC, DOGE, DGB, and RVN mainnet seeds, plus BTC and LTC testnet seeds
- scrapes OK TCP/SSL rows from `https://1209k.com/bitcoin-eye/ele.php`
- asks a limited number of Electrum servers for `server.peers.subscribe`
- verifies candidates with `server.version`, `server.features`, expected genesis hash, and `blockchain.headers.subscribe`
- keeps SSL servers when any are available, falling back to TCP only if no SSL servers verify
- sorts retained servers with numeric/IP-style hosts first, followed by alphabetic hostnames
- writes a refreshed `crosschain/electrum-servers.json`

Useful options:

```bash
tools/refresh-electrum-servers --coins BTC,LTC
tools/refresh-electrum-servers --skip-peers
tools/refresh-electrum-servers --skip-verify
tools/refresh-electrum-servers --timeout-ms 8000 --threads 16
tools/refresh-electrum-servers --update-builtins --builtin-limit 25
tools/refresh-electrum-servers --update-builtins-only
```

Use `--skip-verify` only when generating a seed file from trusted local inputs. Verified refreshes are preferred because they prune stale servers and reject servers from the wrong chain.

By default the script updates only the generated JSON resource. Use `--update-builtins` when you also want to refresh the hardcoded Java fallback lists in `BitcoinyServers`. Use `--update-builtins-only` to rewrite the Java fallback lists from an already refreshed JSON resource without repeating live network checks. The hardcoded lists are unlimited by default; use `--builtin-limit` when you want to cap the number of fallback servers written per network.

## Scope

This maintenance flow improves Electrum server availability. It does not create live HTLC-secret fixtures or require test coins. Deterministic HTLC tests remain the primary coverage for script behavior; live crosschain tests should stay opt-in connectivity/indexer checks.
