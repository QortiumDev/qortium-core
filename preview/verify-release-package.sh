#!/usr/bin/env bash
set -euo pipefail

PACKAGE_PATH=""
JAR_PATH=""

usage() {
	echo "Usage: ./preview/verify-release-package.sh [--package=PATH] [--jar=PATH]"
	echo
	echo "Verifies that a Previewnet release ZIP contains the current built JAR,"
	echo "the JAR's current full Git commit, and the source previewchain.json."
	echo "Defaults to target/qortium-preview.zip and the current project-version JAR."
}

for arg in "$@"; do
	case "${arg}" in
		--package=*)
			PACKAGE_PATH="${arg#*=}"
			;;
		--jar=*)
			JAR_PATH="${arg#*=}"
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
SOURCE_CHAIN_CONFIG="${REPO_DIR}/src/main/resources/previewchain.json"

WORKTREE_STATUS="$(git -C "${REPO_DIR}" status --porcelain --untracked-files=normal)"
if [ -n "${WORKTREE_STATUS}" ]; then
	echo "Preview package verification failed: the source checkout is not clean."
	printf '%s\n' "${WORKTREE_STATUS}"
	exit 1
fi

if [ -z "${PACKAGE_PATH}" ]; then
	PACKAGE_PATH="${REPO_DIR}/target/qortium-preview.zip"
fi

if [ -z "${JAR_PATH}" ]; then
	PROJECT_VERSION="$(grep -m1 '<version>' "${REPO_DIR}/pom.xml" \
		| sed -E 's/.*<version>([^<]+)<\/version>.*/\1/')"
	JAR_PATH="${REPO_DIR}/target/qortium-${PROJECT_VERSION}.jar"
fi

for required_file in "${PACKAGE_PATH}" "${JAR_PATH}" "${SOURCE_CHAIN_CONFIG}"; do
	if [ ! -f "${required_file}" ]; then
		echo "Required file not found: ${required_file}"
		exit 1
	fi
done

if ! command -v unzip >/dev/null 2>&1; then
	echo "unzip is required to verify ${PACKAGE_PATH}."
	exit 1
fi

TEMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/qortium-preview-verify.XXXXXX")"
trap 'rm -rf "${TEMP_DIR}"' EXIT

INNER_JAR="${TEMP_DIR}/qortium.jar"
PACKAGED_CHAIN_CONFIG="${TEMP_DIR}/previewchain.json"

unzip -tq "${PACKAGE_PATH}" >/dev/null
unzip -p "${PACKAGE_PATH}" qortium-preview/qortium.jar > "${INNER_JAR}"
unzip -p "${PACKAGE_PATH}" qortium-preview/preview/previewchain.json > "${PACKAGED_CHAIN_CONFIG}"
unzip -tq "${INNER_JAR}" >/dev/null

if ! cmp -s "${JAR_PATH}" "${INNER_JAR}"; then
	echo "Preview package verification failed: inner JAR does not match ${JAR_PATH}."
	exit 1
fi

if ! cmp -s "${SOURCE_CHAIN_CONFIG}" "${PACKAGED_CHAIN_CONFIG}"; then
	echo "Preview package verification failed: packaged previewchain.json does not match source."
	exit 1
fi

EXPECTED_COMMIT="$(git -C "${REPO_DIR}" rev-parse HEAD)"
GIT_PROPERTIES="$(unzip -p "${INNER_JAR}" git.properties | tr -d '\r')"
COMMIT_PROPERTY_COUNT="$(printf '%s\n' "${GIT_PROPERTIES}" | grep -c '^git.commit.id.full=' || true)"

if [ "${COMMIT_PROPERTY_COUNT}" -ne 1 ]; then
	echo "Preview package verification failed: inner JAR must contain one full Git commit property."
	exit 1
fi

EMBEDDED_COMMIT="$(printf '%s\n' "${GIT_PROPERTIES}" | sed -n 's/^git\.commit\.id\.full=//p')"
if [ "${EMBEDDED_COMMIT}" != "${EXPECTED_COMMIT}" ]; then
	echo "Preview package verification failed: inner JAR commit ${EMBEDDED_COMMIT} does not match HEAD ${EXPECTED_COMMIT}."
	exit 1
fi

echo "Preview package verified: ${PACKAGE_PATH}"
echo "Verified jar: ${JAR_PATH}"
echo "Verified commit: ${EMBEDDED_COMMIT}"
echo "Verified chain config: ${SOURCE_CHAIN_CONFIG}"
