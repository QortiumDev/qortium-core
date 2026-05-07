# Zcash-Family Chain Support

Pirate Chain is the first supported Zcash-family chain in Qortium. Its wallet,
lightwalletd provider, QDN library lookup, and transparent transaction parser now
run through shared `ZcashFamily*` foundations, while `PirateChain`,
`PirateWallet`, `PirateLightClient`, and `PirateChainWalletController` remain as
thin compatibility wrappers for existing API and runtime callers.

This is intended as the base for later HUSH and Zcash work, but Java wrappers
are not enough to enable another chain. A new Zcash-family chain should supply a
wallet config, lightwalletd server list, network parameters, wallet-library QDN
metadata, address HRP, default birthday, and any chain-specific transaction
parser extensions. Do not add another full copy of the Pirate
wallet/controller/light-client code.

The current generic transaction parser intentionally reads only the transparent
fields used by the existing HTLC paths: transparent inputs, transparent outputs,
locktime, and expiry height. Shielded spend/output parsing should be added only
when a chain integration needs full shielded transaction introspection.

## Deferred HUSH/Zcash Work

HUSH and Zcash-style chain support is paused until the native wallet side is
real and reproducible. Pirate Chain works because Qortium already knows how to
load the ARRR Rust JNI wallet library from QDN and where to find the ARRR
`coinparams.json`, Sapling spend, and Sapling output files. Another
Sapling-family chain needs the same operational foundation before it should be
registered as active runtime support.

Before enabling HUSH, Zcash, or another Sapling-family chain, verify and
document:

- Rust/lightwallet JNI source, build steps, and upstream commit or release
- per-OS native library outputs and filenames matching `LiteWalletJni`
- `coinparams.json`, `saplingoutput_base64`, and `saplingspend_base64`
  compatibility for that chain
- QDN publication name, service, identifier, and transaction signature for the
  wallet-library package
- reliable lightwalletd servers and their SSL/plaintext behavior
- seed phrase, wallet restore, sync, receive, private-key export, send, and
  shielded/transparent address behavior
- raw transaction compatibility with `ZcashFamilyTransactionParser`, or the
  exact parser extensions required
- deterministic tests that prove the chain-specific config and parser behavior
  without requiring live servers
- opt-in live tests that prove the published native library and lightwalletd
  servers can initialize and sync a wallet

If any of those pieces are missing, keep the chain documented as planned rather
than registering runtime support that compiles but cannot operate.

## KMD Routing Note

KMD should be evaluated separately before implementation. If the support target
is transparent KMD over Electrum-compatible servers, add it through
`BitcoinyChainSpecs` like the other BTC-like transparent coins. Only route KMD
through the Zcash-family layer if the intended support requires a
Sapling/lightwalletd-style native wallet package.
