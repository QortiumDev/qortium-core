#!/bin/sh
set -eu

# Runs the unfunded, loopback-only verified-recovery acceptance (end-to-end import +
# rescan + counterexamples + reopen, and the Core recovery driver against the real
# native library) in one isolated JVM/native process, then publishes a receipt.

usage() {
	printf '%s\n' "Usage: $0 <absolute-pinned-artifact.zip> <absolute-staged-bundle-directory> <new-receipt.md>" >&2
}

require_absolute() {
	label=$1
	value=$2
	case $value in
		/*) ;;
		*) printf '%s path must be absolute: %s\n' "$label" "$value" >&2; exit 2 ;;
	esac
}

require_command() {
	command -v "$1" >/dev/null 2>&1 || {
		printf 'Required command not found: %s\n' "$1" >&2
		exit 1
	}
}

[ "$#" -eq 3 ] || {
	usage
	exit 2
}

artifact=$1
bundle=$2
receipt=$3
require_absolute artifact "$artifact"
require_absolute bundle "$bundle"
require_absolute receipt "$receipt"
require_command git
require_command grep
require_command mvn
require_command sha256sum
require_command uname

host_os=$(uname -s)
host_arch=$(uname -m)
if [ "$host_os" != Linux ] || [ "$host_arch" != x86_64 ]; then
	printf 'Recovery acceptance currently supports only Linux x86_64, not %s %s\n' \
		"$host_os" "$host_arch" >&2
	exit 1
fi

if [ ! -f "$artifact" ] || [ -L "$artifact" ]; then
	printf '%s\n' 'Pinned artifact must be a regular non-symlink file' >&2
	exit 1
fi
if [ ! -d "$bundle" ] || [ -L "$bundle" ]; then
	printf '%s\n' 'Staged bundle must be a non-symlink directory' >&2
	exit 1
fi
[ ! -e "$receipt" ] || {
	printf 'Receipt already exists: %s\n' "$receipt" >&2
	exit 1
}

script_dir=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
repo_root=$(CDPATH='' cd -- "$script_dir/.." && pwd)
cd "$repo_root"
[ -z "$(git status --porcelain)" ] || {
	printf '%s\n' 'Recovery acceptance requires a clean Core tree' >&2
	exit 1
}

initial_home=${HOME:?HOME is required to resolve the existing Maven repository}
maven_repo=${MAVEN_REPO_LOCAL:-$initial_home/.m2/repository}
[ -d "$maven_repo" ] || {
	printf 'Maven repository does not exist: %s\n' "$maven_repo" >&2
	exit 1
}

umask 077
runtime=$(mktemp -d "${TMPDIR:-/tmp}/qortium-pirate-recovery.XXXXXX")
run_id=${runtime##*.}
report_suffix=pirate-recovery-$run_id
lock=$repo_root/target/.pirate-recovery-acceptance.lock
lock_owned=false
cleanup() {
	rm -f "$repo_root"/target/surefire-reports/TEST-*-"$report_suffix".xml \
		"$repo_root"/target/surefire-reports/*-"$report_suffix".txt
	case $runtime in
		"${TMPDIR:-/tmp}"/qortium-pirate-recovery.*) rm -rf -- "$runtime" ;;
	esac
	if [ "$lock_owned" = true ]; then
		rmdir "$lock" 2>/dev/null || true
	fi
}
trap cleanup 0 HUP INT TERM
mkdir -p "$repo_root/target"
if mkdir "$lock" 2>/dev/null; then
	lock_owned=true
else
	printf '%s\n' 'Another recovery acceptance appears to be using this Core clone' >&2
	exit 1
fi

run_root=$runtime/run
log=$runtime/run.log
mkdir -m 700 "$run_root"
mkdir -m 700 "$run_root/home" "$run_root/data" "$run_root/cache" \
	"$run_root/config" "$run_root/tmp" "$run_root/surefire"
acceptance_storage=$run_root/recovery-storage
driver_storage=$run_root/driver-storage

maven_status=0
if env -i PATH="$PATH" HOME="$run_root/home" \
	XDG_DATA_HOME="$run_root/data" \
	XDG_CACHE_HOME="$run_root/cache" \
	XDG_CONFIG_HOME="$run_root/config" \
	PIRATE_BLOCK_CACHE_DIR="$run_root/block-cache" \
	PIRATE_DEBUG_LOG_PATH="$run_root/pirate-debug.log" \
	mvn -o -Dmaven.repo.local="$maven_repo" \
		-Duser.home="$run_root/home" \
		-Djava.io.tmpdir="$run_root/tmp" \
		-Dsurefire.reportNameSuffix="$report_suffix" \
		-DskipJUnitTests=false \
		-Dtest='PirateUnifiedRecoveryAcceptanceTests,PirateChainRecoveryDriverNativeTests' \
		-Dqortium.runPirateUnifiedRecoveryAcceptanceTests=true \
		-Dqortium.runPirateRecoveryDriverNativeTests=true \
		-Dqortium.pirateUnifiedArtifactPath="$artifact" \
		-Dqortium.pirateUnifiedBundlePath="$bundle" \
		-Dqortium.pirateUnifiedRecoveryStoragePath="$acceptance_storage" \
		-Dqortium.pirateRecoveryDriverStoragePath="$driver_storage" \
		test > "$log" 2>&1; then
	:
else
	maven_status=$?
fi

for report in "$repo_root"/target/surefire-reports/TEST-*-"$report_suffix".xml \
		"$repo_root"/target/surefire-reports/*-"$report_suffix".txt; do
	if [ -f "$report" ]; then
		cp "$report" "$run_root/surefire/"
		rm -f "$report"
	fi
done

if [ "$maven_status" -ne 0 ]; then
	printf '%s\n' 'Recovery acceptance JVM failed; raw evidence will be deleted' >&2
	grep -E 'Tests run: [0-9]+, Failures:|Recovered note missing|Counterexample import' \
		"$log" | tail -n 6 >&2 || true
	exit 1
fi

grep -F 'Tests run: 2, Failures: 0, Errors: 0, Skipped: 0' "$log" >/dev/null
grep -F 'BUILD SUCCESS' "$log" >/dev/null
for suite in org.qortium.controller.PirateUnifiedRecoveryAcceptanceTests \
		org.qortium.crosschain.PirateChainRecoveryDriverNativeTests; do
	report="$run_root/surefire/TEST-$suite-$report_suffix.xml"
	[ -f "$report" ] || {
		printf 'Missing uniquely suffixed Surefire XML report for %s\n' "$suite" >&2
		exit 1
	}
	testsuite=$(grep -m 1 '<testsuite ' "$report")
	printf '%s\n' "$testsuite" | grep -F 'tests="1"' >/dev/null
	printf '%s\n' "$testsuite" | grep -F 'errors="0"' >/dev/null
	printf '%s\n' "$testsuite" | grep -F 'skipped="0"' >/dev/null
	printf '%s\n' "$testsuite" | grep -F 'failures="0"' >/dev/null
done
[ -d "$acceptance_storage" ] || {
	printf '%s\n' 'Recovery acceptance did not create its fresh wallet storage' >&2
	exit 1
}
[ -d "$driver_storage" ] || {
	printf '%s\n' 'Driver acceptance did not create its fresh wallet storage' >&2
	exit 1
}

secret_pattern='seedPhrase|private_key|privateKey|spending_key|spendingKey|viewing_key|viewingKey|mnemonic|ARRRWalletEncryption|"seed"[[:space:]]*:|"entropy"[[:space:]]*:'
address_pattern='(zs1|ztestsapling1|zregtestsapling1)[[:alnum:]]{60,}'
if grep -E -i "$secret_pattern" "$log" >/dev/null 2>&1 \
		|| grep -E "$address_pattern" "$log" >/dev/null 2>&1 \
		|| grep -R -E -i "$secret_pattern|$address_pattern" "$run_root/surefire" >/dev/null 2>&1; then
	printf '%s\n' 'Recovery raw evidence contained secret-shaped wallet material' >&2
	exit 1
fi

# Delete every wallet-capable input before publishing a receipt that claims deletion.
rm -rf -- "$run_root"
rm -f -- "$log"
for raw_evidence in "$run_root" "$log"; do
	[ ! -e "$raw_evidence" ] || {
		printf 'Recovery raw evidence was not deleted: %s\n' "$raw_evidence" >&2
		exit 1
	}
done

commit=$(git rev-parse HEAD)
artifact_hash_line=$(sha256sum "$artifact")
artifact_sha256=${artifact_hash_line%% *}
library=$bundle/librust-linux-x86_64.so
if [ ! -f "$library" ] || [ -L "$library" ]; then
	printf '%s\n' 'Linux x86_64 staged library is missing or is a symlink' >&2
	exit 1
fi
library_hash_line=$(sha256sum "$library")
library_sha256=${library_hash_line%% *}
fixture_hash_line=$(sha256sum src/test/java/org/qortium/controller/PirateUnifiedLoopbackLightwalletd.java)
fixture_sha256=${fixture_hash_line%% *}
timestamp=$(date -u '+%Y-%m-%dT%H:%M:%SZ')

mkdir -p "$(dirname -- "$receipt")"
receipt_tmp=$runtime/receipt.md
{
	printf '%s\n\n' '# Pirate Unified verified-recovery acceptance'
	printf '%s\n\n' 'Result: **PASS**'
	printf -- "- Timestamp: \`%s\`\n" "$timestamp"
	printf -- "- Core commit: \`%s\` (clean tree)\n" "$commit"
	printf -- "- Artifact SHA-256: \`%s\`\n" "$artifact_sha256"
	printf -- "- Linux x86_64 JNI SHA-256: \`%s\`\n" "$library_sha256"
	printf -- "- Loopback fixture source SHA-256: \`%s\`\n" "$fixture_sha256"
	printf '%s\n' "- Executed platform: \`Linux x86_64\`"
	printf '%s\n' "- Process-local HOME/XDG/temp/storage roots: fresh, single isolated JVM/native process"
	printf '%s\n' "- End-to-end recovery: verified import of a foreign spending key + dedicated rescan recovered \`123456789\` arrrtoshis at height \`152856\`: \`PASS\`"
	printf '%s\n' "- Fail-closed counterexamples (wrong address, mixed-case key, birthday above tip): \`PASS\`, each rejected with the wallet's key-group set unchanged"
	printf '%s\n' "- Legacy 32-bit address-index metadata retry: \`PASS\`, idempotent with the verified key group and full 88-bit ownership cursor"
	printf '%s\n' "- Bech32 uppercase exact-retry idempotency and post-completion null-floor retry: \`PASS\`"
	printf '%s\n' "- In-process storage reopen retained the imported key and recovered history: \`PASS\`"
	printf '%s\n' "- Core R2 driver against the real native library (durable record -> issue -> observe -> spendability-terminal clear): \`PASS\`"
	printf '%s\n' "- Post-recovery Core identity paths (validated-sync recording + persistent Unified reinitialization): \`PASS\`, each completed in under \`15 seconds\`"
	printf '%s\n' "- Reinitialized recovered namespace state and balance: \`UNIFIED_READY\`, \`123456789\` total and verified arrrtoshis"
	printf '%s\n' "- Forbidden transaction RPCs: \`0\`; unexpected RPCs: \`0\`"
	printf '%s\n\n' "- Raw-log and Surefire secret scan: \`PASS\`; raw evidence deleted"
	printf '%s\n' 'This proves the unfunded verified-import recovery path against the pinned native library on loopback fixtures: the verified import contract, the exact dedicated rescan, the spendability completion authority, durable driver state, post-recovery validated-sync identity recording, and persistent Unified reinitialization. It does not prove production lightwalletd interoperability, canonical mainnet history, a funded send, cross-process packaged-Core recovery, QDN publication, deployment, default enablement, or Home behavior.'
} > "$receipt_tmp"
mv "$receipt_tmp" "$receipt"
printf 'PASS receipt=%s\n' "$receipt"
