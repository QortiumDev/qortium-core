#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

cd "${REPO_DIR}"

echo "Stopping local testnet if it is running..."
if stop_output="$("${SCRIPT_DIR}/stop.sh" 2>&1)"; then
	if grep -qi "not running" <<< "${stop_output}"; then
		echo "Local testnet was not running."
	else
		printf '%s\n' "${stop_output}"
		echo "Local testnet stopped."
	fi
else
	if grep -qi "not running" <<< "${stop_output}"; then
		echo "Local testnet was not running."
	else
		printf '%s\n' "${stop_output}"
		echo "No running local testnet was stopped. Continuing with reset."
	fi
fi

paths=(
	"${SCRIPT_DIR}/db-testnet"
	"${SCRIPT_DIR}/qortium-backup-test"
	"${SCRIPT_DIR}/settings-test-local.json"
	"${SCRIPT_DIR}/testchain-local.json"
	"${SCRIPT_DIR}/run.log"
	"${SCRIPT_DIR}/run.pid"
	"${SCRIPT_DIR}/qortium.log"
	"${SCRIPT_DIR}/QortiumKeyStore.jks"
	"${SCRIPT_DIR}/apikey.txt"
)

removed=0
echo "Removing generated local testnet files:"
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
	echo "No generated local testnet files were present."
fi

echo
echo "Reset complete. Start a fresh local testnet with:"
echo "  ./testnet/start.sh"
