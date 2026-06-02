#!/usr/bin/env bash
set -euo pipefail

if [ "$(id -u)" -eq 0 ]; then
	echo "Please run preview nodes as a non-root user."
	exit 1
fi

MIN_JAVA_VER=17
MODE="participant"
HEADLESS_MODE="auto"

usage() {
	echo "Usage: ./preview/start.sh [--seed|--seed-regxa|--seed-netcup|--participant] [--headless|--gui]"
	echo
	echo "Starts a Qortium preview-network node."
	echo "  --participant  connect to the preview seeds at 146.103.42.59 and 185.207.104.78 (default)"
	echo "  --seed         use the Regxa seed settings"
	echo "  --seed-regxa   advertise the Regxa seed IP 146.103.42.59"
	echo "  --seed-netcup  advertise the Netcup seed IP 185.207.104.78"
	echo "  --headless     force Java headless mode"
	echo "  --gui          force Java GUI mode"
	echo
	echo "By default, the launcher uses headless mode only when no desktop display"
	echo "is detected."
}

for arg in "$@"; do
	case "${arg}" in
		--seed|--seed-regxa)
			MODE="seed-regxa"
			;;
		--seed-netcup)
			MODE="seed-netcup"
			;;
		--participant)
			MODE="participant"
			;;
		--headless)
			HEADLESS_MODE="true"
			;;
		--gui)
			HEADLESS_MODE="false"
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

detect_headless_environment() {
	case "$(uname -s)" in
		Linux|FreeBSD|OpenBSD|NetBSD)
			[ -z "${DISPLAY:-}" ] && [ -z "${WAYLAND_DISPLAY:-}" ]
			;;
		*)
			return 1
			;;
	esac
}

read_auto_update_mode() {
	local settings_file="$1"
	grep -Eo '"autoUpdateMode"[[:space:]]*:[[:space:]]*"[^"]+"' "${settings_file}" 2>/dev/null \
		| head -n 1 \
		| sed -E 's/.*"autoUpdateMode"[[:space:]]*:[[:space:]]*"([^"]+)".*/\1/'
}

apply_auto_update_mode() {
	local settings_file="$1"
	local mode="$2"

	case "${mode}" in
		OFF|CHECK_ONLY|NOTIFY|INSTALL)
			;;
		*)
			echo "Ignoring invalid local autoUpdateMode: ${mode}"
			return 0
			;;
	esac

	sed -i "s/\"autoUpdateMode\"[[:space:]]*:[[:space:]]*\"[^\"]*\"/\"autoUpdateMode\": \"${mode}\"/" "${settings_file}"
}

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
RUN_LOG="${SCRIPT_DIR}/run.log"
RUN_PID="${SCRIPT_DIR}/run.pid"
APP_LOG="${SCRIPT_DIR}/qortium.log"
LOG4J_CONFIG="${SCRIPT_DIR}/log4j2.properties"

case "${MODE}" in
	seed-regxa)
		SETTINGS_TEMPLATE="${SCRIPT_DIR}/settings-preview-seed.json"
		SETTINGS_LOCAL="${SCRIPT_DIR}/settings-preview-seed-local.json"
		;;
	seed-netcup)
		SETTINGS_TEMPLATE="${SCRIPT_DIR}/settings-preview-seed-netcup.json"
		SETTINGS_LOCAL="${SCRIPT_DIR}/settings-preview-seed-netcup-local.json"
		;;
	participant)
		SETTINGS_TEMPLATE="${SCRIPT_DIR}/settings-preview.json"
		SETTINGS_LOCAL="${SCRIPT_DIR}/settings-preview-local.json"
		;;
esac

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
	echo "For the release zip, make sure qortium.jar is in the extracted folder or preview folder."
	echo "For a source checkout, build it first with: ./build.sh --yes"
	exit 1
fi

AUTO_UPDATE_MODE_OVERRIDE="${QORTIUM_PREVIEW_AUTO_UPDATE_MODE:-}"
if [ -z "${AUTO_UPDATE_MODE_OVERRIDE}" ] && [ -f "${SETTINGS_LOCAL}" ]; then
	AUTO_UPDATE_MODE_OVERRIDE="$(read_auto_update_mode "${SETTINGS_LOCAL}" || true)"
fi

cp "${SETTINGS_TEMPLATE}" "${SETTINGS_LOCAL}"
if [ -n "${AUTO_UPDATE_MODE_OVERRIDE}" ]; then
	apply_auto_update_mode "${SETTINGS_LOCAL}" "${AUTO_UPDATE_MODE_OVERRIDE}"
fi
AUTO_UPDATE_MODE_EFFECTIVE="$(read_auto_update_mode "${SETTINGS_LOCAL}" || true)"

JVM_MEMORY_ARGS_STRING="${QORTIUM_PREVIEW_JVM_MEMORY_ARGS:--XX:MaxRAMPercentage=50 -XX:+UseG1GC -Xss1024k}"
read -r -a JVM_MEMORY_ARGS <<< "${JVM_MEMORY_ARGS_STRING}"

JAVA_DISPLAY_ARGS=()
DISPLAY_MODE_DESCRIPTION="GUI auto-detected"
case "${HEADLESS_MODE}" in
	true)
		JAVA_DISPLAY_ARGS=("-Djava.awt.headless=true")
		DISPLAY_MODE_DESCRIPTION="headless forced"
		;;
	false)
		JAVA_DISPLAY_ARGS=("-Djava.awt.headless=false")
		DISPLAY_MODE_DESCRIPTION="GUI forced"
		;;
	auto)
		if detect_headless_environment; then
			JAVA_DISPLAY_ARGS=("-Djava.awt.headless=true")
			DISPLAY_MODE_DESCRIPTION="headless auto-detected"
		fi
		;;
esac

NICE_ARGS=()
if command -v nice >/dev/null 2>&1; then
	NICE_ARGS=(nice -n 20)
fi

{
	echo "Qortium preview launcher started at $(date -Is 2>/dev/null || date)"
	echo "Mode: ${MODE}"
	echo "Settings file: ${SETTINGS_LOCAL}"
	echo "Jar file: ${JAR_PATH}"
	echo "Log4j config: ${LOG4J_CONFIG}"
	echo "Application log: ${APP_LOG}"
	echo "Display mode: ${DISPLAY_MODE_DESCRIPTION}"
	echo "Auto-update mode: ${AUTO_UPDATE_MODE_EFFECTIVE:-OFF}"
	echo
} >"${RUN_LOG}"

if command -v setsid >/dev/null 2>&1; then
	nohup setsid "${NICE_ARGS[@]}" java \
		-Djava.net.preferIPv4Stack=false \
		-Dlog4j.configurationFile="${LOG4J_CONFIG}" \
		-Dqortium.log.dir="${SCRIPT_DIR}" \
		-Dqortium.pid.file="${RUN_PID}" \
		"${JAVA_DISPLAY_ARGS[@]}" \
		"${JVM_MEMORY_ARGS[@]}" \
		-jar "${JAR_PATH}" \
		"${SETTINGS_LOCAL}" \
		>>"${RUN_LOG}" 2>&1 &
elif command -v nohup >/dev/null 2>&1; then
	nohup "${NICE_ARGS[@]}" java \
		-Djava.net.preferIPv4Stack=false \
		-Dlog4j.configurationFile="${LOG4J_CONFIG}" \
		-Dqortium.log.dir="${SCRIPT_DIR}" \
		-Dqortium.pid.file="${RUN_PID}" \
		"${JAVA_DISPLAY_ARGS[@]}" \
		"${JVM_MEMORY_ARGS[@]}" \
		-jar "${JAR_PATH}" \
		"${SETTINGS_LOCAL}" \
		>>"${RUN_LOG}" 2>&1 &
else
	"${NICE_ARGS[@]}" java \
	-Djava.net.preferIPv4Stack=false \
	-Dlog4j.configurationFile="${LOG4J_CONFIG}" \
	-Dqortium.log.dir="${SCRIPT_DIR}" \
	-Dqortium.pid.file="${RUN_PID}" \
	"${JAVA_DISPLAY_ARGS[@]}" \
	"${JVM_MEMORY_ARGS[@]}" \
	-jar "${JAR_PATH}" \
	"${SETTINGS_LOCAL}" \
	>>"${RUN_LOG}" 2>&1 &
fi

echo "$!" > "${RUN_PID}"
echo "Qortium preview ${MODE} node running as pid $!"
echo "Settings file: ${SETTINGS_LOCAL}"
echo "Jar file: ${JAR_PATH}"
echo "Display mode: ${DISPLAY_MODE_DESCRIPTION}"
echo "Auto-update mode: ${AUTO_UPDATE_MODE_EFFECTIVE:-OFF}"
echo "Console log: ${RUN_LOG}"
echo "Log4j config: ${LOG4J_CONFIG}"
echo "Application log: ${APP_LOG}"
echo
echo "Preview genesis and settings are fixed. No minting key was added automatically."
echo "Next commands:"
echo "  ./preview/status.sh --wait"
echo "  ./preview/stop.sh"
echo "  ./preview/reset.sh"
echo "Tester guide: ${SCRIPT_DIR}/TESTER-GUIDE.md"
