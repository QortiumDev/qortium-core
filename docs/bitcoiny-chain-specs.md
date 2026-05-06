# Bitcoiny Chain Specs

BTC-like crosschain support is driven by `BitcoinyChainSpecs`.

Each spec defines the chain's canonical registry name, currency code, foreign blockchain id, display name, fee defaults, minimum order amount, supported networks, genesis hashes, and Electrum refresh metadata. The runtime creates one generic `RegisteredBitcoiny` instance from the selected spec and network instead of maintaining separate Bitcoin, Litecoin, Dogecoin, DigiByte, and Ravencoin wrapper classes.

## Network Settings

Use `bitcoinyNetworks` in `settings.json` to choose the active network for BTC-like chains:

```json
{
  "bitcoinyNetworks": {
    "BTC": "TEST3",
    "LTC": "TEST3",
    "DOGE": "MAIN",
    "DGB": "MAIN",
    "RVN": "MAIN"
  }
}
```

Missing entries default to `MAIN`. Supported network names are currently `MAIN`, `TEST3`, and `REGTEST` for each registered Bitcoiny chain.

## Adding A Coin

Adding another BTC-like coin should start with a new `BitcoinyChainSpec` builder entry. The builder keeps the common mainnet, testnet, regtest, Electrum refresh, fee, and minimum-order wiring in one place, so a new coin entry should mostly supply chain metadata and any needed explicit hooks. The registry then feeds network resolution, runtime startup, supported-chain lookup, and the Electrum server refresh tool. Coin-specific behavior should be added to the spec as an explicit hook, as with Bitcoin's default spend fee override and Litecoin's P2SH address normalization.

`ForeignBlockchainRegistry` is the central lookup point for crosschain runtime code. It resolves canonical names and currency codes, maps Bitcoiny foreign-chain ids back to registered chains, owns the runtime instance definitions, exposes registry iteration helpers and the shared ACCT registry, drives API/websocket blockchain filters from string names instead of enum parameters, and feeds foreign-fee processing and backup/import paths. `SupportedBlockchain` remains only as a transitional facade for older compatibility callers. New backend code should use the registry directly instead of adding new `SupportedBlockchain` dependencies.

The shared Bitcoiny ACCT stores only the registered foreign-chain id in AT data. Build and parse paths resolve that id through `ForeignBlockchainRegistry`, so the ACCT code does not depend on the temporary `SupportedBlockchain` enum facade.

## Tests

The shared BTC-like wallet, HTLC, and fee tests live in `RegisteredBitcoinyTests`. Add a fixture there for any new registered Bitcoiny chain so the common deterministic coverage runs without creating another per-coin test class.
