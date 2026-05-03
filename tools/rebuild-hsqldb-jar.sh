#!/usr/bin/env bash
set -euo pipefail

VERSION="2.7.4"
UPSTREAM_SHA256="5fab2bb4384ac06b762638c8fa2740c944b8d080e4796c0c6c2af8b90dd4e5ad"
PATCHED_SHA256="d26b2294f9f16ea54b72f87ca1e2b32e8da1a66b2995ab41385ff926e518c069"
UPSTREAM_URL="https://repo.maven.apache.org/maven2/org/hsqldb/hsqldb/${VERSION}/hsqldb-${VERSION}.jar"

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "${SCRIPT_DIR}/.." && pwd)"
LOCAL_JAR="${REPO_ROOT}/lib/org/hsqldb/hsqldb/${VERSION}/hsqldb.jar"

usage() {
	printf 'Usage: %s [--verify] [--output PATH]\n' "$(basename "$0")"
	printf '\n'
	printf 'Verifies Qortium'\''s patched HSQLDB jar against Maven Central.\n'
	printf 'With --output, also writes a freshly patched jar to PATH.\n'
}

mode="verify"
output_path=""

while [ "$#" -gt 0 ]; do
	case "$1" in
		--verify)
			mode="verify"
			;;
		--output)
			if [ "$#" -lt 2 ]; then
				usage >&2
				exit 2
			fi
			mode="output"
			output_path="$2"
			shift
			;;
		-h|--help)
			usage
			exit 0
			;;
		*)
			usage >&2
			exit 2
			;;
	esac
	shift
done

require_command() {
	if ! command -v "$1" >/dev/null 2>&1; then
		printf 'Missing required command: %s\n' "$1" >&2
		exit 1
	fi
}

for command_name in awk cp curl diff dirname jar mkdir mktemp rm sed sha256sum zip; do
	require_command "${command_name}"
done

work_dir="$(mktemp -d)"
trap 'rm -rf "${work_dir}"' EXIT

upstream_jar="${work_dir}/hsqldb-${VERSION}-upstream.jar"
expected_manifest="${work_dir}/MANIFEST.MF.expected"

verify_sha256() {
	local expected="$1"
	local file="$2"
	local actual

	actual="$(sha256sum "${file}" | awk '{print $1}')"
	if [ "${actual}" != "${expected}" ]; then
		printf 'Unexpected SHA-256 for %s\n' "${file}" >&2
		printf 'Expected: %s\n' "${expected}" >&2
		printf 'Actual:   %s\n' "${actual}" >&2
		exit 1
	fi
}

extract_jar() {
	local jar_file="$1"
	local target_dir="$2"

	mkdir -p "${target_dir}"
	(cd "${target_dir}" && jar xf "${jar_file}")
}

verify_patch_only_changes_manifest_seal() {
	local candidate_jar="$1"
	local candidate_dir="${work_dir}/candidate"
	local upstream_dir="${work_dir}/upstream"

	rm -rf "${candidate_dir}" "${upstream_dir}"
	extract_jar "${upstream_jar}" "${upstream_dir}"
	extract_jar "${candidate_jar}" "${candidate_dir}"

	sed 's/^Sealed: true/Sealed: false/' \
		"${upstream_dir}/META-INF/MANIFEST.MF" > "${expected_manifest}"

	if ! diff -u "${expected_manifest}" "${candidate_dir}/META-INF/MANIFEST.MF"; then
		printf 'Patched manifest does not match the expected Sealed:false manifest.\n' >&2
		exit 1
	fi

	rm "${upstream_dir}/META-INF/MANIFEST.MF" "${candidate_dir}/META-INF/MANIFEST.MF"

	if ! diff -qr "${upstream_dir}" "${candidate_dir}"; then
		printf 'Patched jar differs from upstream outside META-INF/MANIFEST.MF.\n' >&2
		exit 1
	fi
}

printf 'Downloading HSQLDB %s from Maven Central...\n' "${VERSION}"
curl -fsSL "${UPSTREAM_URL}" -o "${upstream_jar}"
verify_sha256 "${UPSTREAM_SHA256}" "${upstream_jar}"

case "${mode}" in
	verify)
		if [ ! -f "${LOCAL_JAR}" ]; then
			printf 'Missing local patched jar: %s\n' "${LOCAL_JAR}" >&2
			exit 1
		fi

		verify_sha256 "${PATCHED_SHA256}" "${LOCAL_JAR}"
		verify_patch_only_changes_manifest_seal "${LOCAL_JAR}"
		printf 'Verified %s\n' "${LOCAL_JAR}"
		;;
	output)
		output_dir="$(dirname -- "${output_path}")"
		mkdir -p "${output_dir}"
		cp "${upstream_jar}" "${output_path}"

		manifest_work="${work_dir}/manifest-work/META-INF"
		mkdir -p "${manifest_work}"
		extract_jar "${upstream_jar}" "${work_dir}/manifest-source"
		sed 's/^Sealed: true/Sealed: false/' \
			"${work_dir}/manifest-source/META-INF/MANIFEST.MF" > "${manifest_work}/MANIFEST.MF"
		(cd "${work_dir}/manifest-work" && zip -q -X "${output_path}" META-INF/MANIFEST.MF)

		verify_patch_only_changes_manifest_seal "${output_path}"
		printf 'Wrote patched jar to %s\n' "${output_path}"
		;;
esac
