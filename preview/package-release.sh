#!/usr/bin/env bash
set -euo pipefail

SKIP_BUILD=0
OUTPUT_PATH=""

usage() {
	echo "Usage: ./preview/package-release.sh [--skip-build] [--output=PATH]"
	echo
	echo "Builds qortium-preview.zip for GitHub pre-release uploads."
	echo "  --skip-build   package the existing built jar without running ./build.sh"
	echo "  --output=PATH  write the zip to PATH instead of target/qortium-preview.zip"
}

for arg in "$@"; do
	case "${arg}" in
		--skip-build)
			SKIP_BUILD=1
			;;
		--output=*)
			OUTPUT_PATH="${arg#*=}"
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
TARGET_DIR="${REPO_DIR}/target"
STAGING_DIR="${TARGET_DIR}/qortium-preview-package"
PACKAGE_ROOT="${STAGING_DIR}/qortium-preview"

if [ -z "${OUTPUT_PATH}" ]; then
	OUTPUT_PATH="${TARGET_DIR}/qortium-preview.zip"
else
	case "${OUTPUT_PATH}" in
		/*) ;;
		*) OUTPUT_PATH="$(pwd)/${OUTPUT_PATH}" ;;
	esac
fi

if [ "${SKIP_BUILD}" -ne 1 ]; then
	"${REPO_DIR}/build.sh" --yes
fi

pom_version() {
	grep -m1 '<version>' "${REPO_DIR}/pom.xml" \
		| sed -E 's/.*<version>([^<]+)<\/version>.*/\1/'
}

find_qortium_jar() {
	local jar version
	version="$(pom_version)"
	# Prefer the jar matching the project version so a stale older jar left in
	# target/ (e.g. after a version bump) is never packaged in its place.
	if [ -n "${version}" ] && [ -f "${TARGET_DIR}/qortium-${version}.jar" ]; then
		printf '%s\n' "${TARGET_DIR}/qortium-${version}.jar"
		return 0
	fi
	for jar in "${TARGET_DIR}"/qortium*.jar; do
		if [ -f "${jar}" ]; then
			printf '%s\n' "${jar}"
			return 0
		fi
	done

	if [ -f "${REPO_DIR}/qortium.jar" ]; then
		printf '%s\n' "${REPO_DIR}/qortium.jar"
		return 0
	fi

	return 1
}

JAR_PATH="$(find_qortium_jar || true)"
if [ -z "${JAR_PATH}" ]; then
	echo "Could not find a qortium jar to package."
	echo "Run ./build.sh --yes first, or rerun without --skip-build."
	exit 1
fi

rm -rf "${STAGING_DIR}"
mkdir -p "${PACKAGE_ROOT}/preview"

cp "${JAR_PATH}" "${PACKAGE_ROOT}/qortium.jar"
cp "${SCRIPT_DIR}/TESTER-GUIDE.md" "${PACKAGE_ROOT}/README.md"

preview_files=(
	"README.md"
	"TESTER-GUIDE.md"
	"OPERATOR-RUNBOOK.md"
	"log4j2.properties"
	"previewchain.json"
	"settings-preview.json"
	"settings-preview-seed.json"
	"settings-preview-seed-netcup.json"
	"start.sh"
	"status.sh"
	"stop.sh"
	"reset.sh"
	"smoke-release-logging.sh"
	"start.bat"
	"status.bat"
	"stop.bat"
	"reset.bat"
	"start.ps1"
	"status.ps1"
	"stop.ps1"
	"reset.ps1"
)

for file in "${preview_files[@]}"; do
	cp "${SCRIPT_DIR}/${file}" "${PACKAGE_ROOT}/preview/${file}"
done

chmod +x "${PACKAGE_ROOT}/preview/"*.sh

mkdir -p "$(dirname "${OUTPUT_PATH}")"
rm -f "${OUTPUT_PATH}"

if command -v zip >/dev/null 2>&1; then
	(
		cd "${STAGING_DIR}"
		zip -qr "${OUTPUT_PATH}" qortium-preview
	)
elif command -v jar >/dev/null 2>&1; then
	jar --create --file "${OUTPUT_PATH}" --no-manifest -C "${STAGING_DIR}" qortium-preview
else
	echo "Neither zip nor jar is available to create ${OUTPUT_PATH}."
	exit 1
fi

echo "Preview package created: ${OUTPUT_PATH}"
echo "Included jar: ${JAR_PATH}"
