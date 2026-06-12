#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
RUNTIME_DIR_OPTION=""

for arg in "$@"; do
	case "${arg}" in
		--runtime-dir=*)
			RUNTIME_DIR_OPTION="${arg#*=}"
			;;
		-h|--help)
			echo "Usage: ./preview/reset.sh [--runtime-dir=PATH]"
			exit 0
			;;
		*)
			echo "Unknown option: ${arg}"
			echo "Usage: ./preview/reset.sh [--runtime-dir=PATH]"
			exit 1
			;;
	esac
done

resolve_runtime_dir() {
	local runtime_dir="$1"

	if [ -z "${runtime_dir}" ]; then
		runtime_dir="${SCRIPT_DIR}"
	fi

	(
		cd "${runtime_dir}" 2>/dev/null
		pwd -P
	) || printf '%s\n' "${runtime_dir}"
}

RUNTIME_DIR="$(resolve_runtime_dir "${RUNTIME_DIR_OPTION:-${QORTIUM_PREVIEW_RUNTIME_DIR:-}}")"

cd "${REPO_DIR}"

echo "Stopping preview node if it is running..."
if stop_output="$("${SCRIPT_DIR}/stop.sh" --runtime-dir="${RUNTIME_DIR}" 2>&1)"; then
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
	"${RUNTIME_DIR}/db-preview"
	"${RUNTIME_DIR}/data-preview"
	"${RUNTIME_DIR}/qortium-backup"
	"${RUNTIME_DIR}/qortium-backup-preview"
	"${RUNTIME_DIR}/settings-preview-local.json"
	"${RUNTIME_DIR}/settings-preview-local.template.json"
	"${RUNTIME_DIR}/settings-preview-seed-local.json"
	"${RUNTIME_DIR}/settings-preview-seed-local.template.json"
	"${RUNTIME_DIR}/settings-preview-seed-netcup-local.json"
	"${RUNTIME_DIR}/settings-preview-seed-netcup-local.template.json"
	"${RUNTIME_DIR}/run.log"
	"${RUNTIME_DIR}/run-error.log"
	"${RUNTIME_DIR}/run.pid"
	"${RUNTIME_DIR}/qortium.log"
	"${RUNTIME_DIR}/QortiumKeyStore.jks"
	"${RUNTIME_DIR}/apikey.txt"
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
echo "  ./preview/start.sh --runtime-dir=${RUNTIME_DIR}"
