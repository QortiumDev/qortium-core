# Qortium Preview Network

The preview network is a shared public alpha/demo network for Qortium. It is
separate from the disposable local `testnet/` profile:

- `testnet/` is a single-node local lab that can mint blocks by itself.
- `preview/` is a normal multi-node preview network that connects to the
  public seed at `146.103.42.59`.

The preview network intentionally does not use `singleNodeTestnet`. That keeps
preview behavior closer to the normal node rules while Qortium is still changing
quickly.

## Current State

The preview scaffold is connection-ready, but it does not include committed
minting identities yet. Nodes can start from the fixed preview genesis and
connect to the seed, but the chain is expected to stay at genesis until launch
minting keys are added in a later update.

Preview uses accelerated chain timing for early testing. Reward schedule
intervals are compressed by 100 compared with the current Qortium defaults, and
account level block requirements are divided by 10. Reward share bins need 5
online accounts before activating so small preview groups can exercise reward
distribution sooner.

## Start A Preview Participant

Build Qortium from the repository root:

```sh
./build.sh
```

Start a participant node:

```sh
./preview/start.sh
```

Check that the local API is reachable:

```sh
./preview/status.sh --wait
```

Stop the node:

```sh
./preview/stop.sh
```

Reset generated preview runtime files:

```sh
./preview/reset.sh
```

## Start The VPS Seed

On the seed VPS, use seed mode:

```sh
./preview/start.sh --seed
```

The seed profile advertises the public seed IP `146.103.42.59` and does not
try to peer with itself. It uses the same fixed preview genesis and ports as
participant nodes.

## Ports

The preview profile reuses the `623xx` test-network port range:

- API: `62391`
- P2P: `62392`
- QDN/data: `62394`

The API is configured as restricted and whitelisted for local access. For a
public VPS, firewall the API port unless you intentionally need remote
administration. Public preview peers need to reach the P2P port, and QDN/data
peers need to reach the QDN port.

## Runtime Files

`start.sh` copies the tracked settings template to a local runtime settings
file before starting the node. This prevents API settings changes from editing
tracked files.

Generated runtime files include:

- `settings-preview-local.json`
- `settings-preview-seed-local.json`
- `db-preview/`
- `data-preview/`
- `qortium-backup-preview/`
- `run.log`
- `run.pid`
- `qortium.log`
- `QortiumKeyStore.jks`
- `apikey.txt`

These files are ignored by git and can be removed with `./preview/reset.sh`.

## Launch Minting

Preview minting requires real launch minting identities to be added later. Do
not commit private minting keys. The eventual launch update should add only the
required public chain data, then each minting node should add its own private
minting key locally through the secured admin API.
