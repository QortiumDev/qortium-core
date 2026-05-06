# Electrum Server Refresh

Qortium ships Electrum server seeds from the generated resource:

`src/main/resources/crosschain/electrum-servers.json`

At runtime, Qortium uses this resource when it contains entries for the selected coin/network. SSL servers are preferred; TCP servers are used only when no SSL servers are available. Regtest networks still use a local fallback server so local test chains can run without a generated resource entry.

Because non-regtest Bitcoiny networks no longer keep Java hardcoded fallback lists, the generated resource should stay populated for every refreshable coin/network before release packaging.

## Refreshing The List

Run:

```bash
tools/refresh-electrum-servers
```

The refresh tool derives its supported BTC-like coins and refreshable networks from `BitcoinyChainSpecs`. It:

- starts with the existing generated JSON resource when present
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
```

Use `--skip-verify` only when generating a seed file from trusted local inputs. Verified refreshes are preferred because they prune stale servers and reject servers from the wrong chain.

## Scope

This maintenance flow improves Electrum server availability. It does not create live HTLC-secret fixtures or require test coins. Deterministic HTLC tests remain the primary coverage for script behavior; live crosschain tests should stay opt-in connectivity/indexer checks.
