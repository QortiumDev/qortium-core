#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

cd "${REPO_DIR}"

echo "Stopping preview node if it is running..."
if stop_output="$("${SCRIPT_DIR}/stop.sh" 2>&1)"; then
	if grep -qi "not running" <<< "${stop_output}"; then
		echo "Preview node was not running."
	else
		printf '%s\n' "${stop_output}"
		echo "Preview node stopped."
	fi
else
	if grep -qi "not running" <<< "${stop_output}"; then
		echo "Preview node was not running."
	else
		printf '%s\n' "${stop_output}"
		echo "No running preview node was stopped. Continuing with reset."
	fi
fi

paths=(
	"${SCRIPT_DIR}/db-preview"
	"${SCRIPT_DIR}/data-preview"
	"${SCRIPT_DIR}/qortium-backup"
	"${SCRIPT_DIR}/qortium-backup-preview"
	"${SCRIPT_DIR}/settings-preview-local.json"
	"${SCRIPT_DIR}/settings-preview-seed-local.json"
	"${SCRIPT_DIR}/settings-preview-seed-netcup-local.json"
	"${SCRIPT_DIR}/run.log"
	"${SCRIPT_DIR}/run-error.log"
	"${SCRIPT_DIR}/run.pid"
	"${SCRIPT_DIR}/qortium.log"
	"${SCRIPT_DIR}/QortiumKeyStore.jks"
	"${SCRIPT_DIR}/apikey.txt"
)

removed=0
echo "Removing generated preview files:"
for path in "${paths[@]}"; do
	if [ -e "${path}" ]; then
		rm -rf "${path}"
		echo "  removed ${path}"
		removed=1
	else
		echo "  not present ${path}"
	fi
done

if [ "${removed}" -eq 0 ]; then
	echo "No generated preview files were present."
fi

echo
echo "Reset complete. Start a fresh preview participant node with:"
echo "  ./preview/start.sh"
