#!/usr/bin/env bash
set -euo pipefail

port=14891
tx_group_id=1
approval_fee="0.01"

usage() {
	cat <<USAGE
Usage: ./tools/approve-dev-transaction.sh [--preview|-t] [--port PORT] [--tx-group-id ID] [--fee FEE]

Approves the latest pending development-group admin transaction.

Options:
  --preview, -t        use preview defaults: port 24891 and zero-fee MemoryPoW approval
  --port PORT          API port to use
  --tx-group-id ID     development group id to search, default 1
  --fee FEE            approval fee, default 0.01; use 0 for MemoryPoW
USAGE
}

while [ "$#" -gt 0 ]; do
	case "$1" in
		--preview|-t)
			port=24891
			approval_fee="0"
			shift
			;;
		--port)
			port="$2"
			shift 2
			;;
		--tx-group-id)
			tx_group_id="$2"
			shift 2
			;;
		--fee)
			approval_fee="$2"
			shift 2
			;;
		-h|--help)
			usage
			exit 0
			;;
		*)
			echo "Unknown option: $1" >&2
			usage
			exit 1
			;;
	esac
done

read_api_key() {
	if [ -n "${QORTIUM_API_KEY:-}" ]; then
		printf '%s\n' "${QORTIUM_API_KEY}"
		return 0
	fi

	local candidate
	for candidate in preview/apikey.txt apikey.txt "${HOME}/qortium/apikey.txt"; do
		if [ -f "${candidate}" ]; then
			tr -d '\r\n' < "${candidate}"
			return 0
		fi
	done

	return 1
}

require_base58_tx() {
	local label="$1"
	local value="$2"
	if ! grep -Eq '^[1-9A-HJ-NP-Za-km-z]{100,}$' <<< "${value}"; then
		printf "%s failed:\n%s\n" "${label}" "${value}" >&2
		exit 1
	fi
}

printf "Searching for development transactions to approve...\n"

tx=$(curl --silent --show-error --url "http://localhost:${port}/transactions/search?txGroupId=${tx_group_id}&txType=ADD_GROUP_ADMIN&txType=REMOVE_GROUP_ADMIN&confirmationStatus=CONFIRMED&limit=1&reverse=true")
if ! grep --fixed-strings --silent '"approvalStatus":"PENDING"' <<< "${tx}"; then
	echo "Can't find any pending transactions"
	exit 1
fi

sig=$(perl -n -e 'print $1 if m/"signature":"(\w+)"/' <<< "${tx}")
if [ -z "${sig}" ]; then
	printf "Can't find transaction signature in JSON:\n%s\n" "${tx}"
	exit 1
fi

printf "Found transaction %s\n" "${sig}"

printf "\nPaste your dev account private key:\n"
IFS=
read -r -s privkey
printf "\n"

pubkey=$(curl --silent --show-error --url "http://localhost:${port}/utils/publickey" --data @- <<< "${privkey}")
if grep -Eqv '^\w{44,46}$' <<< "${pubkey}"; then
	printf "Invalid response from API - was your private key correct?\n%s\n" "${pubkey}"
	exit 1
fi
printf "Your public key: %s\n" "${pubkey}"

address=$(curl --silent --show-error --url "http://localhost:${port}/addresses/convert/${pubkey}")
printf "Your address: %s\n" "${address}"

lastref=$(curl --silent --show-error --url "http://localhost:${port}/addresses/lastreference/${address}")
printf "Your last reference: %s\n" "${lastref}"

timestamp=$(date +%s)000
tx_json=$(cat <<TX_END
{
  "timestamp": ${timestamp},
  "reference": "${lastref}",
  "fee": ${approval_fee},
  "txGroupId": 0,
  "adminPublicKey": "${pubkey}",
  "pendingSignature": "${sig}",
  "approval": true
}
TX_END
)

raw_tx=$(curl --silent --show-error --header "Content-Type: application/json" --url "http://localhost:${port}/groups/approval" --data @- <<< "${tx_json}")
require_base58_tx "Building GROUP_APPROVAL transaction" "${raw_tx}"
printf "\nRaw approval tx:\n%s\n" "${raw_tx}"

if [ "${approval_fee}" = "0" ]; then
	api_key=$(read_api_key || true)
	if [ -z "${api_key}" ]; then
		echo "Zero-fee approval requires an API key for /transactions/mempow/compute." >&2
		echo "Set QORTIUM_API_KEY or run from a directory containing preview/apikey.txt or apikey.txt." >&2
		exit 1
	fi

	raw_tx=$(curl --silent --show-error --header "X-API-KEY: ${api_key}" --url "http://localhost:${port}/transactions/mempow/compute" --data @- <<< "${raw_tx}")
	require_base58_tx "Computing GROUP_APPROVAL MemoryPoW nonce" "${raw_tx}"
	printf "\nRaw approval tx with MemoryPoW nonce:\n%s\n" "${raw_tx}"
fi

sign_json=$(cat <<SIGN_END
{
  "privateKey": "${privkey}",
  "transactionBytes": "${raw_tx}"
}
SIGN_END
)
signed_tx=$(curl --silent --show-error --header "Content-Type: application/json" --url "http://localhost:${port}/transactions/sign" --data @- <<< "${sign_json}")
printf "\nSigned tx:\n%s\n" "${signed_tx}"
require_base58_tx "Signing GROUP_APPROVAL transaction" "${signed_tx}"

plural="s"
printf "\n"
for ((seconds = 5; seconds > 0; seconds--)); do
	if [ "${seconds}" = "1" ]; then
		plural=""
	fi
	printf "\rBroadcasting in %d second%s...(CTRL-C) to abort " "${seconds}" "${plural}"
	sleep 1
done

printf "\rBroadcasting signed GROUP_APPROVAL transaction...      \n"
result=$(curl --silent --show-error --url "http://localhost:${port}/transactions/process" --data @- <<< "${signed_tx}")
printf "API response:\n%s\n" "${result}"
