#!/bin/sh
set -eu
umask 077

# Double-opt-in, wallet-free production probe. This loads only the pinned
# Pirate Unified library and performs read-only node admission against the
# configured mainnet endpoints. It never initializes a wallet.

usage() {
	printf '%s\n' "Usage: $0 <absolute-artifact.zip> <absolute-staged-bundle-directory> <absolute-new-receipt.md> --native" >&2
}

require_command() {
	command -v "$1" >/dev/null 2>&1 || {
		printf 'Required command not found: %s\n' "$1" >&2
		exit 1
	}
}

process_group_live() {
	[ -n "$1" ] || return 1
	ps -o stat= -g "$1" 2>/dev/null | awk '
		$1 !~ /^Z/ { live = 1 }
		END { exit live ? 0 : 1 }
	'
}

git_tree_status() {
	HOME=$original_home XDG_CONFIG_HOME=$original_xdg_config_home git status --porcelain
}

terminate_maven() {
	if [ -n "$maven_process_group" ] && process_group_live "$maven_process_group"; then
		/bin/kill -TERM -- "-$maven_process_group" 2>/dev/null || true
	elif [ -n "$maven_pid" ] && /bin/kill -0 "$maven_pid" 2>/dev/null; then
		# Cover the brief launcher interval before os.setsid() establishes the group.
		/bin/kill -TERM "$maven_pid" 2>/dev/null || true
	fi
	remaining=10
	while [ "$remaining" -gt 0 ] && { \
			{ [ -n "$maven_process_group" ] && process_group_live "$maven_process_group"; } \
			|| { [ -n "$maven_pid" ] && /bin/kill -0 "$maven_pid" 2>/dev/null; }; \
	}; do
		sleep 1
		remaining=$((remaining - 1))
	done
	if [ -n "$maven_process_group" ] && process_group_live "$maven_process_group"; then
		/bin/kill -KILL -- "-$maven_process_group" 2>/dev/null || true
	elif [ -n "$maven_pid" ] && /bin/kill -0 "$maven_pid" 2>/dev/null; then
		/bin/kill -KILL "$maven_pid" 2>/dev/null || true
	fi
	if [ -n "$maven_pid" ]; then
		wait "$maven_pid" 2>/dev/null || true
	fi
	if [ -n "$maven_process_group" ] && process_group_live "$maven_process_group"; then
		return 1
	fi
	if [ -n "$maven_pid" ] && /bin/kill -0 "$maven_pid" 2>/dev/null; then
		return 1
	fi
	maven_pid=
	maven_process_group=
}

cleanup() {
	cleanup_status=0
	terminate_maven || cleanup_status=1
	if [ -n "$report_suffix" ]; then
		rm -f "$repository"/target/surefire-reports/TEST-*-"$report_suffix".xml || cleanup_status=1
		rm -f "$repository"/target/surefire-reports/*-"$report_suffix".txt || cleanup_status=1
	fi
	if [ -n "$work_directory" ]; then
		case $work_directory in
			"$receipt_parent"/.pirate-production-native.*)
				rm -rf -- "$work_directory" || cleanup_status=1
				[ ! -e "$work_directory" ] || cleanup_status=1
				;;
			*) printf 'Refusing to remove unexpected work directory: %s\n' "$work_directory" >&2; cleanup_status=1 ;;
		esac
	fi
	if [ "$core_lock_owned" = true ]; then
		rmdir "$core_lock" 2>/dev/null || cleanup_status=1
		[ ! -e "$core_lock" ] || cleanup_status=1
	fi
	if [ "$receipt_published" = true ]; then
		rm -f -- "$receipt" || cleanup_status=1
		[ ! -e "$receipt" ] || cleanup_status=1
	fi
	if [ -n "$temporary_receipt" ]; then
		rm -f -- "$temporary_receipt" || cleanup_status=1
		[ ! -e "$temporary_receipt" ] || cleanup_status=1
	fi
	if [ "$receipt_lock_owned" = true ]; then
		rmdir "$receipt_lock" 2>/dev/null || cleanup_status=1
		[ ! -e "$receipt_lock" ] || cleanup_status=1
	fi
	[ "$cleanup_status" -eq 0 ] || printf '%s\n' 'Acceptance cleanup could not be proven complete' >&2
	return "$cleanup_status"
}

handle_signal() {
	status=$1
	trap - HUP INT TERM
	exit "$status"
}

report_attribute() {
	report=$1
	attribute=$2
	awk -v attribute="$attribute" '
		/<testsuite / {
			needle = attribute "=\""
			start = index($0, needle)
			if (start > 0) {
				rest = substr($0, start + length(needle))
				end = index(rest, "\"")
				print substr(rest, 1, end - 1)
				exit
			}
		}
	' "$report"
}

report_passed() {
	report=$1
	expected_tests=${2:-}
	[ -f "$report" ] || return 1
	report_tests=$(report_attribute "$report" tests)
	report_failures=$(report_attribute "$report" failures)
	report_errors=$(report_attribute "$report" errors)
	report_skipped=$(report_attribute "$report" skipped)
	[ -n "$report_tests" ] && [ "$report_tests" -gt 0 ] \
		&& [ "$report_failures" -eq 0 ] && [ "$report_errors" -eq 0 ] \
		&& [ "$report_skipped" -eq 0 ] \
		&& { [ -z "$expected_tests" ] || [ "$report_tests" -eq "$expected_tests" ]; }
}

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

[ "$#" -eq 4 ] || {
	usage
	exit 2
}

artifact=$1
bundle=$2
receipt=$3
mode=$4
[ "$mode" = '--native' ] || {
	usage
	exit 2
}
for value in "$artifact" "$bundle" "$receipt"; do
	case $value in
		/*) ;;
		*) printf 'Acceptance paths must be absolute: %s\n' "$value" >&2; exit 2 ;;
	esac
	case $value in
		*'`'*|*'"'*|*'\'*|*'
'*) printf '%s\n' 'Acceptance paths cannot contain a backtick, quote, backslash, or newline' >&2; exit 2 ;;
	esac
done
[ -f "$artifact" ] || {
	printf 'Artifact does not exist: %s\n' "$artifact" >&2
	exit 1
}
[ -d "$bundle" ] || {
	printf 'Bundle does not exist: %s\n' "$bundle" >&2
	exit 1
}

for required in awk basename cat cp date dirname find git grep ln mkdir mktemp mvn ps python3 rm rmdir sed sha256sum sleep timeout uname; do
	require_command "$required"
done
[ -x /bin/kill ] || {
	printf '%s\n' 'Production native interoperability requires /bin/kill' >&2
	exit 1
}
[ "$(uname -s):$(uname -m)" = 'Linux:x86_64' ] || {
	printf '%s\n' 'Production native interoperability is currently accepted only on Linux x86_64' >&2
	exit 1
}

script_directory=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
repository=$(CDPATH='' cd -- "$script_directory/.." && pwd)
original_home=${HOME:-}
original_xdg_config_home=${XDG_CONFIG_HOME:-}
case $original_home in
	/*) ;;
	*) printf '%s\n' 'Acceptance requires an absolute HOME' >&2; exit 1 ;;
esac
receipt_parent=$(dirname -- "$receipt")
receipt_name=$(basename -- "$receipt")
mkdir -p -- "$receipt_parent"
receipt_parent=$(CDPATH='' cd -- "$receipt_parent" && pwd -P)
receipt=$receipt_parent/$receipt_name
[ ! -e "$receipt" ] || {
	printf 'Refusing to overwrite receipt: %s\n' "$receipt" >&2
	exit 1
}

receipt_lock=$receipt.lock
receipt_lock_owned=false
if mkdir "$receipt_lock" 2>/dev/null; then
	receipt_lock_owned=true
else
	printf 'Another acceptance run owns this receipt: %s\n' "$receipt" >&2
	exit 1
fi

cd "$repository"
if ! initial_tree_status=$(git_tree_status); then
	rmdir "$receipt_lock"
	printf '%s\n' 'Could not verify the initial Core tree state' >&2
	exit 1
fi
[ -z "$initial_tree_status" ] || {
	rmdir "$receipt_lock"
	printf '%s\n' 'Production native interoperability requires a clean Core tree' >&2
	exit 1
}

mkdir -p "$repository/target"
core_lock=$repository/target/.pirate-production-native-interoperability.lock
core_lock_owned=false
if mkdir "$core_lock" 2>/dev/null; then
	core_lock_owned=true
else
	rmdir "$receipt_lock"
	printf '%s\n' 'Another production native acceptance is using this Core clone' >&2
	exit 1
fi

work_directory=
maven_pid=
maven_process_group=
report_suffix=
temporary_receipt=
receipt_published=false
trap cleanup 0
trap 'handle_signal 129' HUP
trap 'handle_signal 130' INT
trap 'handle_signal 143' TERM

work_directory=$(mktemp -d "$receipt_parent/.pirate-production-native.XXXXXX")
report_suffix=$(basename "$work_directory" | sed 's/[^A-Za-z0-9]/-/g')
raw_log=$work_directory/maven.log
maven_repository=$original_home/.m2/repository
[ -d "$maven_repository" ] || {
	printf 'Offline Maven repository does not exist: %s\n' "$maven_repository" >&2
	exit 1
}
mkdir "$work_directory/home" "$work_directory/xdg-data" \
	"$work_directory/xdg-config" "$work_directory/xdg-cache" \
	"$work_directory/native-storage" "$work_directory/block-cache"
HOME=$work_directory/home
XDG_DATA_HOME=$work_directory/xdg-data
XDG_CONFIG_HOME=$work_directory/xdg-config
XDG_CACHE_HOME=$work_directory/xdg-cache
PIRATE_WALLET_DB_DIR=$work_directory/native-storage
PIRATE_WALLET_DB_PATH=$work_directory/native-storage/wallet.db
PIRATE_BLOCK_CACHE_DIR=$work_directory/block-cache
PIRATE_DEBUG_LOG_PATH=$work_directory/native-debug.log
export HOME XDG_DATA_HOME XDG_CONFIG_HOME XDG_CACHE_HOME
export PIRATE_WALLET_DB_DIR PIRATE_WALLET_DB_PATH PIRATE_BLOCK_CACHE_DIR PIRATE_DEBUG_LOG_PATH
initial_commit=$(git rev-parse HEAD)
started=$(date -u '+%Y-%m-%dT%H:%M:%SZ')

tests='ZcashFamilyLightClientTrustTests,PirateUnifiedWalletStorageTests,PirateProductionLightwalletTests,PirateProductionNativeInteroperabilityTests'
maven_timeout_seconds=600
set -- timeout --foreground --signal=TERM --kill-after=15s "$maven_timeout_seconds" \
	mvn --offline -Dmaven.repo.local="$maven_repository" -Dstyle.color=never -DskipJUnitTests=false \
	-Dsurefire.reportNameSuffix="$report_suffix" \
	-Dqortium.runPirateProductionLightwalletTests=true \
	-Dqortium.runPirateProductionNativeInteroperabilityTests=true \
	-Dqortium.pirateUnifiedArtifactPath="$artifact" \
	-Dqortium.pirateUnifiedBundlePath="$bundle" \
	-Dtest="$tests" test
set +e
(cd "$repository" && exec python3 -c \
	'import os, sys; os.setsid(); os.execvp(sys.argv[1], sys.argv[1:])' "$@") >"$raw_log" 2>&1 &
maven_pid=$!
maven_process_group=$maven_pid
wait "$maven_pid"
maven_status=$?
if ! terminate_maven; then
	set -e
	printf '%s\n' 'Maven/Surefire process-group termination could not be proven' >&2
	exit 1
fi
set -e
case $maven_status in
	124|137)
		printf 'Maven/Surefire reached the %s-second limit or required its bounded KILL fallback\n' \
			"$maven_timeout_seconds" >&2
		exit 1
		;;
esac

secret_scan=PASS
for scan_file in "$raw_log" \
		"$PIRATE_DEBUG_LOG_PATH" \
		"$repository"/target/surefire-reports/TEST-*-"$report_suffix".xml \
		"$repository"/target/surefire-reports/*-"$report_suffix".txt; do
	[ -f "$scan_file" ] || continue
	set +e
	grep -E '"(seedPhrase|private_key|seed|address)"[[:space:]]*:|zs1[[:alnum:]]{60,}' \
		"$scan_file" >/dev/null 2>&1
	secret_scan_status=$?
	set -e
	case $secret_scan_status in
		0) secret_scan=FAIL; break ;;
		1) ;;
		*) secret_scan=ERROR ;;
	esac
done

mkdir "$work_directory/reports"
for report in "$repository"/target/surefire-reports/TEST-*-"$report_suffix".xml; do
	if [ -f "$report" ]; then
		cp "$report" "$work_directory/reports/"
		rm -f "$report"
	fi
done
rm -f "$repository"/target/surefire-reports/*-"$report_suffix".txt

required_reports='org.qortium.crosschain.ZcashFamilyLightClientTrustTests org.qortium.crosschain.PirateUnifiedWalletStorageTests org.qortium.crosschain.PirateProductionLightwalletTests org.qortium.controller.PirateProductionNativeInteroperabilityTests'
reports_complete=true
for required_class in $required_reports; do
	required_report=$work_directory/reports/TEST-$required_class-$report_suffix.xml
	case $required_class in
		org.qortium.crosschain.PirateProductionLightwalletTests|org.qortium.controller.PirateProductionNativeInteroperabilityTests)
			report_passed "$required_report" 1 || reports_complete=false
			;;
		*) report_passed "$required_report" || reports_complete=false ;;
	esac
done

tests_run=$(sum_attribute tests)
failures=$(sum_attribute failures)
errors=$(sum_attribute errors)
skipped=$(sum_attribute skipped)

if find "$work_directory/native-storage" -mindepth 1 -print -quit | grep -q .; then
	printf '%s\n' 'Wallet-free native admission wrote unexpected wallet storage' >&2
	exit 1
fi

endpoint_evidence=$work_directory/endpoints.txt
grep '^PIRATE_PRODUCTION_NATIVE_ENDPOINT ' "$raw_log" >"$endpoint_evidence" || true
[ -s "$endpoint_evidence" ] || {
	printf '%s\n' 'No native endpoint evidence was emitted' >&2
	exit 1
}
if ! awk '
	/^PIRATE_PRODUCTION_NATIVE_ENDPOINT host=[A-Za-z0-9.-]+ status=READY java_height=[0-9]+ native_height=[0-9]+$/ { next }
	/^PIRATE_PRODUCTION_NATIVE_ENDPOINT host=[A-Za-z0-9.-]+ status=UNAVAILABLE$/ { next }
	{ exit 1 }
' "$endpoint_evidence"; then
	printf '%s\n' 'Native endpoint evidence contained an unexpected field or character' >&2
	exit 1
fi

if ! summary=$(awk '
	/^PIRATE_PRODUCTION_NATIVE_PASS attempts=[0-9]+ ready=[0-9]+ cluster=[0-9]+$/ {
		count++
		for (i = 2; i <= NF; i++) {
			split($i, field, "=")
			value[field[1]] = field[2]
		}
	}
	END {
		if (count != 1)
			exit 1
		printf "%s %s %s\n", value["attempts"], value["ready"], value["cluster"]
	}
' "$raw_log"); then
	printf '%s\n' 'Missing or duplicate native pass summary' >&2
	exit 1
fi
set -- $summary
attempt_count=$1
ready_count=$2
compatible_cluster_size=$3
evidence_count=$(awk 'END { print NR + 0 }' "$endpoint_evidence")
ready_evidence_count=$(grep -c ' status=READY ' "$endpoint_evidence" || true)
[ "$attempt_count" -eq "$evidence_count" ] && [ "$ready_count" -eq "$ready_evidence_count" ] \
		&& [ "$ready_count" -ge 2 ] && [ "$compatible_cluster_size" -ge 2 ] || {
	printf '%s\n' 'Native endpoint counts did not satisfy the bounded configured-pass contract' >&2
	exit 1
}

if [ "$maven_status" -ne 0 ] || [ "$reports_complete" != true ] \
		|| [ "$tests_run" -le 0 ] || [ "$failures" -ne 0 ] || [ "$errors" -ne 0 ] \
		|| [ "$skipped" -ne 0 ] || [ "$secret_scan" != PASS ]; then
	printf '%s\n' 'Production native interoperability acceptance failed' >&2
	exit 1
fi

final_commit=$(git rev-parse HEAD)
[ "$final_commit" = "$initial_commit" ] || {
	printf '%s\n' 'Core HEAD changed during production native acceptance' >&2
	exit 1
}
if ! final_tree_status=$(git_tree_status); then
	printf '%s\n' 'Could not verify the final Core tree state' >&2
	exit 1
fi
[ -z "$final_tree_status" ] || {
	printf '%s\n' 'Core tree changed during production native acceptance' >&2
	exit 1
}

finished=$(date -u '+%Y-%m-%dT%H:%M:%SZ')
artifact_sha256=$(sha256sum "$artifact" | awk '{print $1}')
manifest_sha256=$(sha256sum "$bundle/QORTIUM-MANIFEST.txt" | awk '{print $1}')
temporary_receipt=$receipt_lock/receipt.md
# Backticks in the following format strings are literal Markdown delimiters.
# shellcheck disable=SC2016
{
	printf '%s\n\n' '# Pirate production native interoperability acceptance receipt'
	printf '%s `%s`\n' '- Result:' 'PASS'
	printf '%s `%s`\n' '- Core commit:' "$final_commit"
	printf '%s `%s`\n' '- Core tree:' 'clean and unchanged across the run'
	printf '%s `%s`\n' '- Host:' 'Linux x86_64'
	printf '%s `%s`\n' '- Started UTC:' "$started"
	printf '%s `%s`\n' '- Finished UTC:' "$finished"
	printf '%s `%s`\n' '- Pinned artifact SHA-256:' "$artifact_sha256"
	printf '%s `%s`\n' '- Bundle manifest SHA-256:' "$manifest_sha256"
	printf '%s `%s`\n' '- Configured endpoint attempts:' "$attempt_count"
	printf '%s `%s`\n' '- Native-ready configured endpoints:' "$ready_count"
	printf '%s `%s`\n' '- Largest compatible native cluster:' "$compatible_cluster_size"
	printf '%s `%s`\n\n' '- Tests:' "$tests_run run; 0 failures; 0 errors; 0 skipped"
	printf '%s\n\n' '## Endpoint evidence'
	printf '%s\n' '```text'
	cat "$endpoint_evidence"
	printf '%s\n\n' '```'
	printf '%s\n\n' '## Boundary'
	printf '%s\n' 'This double-opt-in, wallet-free gate loads the pinned Pirate Unified JNI'
	printf '%s\n' 'library and makes one bounded pass over every endpoint hardcoded in Qortium'
	printf '%s\n' 'Core. Every READY endpoint passed the existing Java TLS/chain/tip/history admission'
	printf '%s\n' 'and the native Rust test_node chain, TLS, direct-transport, and height checks.'
	printf '%s\n' 'At least two configured endpoints reported compatible native heights. The same'
	printf '%s\n' 'run also passed the deterministic endpoint-retention and bounded-fallback tests.'
	printf '%s\n' 'It does not prove independent infrastructure, initialize or persist a wallet,'
	printf '%s\n' 'or create or write wallet storage. All native storage, cache, and log paths'
	printf '%s\n' 'were quarantined below the deleted disposable run directory. It does not'
	printf '%s\n' 'derive an address, synchronize chain history, query balances or transactions,'
	printf '%s\n' 'mutate a native wallet endpoint, sign or broadcast data, move funds, publish to'
	printf '%s\n' 'QDN, deploy Core, enable Unified mode by default, or change Home.'
	printf '%s\n' 'Raw native/Maven output and unique Surefire reports were secret-scanned and'
	printf '%s\n' 'deleted before this allowlisted receipt was published atomically.'
} >"$temporary_receipt"

rm -f -- "$raw_log"
rm -rf -- "$work_directory/reports" "$work_directory/native-storage"
for raw_evidence in "$raw_log" "$work_directory/reports" "$work_directory/native-storage" \
		"$repository"/target/surefire-reports/TEST-*-"$report_suffix".xml \
		"$repository"/target/surefire-reports/*-"$report_suffix".txt; do
	[ ! -e "$raw_evidence" ] || {
		printf 'Raw production acceptance evidence was not deleted: %s\n' "$raw_evidence" >&2
		exit 1
	}
done

case $work_directory in
	"$receipt_parent"/.pirate-production-native.*)
		rm -rf -- "$work_directory"
		[ ! -e "$work_directory" ] || {
			printf 'Temporary production acceptance directory was not deleted: %s\n' "$work_directory" >&2
			exit 1
		}
		work_directory=
		;;
	*) printf 'Refusing to remove unexpected work directory: %s\n' "$work_directory" >&2; exit 1 ;;
esac
rmdir "$core_lock"
[ ! -e "$core_lock" ] || {
	printf '%s\n' 'Core acceptance lock was not released' >&2
	exit 1
}
core_lock_owned=false

[ ! -e "$receipt" ] || {
	printf 'Refusing to overwrite receipt created during the run: %s\n' "$receipt" >&2
	exit 1
}
if ! ln "$temporary_receipt" "$receipt"; then
	printf 'Could not atomically publish non-overwriting receipt: %s\n' "$receipt" >&2
	exit 1
fi
receipt_published=true
rm -f -- "$temporary_receipt"
temporary_receipt=
rmdir "$receipt_lock"
receipt_lock_owned=false
receipt_published=false

trap - 0 HUP INT TERM
printf 'PASS receipt=%s attempts=%s native_ready=%s compatible_cluster=%s commit=%s\n' \
	"$receipt" "$attempt_count" "$ready_count" "$compatible_cluster_size" "$final_commit"
