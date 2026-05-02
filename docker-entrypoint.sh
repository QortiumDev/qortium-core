#!/bin/sh
set -eu

if [ ! -d /qortium ]; then
    mkdir -p /qortium || true
fi

if [ ! -w /qortium ]; then
    echo "ERROR: /qortium is not writable by uid:gid $(id -u):$(id -g)." >&2
    echo "ERROR: Ensure host bind path ownership/permissions allow writes (e.g. chown/chmod on qortium/data)." >&2
    ls -ld /qortium >&2 || true
    exit 70
fi

if [ ! -f /qortium/settings.json ]; then
    printf '{}\n' > /qortium/settings.json
fi

exec /usr/local/bin/docker-start.sh "$@"
