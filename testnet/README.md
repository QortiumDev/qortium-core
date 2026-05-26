# Local Testnet

The files in this folder are for running a disposable Qortium testnet from a
local build. The default path is a single-node testnet so testers can try the
node without setting up peers, separate machines, or a multi-node minting
rotation.

## First Test Walkthrough

Build Qortium from the repository root:

```sh
./build.sh
```

The helper checks Java, javac, and Maven first. If a prerequisite is missing,
it links to the official install docs and asks you to run it again after
installing the missing tool.

Start the local testnet:

```sh
./testnet/start.sh
```

Confirm that the API is reachable and the node is minting blocks:

```sh
./testnet/status.sh --wait
```

Run the live smoke checks when you want a broader local sanity test:

```sh
./testnet/smoke-test.sh --wait
```

The smoke helper uses `curl` and `jq` to check the running testnet's API,
minting account, current block, local settings, default groups, empty startup
state, peer summary, and startup log.

The status helper checks the local testnet API at:

```text
http://localhost:24891/blocks/height
```

Stop the local testnet with:

```sh
./testnet/stop.sh
```

Reset the local testnet when you want a fresh disposable chain:

```sh
./testnet/reset.sh
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
- `apikey.txt`

The generated chain file uses a fresh genesis timestamp on the first start so
the local node does not have to catch up from an old committed date. Later
starts reuse the same local chain file until you reset the testnet, so the
database and genesis block stay in sync. The script also adds the default local
minting key with:

```sh
curl -X POST http://localhost:24891/admin/mintingaccounts -d 1CeDCg9TSdBwJNGVTGG7pCKsvsyyoEcaVXYvDT1Xb9f
```

That key is only for this disposable local testnet. The matching account joins
the minting group and publishes the corresponding minting public key directly
from the join transaction, so it can mint without receiving a prefunded
native-asset allocation.

## Resetting

The reset helper stops the node if it is running, then removes the generated
local runtime files:

```sh
./testnet/reset.sh
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
