# Bitcoiny Chain Specs

BTC-like crosschain support is driven by `BitcoinyChainSpecs`.

Each spec defines the chain's canonical registry name, currency code, SLIP-44 coin type, display name, fee defaults, minimum order amount, supported networks, BIP122 chain ids, genesis hashes, and Electrum refresh metadata. The runtime creates one generic `RegisteredBitcoiny` instance from the selected spec and network instead of maintaining separate per-coin wrapper classes.

## Chain IDs

For registered BTC-like chains, the shared Bitcoiny ACCT stores the active network's BIP122 chain reference in AT data. This is the 16-byte binary form of the `bip122:<32 hex chars>` CAIP-2 id, where the 32 hex characters are normally the first 32 characters of the chain's genesis block hash. For fork chains that share another chain's genesis, use the first block hash that uniquely identifies the fork instead.

Mainnet chain ids for the currently registered BTC-like chains are:

| Chain ID | Name | Code |
| --- | --- | --- |
| `bip122:000000000019d6689c085ae165831e93` | `BITCOIN` | `BTC` |
| `bip122:12a765e31ffd4059bada1e25190f6e98` | `LITECOIN` | `LTC` |
| `bip122:1a91e3dace36e2be3bf030a65679fe82` | `DOGECOIN` | `DOGE` |
| `bip122:00000ffd590b1485b3caadc19b22e637` | `DASH` | `DASH` |
| `bip122:0000000032fe677166d54963b62a4677` | `PEERCOIN` | `PPC` |
| `bip122:000000000062b72c5e2ceb45fbc8587e` | `NAMECOIN` | `NMC` |
| `bip122:4381deb85b1b2c9843c222944b616d99` | `FIRO` | `FIRO` |
| `bip122:027e3758c3a65b12aa1046462b486d0a` | `KOMODO` | `KMD` |
| `bip122:9c89283ba0f3227f6c03b70216b9f665` | `LBRYCREDITS` | `LBC` |
| `bip122:7497ea1b465eb39f1c8f507bc877078f` | `DIGIBYTE` | `DGB` |
| `bip122:0000006b444bc2f2ffe627be9d9e7e7a` | `RAVENCOIN` | `RVN` |

SLIP-44 coin types are still kept as wallet derivation metadata: BTC `0`, LTC `2`, DOGE `3`, DASH `5`, PPC `6`, NMC `7`, DGB `20`, FIRO `136` (listed in SLIP-44 as XZC/ZCoin), LBC `140`, KMD `141`, and RVN `175`.

## Network Settings

Use `bitcoinyNetworks` in `settings.json` to choose the active network for BTC-like chains:

```json
{
  "bitcoinyNetworks": {
    "BTC": "TEST3",
    "LTC": "TEST4",
    "DOGE": "MAIN",
    "DGB": "MAIN",
    "RVN": "MAIN",
    "DASH": "MAIN",
    "PPC": "MAIN",
    "NMC": "MAIN",
    "FIRO": "MAIN",
    "KMD": "MAIN",
    "LBC": "MAIN"
  }
}
```

Missing entries default to `MAIN`. Supported network names are currently `MAIN`, `TEST3`, `TEST4`, and `REGTEST`, but each chain only advertises networks that have explicit params and usable server support.

## Adding A Coin

Adding another BTC-like coin should start with a new `BitcoinyChainSpec` builder entry. The builder keeps the common mainnet, testnet, regtest, Electrum refresh, fee, and minimum-order wiring in one place, so a new coin entry should mostly supply chain metadata and any needed explicit hooks. New chains, and existing chains migrated away from inherited params classes, should use `StaticBitcoinyParams` instead of adding another coin-specific params class. The registry then feeds network resolution, runtime startup, supported-chain lookup, and the Electrum server refresh tool. Coin-specific behavior should be added to the spec as an explicit hook, as with Bitcoin's default spend fee override and Litecoin's P2SH address normalization.

`ForeignBlockchainRegistry` is the central lookup point for crosschain runtime code. It resolves canonical names and currency codes, maps Bitcoiny BIP122 chain ids back to registered chains, owns the runtime instance definitions, exposes registry iteration helpers, owns the shared ACCT and trade-bot routing registries, drives API/websocket blockchain filters from string names instead of enum parameters, and feeds foreign-fee processing and backup/import paths. New backend code should use the registry directly.

The shared Bitcoiny ACCT stores only the active network's BIP122 chain reference in AT data. Build and parse paths resolve that chain id through `ForeignBlockchainRegistry`, so the ACCT code does not need per-coin enum cases.

Before coding a new coin, do a compatibility precheck:

- standard legacy transaction serialization and standard SIGHASH behavior, or an explicit `BitcoinyTransactionFormat`/signing hook
- standard P2PKH and P2SH script/address behavior, or an explicit address/script normalization hook
- usable BIP32 headers and deterministic wallet derivation behavior
- ElectrumX support for headers, balances, UTXOs, transaction fetches, and broadcasts
- no fork-id or replay-protection signing rule that bitcoinj's current signing boundary cannot produce

Before adding a coin, collect and verify:

- canonical name, currency code, display name, stable SLIP-44 coin type if one exists, default fee, and minimum order amount
- mainnet genesis hash, BIP122 chain id override if this is a fork that shares another chain's genesis, genesis header fields, address header, P2SH header, private-key header, BIP32 headers, ports, packet magic, monetary format, dust threshold, coinbase maturity, and subsidy interval
- supported networks with explicit params and server support; do not advertise testnet or regtest until they are usable
- Electrum refresh metadata for each public network, plus refreshed SSL-first hardcoded servers
- address normalization hooks, output spendability filters, or default spend-fee hooks only when the chain needs them
- manifest coverage in `BitcoinyChainSpecsTests` and common wallet/HTLC coverage in `RegisteredBitcoinyTests`

Peercoin is registered for ordinary transparent PPC wallet, HTLC, and ACCT trade support. PPC uses six decimal places instead of the usual eight, so shared Bitcoiny config carries per-chain decimal metadata for formatting and Electrum verbose transaction parsing. PPC also uses Peercoin's version-3 transaction format for newly built spends and HTLC transactions so they do not include the pre-version-3 transaction timestamp field.

Namecoin is registered for ordinary NMC wallet, HTLC, and ACCT trade support. Name registration/update flows are intentionally not implemented in core yet, but detected Namecoin name outputs are filtered out of normal wallet spend selection so they are not accidentally spent as plain NMC UTXOs.

Firo is registered for ordinary transparent FIRO wallet, HTLC, and ACCT trade support. Spark, Lelantus, Zerocoin, and exchange-address flows are intentionally not implemented as part of generic BTC-like support.

Komodo is registered for transparent KMD wallet, HTLC, and ACCT trade support through Electrum-compatible servers. KMD is not a legacy-Bitcoin transaction clone: spends and HTLC transactions use the Sapling transparent transaction format while staying in the generic Bitcoiny runtime. Shielded KMD wallet behavior is intentionally not implemented.

LBRY Credits is registered for ordinary transparent LBC wallet, HTLC, and ACCT trade support. LBRY claim, publish, update, and content-discovery flows are intentionally not implemented as part of generic BTC-like support, but detected claim/support/update outputs are filtered out of normal wallet spend selection so claim UTXOs are not accidentally spent as plain LBC.

## Tests

The shared BTC-like wallet, HTLC, and fee tests live in `RegisteredBitcoinyTests`. Add a fixture there for any new registered Bitcoiny chain so the common deterministic coverage runs without creating another per-coin test class.
