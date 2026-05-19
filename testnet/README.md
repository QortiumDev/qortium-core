# Local Testnet

The files in this folder are for running a disposable Qortium testnet from a
local build. The default path is a single-node testnet so testers can try the
node without setting up peers, separate machines, or a multi-node minting
rotation.

## Quick Start

Build Qortium from the repository root:

```sh
mvn -q -DskipTests package
```

Start the local testnet:

```sh
./testnet/start.sh
```

The script starts the node on the local testnet API port:

```text
http://localhost:62391
```

Stop it with:

```sh
./testnet/stop.sh
```

## What The Script Does

`start.sh` uses `settings-test.json` and `testchain.json` as tracked templates,
then creates local runtime copies:

- `settings-test-local.json`
- `testchain-local.json`
- `db-testnet/`
- `qortium-backup-test/`
- `run.log`
- `run.pid`
- `qortium.log`
- `QortiumKeyStore.jks`

The generated chain file uses a fresh genesis timestamp on the first start so
the local node does not have to catch up from an old committed date. Later
starts reuse the same local chain file until you reset the testnet, so the
database and genesis block stay in sync. The script also adds the default local
minting key with:

```sh
curl -X POST http://localhost:62391/admin/mintingaccounts -d 1CeDCg9TSdBwJNGVTGG7pCKsvsyyoEcaVXYvDT1Xb9f
```

That key is only for this disposable local testnet. The matching account joins
the minting group and publishes the corresponding minting public key directly
from the join transaction, so it can mint without receiving a prefunded
native-asset allocation.

## Resetting

Stop the node first, then remove the local runtime files:

```sh
rm -rf testnet/db-testnet \
  testnet/qortium-backup-test \
  testnet/settings-test-local.json \
  testnet/testchain-local.json \
  testnet/run.log \
  testnet/run.pid \
  testnet/qortium.log \
  testnet/QortiumKeyStore.jks
```

The next `./testnet/start.sh` run will create a fresh local chain.

## Template Settings

`settings-test.json` is intentionally local by default:

- `singleNodeTestnet` is `true`, which lets one node mint consecutive blocks
  without peers.
- the API and peer bind address are loopback-only.
- broad API whitelists are not enabled.
- outbound peer targets and UPnP are disabled.
- foreign wallets are disabled by default.

## Multi-Node Testnets

For a multi-node testnet, copy the templates for each node and change the local
copies deliberately:

- set `singleNodeTestnet` to `false`
- set `minBlockchainPeers` to the number of peers required before minting
- use a different `repositoryPath` per node
- use different `apiPort` and `listenPort` values per node
- bind to an address other nodes can reach
- configure `fixedNetwork` or add peers manually through the API
- add a valid minting key to each node with `POST /admin/mintingaccounts`

Do not reuse the generated `testchain-local.json` from one node after another
node has already started from a different genesis timestamp. Multi-node testnets
must share the exact same chain config.
