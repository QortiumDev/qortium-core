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

## Update And Build

Run seed nodes from a non-root user.

```sh
cd ~/git/qortium
git fetch --all --prune
git checkout qortium-6.1.5
git pull --ff-only
./build.sh --yes
```

The preview launcher will use the built jar under `target/` unless a local
`qortium.jar` exists in the repository or preview folder.

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

## Public API Contract

Preview seed nodes keep the normal API restricted, but intentionally expose
these read-only discovery endpoints:

```text
GET /admin/status
GET /peers/known
```

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
