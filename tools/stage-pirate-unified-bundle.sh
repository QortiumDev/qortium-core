#!/bin/sh
set -eu

# Stages an already downloaded, pinned Pirate Unified artifact for offline
# validation. This script never downloads, signs, publishes, or loads native code.

usage() {
	printf '%s\n' "Usage: $0 <artifact.zip> <new-output-directory>" >&2
}

sha256_file() {
	if command -v sha256sum >/dev/null 2>&1; then
		sha256sum "$1" | awk '{print $1}'
	elif command -v shasum >/dev/null 2>&1; then
		shasum -a 256 "$1" | awk '{print $1}'
	else
		printf '%s\n' 'sha256sum or shasum is required' >&2
		return 1
	fi
}

if [ "$#" -ne 2 ]; then
	usage
	exit 2
fi

artifact=$1
output_directory=$2
script_directory=$(CDPATH='' cd "$(dirname "$0")" && pwd)
pin_file=$script_directory/pirate-unified-artifact.properties

release_tag=
release_url=
artifact_filename=
artifact_size=
artifact_sha256=
while IFS='=' read -r key value; do
	case $key in
		release_tag) release_tag=$value ;;
		release_url) release_url=$value ;;
		artifact_filename) artifact_filename=$value ;;
		artifact_size) artifact_size=$value ;;
		artifact_sha256) artifact_sha256=$value ;;
		''|'#'*) ;;
		*) printf 'Unknown artifact pin: %s\n' "$key" >&2; exit 1 ;;
	esac
done < "$pin_file"

if [ -z "$release_tag" ] || [ -z "$release_url" ] || [ -z "$artifact_filename" ] \
		|| [ -z "$artifact_size" ] || [ -z "$artifact_sha256" ]; then
	printf '%s\n' 'Artifact pin file is incomplete' >&2
	exit 1
fi
if [ ! -f "$artifact" ]; then
	printf 'Artifact does not exist: %s\n' "$artifact" >&2
	exit 1
fi
if [ -e "$output_directory" ]; then
	printf 'Refusing to overwrite existing output directory: %s\n' "$output_directory" >&2
	exit 1
fi
if ! command -v unzip >/dev/null 2>&1; then
	printf '%s\n' 'unzip is required' >&2
	exit 1
fi

actual_size=$(wc -c < "$artifact" | tr -d '[:space:]')
if [ "$actual_size" != "$artifact_size" ]; then
	printf 'Artifact size mismatch: expected %s, got %s\n' "$artifact_size" "$actual_size" >&2
	exit 1
fi
actual_sha256=$(sha256_file "$artifact")
if [ "$actual_sha256" != "$artifact_sha256" ]; then
	printf 'Artifact SHA-256 mismatch: expected %s, got %s\n' "$artifact_sha256" "$actual_sha256" >&2
	exit 1
fi

parent_directory=$(dirname "$output_directory")
mkdir -p "$parent_directory"
staging_directory=$(mktemp -d "$parent_directory/.pirate-unified-stage.XXXXXX")
trap 'rm -rf "$staging_directory"' 0 HUP INT TERM

payload_files='LICENSE-qortal-jni.txt
librust-linux-aarch64.so
librust-linux-x86_64.so
librust-macos-aarch64.dylib
librust-macos-x86_64.dylib
librust-windows-x86_64.dll
qortal-handoff.md'

printf '%s\n' "$payload_files" | while IFS= read -r filename; do
	entry_count=$(unzip -Z1 "$artifact" "$filename" | awk -v expected="$filename" '$0 == expected { count++ } END { print count + 0 }')
	if [ "$entry_count" -ne 1 ]; then
		printf 'Verified artifact must contain exactly one top-level %s entry\n' "$filename" >&2
		exit 1
	fi
	unzip -p "$artifact" "$filename" > "$staging_directory/$filename"
done

manifest=$staging_directory/QORTIUM-MANIFEST.txt
{
	printf 'format: qortium-pirate-unified-bundle-v1\n'
	printf 'release-tag: %s\n' "$release_tag"
	printf 'release-url: %s\n' "$release_url"
	printf 'artifact-filename: %s\n' "$artifact_filename"
	printf 'artifact-size: %s\n' "$artifact_size"
	printf 'artifact-sha256: %s\n' "$artifact_sha256"
	printf 'bundle-kind: cross-platform\n'
	printf 'platform: all\n'
	printf '%s\n' "$payload_files" | while IFS= read -r filename; do
		file_size=$(wc -c < "$staging_directory/$filename" | tr -d '[:space:]')
		file_sha256=$(sha256_file "$staging_directory/$filename")
		printf 'file: %s %s  %s\n' "$file_size" "$file_sha256" "$filename"
	done
} > "$manifest"

if [ -e "$output_directory" ]; then
	printf 'Refusing to replace output directory created during staging: %s\n' "$output_directory" >&2
	exit 1
fi
mv "$staging_directory" "$output_directory"
trap - 0 HUP INT TERM
printf 'Staged verified Pirate Unified %s bundle at %s\n' "$release_tag" "$output_directory"
