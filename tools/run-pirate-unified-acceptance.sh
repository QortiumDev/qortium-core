#!/bin/sh
set -eu

# Validates a staged local bundle and writes a non-overwriting receipt. Native
# execution is separately opt-in. This script never downloads or publishes.

usage() {
	printf '%s\n' "Usage: $0 <absolute-artifact.zip> <absolute-bundle-directory> <new-receipt.md> [--native]" >&2
}

if [ "$#" -lt 3 ] || [ "$#" -gt 4 ]; then
	usage
	exit 2
fi

artifact=$1
bundle=$2
receipt=$3
mode=${4:-}
if [ -n "$mode" ] && [ "$mode" != '--native' ]; then
	usage
	exit 2
fi
case $artifact in
	/*) ;;
	*) printf '%s\n' 'Artifact path must be absolute' >&2; exit 2 ;;
esac
if [ ! -f "$artifact" ]; then
	printf 'Artifact does not exist: %s\n' "$artifact" >&2
	exit 1
fi
case $bundle in
	/*) ;;
	*) printf '%s\n' 'Bundle path must be absolute' >&2; exit 2 ;;
esac
if [ ! -d "$bundle" ]; then
	printf 'Bundle directory does not exist: %s\n' "$bundle" >&2
	exit 1
fi
if [ -e "$receipt" ] || [ -e "$receipt.log" ]; then
	printf 'Refusing to overwrite receipt or log: %s\n' "$receipt" >&2
	exit 1
fi

script_directory=$(CDPATH='' cd "$(dirname "$0")" && pwd)
repository=$(CDPATH='' cd "$script_directory/.." && pwd)
receipt_parent=$(dirname "$receipt")
mkdir -p "$receipt_parent"
lock_directory=$receipt.lock
if ! mkdir "$lock_directory" 2>/dev/null; then
	printf 'Another acceptance run already owns this receipt: %s\n' "$receipt" >&2
	exit 1
fi
if [ -e "$receipt" ] || [ -e "$receipt.log" ]; then
	rmdir "$lock_directory"
	printf 'Refusing to overwrite receipt or log: %s\n' "$receipt" >&2
	exit 1
fi
work_directory=$(mktemp -d "$receipt_parent/.pirate-unified-acceptance.XXXXXX")
trap 'rm -rf "$work_directory"; rmdir "$lock_directory" 2>/dev/null || true' 0 HUP INT TERM

tests='PirateUnifiedArtifactPinTests,PirateUnifiedWalletBundleTests,PirateUnifiedArtifactAcceptanceTests,LiteWalletJniSurfaceTests,ZcashFamilyWalletControllerQdnTests'
native_result=NOT_RUN
native_flag=
if [ "$mode" = '--native' ]; then
	tests=$tests,PirateUnifiedNativeSmokeTests
	native_flag='-Dqortium.runPirateUnifiedNativeSmokeTests=true'
fi

command_text="mvn -DskipTests=false -Dqortium.runPirateUnifiedArtifactAcceptanceTests=true -Dqortium.pirateUnifiedArtifactPath=<artifact> -Dqortium.pirateUnifiedBundlePath=<bundle> -Dtest=$tests test"
set +e
(cd "$repository" && mvn -DskipTests=false \
	-Dqortium.runPirateUnifiedArtifactAcceptanceTests=true \
	-Dqortium.pirateUnifiedArtifactPath="$artifact" \
	-Dqortium.pirateUnifiedBundlePath="$bundle" \
	$native_flag \
	-Dsurefire.reportsDirectory="$work_directory/reports" \
	-Dtest="$tests" test) > "$receipt.log" 2>&1
maven_status=$?
set -e

result=PASS
if [ "$maven_status" -ne 0 ]; then
	result=FAIL
elif [ "$mode" = '--native' ]; then
	native_result=PASS
fi

sum_attribute() {
	attribute=$1
	awk -v attribute="$attribute" '
		/<testsuite / {
			needle = attribute "=\""
			start = index($0, needle)
			if (start > 0) {
				rest = substr($0, start + length(needle))
				end = index(rest, "\"")
				total += substr(rest, 1, end - 1)
			}
		}
		END { print total + 0 }
	' "$work_directory"/reports/TEST-*.xml
}

tests_run=0
failures=0
errors=0
skipped=0
reports_found=false
if [ -d "$work_directory/reports" ]; then
	for report in "$work_directory"/reports/TEST-*.xml; do
		if [ -f "$report" ]; then
			reports_found=true
			break
		fi
	done
fi
if [ "$reports_found" = true ]; then
	tests_run=$(sum_attribute tests)
	failures=$(sum_attribute failures)
	errors=$(sum_attribute errors)
	skipped=$(sum_attribute skipped)
fi

core_commit=$(cd "$repository" && git rev-parse HEAD)
tree_state=clean
if [ -n "$(cd "$repository" && git status --porcelain)" ]; then
	tree_state=dirty
fi
artifact_sha256=$(awk -F= '$1 == "artifact_sha256" { print $2 }' \
	"$script_directory/pirate-unified-artifact.properties")
manifest_sha256=UNAVAILABLE
if [ -f "$bundle/QORTIUM-MANIFEST.txt" ]; then
	if command -v sha256sum >/dev/null 2>&1; then
		manifest_sha256=$(sha256sum "$bundle/QORTIUM-MANIFEST.txt" | awk '{print $1}')
	elif command -v shasum >/dev/null 2>&1; then
		manifest_sha256=$(shasum -a 256 "$bundle/QORTIUM-MANIFEST.txt" | awk '{print $1}')
	fi
fi
timestamp=$(date -u '+%Y-%m-%dT%H:%M:%SZ')
host=$(uname -srm)
host_target=
case "$(uname -s):$(uname -m)" in
	Linux:x86_64) host_target='Linux x86_64' ;;
	Linux:aarch64) host_target='Linux aarch64' ;;
	Darwin:x86_64) host_target='macOS x86_64' ;;
	Darwin:arm64) host_target='macOS aarch64' ;;
	MINGW*:x86_64|MSYS*:x86_64) host_target='Windows x86_64' ;;
esac

temporary_receipt=$work_directory/receipt.md
# Backticks in the following printf formats are literal Markdown delimiters.
# shellcheck disable=SC2016
{
	printf '# Pirate Unified acceptance receipt\n\n'
	printf '%s `%s`\n' '- Timestamp:' "$timestamp"
	printf '%s `%s`\n' '- Result:' "$result"
	printf '%s `%s`\n' '- Core commit:' "$core_commit"
	printf '%s `%s`\n' '- Core tree:' "$tree_state"
	printf '%s `%s`\n' '- Host:' "$host"
	printf '%s `%s`\n' '- Pinned release artifact:' "$artifact"
	printf '%s `%s`\n' '- Bundle:' "$bundle"
	printf '%s `%s`\n' '- Pinned release artifact SHA-256:' "$artifact_sha256"
	printf '%s `%s`\n' '- Bundle manifest SHA-256:' "$manifest_sha256"
	printf '%s `%s`\n' '- Native smoke:' "$native_result"
	printf '%s `%s` run, `%s` failures, `%s` errors, `%s` skipped\n' '- Tests:' \
		"$tests_run" "$failures" "$errors" "$skipped"
	printf '%s `%s`\n' '- Command:' "$command_text"
	printf '%s `%s`\n\n' '- Full log:' "$receipt.log"
	printf '## Platform runtime matrix\n\n'
	printf '| Target | Result |\n|---|---|\n'
	for target in 'Linux x86_64' 'Linux aarch64' 'macOS x86_64' 'macOS aarch64' 'Windows x86_64'; do
		platform_result=NOT_RUN
		if [ "$target" = "$host_target" ] && [ "$native_result" = PASS ]; then
			platform_result=PASS
		fi
		printf '| %s | %s |\n' "$target" "$platform_result"
	done
	printf '\nFilename presence and bundle validation do not count as native runtime acceptance. '
	printf 'Replace a platform result only after an actual host JNI and packaged-Core run.\n'
	printf '\n## Counterexamples\n\n'
	if [ "$result" = PASS ]; then
		printf 'None observed in this run.\n'
	else
		printf 'Acceptance failed. Inspect the retained full log above before continuing.\n'
	fi
} > "$temporary_receipt"

mv "$temporary_receipt" "$receipt"
trap - 0 HUP INT TERM
rm -rf "$work_directory"
rmdir "$lock_directory"
if [ "$maven_status" -ne 0 ]; then
	printf 'Acceptance failed; receipt: %s, log: %s\n' "$receipt" "$receipt.log" >&2
	exit "$maven_status"
fi
printf 'Acceptance passed; receipt: %s, log: %s\n' "$receipt" "$receipt.log"
