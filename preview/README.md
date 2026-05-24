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

The preview scaffold is connection-ready and includes two initial public
minting authorizations in genesis, one for the seed node and one for a local
test node. The private minting keys are not committed; each node has to install
its own ignored local key before it can mint.

Preview also bootstraps the native asset at asset ID `0` and gives each initial
minting account `1,000,000` preview native units. This keeps chat, QDN publish,
minting-group joins, and other normal transactions on the paid-fee path during
alpha testing. New testers still need an existing funded preview account to
send them a small amount before they can submit paid transactions.

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

On desktop systems, the preview launcher opens the normal Qortium splash and
tray UI when a graphical display is available. On VPS or terminal-only systems,
it automatically starts Java in headless mode. You can override detection with:

```sh
./preview/start.sh --gui
./preview/start.sh --headless
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

Participant nodes allow normal local transaction-builder API calls so testers
can create payments, group joins, chain-parameter proposals, and other signed
transactions through their own local node. The public seed profile stays API
restricted. Both profiles keep the API whitelisted for local access only.

For a public VPS, firewall the API port unless you intentionally need remote
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

Preview genesis authorizes these initial minting accounts:

| Role | Account Address | Account Public Key | Minting Public Key | Initial Native Balance |
| --- | --- | --- | --- | --- |
| seed | `QXhkAy3zNBQwzxxJLLP9u42Ec2XvASyvf3` | `HADRP9cBQ5EM7vkKmJMP3xAjBdNsQjX8hQTK129ZUsiq` | `6Ue5kRXpHbNrQguXjpHuTu6RzPiqZrYLStJ6gmBDobov` | `1,000,000` |
| local | `QaLdnApWW3hps1qXM8cpsL1pVgw7RtyJmN` | `BUL1j6C63NJqEfMmopqovRC3NFRsHTHGMwGPS3ut1tNY` | `6DdhEueMEopFphx81ywZ5WWdZHCUDERi9J43rjQDtvMV` | `1,000,000` |

The corresponding private keys are stored locally in
`preview/secrets/initial-minting-accounts.json`, which is ignored by git. Do
not commit private minting keys.

After a node starts and creates `preview/apikey.txt`, install the correct
minting private key for that node:

```sh
curl -X POST \
  -H "X-API-KEY: $(cat preview/apikey.txt)" \
  -H "Content-Type: text/plain" \
  --data "MINTING_PRIVATE_KEY_FROM_PREVIEW_SECRETS" \
  http://127.0.0.1:62391/admin/mintingaccounts
```

Use the `mintingPrivateKey` value from the ignored secrets file. Do not use the
account private key as the minting key.
