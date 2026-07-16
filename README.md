# Qortium Core

Qortium is a stripped-down and cleaned-up fork of Qortal Core. The goal is to
keep a practical blockchain node foundation that other projects can understand,
test, and adapt into their own chain with less inherited baggage.

This repository contains the Java node, blockchain processing code, local APIs,
networking, build tooling, and the early Qortium documentation set.

## Current Status

Qortium is active development software for builders and testers. It is not yet
packaged as a polished end-user application, and some inherited documentation
and workflows are still being cleaned up.

For a plain-language history of the fork work, start with
[QORTIUM-CHANGELOG.md](QORTIUM-CHANGELOG.md).

## What Qortium Changes Vs Qortal

Qortium is an independent fork for building derived chains; it is not a
replacement for Qortal. Notable additions so far:

- Dev-group approval governance split - separates development-transaction
  approval from the general minting admin group; see
  [docs/design/dev-group-approval-split.md](docs/design/dev-group-approval-split.md).
- Poll upgrades - optional scheduled start times and multi-option voting in a
  single transaction, while legacy single-choice votes keep working.
- Websocket notification subscriptions - clients can subscribe to filtered
  node events (for example specific transaction types or QDN publishes).
- Account trust network and resource-rating APIs - see
  [docs/trust/account-trust-network.md](docs/trust/account-trust-network.md).
- Optional I2P fallback transport for peers without inbound TCP; see
  [docs/design/i2p-fallback-transport.md](docs/design/i2p-fallback-transport.md).
- QDN auto-update - nodes can fetch and apply signed updates over QDN when the
  operator opts in via `autoUpdateMode`.

For a feature-by-feature comparison with upstream, see
[docs/upstream/qortal-6.1.5-comparison.md](docs/upstream/qortal-6.1.5-comparison.md).

## Tester Quick Start

The easiest first test is the local single-node testnet. It starts a disposable
chain on your machine and avoids setting up peers or a multi-node minting
rotation.

Prerequisites:

- Java 17 or newer
- Maven

Use the build helper from the repository root:

```sh
./build.sh
```

The helper checks Java, javac, and Maven first. If something is missing, it
links to the official install docs and stops before running the build.

Start the local testnet and confirm that it is minting blocks:

```sh
./testnet/start.sh
./testnet/status.sh --wait
```

The local testnet API listens at:

```text
http://localhost:24891
```

Stop it with:

```sh
./testnet/stop.sh
```

See [testnet/README.md](testnet/README.md) for the full first-test walkthrough,
reset instructions, generated runtime files, and multi-node testnet notes.

## Preview Network

The shared Qortium preview network ("Previewnet") is a live public alpha/demo
network with seed nodes at `146.103.42.59` and `185.207.104.78`. It is separate
from the local single-node testnet and uses normal multi-node rules. Consensus
features activate at scheduled block heights, so testers must run the latest
release to stay on the network - an older build will fork off at the next
activation height.

There are two ways to join. The easy path is to download the prebuilt
`qortium-preview.zip` from the latest release at
<https://github.com/QortiumDev/qortium-core/releases> - it only needs Java 17
or newer. Alternatively, build from source:

```sh
./build.sh
./preview/start.sh
./preview/status.sh --wait
```

Public testers should start with
[preview/TESTER-GUIDE.md](preview/TESTER-GUIDE.md), which covers both paths.
Preview nodes can also keep themselves current through QDN auto-update: set
`autoUpdateMode` in the local settings (the default is `OFF`), or export
`QORTIUM_PREVIEW_AUTO_UPDATE_MODE` before running `preview/start.sh`. Seed
operators and minting-key setup should use
[preview/README.md](preview/README.md) and
[preview/OPERATOR-RUNBOOK.md](preview/OPERATOR-RUNBOOK.md).

## Local Node Build And Run

For normal local node operation, build the jar with the helper and use the root
lifecycle scripts:

```sh
./build.sh
./start.sh
```

Stop the node with:

```sh
./stop.sh
```

The root scripts look for `qortium.jar` first and otherwise use a built
`target/qortium*.jar`. Runtime state such as `settings.json`, `run.log`, and
the database directory is local to the repository working directory unless
configured otherwise.

### Optional I2P Fallback

Qortium Core can use I2P as a fallback transport for peers that cannot accept
inbound TCP. Direct TCP remains primary, and Core runs normally without I2P.

Qortium Home is expected to manage `i2pd` for normal desktop users. Standalone
Core operators who want I2P fallback can run a local `i2pd` SAM bridge on
`127.0.0.1:7656`; see
[docs/networking/i2p-fallback-operator-guide.md](docs/networking/i2p-fallback-operator-guide.md).
Operators who do not want I2P attempts can set `"i2pEnabled": false` in
`settings.json`.

## Docker

Docker support is available for developers who prefer a containerized node.

```sh
cp .env.example .env
docker compose up -d --build
docker compose logs -f qortium
docker compose down
```

For an internal Docker network without host port publishing:

```sh
docker compose -f docker-compose.internal.yml up -d --build
```

Container data and `settings.json` are stored under `./data/qortium`. The JVM
start arguments file is stored at `./data/qortium/start-arguments.txt`.

## Development

Useful local checks:

```sh
mvn -q -DskipTests package
mvn -q -DskipTests compile
mvn -q test -DskipJUnitTests=false
```

Note that tests are skipped by default (`skipJUnitTests` is `true` in
`pom.xml`), so a bare `mvn test` runs zero tests. See
[docs/development/testing.md](docs/development/testing.md) for the full test
guidance, including coverage runs and opt-in live checks.

For IDE runs, use Java 17 and the main class:

```text
org.qortium.controller.Controller
```

Use `settings.json` as the program argument when running a normal local node.

## Documentation

- [Documentation index](docs/README.md) - topic-based guide to the docs tree
- [Qortium changelog](QORTIUM-CHANGELOG.md) - plain-language project history
- [Local testnet guide](testnet/README.md) - single-node and multi-node testnet setup
- [Testing notes](docs/development/testing.md) - repository test guidance
- [Account trust network](docs/trust/account-trust-network.md) - trust model and rating behavior
- [QDN app documentation](docs/qdn/q-apps.md) - QDN app concepts and request examples

Most detailed docs now live under `docs/` by topic. Tool-specific docs remain
beside the scripts or packaging files they describe. Release, auto-update, and
maintenance helper scripts live under `tools/`. The inherited Windows installer
project lives under `WindowsInstaller/` (see
[WindowsInstaller/README.md](WindowsInstaller/README.md); developer reference
only for now).

## License And Contact

- Qortium Core is licensed under the GNU General Public License v3.0; see
  [LICENSE](LICENSE).
- Report bugs and request features through
  [GitHub Issues](https://github.com/QortiumDev/qortium-core/issues).
- Releases: <https://github.com/QortiumDev/qortium-core/releases>
- Homepage: <https://qortium.app>
