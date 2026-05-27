# Qortium QDN Auto-Update Scripts

Qortium auto-update is disabled by default. To receive automatic update
installs, a node must set `"autoUpdateMode": "INSTALL"` and leave QDN enabled.
The old `autoUpdateRepos` GitHub mirror setting and `autoUpdateEnabled` boolean
setting have been removed.

Supported `autoUpdateMode` values are:

- `OFF`: do not run the background update checker.
- `CHECK_ONLY`: check for approved updates and log availability.
- `NOTIFY`: check for approved updates and show one notification per manifest.
- `INSTALL`: check for approved updates, download them, verify them, and restart
  through the normal apply-update path.

The update flow now uses two ARBITRARY transactions:

- `AUTO_UPDATE_BINARY`: a QDN resource containing the XORed `qortium.update`
  file.
- `AUTO_UPDATE`: a compact on-chain manifest submitted in the configured
  development group. The manifest must pin the exact binary transaction
  signature, Git commit hash, build timestamp, and SHA-256 of the decoded update
  JAR.

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
  --port 14891 \
  <commit>
```

For previewnet, use the convenience flag. It selects port `24891`, development
group `1`, and zero-fee transactions so the no-native-asset preview chain uses
MemoryPoW instead of requiring spendable native funds:

```bash
python3 tools/auto-update-scripts/publish-auto-update.py --preview <private-key> <commit>
```

The publisher:

- reads the local `qortium.update`;
- computes the SHA-256 hash of the decoded JAR bytes inside it;
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

For previewnet, approve with:

```bash
./tools/approve-auto-update.sh --preview
```

The preview mode computes the zero-fee `GROUP_APPROVAL` MemoryPoW nonce before
signing and submitting the approval transaction.

The updater ignores unapproved update manifests and rejects approved manifests
that do not pin a QDN binary transaction signature. Once approved and confirmed,
nodes in `INSTALL` mode fetch the pinned QDN binary, verify the SHA-256 hash over
the decoded JAR bytes, write it to `new-qortium.jar`, and restart through the
standard apply-update path.

## Manual Checks

Nodes with `"autoUpdateMode": "OFF"` can still use the restricted admin API to
check or install an approved update manually:

```bash
curl -H "X-API-KEY: $(cat apikey.txt)" \
  http://localhost:14891/admin/update
```

To schedule installation of the latest approved update when it is newer than the
running build:

```bash
curl -X POST -H "X-API-KEY: $(cat apikey.txt)" \
  http://localhost:14891/admin/update
```

Both endpoints use the same approved development-group manifest lookup, pinned
QDN binary lookup, and hash verification as automatic background updates. The
`GET` response includes the active development groups, manifest approval
metadata, and pinned binary metadata so operators can inspect update authority
before installing. The `POST` endpoint returns a status response before the node
begins the apply-update restart flow.
