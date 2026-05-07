# Zcash-Family Chain Support

Pirate Chain is the first supported Zcash-family chain in Qortium. Its wallet,
lightwalletd provider, QDN library lookup, and transparent transaction parser now
run through shared `ZcashFamily*` foundations, while `PirateChain`,
`PirateWallet`, `PirateLightClient`, and `PirateChainWalletController` remain as
thin compatibility wrappers for existing API and runtime callers.

This is intended as the base for later HUSH and Zcash work. A new
Zcash-family chain should supply a wallet config, lightwalletd server list,
network parameters, wallet-library QDN metadata, address HRP, default birthday,
and any chain-specific transaction parser extensions. Do not add another full
copy of the Pirate wallet/controller/light-client code.

The current generic transaction parser intentionally reads only the transparent
fields used by the existing HTLC paths: transparent inputs, transparent outputs,
locktime, and expiry height. Shielded spend/output parsing should be added only
when a chain integration needs full shielded transaction introspection.

KMD support is intentionally paused until its active transparent/shielded wallet
behavior is verified. Chains with Sapling-compatible lightwalletd support should
be evaluated through the shared Zcash-family layer first; BTC-like transparent
coins should continue to use `BitcoinyChainSpecs`.
