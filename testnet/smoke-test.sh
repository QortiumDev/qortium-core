#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

API_URL="${QORTIUM_TESTNET_API_URL:-http://localhost:62391}"
APIKEY_FILE="${QORTIUM_TESTNET_APIKEY_FILE:-${SCRIPT_DIR}/apikey.txt}"
LOG_FILE="${QORTIUM_TESTNET_LOG_FILE:-${SCRIPT_DIR}/qortium.log}"
CURL_TIMEOUT=5
WAIT=0
TIMEOUT_SECONDS=120
MIN_HEIGHT=2

DEFAULT_MINTING_PUBLIC_KEY="7PpfnvLSG7y4HPh8hE7KoqAjLCkv7Ui6xw4mKAkbZtox"
DEFAULT_MINTING_ADDRESS="QgV4s3xnzLhVBEJxcYui4u4q11yhUHsd9v"

usage() {
	echo "Usage: ./testnet/smoke-test.sh [--wait] [--api-url=URL] [--min-height=N] [--timeout=SECONDS]"
	echo
	echo "Checks a running local single-node testnet through its HTTP API."
}

for arg in "$@"; do
	case "${arg}" in
		--wait)
			WAIT=1
			;;
		--api-url=*)
			API_URL="${arg#*=}"
			;;
		--min-height=*)
			MIN_HEIGHT="${arg#*=}"
			;;
		--timeout=*)
			TIMEOUT_SECONDS="${arg#*=}"
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

if ! [[ "${MIN_HEIGHT}" =~ ^[0-9]+$ ]]; then
	echo "--min-height must be a whole number"
	exit 1
fi

if ! [[ "${TIMEOUT_SECONDS}" =~ ^[0-9]+$ ]]; then
	echo "--timeout must be a whole number"
	exit 1
fi

for command in curl jq; do
	if ! command -v "${command}" >/dev/null 2>&1; then
		echo "${command} is required for live testnet smoke checks."
		exit 1
	fi
done

read_api_key() {
	if [ -n "${QORTIUM_API_KEY:-}" ]; then
		printf '%s' "${QORTIUM_API_KEY}"
	elif [ -f "${APIKEY_FILE}" ]; then
		tr -d '\r\n' < "${APIKEY_FILE}"
	fi
}

API_KEY="$(read_api_key)"
if [ -z "${API_KEY}" ]; then
	echo "Admin API key not found. Start the local testnet first, or set QORTIUM_API_KEY."
	exit 1
fi

failures=0

pass() {
	echo "ok - $1"
}

fail() {
	echo "not ok - $1" >&2
	failures=1
}

get() {
	curl -fsS --max-time "${CURL_TIMEOUT}" "$@"
}

get_auth() {
	curl -fsS --max-time "${CURL_TIMEOUT}" -H "X-API-KEY: ${API_KEY}" "$@"
}

get_height() {
	get "${API_URL}/blocks/height" 2>/dev/null || true
}

check_json() {
	local name="$1"
	local json="$2"
	shift 2

	if jq -e "$@" >/dev/null <<< "${json}"; then
		pass "${name}"
	else
		fail "${name}"
	fi
}

if [ "${WAIT}" -eq 1 ]; then
	deadline="$((SECONDS + TIMEOUT_SECONDS))"
	echo -n "Waiting for local testnet height >= ${MIN_HEIGHT}"
	while [ "${SECONDS}" -le "${deadline}" ]; do
		height="$(get_height)"
		if [[ "${height}" =~ ^[0-9]+$ ]] && [ "${height}" -ge "${MIN_HEIGHT}" ]; then
			echo
			break
		fi

		echo -n "."
		sleep 2
	done
	echo
fi

height="$(get_height)"
if [[ "${height}" =~ ^[0-9]+$ ]] && [ "${height}" -ge "${MIN_HEIGHT}" ]; then
	pass "block height is ${height}"
else
	fail "block height is at least ${MIN_HEIGHT}"
fi

if admin_info="$(get_auth "${API_URL}/admin/info" 2>/dev/null)"; then
	check_json "admin info reports testnet full node" "${admin_info}" \
		'.isTestNet == true and .type == "full" and (.buildVersion | type == "string" and length > 0)'
else
	fail "admin info is reachable"
fi

if settings="$(get_auth "${API_URL}/admin/settings" 2>/dev/null)"; then
	check_json "settings are local single-node testnet" "${settings}" \
		'.isTestNet == true and .singleNodeTestnet == true and .apiPort == 62391 and .listenPort == 62392 and .minOutboundPeers == 0 and .bootstrap == false'
else
	fail "admin settings are reachable"
fi

if minting_accounts="$(get_auth "${API_URL}/admin/mintingaccounts" 2>/dev/null)"; then
	check_json "default minting account is installed" "${minting_accounts}" \
		--arg publicKey "${DEFAULT_MINTING_PUBLIC_KEY}" --arg address "${DEFAULT_MINTING_ADDRESS}" \
		'any(.[]; .publicKey == $publicKey and .mintingAccount == $address and .recipientAccount == $address)'
else
	fail "minting accounts are reachable"
fi

if [[ "${height}" =~ ^[0-9]+$ ]] && block="$(get "${API_URL}/blocks/byheight/${height}" 2>/dev/null)"; then
	check_json "latest block has expected height" "${block}" --argjson height "${height}" '.height == $height'
	check_json "latest block was minted by default local account" "${block}" \
		--arg address "${DEFAULT_MINTING_ADDRESS}" '.minterAddress == $address and .onlineAccountsCount >= 1'
else
	fail "latest block is reachable"
fi

if peer_summary="$(get "${API_URL}/peers/summary" 2>/dev/null)"; then
	check_json "peer summary is single-node" "${peer_summary}" '.inboundConnections == 0 and .outboundConnections == 0'
else
	fail "peer summary is reachable"
fi

if groups="$(get "${API_URL}/groups" 2>/dev/null)"; then
	check_json "default testnet groups exist" "${groups}" \
		'any(.[]; .groupName == "development") and any(.[]; .groupName == "minting")'
else
	fail "groups are reachable"
fi

if assets="$(get "${API_URL}/assets" 2>/dev/null)"; then
	check_json "testnet starts without issued assets" "${assets}" 'type == "array" and length == 0'
else
	fail "assets are reachable"
fi

if names="$(get "${API_URL}/names?limit=5" 2>/dev/null)"; then
	check_json "testnet starts without registered names" "${names}" 'type == "array" and length == 0'
else
	fail "names are reachable"
fi

if unconfirmed="$(get "${API_URL}/transactions/unconfirmed" 2>/dev/null)"; then
	check_json "unconfirmed transaction pool is empty" "${unconfirmed}" 'type == "array" and length == 0'
else
	fail "unconfirmed transactions are reachable"
fi

if [ -f "${LOG_FILE}" ]; then
	if grep -E 'ERROR|FATAL|Exception' "${LOG_FILE}" >/dev/null; then
		fail "log has no errors or exceptions"
	else
		pass "log has no errors or exceptions"
	fi

	unexpected_warnings="$(grep ' WARN ' "${LOG_FILE}" || true)"
	if [ -z "${unexpected_warnings}" ]; then
		pass "log has no unexpected warnings"
	else
		fail "log has no unexpected warnings"
		printf '%s\n' "${unexpected_warnings}" >&2
	fi
else
	fail "application log exists"
fi

if [ "${failures}" -ne 0 ]; then
	echo "Smoke checks failed."
	exit 1
fi

echo "Smoke checks passed."
