# Qortium QDN Auto-Update Scripts

Qortium auto-update is disabled by default. To receive updates, a node must set
`"autoUpdateEnabled": true` and leave QDN enabled. The old `autoUpdateRepos`
GitHub mirror setting is no longer required by the updater.

The update flow now uses two ARBITRARY transactions:

- `AUTO_UPDATE_BINARY`: a QDN resource containing the XORed `qortium.update`
  file.
- `AUTO_UPDATE`: a compact on-chain manifest submitted in the configured
  development group. The manifest pins the exact binary transaction signature,
  Git commit hash, build timestamp, and SHA-256 of the XORed update bytes.

The network only accepts the manifest after normal development-group approval.
For the null-owned development group, admin-submitted update manifests still
remain pending until enough admins approve. If there are no usable admins, the
approval logic falls back to members according to group rules.

## Prerequisites

- A synced local Qortium node with API access.
- A local checkout of the Qortium repository.
- A Base58 private key that can submit the manifest in the development group.
- A QDN name owned by the publishing key for the binary resource.

The publisher defaults to the QDN name `qortium`, but any owned name can be used
with `--qdn-name`. The approved manifest pins the binary transaction signature,
so update validation does not depend on mutable latest-name lookup.

## Build

Run:

```bash
./tools/auto-update-scripts/build-auto-update.sh
```

The build script:

- increments `pom.xml` interactively;
- commits and tags the version bump;
- builds the JAR;
- creates the XORed `qortium.update` file;
- optionally runs the QDN publisher.

It no longer creates or pushes `auto-update-<hash>` branches.

## Publish

Run manually:

```bash
python3 tools/auto-update-scripts/publish-auto-update.py <private-key> <commit>
```

Or prompt securely for the private key:

```bash
python3 tools/auto-update-scripts/publish-auto-update.py <commit>
```

Useful options:

```bash
python3 tools/auto-update-scripts/publish-auto-update.py \
  --qdn-name my-update-name \
  --tx-group-id 1 \
  --port 12391 \
  <commit>
```

The publisher:

- reads the local `qortium.update`;
- computes its SHA-256 hash;
- publishes it to `AUTO_UPDATE_BINARY/<name>/<full-commit-hash>`;
- computes and signs the QDN binary transaction;
- builds a compact `QAU1` manifest that pins that binary signature;
- submits the `AUTO_UPDATE` manifest transaction for group approval.

Use `--dry-run` to verify the local commit, update file, QDN identity, and hash
without building or submitting transactions.

## Approval

After publishing, approve the `AUTO_UPDATE` manifest transaction using the
normal group-approval tools. For example:

```bash
./tools/approve-auto-update.sh
```

The updater ignores unapproved update manifests. Once approved and confirmed,
auto-update-enabled nodes fetch the pinned QDN binary, verify the SHA-256 hash
over the XORed bytes, decode it to `new-qortium.jar`, and restart through the
standard apply-update path.

## Manual Checks

Nodes with `"autoUpdateEnabled": false` can still use the restricted admin API
to check or install an approved update manually:

```bash
curl -H "X-API-KEY: $(cat apikey.txt)" \
  http://localhost:12391/admin/update
```

To schedule installation of the latest approved update when it is newer than the
running build:

```bash
curl -X POST -H "X-API-KEY: $(cat apikey.txt)" \
  http://localhost:12391/admin/update
```

Both endpoints use the same approved development-group manifest lookup and QDN
hash verification as automatic background updates. The `POST` endpoint returns a
status response before the node begins the apply-update restart flow.
