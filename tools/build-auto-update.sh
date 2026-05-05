#!/usr/bin/env bash

set -euo pipefail

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)

echo "tools/build-auto-update.sh is a compatibility wrapper."
echo "Using tools/auto-update-scripts/build-auto-update.sh for the QDN auto-update flow."

exec "${script_dir}/auto-update-scripts/build-auto-update.sh" "$@"
