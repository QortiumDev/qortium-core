#!/usr/bin/env bash
set -euo pipefail

normal=""
red=""
green=""
if [ -t 1 ] && command -v tput >/dev/null 2>&1; then
	ncolors="$(tput colors 2>/dev/null || true)"
	if [[ "${ncolors}" =~ ^[0-9]+$ ]] && [ "${ncolors}" -ge 8 ]; then
		normal="$(tput sgr0 2>/dev/null || true)"
		red="$(tput setaf 1 2>/dev/null || true)"
		green="$(tput setaf 2 2>/dev/null || true)"
	fi
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RUN_PID="${SCRIPT_DIR}/run.pid"
APIKEY_FILE="${SCRIPT_DIR}/apikey.txt"

api_port=14891
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
stale_pid=0
if [ -f "${RUN_PID}" ]; then
	read -r pid < "${RUN_PID}" || pid=""
fi

if [ -n "${pid}" ] && ! ps -p "${pid}" >/dev/null 2>&1; then
	pid=""
	stale_pid=1
fi

if [ -z "${pid}" ]; then
	pid="$(ps aux | awk -v repo_dir="${SCRIPT_DIR}" '$0 ~ /[j]ava/ && $0 ~ /qortium.*\.jar/ && index($0, repo_dir) { print $2; exit }')"
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
		echo "Qortium is not running; removed stale pid file"
		exit 0
	fi

	if [ -n "${pid}" ]; then
		echo "${red}Stop command failed - not running with process id ${pid}?${normal}"
	else
		echo "${red}Stop command failed - not running?${normal}"
	fi
	exit 1
fi

echo "Qortium node should be shutting down"
if [ -n "${pid}" ]; then
	echo -n "Monitoring for Qortium node to end"
	while state="$(ps -p "${pid}" -o stat= 2>/dev/null | tr -d '[:space:]')" && [ -n "${state}" ] && [ "${state}" != "Z" ]; do
		echo -n "."
		sleep 1
	done
	echo
	echo "${green}Qortium ended gracefully${normal}"
fi

rm -f "${RUN_PID}"
