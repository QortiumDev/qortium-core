# Bitcoiny Chain Specs

BTC-like crosschain support is driven by `BitcoinyChainSpecs`.

Each spec defines the chain's canonical registry name, currency code, SLIP-44 coin type, display name, fee defaults, minimum order amount, supported networks, BIP122 chain ids, genesis hashes, and Electrum refresh metadata. The runtime creates one generic `RegisteredBitcoiny` instance from the selected spec and network instead of maintaining separate per-coin wrapper classes.

## Chain IDs

For registered BTC-like chains, the shared Bitcoiny ACCT stores the active network's BIP122 chain reference in AT data. This is the 16-byte binary form of the `bip122:<32 hex chars>` CAIP-2 id, where the 32 hex characters are normally the first 32 characters of the chain's genesis block hash. For fork chains that share another chain's genesis, use the first block hash that uniquely identifies the fork instead.

Mainnet chain ids for the currently registered BTC-like chains are:

| Chain ID | Name | Code |
| --- | --- | --- |
| `bip122:000000000019d6689c085ae165831e93` | `BITCOIN` | `BTC` |
| `bip122:000000000000000000651ef99cb9fcbe` | `BITCOINCASH` | `BCH` |
| `bip122:12a765e31ffd4059bada1e25190f6e98` | `LITECOIN` | `LTC` |
| `bip122:1a91e3dace36e2be3bf030a65679fe82` | `DOGECOIN` | `DOGE` |
| `bip122:00000ffd590b1485b3caadc19b22e637` | `DASH` | `DASH` |
| `bip122:0000000032fe677166d54963b62a4677` | `PEERCOIN` | `PPC` |
| `bip122:000000000062b72c5e2ceb45fbc8587e` | `NAMECOIN` | `NMC` |
| `bip122:4381deb85b1b2c9843c222944b616d99` | `FIRO` | `FIRO` |
| `bip122:027e3758c3a65b12aa1046462b486d0a` | `KOMODO` | `KMD` |
| `bip122:ac2cd7d37177140ea4991cf630c0b9c7` | `VERUSCOIN` | `VRSC` |
| `bip122:00040fe8ec8471911baa1db1266ea15d` | `ZCASH` | `ZEC` |
| `bip122:9c89283ba0f3227f6c03b70216b9f665` | `LBRYCREDITS` | `LBC` |
| `bip122:00000fc63692467faeb20cdb3b53200d` | `VERGE` | `XVG` |
| `bip122:7497ea1b465eb39f1c8f507bc877078f` | `DIGIBYTE` | `DGB` |
| `bip122:0000006b444bc2f2ffe627be9d9e7e7a` | `RAVENCOIN` | `RVN` |

Bitcoin Cash shares Bitcoin's genesis block, so its AT chain reference uses the first 16 bytes of the first BCH-only fork block hash instead of the shared genesis hash. VRSC shares KMD's genesis block, so its AT chain reference uses the first 16 bytes of Verus block 10000's checkpoint hash instead of the shared genesis hash.

SLIP-44 coin types are still kept as wallet derivation metadata: BTC `0`, LTC `2`, DOGE `3`, DASH `5`, PPC `6`, NMC `7`, DGB `20`, XVG `77`, ZEC `133`, VRSC `133` (used by upstream Verus wallet metadata even though SLIP-44 labels 133 as Zcash), FIRO `136` (listed in SLIP-44 as XZC/ZCoin), LBC `140`, KMD `141`, BCH `145`, and RVN `175`.

## Network Settings

Use `bitcoinyNetworks` in `settings.json` to choose the active network for BTC-like chains:

```json
{
  "bitcoinyNetworks": {
    "BTC": "TEST4",
    "BCH": "MAIN",
    "LTC": "TEST4",
    "DOGE": "MAIN",
    "DGB": "MAIN",
    "RVN": "MAIN",
    "DASH": "MAIN",
    "PPC": "MAIN",
    "NMC": "MAIN",
    "FIRO": "MAIN",
    "KMD": "MAIN",
    "VRSC": "MAIN",
    "ZEC": "MAIN",
    "LBC": "MAIN",
    "XVG": "MAIN"
  }
}
```

Missing entries default to `MAIN`. Supported network names are currently `MAIN`, `TEST3`, `TEST4`, and `REGTEST`, but each chain only advertises networks that have explicit params and usable server support. BTC keeps legacy `TEST3` available, but `TEST4` is the preferred Bitcoin test network for new test settings. BCH supports `TEST4` via `tbch4` servers while keeping `MAIN` as the default when no setting is supplied.

Use `bitcoinyServers` to persist Electrum server overrides for a specific BTC-like coin and network:

```json
{
  "bitcoinyServers": {
    "BTC": {
      "MAIN": {
        "replaceDefaults": false,
        "servers": [
          {
            "hostName": "electrum.example.org",
            "port": 50002,
            "connectionType": "SSL",
            "certificateSha256Fingerprint": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
          }
        ],
        "disabledServers": [
          {"hostName": "bad.example.org", "port": 50002, "connectionType": "SSL"}
        ]
      }
    }
  }
}
```

Generated Electrum server lists remain the default source. `servers` appends user-provided servers, `disabledServers` suppresses generated/default servers, and `replaceDefaults` makes the node use only the configured `servers` for that coin/network. SSL servers are preferred whenever at least one SSL server is available after merging, and plaintext TCP Electrum servers are ignored by default. Set `allowPlaintextElectrumServers` to `true` only for explicit compatibility or development use.

SSL Electrum servers use normal JVM certificate validation unless a server entry includes `certificateSha256Fingerprint`. The fingerprint is a SHA-256 hash of the server's leaf certificate encoded as 64 hex characters, with optional colons accepted in settings input. Use certificate pins only for self-signed or otherwise non-publicly trusted Electrum servers that you have verified out of band.

## Adding A Coin

Adding another BTC-like coin should start with a new `BitcoinyChainSpec` builder entry. The builder keeps the common mainnet, testnet, regtest, Electrum refresh, fee, and minimum-order wiring in one place, so a new coin entry should mostly supply chain metadata and any needed explicit hooks. New chains, and existing chains migrated away from inherited params classes, should use `StaticBitcoinyParams` instead of adding another coin-specific params class. The registry then feeds network resolution, runtime startup, supported-chain lookup, and the Electrum server refresh tool. Coin-specific behavior should be added to the spec as an explicit hook, as with Bitcoin's default spend fee override and Litecoin's P2SH address normalization.

`ForeignBlockchainRegistry` is the central lookup point for crosschain runtime code. It resolves canonical names and currency codes, maps Bitcoiny BIP122 chain ids back to registered chains, owns the runtime instance definitions, exposes registry iteration helpers, owns the shared ACCT and trade-bot routing registries, drives API/websocket blockchain filters from string names instead of enum parameters, and feeds foreign-fee processing and backup/import paths. New backend code should use the registry directly.

The shared Bitcoiny ACCT stores only the active network's BIP122 chain reference in AT data. Build and parse paths resolve that chain id through `ForeignBlockchainRegistry`, so the ACCT code does not need per-coin enum cases.

Before coding a new coin, do a compatibility precheck:

- standard legacy transaction serialization and standard SIGHASH behavior, or an explicit `BitcoinyTransactionFormat`/signing hook for fork-ID, replay-protection, timestamp, or Sapling-transparent rules
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

Bitcoin Cash is registered for ordinary BCH wallet, HTLC, and ACCT trade support. BCH shares Bitcoin's genesis, so the shared Bitcoiny ACCT uses the first BCH-only fork block reference to distinguish BCH trades from BTC trades. The generic runtime accepts legacy Base58 addresses in BCH context, generates and normalizes `bitcoincash:` or `bchtest:` CashAddr addresses for the selected network, and builds BCH spends and HTLC transactions with fork-ID signatures. CashTokens and P2SH32 are intentionally outside the generic path; CashToken-prefixed outputs are filtered out of wallet spend selection.

Peercoin is registered for ordinary transparent PPC wallet, HTLC, and ACCT trade support. PPC uses six decimal places instead of the usual eight, so shared Bitcoiny config carries per-chain decimal metadata for formatting and Electrum verbose transaction parsing. PPC also uses Peercoin's version-3 transaction format for newly built spends and HTLC transactions so they do not include the pre-version-3 transaction timestamp field.

Namecoin is registered for ordinary NMC wallet, HTLC, and ACCT trade support. Name registration/update flows are intentionally not implemented in core yet, but detected Namecoin name outputs are filtered out of normal wallet spend selection so they are not accidentally spent as plain NMC UTXOs.

Firo is registered for ordinary transparent FIRO wallet, HTLC, and ACCT trade support. Spark, Lelantus, Zerocoin, and exchange-address flows are intentionally not implemented as part of generic BTC-like support.

Komodo is registered for transparent KMD wallet, HTLC, and ACCT trade support through Electrum-compatible servers. KMD is not a legacy-Bitcoin transaction clone: spends and HTLC transactions use the Sapling transparent transaction format while staying in the generic Bitcoiny runtime. Shielded KMD wallet behavior is intentionally not implemented.

VerusCoin is registered for ordinary transparent VRSC wallet, HTLC, and ACCT trade support through Electrum-compatible servers. VRSC shares KMD's genesis and Sapling-transparent transaction route, but it uses a Verus checkpoint-based BIP122 reference so trades cannot collide with KMD. VerusID, PBaaS, reserve/multicurrency transactions, and shielded wallet behavior are intentionally outside generic BTC-like support.

Zcash is registered for transparent-only ZEC wallet, HTLC, and ACCT trade support through Electrum-compatible servers. It supports `t1` and `t3` transparent addresses, two-byte Zcash Base58 address prefixes, and ZIP225/v5 transparent transaction building. Shielded, unified, Sapling, and Orchard wallet flows are intentionally outside generic BTC-like support.

LBRY Credits is registered for ordinary transparent LBC wallet, HTLC, and ACCT trade support. LBRY claim, publish, update, and content-discovery flows are intentionally not implemented as part of generic BTC-like support, but detected claim/support/update outputs are filtered out of normal wallet spend selection so claim UTXOs are not accidentally spent as plain LBC.

Verge is registered for ordinary transparent XVG wallet, HTLC, and ACCT trade support. XVG uses six decimal places and includes a transaction timestamp field in normal transparent transaction serialization, so shared Bitcoiny support uses a timestamped legacy transaction builder/parser for XVG. Verge stealth transactions, messaging, Tor/I2P routing, and domain-name send flows are intentionally outside generic BTC-like support.

## Tests

The shared BTC-like wallet, HTLC, and fee tests live in `RegisteredBitcoinyTests`. Add a fixture there for any new registered Bitcoiny chain so the common deterministic coverage runs without creating another per-coin test class.
