#!/usr/bin/env bash
set -euo pipefail

API_URL="http://localhost:62391"
WAIT=0
TIMEOUT_SECONDS=120
SLEEP_SECONDS=2

usage() {
	echo "Usage: ./preview/status.sh [--wait] [--api-url=URL]"
	echo
	echo "Checks the local preview-node API and prints the current block height."
}

for arg in "$@"; do
	case "${arg}" in
		--wait)
			WAIT=1
			;;
		--api-url=*)
			API_URL="${arg#*=}"
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
	echo "curl is not available, so the preview API could not be checked."
	echo "Install curl or open this URL in a browser after starting the preview node:"
	echo "  ${HEIGHT_URL}"
	exit 1
fi

print_unreachable_help() {
	echo "The preview API is not reachable yet."
	echo "Start it with:"
	echo "  ./preview/start.sh"
	echo
	echo "If it is already starting, check:"
	echo "  ${SCRIPT_DIR}/run.log"
}

fetch_height() {
	curl -fsS --max-time 5 "${HEIGHT_URL}" 2>/dev/null || true
}

print_height() {
	local height="$1"
	echo "API is reachable. Current block height: ${height}"
	if [ "${height}" -le 1 ]; then
		echo "The preview chain is at genesis. This is expected until preview minting keys are added."
	fi
}

height=""
if [ "${WAIT}" -eq 1 ]; then
	deadline="$((SECONDS + TIMEOUT_SECONDS))"
	echo -n "Waiting for preview block height"

	while [ "${SECONDS}" -le "${deadline}" ]; do
		height="$(fetch_height)"
		if [[ "${height}" =~ ^[0-9]+$ ]]; then
			echo
			print_height "${height}"
			exit 0
		fi

		echo -n "."
		sleep "${SLEEP_SECONDS}"
	done

	echo
	echo "Timed out waiting for the preview API."
	print_unreachable_help
	exit 1
fi

height="$(fetch_height)"
if [[ "${height}" =~ ^[0-9]+$ ]]; then
	print_height "${height}"
	exit 0
fi

print_unreachable_help
exit 1
