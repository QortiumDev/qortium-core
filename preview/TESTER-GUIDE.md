# Qortium Preview Tester Guide

The preview network is a public alpha/demo network. It can be reset while
Qortium is still changing, so do not treat preview accounts, data, or balances
as permanent.

## Prerequisites

For the easy download path:

- Java 17 or newer

For the source-build path:

- Java 17 or newer JDK
- Maven
- Git

## Easy Path: Download The Preview Zip

1. Download `qortium-preview.zip` from the GitHub pre-release.
2. Extract the zip.
3. Open a terminal in the extracted `qortium-preview` folder.
4. Start the preview node.

Linux or macOS:

```sh
./preview/start.sh
```

If your extractor removed script permissions, run this once first:

```sh
chmod +x preview/*.sh
```

Windows:

```bat
preview\start.bat
```

5. Wait for the local API to come online.

Linux or macOS:

```sh
./preview/status.sh --wait
```

Windows:

```bat
preview\status.bat --wait
```

The status command only checks that the local node API is reachable and can
report a block height. A node may still need more time to find peers and sync
from genesis after the API starts. On the current preview chain, first sync can
take a few minutes and will take longer as the preview chain grows.

To check the full local node status, open this URL in a browser:

```text
http://localhost:24891/admin/status
```

To check known peers:

```text
http://localhost:24891/peers/known
```

6. Stop the preview node when finished.

Linux or macOS:

```sh
./preview/stop.sh
```

Windows:

```bat
preview\stop.bat
```

## Advanced Path: Build From Source

1. Clone the repository.

```sh
git clone https://github.com/QuickMythril/qortium.git
cd qortium
```

2. Build the jar.

```sh
./build.sh --yes
```

3. Start the preview node.

Linux or macOS:

```sh
./preview/start.sh
```

Windows:

```bat
preview\start.bat
```

## Reset The Preview Node

Resetting removes local preview runtime files, including the local database,
API key, logs, and generated settings. Use this when the preview chain is reset
or when you want to start fresh. The next start will create a new local
database and API key.

Linux or macOS:

```sh
./preview/reset.sh
```

Windows:

```bat
preview\reset.bat
```

## What To Expect

- The preview chain does not start with a native asset.
- Preview accounts are not prefunded.
- Normal fee-bearing transactions can use MemoryPoW instead of a paid native
  fee before asset ID `0` exists.
- The first start may take a few minutes to find peers, synchronize from
  genesis, and settle into the current preview height.
- The first useful tests are connecting to the seed nodes, staying synced, sending
  chat messages, trying QDN features, and reporting issues.
- Preview participant and seed nodes expose limited public read-only API access
  by default so Qortium Home can discover useful peers, browse QDN resources,
  and read common chain data. Your local node still keeps transaction building,
  signing, admin, and other sensitive actions local-only.
- The preview network can be reset while it is still an alpha/demo network.

If you want your node to be reachable by other testers, your firewall or router
needs to allow inbound TCP `24891` for public read API access, `24892` for P2P,
and `24894` for QDN/data. If you do not open those ports, your node can still
use the preview network normally, but other users may not be able to use it as a
public read or QDN peer.

The public seed status endpoints are:

```text
http://146.103.42.59:24891/admin/status
http://185.207.104.78:24891/admin/status
```

The public seed known-peer endpoints are:

```text
http://146.103.42.59:24891/peers/known
http://185.207.104.78:24891/peers/known
```

The seed APIs intentionally expose only limited read-only discovery endpoints.
Normal transaction building and account activity should go through your own
local preview node.

## Troubleshooting

- If Java is missing or too old, install Java 17 or newer and open a new
  terminal before starting again.
- If the node is already running, `start` will print the existing process ID.
- If the API is not reachable yet, wait a little longer or check
  `preview/qortium.log`. If that file was not created, check `preview/run.log`
  for launcher or Java startup errors.
- If the node API is reachable but there are no peers, leave the node running
  for a few minutes and then check the known-peer URL above.
- If the public preview is reset, run the reset command and start again.
