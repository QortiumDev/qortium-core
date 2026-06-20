# Qortium Documentation

This index groups the tracked Qortium docs by topic. The root
[README](../README.md) is the quick start, and the
[Qortium changelog](../QORTIUM-CHANGELOG.md) is the plain-language project
history.

## Document Status Labels

- `Tester guide`: current instructions for nontechnical testers.
- `Developer reference`: current technical guidance for contributors.
- `Chain builder reference`: current guidance for projects using Qortium as a
  base chain.
- `Planning note`: active design direction, not final user instructions.
- `Legacy reference`: retained historical material, not the recommended path.
- `Upstream reference`: Qortal reference material kept for comparison only.

## Getting Started And Testing

- `Tester guide` [Tester build helper](../build.sh) - guided local build with prerequisite checks
- `Tester guide` [Local testnet guide](../testnet/README.md) - first-test walkthrough and multi-node setup
- `Developer reference` [Testing notes](development/testing.md) - repository test guidance and opt-in checks
- `Developer reference` [Developer proxy](development/developer-proxy.md) - local QDN app proxy setup and safety boundary

## Development And Operations

- `Developer reference` [Database inspection](development/database.md) - local HSQLDB inspection notes
- `Developer reference` [I2P fallback operator guide](networking/i2p-fallback-operator-guide.md) - standalone Core setup and verification for optional i2pd fallback
- `Developer reference` [Current QDN auto-update scripts](../tools/auto-update-scripts/README.md) - current update publisher flow
- `Developer reference` [Core preview 14 release prep](releases/v1.0.0-preview.14.md) - post-merge prerelease checklist and draft release notes
- `Developer reference` [Windows installer notes](../WindowsInstaller/README.md) - installer build notes

## Chain Design

- `Chain builder reference` [Chain parameter audit](chain-design/chain-parameter-audit.md) - remaining hardcoded chain parameter review
- `Chain builder reference` [On-chain chain parameters](chain-design/on-chain-chain-parameters.md) - development-group approved runtime parameter updates

## Trust System

- `Chain builder reference` [Account trust network](trust/account-trust-network.md) - user-facing trust model
- `Planning note` [Aura trust-tier minting](trust/aura-trust-tier-minting.md) - deeper trust-tier design notes
- `Developer reference` [Trust network client integration](trust/trust-network-client-integration.md) - wallet and explorer guidance
- `Planning note` [Trust network launch readiness](trust/trust-network-launch-readiness.md) - launch verification checklist

## Lite Node Work

- `Planning note` [Lite node plan](lite-node/lite-node-plan.md) - current lite-node roadmap
- `Planning note` [Lite node proof anchoring](lite-node/lite-node-proof-anchoring.md) - response anchoring plan
- `Planning note` [Lite node state-root design](lite-node/lite-node-state-root-design.md) - later proof-bearing response design

## Chat And QDN

- `Developer reference` [Private group chat encryption workflow](chat/private-group-chat-encryption.md) - Core-managed private group chat design and client API flow
- `Developer reference` [QDN app documentation](qdn/q-apps.md) - QDN app concepts and request examples
- `Developer reference` [Multi-file resources and entry points](qdn/multi-file-resources.md) - client guide to multi-file resources, the entryPoint, default-file resolution, private resources, and app responsibilities
- `Developer reference` [QDN encrypted data envelope](qdn/encrypted-data-envelope.md) - v1 envelope format for private resource encryption, with legacy-prefix fallback

## Cross-Chain Support

- `Developer reference` [Bitcoiny chain specs](cross-chain/bitcoiny-chain-specs.md) - cross-chain adapter notes
- `Planning note` [Foreign/foreign trade design](cross-chain/crosschain-foreign-foreign-trades.md) - trade flow design
- `Planning note` [Reverse ACCT trade design](cross-chain/crosschain-reverse-trades.md) - reverse trade design
- `Developer reference` [Electrum server refresh](cross-chain/electrum-server-refresh.md) - Electrum server list maintenance
- `Developer reference` [ElectrumX TLS trust model](cross-chain/electrum-tls-trust.md) - certificate pinning and trust modes
- `Planning note` [Zcash-family chain support](cross-chain/zcash-family-chain-support.md) - deferred native wallet notes

## Dependencies And Upstream

- `Developer reference` [Dependency security review](dependencies/dependency-security-review.md) - current dependency security posture
- `Developer reference` [Code scanning triage](dependencies/code-scanning-triage.md) - remaining CodeQL alert review and handling guidance
- `Developer reference` [Dependency provenance](dependencies/dependency-provenance.md) - pinned and vendored dependency rationale
- `Upstream reference` [Qortal 6.1.5 comparison](upstream/qortal-6.1.5-comparison.md) - upstream comparison and integration notes
- `Upstream reference` [Qortal v6 network notes](upstream/qortal-v6-network-notes.md) - transitional upstream network-design reference
