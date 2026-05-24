#!/usr/bin/env bash
set -euo pipefail

if [ "$(id -u)" -eq 0 ]; then
	echo "Please run preview nodes as a non-root user."
	exit 1
fi

MIN_JAVA_VER=17
MODE="participant"

usage() {
	echo "Usage: ./preview/start.sh [--seed|--participant]"
	echo
	echo "Starts a Qortium preview-network node."
	echo "  --participant  connect to the preview seed at 146.103.42.59 (default)"
	echo "  --seed         use the VPS seed settings"
}

for arg in "$@"; do
	case "${arg}" in
		--seed)
			MODE="seed"
			;;
		--participant)
			MODE="participant"
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
REPO_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
RUN_LOG="${SCRIPT_DIR}/run.log"
RUN_PID="${SCRIPT_DIR}/run.pid"

if [ "${MODE}" = "seed" ]; then
	SETTINGS_TEMPLATE="${SCRIPT_DIR}/settings-preview-seed.json"
	SETTINGS_LOCAL="${SCRIPT_DIR}/settings-preview-seed-local.json"
else
	SETTINGS_TEMPLATE="${SCRIPT_DIR}/settings-preview.json"
	SETTINGS_LOCAL="${SCRIPT_DIR}/settings-preview-local.json"
fi

cd "${SCRIPT_DIR}"

if [ -f "${RUN_PID}" ]; then
	read -r existing_pid < "${RUN_PID}" || existing_pid=""
	if [ -n "${existing_pid}" ] && ps -p "${existing_pid}" >/dev/null 2>&1; then
		echo "Qortium preview node is already running as pid ${existing_pid}"
		echo "Console log: ${RUN_LOG}"
		exit 0
	fi

	rm -f "${RUN_PID}"
fi

if command -v java >/dev/null 2>&1; then
	version="$(java -version 2>&1 | awk -F '"' '/version/ { print $2 }' | awk -F. '{ if ($1 == "1") print $2; else print $1 }')"
	if ! awk -v found="${version:-0}" -v required="${MIN_JAVA_VER}" 'BEGIN { exit !(found >= required) }'; then
		echo "Please upgrade Java to version ${MIN_JAVA_VER} or greater"
		exit 1
	fi
else
	echo "Java is not available. Please install Java ${MIN_JAVA_VER} or greater."
	exit 1
fi

find_qortium_jar() {
	if [ -f "${SCRIPT_DIR}/qortium.jar" ]; then
		printf '%s\n' "${SCRIPT_DIR}/qortium.jar"
		return 0
	fi

	if [ -f "${REPO_DIR}/qortium.jar" ]; then
		printf '%s\n' "${REPO_DIR}/qortium.jar"
		return 0
	fi

	local jar
	for jar in "${REPO_DIR}"/target/qortium*.jar; do
		if [ -f "${jar}" ]; then
			printf '%s\n' "${jar}"
			return 0
		fi
	done

	return 1
}

JAR_PATH="$(find_qortium_jar || true)"
if [ -z "${JAR_PATH}" ]; then
	echo "Could not find qortium.jar."
	echo "Build it first with: ./build.sh"
	exit 1
fi

cp "${SETTINGS_TEMPLATE}" "${SETTINGS_LOCAL}"

JVM_MEMORY_ARGS_STRING="${QORTIUM_PREVIEW_JVM_MEMORY_ARGS:--XX:MaxRAMPercentage=50 -XX:+UseG1GC -Xss1024k}"
read -r -a JVM_MEMORY_ARGS <<< "${JVM_MEMORY_ARGS_STRING}"

nohup nice -n 20 java \
	-Djava.net.preferIPv4Stack=false \
	"${JVM_MEMORY_ARGS[@]}" \
	-jar "${JAR_PATH}" \
	"${SETTINGS_LOCAL}" \
	>"${RUN_LOG}" 2>&1 &

echo "$!" > "${RUN_PID}"
echo "Qortium preview ${MODE} node running as pid $!"
echo "Settings file: ${SETTINGS_LOCAL}"
echo "Jar file: ${JAR_PATH}"
echo "Console log: ${RUN_LOG}"
echo "Application log: ${SCRIPT_DIR}/qortium.log"
echo
echo "Preview genesis and settings are fixed. No minting key was added automatically."
