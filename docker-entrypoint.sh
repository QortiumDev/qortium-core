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

SETTINGS_FILE="${QORTIUM_SETTINGS_FILE:-/qortium/settings.json}"
SETTINGS_TEMPLATE="${QORTIUM_DEFAULT_SETTINGS_TEMPLATE:-/usr/local/qortium/settings-preview.json}"
/usr/local/bin/docker-init-settings.sh "${SETTINGS_TEMPLATE}" "${SETTINGS_FILE}"

exec /usr/local/bin/docker-start.sh "$@"
