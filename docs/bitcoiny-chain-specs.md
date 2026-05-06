# Bitcoiny Chain Specs

BTC-like crosschain support is driven by `BitcoinyChainSpecs`.

Each spec defines the chain's currency code, foreign blockchain id, display name, fee defaults, minimum order amount, supported networks, genesis hashes, built-in Electrum seeds, and Electrum refresh metadata. The runtime creates one generic `RegisteredBitcoiny` instance from the selected spec and network instead of maintaining separate Bitcoin, Litecoin, Dogecoin, DigiByte, and Ravencoin wrapper classes.

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

Adding another BTC-like coin should start with a new `BitcoinyChainSpec` entry. The registry then feeds network resolution, runtime startup, supported-chain lookup, and the Electrum server refresh tool. Coin-specific behavior should be added to the spec as an explicit hook, as with Bitcoin's default spend fee override and Litecoin's P2SH address normalization.
