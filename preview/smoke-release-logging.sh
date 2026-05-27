#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
PACKAGE_PATH="${REPO_DIR}/target/qortium-preview.zip"
TIMEOUT_SECONDS=90
KEEP_WORK_DIR=0
WORK_DIR=""
PREVIEW_DIR=""

usage() {
	echo "Usage: ./preview/smoke-release-logging.sh [--package=PATH] [--timeout=SECONDS] [--keep]"
	echo
	echo "Extracts a preview release zip, starts it headlessly, and verifies logging works."
}

fail() {
	echo "Smoke check failed: $*" >&2

	if [ -n "${PREVIEW_DIR}" ]; then
		if [ -f "${PREVIEW_DIR}/run.log" ]; then
			echo >&2
			echo "Last run.log lines:" >&2
			tail -n 40 "${PREVIEW_DIR}/run.log" >&2 || true
		fi

		if [ -f "${PREVIEW_DIR}/qortium.log" ]; then
			echo >&2
			echo "Last qortium.log lines:" >&2
			tail -n 40 "${PREVIEW_DIR}/qortium.log" >&2 || true
		fi
	fi

	exit 1
}

cleanup() {
	if [ -n "${PREVIEW_DIR}" ] && [ -x "${PREVIEW_DIR}/stop.sh" ]; then
		"${PREVIEW_DIR}/stop.sh" >/dev/null 2>&1 || true
	fi

	if [ -n "${WORK_DIR}" ]; then
		if [ "${KEEP_WORK_DIR}" -eq 1 ]; then
			echo "Kept smoke work directory: ${WORK_DIR}"
		else
			rm -rf "${WORK_DIR}"
		fi
	fi
}

trap cleanup EXIT

for arg in "$@"; do
	case "${arg}" in
		--package=*)
			PACKAGE_PATH="${arg#*=}"
			;;
		--timeout=*)
			TIMEOUT_SECONDS="${arg#*=}"
			;;
		--keep)
			KEEP_WORK_DIR=1
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

case "${TIMEOUT_SECONDS}" in
	''|*[!0-9]*)
		fail "--timeout must be a positive integer"
		;;
esac
if [ "${TIMEOUT_SECONDS}" -le 0 ]; then
	fail "--timeout must be a positive integer"
fi

if [ ! -f "${PACKAGE_PATH}" ]; then
	fail "package not found: ${PACKAGE_PATH}"
fi

if command -v curl >/dev/null 2>&1; then
	if curl -fsS --max-time 2 http://127.0.0.1:24891/admin/status >/dev/null 2>&1; then
		fail "a preview node already appears to be running on local API port 24891"
	fi
fi

WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/qortium-preview-logging-smoke.XXXXXX")"

if command -v unzip >/dev/null 2>&1; then
	unzip -q "${PACKAGE_PATH}" -d "${WORK_DIR}"
elif command -v jar >/dev/null 2>&1; then
	(
		cd "${WORK_DIR}"
		jar -xf "${PACKAGE_PATH}"
	)
else
	fail "neither unzip nor jar is available to extract ${PACKAGE_PATH}"
fi

PREVIEW_DIR="${WORK_DIR}/qortium-preview/preview"
if [ ! -x "${PREVIEW_DIR}/start.sh" ]; then
	fail "release package does not contain an executable preview/start.sh"
fi

START_OUTPUT="${WORK_DIR}/start-output.log"
if ! "${PREVIEW_DIR}/start.sh" --participant --headless >"${START_OUTPUT}" 2>&1; then
	cat "${START_OUTPUT}" >&2 || true
	fail "preview/start.sh failed"
fi

APP_LOG="${PREVIEW_DIR}/qortium.log"
RUN_LOG="${PREVIEW_DIR}/run.log"
DEADLINE=$((SECONDS + TIMEOUT_SECONDS))
SAW_LOG_GROWTH=0

while [ "${SECONDS}" -le "${DEADLINE}" ]; do
	if [ -s "${APP_LOG}" ]; then
		LOG_SIZE="$(wc -c <"${APP_LOG}" | tr -d ' ')"
		if [ "${LOG_SIZE}" -gt 0 ]; then
			SAW_LOG_GROWTH=1
		fi

		if [ "${SAW_LOG_GROWTH}" -eq 1 ] && grep -q "Starting up" "${APP_LOG}"; then
			break
		fi
	fi

	sleep 1
done

if [ "${SAW_LOG_GROWTH}" -ne 1 ]; then
	fail "preview/qortium.log was not created or did not grow within ${TIMEOUT_SECONDS}s"
fi

if ! grep -q "Starting up" "${APP_LOG}"; then
	fail "preview/qortium.log did not contain the expected startup line within ${TIMEOUT_SECONDS}s"
fi

if [ ! -s "${RUN_LOG}" ]; then
	fail "preview/run.log was not created"
fi

if ! grep -q "Qortium preview launcher started" "${RUN_LOG}"; then
	fail "preview/run.log did not contain the launcher header"
fi

echo "Preview release logging smoke check passed."
echo "Verified application log: ${APP_LOG}"
echo "Verified launcher log: ${RUN_LOG}"
