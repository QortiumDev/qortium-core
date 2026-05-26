#!/usr/bin/env bash
set -euo pipefail

MIN_JAVA_VER=17
DEFAULT_MINTING_PRIVATE_KEY="1CeDCg9TSdBwJNGVTGG7pCKsvsyyoEcaVXYvDT1Xb9f"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

SETTINGS_TEMPLATE="${SCRIPT_DIR}/settings-test.json"
SETTINGS_LOCAL="${SCRIPT_DIR}/settings-test-local.json"
CHAIN_TEMPLATE="${SCRIPT_DIR}/testchain.json"
CHAIN_LOCAL="${SCRIPT_DIR}/testchain-local.json"
RUN_LOG="${SCRIPT_DIR}/run.log"
RUN_PID="${SCRIPT_DIR}/run.pid"
APIKEY_FILE="${SCRIPT_DIR}/apikey.txt"
DB_PATH="${SCRIPT_DIR}/db-testnet"

cd "${SCRIPT_DIR}"

if [ -f "${RUN_PID}" ]; then
	read -r existing_pid < "${RUN_PID}" || existing_pid=""
	if [ -n "${existing_pid}" ] && ps -p "${existing_pid}" >/dev/null 2>&1; then
		echo "Qortium testnet is already running as pid ${existing_pid}"
		exit 0
	fi

	rm -f "${RUN_PID}"
fi

if command -v java >/dev/null 2>&1; then
	version="$(java -version 2>&1 | awk -F '"' '/version/ {print $2}' | cut -d. -f1)"
	if ! awk -v found="${version}" -v required="${MIN_JAVA_VER}" 'BEGIN { exit !(found >= required) }'; then
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
	echo "Build it first with: mvn -q -DskipTests package"
	exit 1
fi

if [ -f "${CHAIN_LOCAL}" ]; then
	echo "Using existing local chain config: ${CHAIN_LOCAL}"
else
	if [ -d "${DB_PATH}" ] && find "${DB_PATH}" -mindepth 1 -print -quit | grep -q .; then
		echo "Existing testnet database found, but ${CHAIN_LOCAL} is missing."
		echo "Restore ${CHAIN_LOCAL}, or reset the local testnet by removing ${DB_PATH}."
		exit 1
	fi

	GENESIS_TIMESTAMP="$(($(date +%s) * 1000 - 60000))"
	awk -v genesis_timestamp="${GENESIS_TIMESTAMP}" '
		/"genesisInfo"[[:space:]]*:/ { in_genesis = 1 }
		in_genesis && !updated && /"timestamp"[[:space:]]*:[[:space:]]*[0-9]+/ {
			sub(/"timestamp"[[:space:]]*:[[:space:]]*[0-9]+/, "\"timestamp\": " genesis_timestamp)
			updated = 1
		}
		{ print }
	' "${CHAIN_TEMPLATE}" > "${CHAIN_LOCAL}"
	echo "Created local chain config: ${CHAIN_LOCAL}"
fi

sed 's/"blockchainConfig": "testchain.json"/"blockchainConfig": "testchain-local.json"/' \
	"${SETTINGS_TEMPLATE}" > "${SETTINGS_LOCAL}"

JVM_MEMORY_ARGS=("-Xss256m" "-XX:+UseSerialGC")

nohup nice -n 20 java \
	-Djava.net.preferIPv4Stack=false \
	"${JVM_MEMORY_ARGS[@]}" \
	-jar "${JAR_PATH}" \
	"${SETTINGS_LOCAL}" \
	>"${RUN_LOG}" 2>&1 &

echo "$!" > "${RUN_PID}"
echo "Qortium testnet running as pid $!"
echo "Console log: ${RUN_LOG}"
echo "Application log: ${SCRIPT_DIR}/qortium.log"

if ! command -v curl >/dev/null 2>&1; then
	echo "curl is not available. Add the default minting key after startup with:"
	echo "curl -X POST http://localhost:24891/admin/mintingaccounts \\"
	echo "  -H \"X-API-KEY: \$(cat ${APIKEY_FILE})\" \\"
	echo "  -d ${DEFAULT_MINTING_PRIVATE_KEY}"
	exit 0
fi

read_api_key() {
	if [ -f "${APIKEY_FILE}" ]; then
		tr -d '\r\n' < "${APIKEY_FILE}"
	fi
}

echo -n "Adding default local minting key"
for _ in $(seq 1 60); do
	api_key="$(read_api_key)"
	if [ -n "${api_key}" ] && curl -fsS -X POST "http://localhost:24891/admin/mintingaccounts" \
		-H "X-API-KEY: ${api_key}" \
		--data "${DEFAULT_MINTING_PRIVATE_KEY}" >/dev/null 2>&1; then
		echo
		echo "Default local minting key added"
		exit 0
	fi

	echo -n "."
	sleep 1
done

echo
echo "The node started, but the default minting key was not added automatically."
echo "Run this after the API is ready:"
echo "curl -X POST http://localhost:24891/admin/mintingaccounts \\"
echo "  -H \"X-API-KEY: \$(cat ${APIKEY_FILE})\" \\"
echo "  -d ${DEFAULT_MINTING_PRIVATE_KEY}"
