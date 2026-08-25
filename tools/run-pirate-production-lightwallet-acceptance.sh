#!/bin/sh

set -eu
umask 077

usage() {
	printf '%s\n' "Usage: $0 <absolute-new-receipt.md>" >&2
}

require_command() {
	command -v "$1" >/dev/null 2>&1 || {
		printf 'Required command not found: %s\n' "$1" >&2
		exit 1
	}
}

terminate_maven() {
	if [ -n "$maven_process_group" ] && kill -0 -- "-$maven_process_group" 2>/dev/null; then
		kill -TERM -- "-$maven_process_group" 2>/dev/null || true
	fi
	remaining=10
	while [ -n "$maven_process_group" ] \
			&& kill -0 -- "-$maven_process_group" 2>/dev/null \
			&& [ "$remaining" -gt 0 ]; do
		sleep 1
		remaining=$((remaining - 1))
	done
	if [ -n "$maven_process_group" ] && kill -0 -- "-$maven_process_group" 2>/dev/null; then
		kill -KILL -- "-$maven_process_group" 2>/dev/null || true
	fi
	if [ -n "$maven_pid" ]; then
		wait "$maven_pid" 2>/dev/null || true
	fi
	if [ -n "$maven_process_group" ] && kill -0 -- "-$maven_process_group" 2>/dev/null; then
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
			"$receipt_parent"/.pirate-production-lightwallet.*)
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

[ "$#" -eq 1 ] || {
	usage
	exit 2
}

receipt=$1
case $receipt in
	/*) ;;
	*) printf '%s\n' 'Receipt path must be absolute' >&2; exit 2 ;;
esac
case $receipt in
	*'`'*|*'
'*) printf '%s\n' 'Receipt path cannot contain a backtick or newline' >&2; exit 2 ;;
esac

for required in awk git grep ln mvn sed; do
	require_command "$required"
done

script_directory=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
repository=$(CDPATH='' cd -- "$script_directory/.." && pwd)
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
[ ! -e "$receipt" ] || {
	rmdir "$receipt_lock"
	printf 'Refusing to overwrite receipt: %s\n' "$receipt" >&2
	exit 1
}

cd "$repository"
[ -z "$(git status --porcelain)" ] || {
	rmdir "$receipt_lock"
	printf '%s\n' 'Production lightwallet acceptance requires a clean Core tree' >&2
	exit 1
}

mkdir -p "$repository/target"
core_lock=$repository/target/.pirate-production-lightwallet-acceptance.lock
core_lock_owned=false
if mkdir "$core_lock" 2>/dev/null; then
	core_lock_owned=true
else
	rmdir "$receipt_lock"
	printf '%s\n' 'Another production lightwallet acceptance is using this Core clone' >&2
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

work_directory=$(mktemp -d "$receipt_parent/.pirate-production-lightwallet.XXXXXX")
report_suffix=$(basename "$work_directory" | sed 's/[^A-Za-z0-9]/-/g')
raw_log=$work_directory/maven.log
initial_commit=$(git rev-parse HEAD)
started=$(date -u '+%Y-%m-%dT%H:%M:%SZ')

if command -v setsid >/dev/null 2>&1; then
	session_launcher=setsid
elif command -v python3 >/dev/null 2>&1; then
	session_launcher=python3
else
	printf '%s\n' 'Acceptance requires setsid or python3 with os.setsid support' >&2
	exit 1
fi

set -- mvn --offline -Dstyle.color=never -DskipJUnitTests=false \
	-Dsurefire.reportNameSuffix="$report_suffix" \
	-Dtest=ZcashFamilyLightClientTrustTests,PirateProductionLightwalletTests \
	-Dqortium.runPirateProductionLightwalletTests=true test
set +e
if [ "$session_launcher" = setsid ]; then
	(cd "$repository" && exec setsid "$@") >"$raw_log" 2>&1 &
else
	(cd "$repository" && exec python3 -c \
		'import os, sys; os.setsid(); os.execvp(sys.argv[1], sys.argv[1:])' "$@") >"$raw_log" 2>&1 &
fi
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

if [ "$maven_status" -ne 0 ]; then
	printf '%s\n' 'Production lightwallet acceptance failed' >&2
	grep -E 'PIRATE_PRODUCTION_ENDPOINT|Tests run:|BUILD ' "$raw_log" | tail -n 30 >&2 || true
	exit 1
fi

trust_report=$repository/target/surefire-reports/TEST-org.qortium.crosschain.ZcashFamilyLightClientTrustTests-$report_suffix.xml
production_report=$repository/target/surefire-reports/TEST-org.qortium.crosschain.PirateProductionLightwalletTests-$report_suffix.xml
for report in "$trust_report" "$production_report"; do
	[ -f "$report" ] || {
		printf 'Missing unique Surefire report: %s\n' "$report" >&2
		exit 1
	}
	testsuite=$(grep -m 1 '<testsuite ' "$report")
	printf '%s\n' "$testsuite" | grep -E 'errors="0"' >/dev/null
	printf '%s\n' "$testsuite" | grep -E 'failures="0"' >/dev/null
	printf '%s\n' "$testsuite" | grep -E 'skipped="0"' >/dev/null
done
printf '%s\n' "$(grep -m 1 '<testsuite ' "$trust_report")" | grep -E 'tests="9"' >/dev/null
printf '%s\n' "$(grep -m 1 '<testsuite ' "$production_report")" | grep -E 'tests="1"' >/dev/null
grep -F 'BUILD SUCCESS' "$raw_log" >/dev/null

endpoint_evidence=$work_directory/endpoints.txt
grep '^PIRATE_PRODUCTION_ENDPOINT ' "$raw_log" >"$endpoint_evidence"
[ -s "$endpoint_evidence" ] || {
	printf '%s\n' 'No endpoint evidence was emitted' >&2
	exit 1
}
if ! awk '
	/^PIRATE_PRODUCTION_ENDPOINT host=[A-Za-z0-9.-]+ status=READY height=[0-9]+$/ { next }
	/^PIRATE_PRODUCTION_ENDPOINT host=[A-Za-z0-9.-]+ status=UNAVAILABLE$/ { next }
	{ exit 1 }
' "$endpoint_evidence"; then
	printf '%s\n' 'Endpoint evidence contained an unexpected field or character' >&2
	exit 1
fi
ready_count=$(grep -c ' status=READY ' "$endpoint_evidence" || true)
[ "$ready_count" -ge 2 ] || {
	printf '%s\n' 'Fewer than two configured endpoints were fully validated' >&2
	exit 1
}
if ! compatible_cluster_size=$(awk '
	/^PIRATE_PRODUCTION_CLUSTER size=[0-9]+$/ {
		count++
		sub(/^PIRATE_PRODUCTION_CLUSTER size=/, "")
		size = $0
	}
	END {
		if (count != 1)
			exit 1
		print size
	}
' "$raw_log"); then
	printf '%s\n' 'Missing or duplicate compatible-cluster evidence' >&2
	exit 1
fi
[ "$compatible_cluster_size" -ge 2 ] || {
	printf '%s\n' 'Fewer than two fully validated endpoints formed a compatible cluster' >&2
	exit 1
}

final_commit=$(git rev-parse HEAD)
[ "$final_commit" = "$initial_commit" ] || {
	printf '%s\n' 'Core HEAD changed during production acceptance' >&2
	exit 1
}
[ -z "$(git status --porcelain)" ] || {
	printf '%s\n' 'Core tree changed during production acceptance' >&2
	exit 1
}

finished=$(date -u '+%Y-%m-%dT%H:%M:%SZ')
temporary_receipt=$receipt_lock/receipt.md
# Backticks in the following format strings are literal Markdown delimiters.
# shellcheck disable=SC2016
{
	printf '%s\n\n' '# Pirate production lightwallet acceptance receipt'
	printf '%s `%s`\n' '- Result:' 'PASS'
	printf '%s `%s`\n' '- Core commit:' "$final_commit"
	printf '%s `%s`\n' '- Core tree:' 'clean and unchanged across the run'
	printf '%s `%s`\n' '- Started UTC:' "$started"
	printf '%s `%s`\n' '- Finished UTC:' "$finished"
	printf '%s `%s`\n' '- Fully validated configured endpoints:' "$ready_count"
	printf '%s `%s`\n' '- Largest compatible endpoint cluster:' "$compatible_cluster_size"
	printf '%s `%s`\n\n' '- Tests:' '10 run; 0 failures; 0 errors; 0 skipped'
	printf '%s\n\n' '## Endpoint evidence'
	printf '%s\n' '```text'
	cat "$endpoint_evidence"
	printf '%s\n\n' '```'
	printf '%s\n\n' '## Boundary'
	printf '%s\n' 'This read-only gate checks each distinct configured Pirate mainnet endpoint'
	printf '%s\n' "through Qortium Core's Java lightwallet client. Every READY endpoint passed"
	printf '%s\n' 'TLS and chain-name admission, returned compatible positive tip heights from'
	printf '%s\n' 'both GetLightdInfo and GetLatestBlock, and served two nonempty, hash-linked'
	printf '%s\n' 'compact blocks. At least two configured endpoints formed a compatible cluster.'
	printf '%s\n' 'It does not prove that the endpoints use independent infrastructure. It does'
	printf '%s\n' 'not initialize a wallet, derive an address, query balances or transactions,'
	printf '%s\n' 'test the Pirate-native gRPC service, prove native endpoint cutover or'
	printf '%s\n' 'automatic failover, sign or broadcast data, move funds, publish to QDN,'
	printf '%s\n\n' 'deploy Core, enable Unified mode by default, or change Home.'
	printf '%s\n' 'Raw Maven output and unique Surefire reports were held in private temporary'
	printf '%s\n' 'storage and deleted before this allowlisted receipt was published atomically.'
} >"$temporary_receipt"

rm -f -- "$raw_log" "$trust_report" "$production_report" \
	"$repository/target/surefire-reports/org.qortium.crosschain.ZcashFamilyLightClientTrustTests-$report_suffix.txt" \
	"$repository/target/surefire-reports/org.qortium.crosschain.PirateProductionLightwalletTests-$report_suffix.txt"
for raw_evidence in "$raw_log" "$trust_report" "$production_report" \
		"$repository/target/surefire-reports/org.qortium.crosschain.ZcashFamilyLightClientTrustTests-$report_suffix.txt" \
		"$repository/target/surefire-reports/org.qortium.crosschain.PirateProductionLightwalletTests-$report_suffix.txt"; do
	[ ! -e "$raw_evidence" ] || {
		printf 'Raw production acceptance evidence was not deleted: %s\n' "$raw_evidence" >&2
		exit 1
	}
done

case $work_directory in
	"$receipt_parent"/.pirate-production-lightwallet.*)
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
	printf 'Core acceptance lock was not released: %s\n' "$core_lock" >&2
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
[ ! -e "$temporary_receipt" ] || {
	printf 'Staged receipt was not deleted: %s\n' "$temporary_receipt" >&2
	exit 1
}
temporary_receipt=
rmdir "$receipt_lock"
[ ! -e "$receipt_lock" ] || {
	printf 'Receipt lock was not released: %s\n' "$receipt_lock" >&2
	exit 1
}
receipt_lock_owned=false
receipt_published=false

trap - 0 HUP INT TERM
printf 'PASS receipt=%s ready_endpoints=%s compatible_cluster=%s commit=%s\n' \
	"$receipt" "$ready_count" "$compatible_cluster_size" "$final_commit"
