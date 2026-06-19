# Qortium Preview Seed Operator Runbook

This runbook is for maintaining the public preview seed nodes. The preview
network is still an alpha/demo network, so the goal is repeatable operation and
clear recovery, not long-term chain permanence.

## Seed Hosts

- Regxa seed: `146.103.42.59`
- Netcup seed: `185.207.104.78`

Both seeds use the preview port range:

- API: `24891`
- P2P: `24892`
- QDN/data: `24894`

I2P fallback uses a local SAM bridge, not an exposed public port:

- SAM: `127.0.0.1:7656`
- i2pd console, when enabled locally: `http://127.0.0.1:7070`

Do not expose SAM publicly.

## Update And Build

Run seed nodes from a non-root user.

```sh
cd ~/git/qortium
git fetch --all --prune
git checkout main
git pull --ff-only
./build.sh --yes
```

The preview launcher will use the built jar under `target/` unless a local
`qortium.jar` exists in the repository or preview folder.

If the node is using the release-style preview runtime jar, replace it after
building and before restarting:

```sh
cp target/qortium-1.0.0.jar preview/qortium.jar
```

## Stop, Start, And Check

Stop the current preview node:

```sh
./preview/stop.sh
```

Start the Regxa seed:

```sh
./preview/start.sh --seed-regxa
```

Start the Netcup seed:

```sh
./preview/start.sh --seed-netcup
```

Wait for the local API:

```sh
./preview/status.sh --wait
```

Check logs:

```sh
tail -n 120 preview/run.log
tail -n 120 preview/qortium.log
```

`preview/run.log` records launcher details and Java stdout/stderr.
`preview/qortium.log` is the main application log written by Log4j.

## I2P Fallback

Preview builds that include I2P fallback use an external `i2pd` process for
now. Core connects to SAM on `127.0.0.1:7656`, creates one persistent I2P
destination for the chain network and one for QDN/data, and advertises those
destinations only after the corresponding SAM session is up.

Direct TCP remains primary for public seed-to-seed and reachable peer traffic.
I2P is the fallback path for peers that cannot accept inbound TCP, unless the
local `i2pPreferred` setting is enabled for testing.

On Debian-family hosts, the basic service setup is:

```sh
sudo apt install i2pd
sudo systemctl enable --now i2pd
```

Verify that SAM is listening locally before starting or restarting Core:

```sh
ss -ltn | grep ':7656'
```

Check Core's active I2P state in the application log:

```sh
grep -E "I2P .*fallback reachable|I2P session" preview/qortium.log
```

Check connected peer transports through the local API:

```sh
curl -fsS http://127.0.0.1:24891/peers
curl -fsS http://127.0.0.1:24891/peers/data
```

Each connected peer entry includes `transport`. Seed peers should normally stay
on `IP`; fallback peers that have no direct TCP path should show `I2P`.

On hardened hosts where normal URL or Tor reseed fails, use i2pd's local-file
reseed path instead of repeatedly restarting the router. Place a valid SU3
reseed file on the host, configure i2pd with:

```ini
[reseed]
file = /var/lib/i2pd/i2pseeds.su3
```

Then restart `i2pd` and wait for the router count to climb before restarting
Core or judging I2P fallback connectivity.

Standalone node operators can also use the general
[I2P fallback operator guide](../docs/networking/i2p-fallback-operator-guide.md).

## Public API Contract

Preview seed nodes keep the normal API restricted for local transaction-builder
style endpoints, but intentionally expose public read-only API access for
discovery, QDN browsing, and common chain-data reads. The minimum discovery
endpoints are:

```text
GET /admin/status
GET /peers/known
```

The seed profiles also expose the same public read-only QDN and chain-data
allowlist used by default preview participants. Public write, admin, utility,
list-management, and peer mutation routes remain blocked unless the request
comes from the local API whitelist and supplies any required API key.

Verify both seeds from outside the VPS network:

```sh
curl -fsS http://146.103.42.59:24891/admin/status
curl -fsS http://146.103.42.59:24891/peers/known
curl -fsS http://185.207.104.78:24891/admin/status
curl -fsS http://185.207.104.78:24891/peers/known
```

Verify a normal restricted endpoint stays blocked:

```sh
curl -o /dev/null -sS -w "%{http_code}\n" http://146.103.42.59:24891/admin/settings
curl -o /dev/null -sS -w "%{http_code}\n" http://185.207.104.78:24891/admin/settings
```

The expected response code for restricted endpoints is `403`.

## QDN Auto-Update Testing

The tracked preview settings keep `"autoUpdateMode": "OFF"` so a normal seed
restart does not automatically install a new jar. For a live update test, use
two different seed paths:

- keep one seed on `OFF` and trigger the approved update manually with
  `POST /admin/update`;
- switch the other seed's local settings to `INSTALL`, restart it, and let the
  background updater pick up the approved manifest.

Use a small non-consensus commit as the first update target so the smoke test
proves the updater path without changing preview chain rules.

The staged QDN auto-update smoke target is also a non-consensus operator-note
commit. It is only meant to prove that a seed can host the update chunks while a
separate local node signs and submits the pinned binary transaction and update
manifest.

The local QDN auto-update smoke target is another non-consensus operator-note
commit. It is only meant to retest the normal one-step local publish flow where
the local node owns the QDN name, publishes the update binary, and submits the
approval manifest in one run.

The QDN auto-update retry smoke target is another non-consensus operator-note
commit. It is only meant to prove that a manual `/admin/update` request keeps
retrying after an initial missing-data result, while a separate node in
`INSTALL` mode can also test the automatic retry path.

Enable automatic install on one seed only:

```sh
curl -X PATCH \
  -H "X-API-KEY: $(cat preview/apikey.txt)" \
  -H "Content-Type: application/json" \
  --data '{"autoUpdateMode":"INSTALL"}' \
  http://127.0.0.1:24891/admin/settings
./preview/stop.sh
./preview/start.sh --seed-regxa
```

The preview launcher merges the local settings file with the tracked seed
template on each start, so a locally patched `autoUpdateMode` — like any other
local settings change — survives the restart, while untouched settings keep
following the template. To force a mode from the environment instead of
patching settings, start with `QORTIUM_PREVIEW_AUTO_UPDATE_MODE=INSTALL`.

Publish a prepared `qortium.update` from a synced preview node that owns the
chosen QDN name:

```sh
python3 tools/auto-update-scripts/publish-auto-update.py --preview <private-key> <commit>
```

For seed-hosted update chunks, stage the unsigned binary transaction on the seed
that has the built `qortium.update`, then sign and submit it from an
unrestricted local node:

```sh
python3 tools/auto-update-scripts/publish-auto-update.py \
  --preview \
  --qdn-name QortiumHomeTest \
  --stage-binary-out /tmp/qortium-update-staged.json \
  <commit>

python3 tools/auto-update-scripts/publish-auto-update.py \
  --preview \
  --staged-binary /tmp/qortium-update-staged.json \
  <private-key>
```

Approve the pending manifest with a development-group authority:

```sh
./tools/approve-auto-update.sh --preview
```

Check availability or trigger the manual path from localhost on the seed that
stays in `OFF` mode:

```sh
curl -H "X-API-KEY: $(cat preview/apikey.txt)" \
  http://127.0.0.1:24891/admin/update
curl -X POST -H "X-API-KEY: $(cat preview/apikey.txt)" \
  http://127.0.0.1:24891/admin/update
```

`POST /admin/update` returns `INSTALL_STARTED` only after the pinned binary is
local, hash verified, and the apply helper is scheduled. If the node still needs
QDN chunks, it returns `DOWNLOAD_STARTED` plus the pinned binary resource
progress fields. Repeat the manual request after the resource reaches
`DOWNLOADED` or `READY`, or leave a node in `INSTALL` mode and let it retry
soon after the first missing-data result.

Restricted update endpoints must stay private. From outside the VPS, public
requests to `/admin/update` should return `403`.

## Firewall Expectations

The seed firewall should allow:

- SSH from the operator's trusted access path
- TCP `24891` for limited read-only seed discovery
- TCP `24892` for blockchain peers
- TCP `24894` for QDN/data peers

The seed firewall should not intentionally expose inherited old port ranges
such as `62391`, `62392`, or `62394`.

## Minting Keys

Preview genesis authorizes one initial minting account for each seed and one
for the local starter node. The private minting keys are stored only in the
ignored local file:

```text
preview/secrets/initial-minting-accounts.json
```

After the node creates `preview/apikey.txt`, install the seed's
`mintingPrivateKey`:

```sh
curl -X POST \
  -H "X-API-KEY: $(cat preview/apikey.txt)" \
  -H "Content-Type: text/plain" \
  --data "MINTING_PRIVATE_KEY_FROM_PREVIEW_SECRETS" \
  http://127.0.0.1:24891/admin/mintingaccounts
```

Do not commit private minting keys.

## Reset Procedure

Only reset public seed nodes when the preview chain is intentionally being
restarted.

1. Stop all preview seed nodes.
2. Confirm the current repository commit and preview genesis/settings are the
   intended public reset version.
3. Run `./preview/reset.sh` on each seed.
4. Start Regxa with `./preview/start.sh --seed-regxa`.
5. Start Netcup with `./preview/start.sh --seed-netcup`.
6. Install the correct minting key on each seed.
7. Verify both seeds report the same block height and can see each other.

For normal jar updates that do not change consensus or genesis files, do not
reset the chain.

## Release Package Check

Before publishing a tester zip, build the package from the repository root:

```sh
./preview/package-release.sh
./preview/smoke-release-logging.sh --package=target/qortium-preview.zip
```

The package should include the jar, preview scripts, settings templates, tester
guide, this operator runbook, and the release logging smoke check. It should not
include local databases, logs, API keys, keystores, backup folders, or ignored
preview secrets.
