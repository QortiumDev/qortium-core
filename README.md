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

Build from the repository root:

```sh
mvn -q -DskipTests package
```

Start the local testnet:

```sh
./testnet/start.sh
```

The local testnet API listens at:

```text
http://localhost:62391
```

Stop it with:

```sh
./testnet/stop.sh
```

See [testnet/README.md](testnet/README.md) for reset instructions, generated
runtime files, and multi-node testnet notes.

## Local Node Build And Run

For normal local node operation, build the jar and use the root lifecycle
helpers:

```sh
mvn -q -DskipTests package
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
mvn -q -DskipTests compile
mvn -q test
```

For IDE runs, use Java 17 and the main class:

```text
org.qortal.controller.Controller
```

Use `settings.json` as the program argument when running a normal local node.

## Documentation

- [Qortium changelog](QORTIUM-CHANGELOG.md) - plain-language project history
- [Local testnet guide](testnet/README.md) - single-node and multi-node testnet setup
- [Testing notes](docs/testing.md) - repository test guidance
- [Dependency security review](docs/dependency-security-review.md) - current security posture and dependency notes
- [Dependency provenance](docs/dependency-provenance.md) - pinned and vendored dependency rationale
- [Qortal 6.1.5 comparison](docs/qortal-6.1.5-comparison.md) - upstream comparison and integration notes
- [Account trust network](docs/account-trust-network.md) - trust model and rating behavior
- [Trust network client integration](docs/trust-network-client-integration.md) - wallet/explorer integration guidance
- [Private group chat encryption plan](docs/private-group-chat-encryption.md) - planned private chat direction

Some root-level documents, including `AutoUpdates.md`, `DATABASE.md`,
`Q-Apps.md`, and `v6.md`, are inherited or transitional references. They will be
reviewed and reorganized in later documentation passes.
