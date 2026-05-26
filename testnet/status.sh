#!/usr/bin/env bash
set -euo pipefail

API_URL="http://localhost:24891"
WAIT=0
TIMEOUT_SECONDS=120
SLEEP_SECONDS=2

usage() {
	echo "Usage: ./testnet/status.sh [--wait]"
	echo
	echo "Checks the local single-node testnet API and prints the current block height."
}

for arg in "$@"; do
	case "${arg}" in
		--wait)
			WAIT=1
			;;
		-h|--help)
			usage
			exit 0
			;;
		*)
			echo "Unknown option: ${arg}"
			usage
			exit 1
			;;
	esac
done

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
HEIGHT_URL="${API_URL}/blocks/height"

if ! command -v curl >/dev/null 2>&1; then
	echo "curl is not available, so the testnet API could not be checked."
	echo "Install curl or open this URL in a browser after starting the testnet:"
	echo "  ${HEIGHT_URL}"
	exit 1
fi

print_unreachable_help() {
	echo "The local testnet API is not reachable yet."
	echo "Start it with:"
	echo "  ./testnet/start.sh"
	echo
	echo "If it is already starting, check:"
	echo "  ${SCRIPT_DIR}/run.log"
}

fetch_height() {
	curl -fsS --max-time 5 "${HEIGHT_URL}" 2>/dev/null || true
}

height=""
if [ "${WAIT}" -eq 1 ]; then
	deadline="$((SECONDS + TIMEOUT_SECONDS))"
	echo -n "Waiting for local testnet block height"

	while [ "${SECONDS}" -le "${deadline}" ]; do
		height="$(fetch_height)"
		if [[ "${height}" =~ ^[0-9]+$ ]] && [ "${height}" -gt 1 ]; then
			echo
			echo "API is reachable. Current block height: ${height}"
			exit 0
		fi

		echo -n "."
		sleep "${SLEEP_SECONDS}"
	done

	echo
	echo "Timed out waiting for the local testnet to mint past block 1."
	if [[ "${height}" =~ ^[0-9]+$ ]]; then
		echo "Last observed block height: ${height}"
	else
		print_unreachable_help
	fi
	exit 1
fi

height="$(fetch_height)"
if [[ "${height}" =~ ^[0-9]+$ ]]; then
	echo "API is reachable. Current block height: ${height}"
	if [ "${height}" -le 1 ]; then
		echo "The node is running, but it has not minted a new block yet."
		echo "Run './testnet/status.sh --wait' to wait for minting confirmation."
	fi
	exit 0
fi

print_unreachable_help
exit 1
