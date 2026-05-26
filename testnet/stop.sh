#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RUN_PID="${SCRIPT_DIR}/run.pid"
APIKEY_FILE="${SCRIPT_DIR}/apikey.txt"

api_port=24891
stale_pid=0
for arg in "$@"; do
	case "${arg}" in
		--api-port=*)
			api_port="${arg#*=}"
			;;
		--mainnet|-m)
			api_port=14891
			;;
		--testnet|-t)
			api_port=24891
			;;
	esac
done

pid=""
if [ -f "${RUN_PID}" ]; then
	read -r pid < "${RUN_PID}" || pid=""
fi

if [ -n "${pid}" ] && ! ps -p "${pid}" >/dev/null 2>&1; then
	pid=""
	stale_pid=1
fi

if [ -z "${pid}" ]; then
	pid="$(ps aux | awk '/[q]ortium.*settings-test-local\.json/ { print $2; exit }')"
fi

apikey=""
if [ -f "${APIKEY_FILE}" ]; then
	apikey="$(cat "${APIKEY_FILE}")"
fi

success=0
if [ -n "${apikey}" ] && command -v curl >/dev/null 2>&1; then
	echo "Stopping Qortium via API..."
	if curl --url "http://localhost:${api_port}/admin/stop" -H "X-API-KEY: ${apikey}" >/dev/null 2>&1; then
		success=1
	fi
fi

if [ "${success}" -ne 1 ] && [ -n "${pid}" ]; then
	echo "Stopping Qortium process ${pid}..."
	if kill -15 "${pid}"; then
		success=1
	fi
fi

if [ "${success}" -ne 1 ]; then
	if [ "${stale_pid}" -eq 1 ]; then
		rm -f "${RUN_PID}"
		echo "Qortium testnet is not running; removed stale pid file"
		exit 0
	fi

	echo "Stop command failed - local testnet node is not running."
	exit 1
fi

if [ -n "${pid}" ]; then
	echo -n "Waiting for Qortium to stop"
	while state="$(ps -p "${pid}" -o stat= 2>/dev/null)" && [ -n "${state}" ] && [ "${state}" != "Z" ]; do
		echo -n "."
		sleep 1
	done
	echo
fi

rm -f "${RUN_PID}"
echo "Qortium testnet stopped"
