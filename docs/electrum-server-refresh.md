# Electrum Server Refresh

Qortium ships built-in Electrum server seeds in the coin classes and also loads a generated resource from:

`src/main/resources/crosschain/electrum-servers.json`

At runtime, Qortium uses the generated resource when it contains entries for the selected coin/network. If the resource is missing, invalid, or has no entries for that coin/network, the built-in seeds remain the fallback.

## Refreshing The List

Run:

```bash
tools/refresh-electrum-servers
```

The refresh tool:

- starts with the built-in BTC, LTC, DOGE, DGB, and RVN mainnet seeds
- scrapes OK TCP/SSL rows from `https://1209k.com/bitcoin-eye/ele.php`
- asks a limited number of Electrum servers for `server.peers.subscribe`
- verifies candidates with `server.version`, `server.features`, expected genesis hash, and `blockchain.headers.subscribe`
- writes a refreshed `crosschain/electrum-servers.json`

Useful options:

```bash
tools/refresh-electrum-servers --coins BTC,LTC
tools/refresh-electrum-servers --skip-peers
tools/refresh-electrum-servers --skip-verify
tools/refresh-electrum-servers --timeout-ms 8000 --threads 16
```

Use `--skip-verify` only when generating a seed file from trusted local inputs. Verified refreshes are preferred because they prune stale servers and reject servers from the wrong chain.

## Scope

This maintenance flow improves Electrum server availability. It does not create live HTLC-secret fixtures or require test coins. Deterministic HTLC tests remain the primary coverage for script behavior; live crosschain tests should stay opt-in connectivity/indexer checks.
