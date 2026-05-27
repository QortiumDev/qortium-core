# Qortium Preview Network

The preview network is a shared public alpha/demo network for Qortium. It is
separate from the disposable local `testnet/` profile:

- `testnet/` is a single-node local lab that can mint blocks by itself.
- `preview/` is a normal multi-node preview network that connects to the
  public seeds at `146.103.42.59` and `185.207.104.78`.

The preview network intentionally does not use `singleNodeTestnet`. That keeps
preview behavior closer to the normal node rules while Qortium is still changing
quickly.

For the public tester walkthrough, start with
[TESTER-GUIDE.md](TESTER-GUIDE.md). Seed operators should use
[OPERATOR-RUNBOOK.md](OPERATOR-RUNBOOK.md). This file is the more detailed
technical reference for the preview profile itself.

## Current State

The preview scaffold is connection-ready and includes three initial public
minting authorizations in genesis, one for each seed node and one for a local
test node. The private minting keys are not committed; each node has to install
its own ignored local key before it can mint.

Preview does not bootstrap a native asset or prefund any accounts. Blocks can
be minted before asset ID `0` exists, but native rewards are skipped until the
development group later approves an explicit native-asset bootstrap. Normal
fee-bearing transactions should use the configured MemoryPoW fee alternative
while the preview has no native asset.

Preview uses accelerated chain timing for early testing. Reward schedule
intervals are compressed by 100 compared with the current Qortium defaults, and
account level block requirements are divided by 10. Reward share bins need 5
online accounts before activating so small preview groups can exercise reward
distribution sooner. Chain-parameter updates use a 20-block activation delay on
preview so governance changes can be tested within the same session.

## Start A Preview Participant

Build Qortium from the repository root:

```sh
./build.sh
```

Start a participant node:

```sh
./preview/start.sh
```

On Windows, use the matching batch wrappers:

```bat
preview\start.bat
preview\status.bat --wait
preview\stop.bat
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

## Start The VPS Seeds

Seed operators should also read [OPERATOR-RUNBOOK.md](OPERATOR-RUNBOOK.md)
before updating, restarting, or resetting public seed nodes.

On the Regxa seed VPS, use the Regxa seed profile:

```sh
./preview/start.sh --seed-regxa
```

The shorter `--seed` option is kept as an alias for `--seed-regxa`.

On the Netcup seed VPS, use the Netcup seed profile:

```sh
./preview/start.sh --seed-netcup
```

Each seed profile advertises its own public IP and lists the other seed as an
initial peer. They use the same fixed preview genesis and ports as participant
nodes.

## Ports

The preview profile uses the `248xx` test/preview-network port range:

- API: `24891`
- P2P: `24892`
- QDN/data: `24894`

Participant nodes allow normal local transaction-builder API calls so testers
can create payments, group joins, chain-parameter proposals, and other signed
transactions through their own local node. Preview participant and seed nodes
also expose a public read-only API allowlist by default so Qortium Home and
other clients can discover useful public nodes, browse QDN resources, and read
common chain data without needing the local API key. Public write, admin,
utility, list-management, and peer mutation routes are still blocked unless the
request comes from the local API whitelist.

The public seed profiles remain API restricted for local transaction-builder
style endpoints, but also serve the same public read and QDN browsing allowlist
so the initial preview network has known public API nodes available.

Auto-update is explicitly off in the tracked preview settings templates.
Operators can enable it in a local runtime settings file when testing approved
QDN update manifests, but public preview defaults do not automatically install
new jars. The preview launcher preserves a local `autoUpdateMode` override when
restarting from the tracked template.

For a public VPS, expose the API port only if you want that public read-only
access. Public preview peers need to reach the P2P port, and QDN/data peers
need to reach the QDN port. On a home network, router or firewall forwarding is
still required before other users can reach ports `24891`, `24892`, or `24894`.

## Runtime Files

`start.sh` copies the tracked settings template to a local runtime settings
file before starting the node. This prevents API settings changes from editing
tracked files.

Generated runtime files include:

- `settings-preview-local.json`
- `settings-preview-seed-local.json`
- `settings-preview-seed-netcup-local.json`
- `db-preview/`
- `data-preview/`
- `qortium-backup/`
- `qortium-backup-preview/`
- `run.log`
  - Unix launcher details and Java stdout/stderr; Windows Java stdout
- `run-error.log`
  - Windows Java stderr capture
- `run.pid`
- `qortium.log`
  - the main application log written by Log4j
- `QortiumKeyStore.jks`
- `apikey.txt`

These files are ignored by git and can be removed with `./preview/reset.sh`.

## Build A Preview Release Zip

For GitHub pre-releases, create the tester zip from the repository root:

```sh
./preview/package-release.sh
```

If the jar has already been built, skip the build step:

```sh
./preview/package-release.sh --skip-build
```

Before uploading the zip, smoke-check that the extracted package creates the
expected runtime logs:

```sh
./preview/smoke-release-logging.sh --package=target/qortium-preview.zip
```

The default output is `target/qortium-preview.zip`. The package includes the
jar, preview configs, Unix shell scripts, Windows wrappers, and the public
tester and operator guides. It intentionally excludes generated runtime files,
local settings, databases, logs, API keys, keystores, backups, and ignored
preview secrets.

## Launch Minting

Preview genesis authorizes these initial minting accounts:

| Role | Account Address | Account Public Key | Minting Public Key | Initial Native Balance |
| --- | --- | --- | --- | --- |
| seed-regxa | `QXhkAy3zNBQwzxxJLLP9u42Ec2XvASyvf3` | `HADRP9cBQ5EM7vkKmJMP3xAjBdNsQjX8hQTK129ZUsiq` | `6Ue5kRXpHbNrQguXjpHuTu6RzPiqZrYLStJ6gmBDobov` | `0` |
| seed-netcup | `QeMe15hDFKkyubnjYYB6FSkSojNpjub5Bq` | `EAv9tuEizRrTwYZtXobfo49sgRDrHvieRrmnGq1PwzQ7` | `7cJcjBD9Vpmwugf8jMhnYJB8yFHhrBZGBzJDV8WnSYNz` | `0` |
| local | `QaLdnApWW3hps1qXM8cpsL1pVgw7RtyJmN` | `BUL1j6C63NJqEfMmopqovRC3NFRsHTHGMwGPS3ut1tNY` | `6DdhEueMEopFphx81ywZ5WWdZHCUDERi9J43rjQDtvMV` | `0` |

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
  http://127.0.0.1:24891/admin/mintingaccounts
```

Use the `mintingPrivateKey` value from the ignored secrets file. Do not use the
account private key as the minting key.
