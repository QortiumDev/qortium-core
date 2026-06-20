#!/usr/bin/env bash
set -euo pipefail

MIN_JAVA_VER=17
JAVA_INSTALL_URL="https://adoptium.net/installation/"
MAVEN_INSTALL_URL="https://maven.apache.org/install.html"

yes=0
for arg in "$@"; do
	case "${arg}" in
		-y|--yes)
			yes=1
			;;
		-h|--help)
			echo "Usage: ./build.sh [--yes]"
			echo
			echo "Checks Java, javac, and Maven, then builds Qortium with:"
			echo "  mvn -q -DskipTests clean package"
			exit 0
			;;
		*)
			echo "Unknown option: ${arg}"
			echo "Usage: ./build.sh [--yes]"
			exit 1
			;;
	esac
done

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "${SCRIPT_DIR}"

print_install_help() {
	echo
	echo "Install or update the missing tools, then open a new terminal and run:"
	echo "  ./build.sh"
	echo
	echo "Java install help:"
	echo "  ${JAVA_INSTALL_URL}"
	echo "Maven install help:"
	echo "  ${MAVEN_INSTALL_URL}"
}

major_from_version() {
	local version="$1"
	if [ -z "${version}" ]; then
		return 1
	fi

	awk -F. '{ if ($1 == "1") print $2; else print $1 }' <<< "${version}"
}

java_major_version() {
	local version
	version="$(java -version 2>&1 | awk -F '"' '/version/ { print $2; exit }')"
	major_from_version "${version}"
}

failures=0

echo "Checking build prerequisites..."

if command -v java >/dev/null 2>&1; then
	java_version="$(java -version 2>&1 | awk -F '"' '/version/ { print $2; exit }')"
	java_major="$(java_major_version || true)"
	if [ -z "${java_major}" ] || ! [[ "${java_major}" =~ ^[0-9]+$ ]]; then
		echo "Java was found, but its version could not be detected."
		failures=1
	elif ! awk -v found="${java_major}" -v required="${MIN_JAVA_VER}" 'BEGIN { exit !(found >= required) }'; then
		echo "Java ${java_version} was found, but Qortium needs Java ${MIN_JAVA_VER} or newer."
		failures=1
	else
		echo "Java: ${java_version}"
	fi
else
	echo "Java was not found on PATH."
	failures=1
fi

if command -v javac >/dev/null 2>&1; then
	javac_version="$(javac -version 2>&1 | awk '{ print $2; exit }')"
	javac_major="$(major_from_version "${javac_version}" || true)"
	if [ -z "${javac_major}" ] || ! [[ "${javac_major}" =~ ^[0-9]+$ ]]; then
		echo "javac was found, but its version could not be detected."
		failures=1
	elif ! awk -v found="${javac_major}" -v required="${MIN_JAVA_VER}" 'BEGIN { exit !(found >= required) }'; then
		echo "javac ${javac_version} was found, but Qortium needs a JDK ${MIN_JAVA_VER} or newer."
		failures=1
	else
		echo "Javac: ${javac_version}"
	fi
else
	echo "javac was not found. Qortium needs a JDK, not only a JRE."
	failures=1
fi

if command -v mvn >/dev/null 2>&1; then
	maven_version="$(mvn -B -v 2>/dev/null | awk 'NR == 1 { print; exit }' || true)"
	if [ -n "${maven_version}" ]; then
		echo "Maven: ${maven_version}"
	else
		echo "Maven was found, but 'mvn -v' did not run successfully."
		failures=1
	fi
else
	echo "Maven was not found on PATH."
	failures=1
fi

if [ "${failures}" -ne 0 ]; then
	print_install_help
	exit 1
fi

echo
echo "This will build Qortium from source."
echo "Command:"
echo "  mvn -q -DskipTests clean package"

if [ "${yes}" -ne 1 ]; then
	printf "Continue? [y/N] "
	read -r answer || answer=""
	case "${answer}" in
		y|Y|yes|YES)
			;;
		*)
			echo "Build cancelled."
			exit 0
			;;
	esac
fi

mvn -q -DskipTests clean package

jar_path=""
for jar in "${SCRIPT_DIR}"/target/qortium*.jar; do
	if [ -f "${jar}" ]; then
		jar_path="${jar}"
		break
	fi
done

echo
if [ -n "${jar_path}" ]; then
	echo "Build complete."
	echo "Jar file: ${jar_path}"
else
	echo "Build completed, but no target/qortium*.jar file was found."
	exit 1
fi

echo
echo "Next preview commands:"
echo "  ./preview/start.sh"
echo "  ./preview/status.sh --wait"
echo "  ./preview/stop.sh"
echo
echo "Next testnet commands:"
echo "  ./testnet/start.sh"
echo "  ./testnet/stop.sh"
