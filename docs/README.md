# Qortium Documentation

This index groups the tracked Qortium docs by topic. The root
[README](../README.md) is the quick start, and the
[Qortium changelog](../QORTIUM-CHANGELOG.md) is the plain-language project
history.

## Getting Started And Testing

- [Local testnet guide](../testnet/README.md) - single-node and multi-node testnet setup
- [Testing notes](development/testing.md) - repository test guidance and opt-in checks

## Development And Operations

- [Database inspection](development/database.md) - local HSQLDB inspection notes
- [Current QDN auto-update scripts](../tools/auto-update-scripts/README.md) - current update publisher flow
- [Legacy GitHub-mirror auto-update notes](legacy/auto-updates-github-mirror.md) - older auto-update reference
- [Windows installer notes](../WindowsInstaller/README.md) - installer build notes

## Chain Design

- [Chain parameter audit](chain-design/chain-parameter-audit.md) - remaining hardcoded chain parameter review

## Trust System

- [Account trust network](trust/account-trust-network.md) - user-facing trust model
- [Aura trust-tier minting](trust/aura-trust-tier-minting.md) - deeper trust-tier design notes
- [Trust network client integration](trust/trust-network-client-integration.md) - wallet and explorer guidance
- [Trust network launch readiness](trust/trust-network-launch-readiness.md) - launch verification checklist

## Lite Node Work

- [Lite node plan](lite-node/lite-node-plan.md) - current lite-node roadmap
- [Lite node proof anchoring](lite-node/lite-node-proof-anchoring.md) - response anchoring plan
- [Lite node state-root design](lite-node/lite-node-state-root-design.md) - later proof-bearing response design

## Chat And QDN

- [Private group chat encryption plan](chat/private-group-chat-encryption.md) - planned Core-managed private chat direction
- [QDN app documentation](qdn/q-apps.md) - QDN app concepts and request examples

## Cross-Chain Support

- [Bitcoiny chain specs](cross-chain/bitcoiny-chain-specs.md) - cross-chain adapter notes
- [Foreign/foreign trade design](cross-chain/crosschain-foreign-foreign-trades.md) - trade flow design
- [Reverse ACCT trade design](cross-chain/crosschain-reverse-trades.md) - reverse trade design
- [Electrum server refresh](cross-chain/electrum-server-refresh.md) - Electrum server list maintenance
- [Zcash-family chain support](cross-chain/zcash-family-chain-support.md) - deferred native wallet notes

## Dependencies And Upstream

- [Dependency security review](dependencies/dependency-security-review.md) - current dependency security posture
- [Dependency provenance](dependencies/dependency-provenance.md) - pinned and vendored dependency rationale
- [Qortal 6.1.5 comparison](upstream/qortal-6.1.5-comparison.md) - upstream comparison and integration notes
- [Qortal v6 network notes](upstream/qortal-v6-network-notes.md) - transitional upstream network-design reference
