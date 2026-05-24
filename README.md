# Qortium Core

Qortium is a stripped-down and cleaned-up fork of Qortal Core. The goal is to
keep a practical blockchain node foundation that other projects can understand,
test, and adapt into their own chain with less inherited baggage.

This repository contains the Java node, blockchain processing code, local APIs,
networking, build tooling, and the early Qortium documentation set.

## Current Status

Qortium is active development software for builders and testers. It is not yet
packaged as a polished end-user application, and some inherited Qortal/QDN
terminology still appears in older docs and APIs while the fork is cleaned up.

For a plain-language history of the fork work, start with
[QORTIUM-CHANGELOG.md](QORTIUM-CHANGELOG.md).

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
http://localhost:62391
```

Stop it with:

```sh
./testnet/stop.sh
```

See [testnet/README.md](testnet/README.md) for the full first-test walkthrough,
reset instructions, generated runtime files, and multi-node testnet notes.

## Preview Network

The shared Qortium preview network is a public alpha/demo profile for connecting
to the seed node at `146.103.42.59`. It is separate from the local single-node
testnet and uses normal multi-node rules.

```sh
./build.sh
./preview/start.sh
./preview/status.sh --wait
```

The preview scaffold is connection-ready and includes two public genesis
minting authorizations for initial seed/local testing. Private minting keys are
not committed. Public testers should start with
[preview/TESTER-GUIDE.md](preview/TESTER-GUIDE.md); seed operators and
minting-key setup should use [preview/README.md](preview/README.md).

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
mvn -q test
```

For IDE runs, use Java 17 and the main class:

```text
org.qortal.controller.Controller
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
beside the scripts or packaging files they describe.
