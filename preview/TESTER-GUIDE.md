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
git clone https://github.com/Qortium/qortium.git
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
or when you want to start fresh.

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
- The first useful tests are connecting to the seed, staying synced, sending
  chat messages, trying QDN features, and reporting issues.
- The preview network can be reset while it is still an alpha/demo network.

## Troubleshooting

- If Java is missing or too old, install Java 17 or newer and open a new
  terminal before starting again.
- If the node is already running, `start` will print the existing process ID.
- If the API is not reachable yet, wait a little longer or check
  `preview/run.log`.
- If the public preview is reset, run the reset command and start again.
