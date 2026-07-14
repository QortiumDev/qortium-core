#!/usr/bin/env bash
set -euo pipefail

# There's no need to run as root, so don't allow it, for security reasons
if [ "$(id -u)" -eq 0 ]; then
	echo "Please su to non-root user before running"
	exit 1
fi

MIN_JAVA_VER=17

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RUN_LOG="${SCRIPT_DIR}/run.log"
RUN_PID="${SCRIPT_DIR}/run.pid"

cd "${SCRIPT_DIR}"

if [ -f "${RUN_PID}" ]; then
	read -r existing_pid < "${RUN_PID}" || existing_pid=""
	if [ -n "${existing_pid}" ] && ps -p "${existing_pid}" >/dev/null 2>&1; then
		echo "Qortium is already running as pid ${existing_pid}"
		echo "Log file: ${RUN_LOG}"
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

	local jar
	for jar in "${SCRIPT_DIR}"/target/qortium*.jar; do
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
	echo "Build it first with: mvn -q -DskipTests package"
	exit 1
fi

# Limits Java JVM stack size and maximum heap usage.
# Comment out for bigger systems, e.g. non-routers
# or when API documentation is enabled.
JVM_MEMORY_ARGS=("-XX:MaxRAMPercentage=50" "-XX:+UseG1GC" "-Xss1024k")

# Compact object headers reduce per-object heap overhead. The flag only exists
# on Java 25+; passing it to an older JVM aborts startup, so gate on the major
# version detected above.
if awk -v found="${version:-0}" 'BEGIN { exit !(found >= 25) }'; then
	JVM_MEMORY_ARGS+=("-XX:+UseCompactObjectHeaders")
fi

nohup nice -n 20 java \
	-Djava.net.preferIPv4Stack=false \
	"${JVM_MEMORY_ARGS[@]}" \
	-jar "${JAR_PATH}" \
	>"${RUN_LOG}" 2>&1 &

echo "$!" > "${RUN_PID}"
echo "Qortium running as pid $!"
echo "Jar file: ${JAR_PATH}"
echo "Log file: ${RUN_LOG}"
