#!/bin/sh
# shellcheck disable=SC1003,SC2016,SC2317,SC3045
set -eu
umask 077
ulimit -c 0

# Proves inspect-only extraction for the three protected, unfunded legacy-v8
# fixtures. Each wallet is copied and loaded in its own JVM inside a rootless,
# loopback-only network namespace. No Core process or recovery import is used.

LEGACY_LINUX_SHA256='7a1ce3b1e855e893f537ab927d135614ff492f5d1ee4b0a392be2a51488cead7'
LEGACY_COINPARAMS_SHA256='051bf1b840305d2cc6f82c75304d31afc613d6a0eff77c2e3e0f29946a14cfba'
LEGACY_OUTPUT_SHA256='59254099ef6622df3bd7b1b96467bb722edea72603cd34d21708214a0b9f6aba'
LEGACY_SPEND_SHA256='3fc70cb6b7beba436545d5b4210c903a9802f4b87f1bfc5a2faf5a0bea268fc5'
FIXTURE_A_METADATA_SHA256='6ed64092d8abe2f492d3f512cb5e9811b7871814eeddc3745bdc59405d838d76'
FIXTURE_A_WALLET_SHA256='3e7495a5c3f398eef48784906df4b82747c29c980911dc2e3fa35ec65383b4cc'
FIXTURE_B_METADATA_SHA256='31fcdcf8c66d2b1f50e781bcf2d8807ccd0468be928245ead0510e5167ef8a05'
FIXTURE_B_WALLET_SHA256='47f959e6149edfeaafdc333a11a216913f60c9abbd3d172866c95852b75454e8'
FIXTURE_U_METADATA_SHA256='f34f596a8c8d2a77d04374a2f6aa1974b746ba6eacffa1cbbbfbf5eb2a7b298c'
FIXTURE_U_WALLET_SHA256='dcfcb7a0e3d2d682330ecd4064d419c82bd905a28efb5b8ad043ea5582dccc9b'

usage() {
	printf '%s\n' "Usage: $0 <absolute-packaged-core.jar> <absolute-legacy-bundle> <absolute-protected-v8-fixture-directory> <absolute-new-receipt.md>" >&2
}

require_absolute() {
	label=$1
	value=$2
	case $value in
		/*) ;;
		*) printf '%s path must be absolute\n' "$label" >&2; exit 2 ;;
	esac
	case $value in
		*'`'*|*'"'*|*'\'*|*'
'*) printf '%s path contains an unsupported character\n' "$label" >&2; exit 2 ;;
	esac
}

require_command() {
	command -v "$1" >/dev/null 2>&1 || {
		printf 'Required command not found: %s\n' "$1" >&2
		exit 1
	}
}

file_sha256() {
	hash_line=$(sha256sum "$1")
	printf '%s\n' "${hash_line%% *}"
}

property() {
	key=$1
	file=$2
	count=$(awk -F= -v key="$key" '$1 == key { count++ } END { print count + 0 }' "$file")
	[ "$count" -eq 1 ] || return 1
	awk -F= -v key="$key" '$1 == key { print substr($0, length(key) + 2) }' "$file"
}

wallet_filename() {
	metadata=$1
	filename=$(jq -er '.walletFile' "$metadata") || return 1
	case $filename in
		*/*) return 1 ;;
		wallet-[1-9A-HJ-NP-Za-km-z]*.dat) ;;
		*) return 1 ;;
	esac
	printf '%s\n' "$filename"
}

fixture_expectations() {
	case $1 in
		legacy-encrypted-a)
			expected_metadata_sha256=$FIXTURE_A_METADATA_SHA256
			expected_wallet_sha256=$FIXTURE_A_WALLET_SHA256
			expected_encrypted=true
			;;
		legacy-encrypted-b)
			expected_metadata_sha256=$FIXTURE_B_METADATA_SHA256
			expected_wallet_sha256=$FIXTURE_B_WALLET_SHA256
			expected_encrypted=true
			;;
		legacy-unencrypted)
			expected_metadata_sha256=$FIXTURE_U_METADATA_SHA256
			expected_wallet_sha256=$FIXTURE_U_WALLET_SHA256
			expected_encrypted=false
			;;
		*) return 1 ;;
	esac
}

terminate_group() {
	process_group=$1
	[ -n "$process_group" ] || return 0
	group_has_live_processes() {
		ps -eo pgid=,stat= | awk -v process_group="$process_group" '
		$1 == process_group && $2 !~ /^Z/ { found = 1 }
		END { exit !found }
		'
	}
	if group_has_live_processes; then
		/bin/kill -TERM -- "-$process_group" 2>/dev/null || true
	fi
	wait_count=0
	while group_has_live_processes && [ "$wait_count" -lt 60 ]; do
		sleep 1
		wait_count=$((wait_count + 1))
	done
	if group_has_live_processes; then
		/bin/kill -KILL -- "-$process_group" 2>/dev/null || true
	fi
	wait "$process_group" 2>/dev/null || true
	! group_has_live_processes
}

inspect_case() {
	case_name=$1
	metadata=$2
	encrypted=$3
	runtime=$4
	jar=$5
	classes=$6
	result_file=$7

	case_directory=$runtime/cases/$case_name
	mkdir -p "$case_directory"
	wallet_filename=$(wallet_filename "$metadata") || {
		printf '%s\n' '[error] protected fixture wallet filename is invalid' >&2
		return 1
	}
	source_wallet=$(dirname "$metadata")/$wallet_filename
	if [ ! -f "$source_wallet" ] || [ -L "$source_wallet" ]; then
		printf '%s\n' '[error] protected fixture wallet is missing or invalid' >&2
		return 1
	fi
	source_hash=$(file_sha256 "$source_wallet")
	wallet_copy=$case_directory/wallet.dat
	cp "$source_wallet" "$wallet_copy"
	chmod 600 "$wallet_copy"
	[ "$(file_sha256 "$wallet_copy")" = "$source_hash" ] || return 1

	password_descriptor=-1
	password_file=
	if [ "$encrypted" = true ]; then
		password_file=$case_directory/password.input
		: > "$password_file"
		(cd "$case_directory" && java -cp "$classes:$jar" \
				org.qortium.tools.pirate.QortalLegacyFixturePassword \
				"$metadata" 3 3> "$password_file") \
			> "$case_directory/password-derivation.log" 2>&1
		chmod 600 "$password_file"
		password_size=$(wc -c < "$password_file" | tr -d ' ')
		if [ "$password_size" -lt 1 ] || [ "$password_size" -gt 128 ]; then
			printf '%s\n' '[error] protected fixture password derivation failed' >&2
			return 1
		fi
		password_descriptor=3
	fi

	inspection=$case_directory/inspection.json
	inspection_log=$case_directory/inspection.log
	if [ "$encrypted" = true ]; then
		(cd "$case_directory" && java -Xmx1g -Djava.awt.headless=true -cp "$classes:$jar" \
				org.qortium.tools.pirate.PirateLegacyV8Inspector \
				"$runtime/legacy/librust-linux-x86_64.so" \
				"$runtime/legacy/coinparams.json" \
				"$runtime/legacy/saplingoutput_base64" \
				"$runtime/legacy/saplingspend_base64" \
				"$wallet_copy" 'http://127.0.0.1:9067' "$password_descriptor" "$inspection" \
				3< "$password_file") > "$inspection_log" 2>&1
	else
		(cd "$case_directory" && java -Xmx1g -Djava.awt.headless=true -cp "$classes:$jar" \
				org.qortium.tools.pirate.PirateLegacyV8Inspector \
				"$runtime/legacy/librust-linux-x86_64.so" \
				"$runtime/legacy/coinparams.json" \
				"$runtime/legacy/saplingoutput_base64" \
				"$runtime/legacy/saplingspend_base64" \
				"$wallet_copy" 'http://127.0.0.1:9067' -1 "$inspection") \
			> "$inspection_log" 2>&1
	fi

	expected_address_hash_line=$(jq -jer '.receiveAddress' "$metadata" | sha256sum)
	expected_address_hash=${expected_address_hash_line%% *}
	recorded_birthday=$(jq -er '.birthdayHeight' "$metadata")
	expected_birthday=$recorded_birthday
	if ! jq -e --arg expectedHash "$expected_address_hash" \
			--argjson expectedEncrypted "$encrypted" \
			--argjson expectedBirthday "$expected_birthday" \
			--arg sourceHash "$source_hash" '
		.format == "qortium-pirate-legacy-v8-inspection-v1" and
		.serializedVersion == 8 and
		.walletSha256 == $sourceHash and
		.sourceUnchanged == true and
		.network == "mainnet" and
		.encrypted == $expectedEncrypted and
		.birthdayHeight == $expectedBirthday and
		.birthdaySource == "legacy-v8-wallet" and
		.pool == "sapling" and
		.suggestedAddressIndex == 0 and
		.selectionBasis == "legacy-v8-default-row" and
		.candidateCount == 1 and
		.candidateAddressSha256 == [$expectedHash]
	' "$inspection" >/dev/null; then
		printf '%s\n' '[error] redacted inspection result did not match protected metadata' >&2
		return 1
	fi
	[ "$(file_sha256 "$source_wallet")" = "$source_hash" ] || return 1
	[ "$(file_sha256 "$wallet_copy")" = "$source_hash" ] || return 1
	if [ -n "$password_file" ]; then
		rm -f "$password_file"
	fi
	{
		printf 'case.%s=PASS\n' "$case_name"
		printf 'case.%s.encrypted=%s\n' "$case_name" "$encrypted"
		printf 'case.%s.birthday=%s\n' "$case_name" "$expected_birthday"
		printf 'case.%s.sourceSha256=%s\n' "$case_name" "$source_hash"
	} >> "$result_file"
}

run_inside_namespace() {
	runtime=$1
	jar=$2
	classes=$3
	fixture_directory=$4
	result_file=$5
	fixture_pid=

	cleanup_inside() {
		if [ -n "$fixture_pid" ]; then
			terminate_group "$fixture_pid" || true
			fixture_pid=
		fi
	}
	trap 'cleanup_inside' 0
	trap 'exit 143' HUP INT TERM

	ip link set lo up
	ip -o link show > "$runtime/network-links.txt"
	ip route show table all > "$runtime/network-routes.txt"
	non_loopback_interfaces=$(awk -F': ' '$2 !~ /^lo(@|$)/ { count++ } END { print count + 0 }' \
		"$runtime/network-links.txt")
	default_routes=$(awk '$1 == "default" { count++ } END { print count + 0 }' \
		"$runtime/network-routes.txt")
	non_loopback_routes=$(awk '$0 !~ / dev lo( |$)/ { count++ } END { print count + 0 }' \
		"$runtime/network-routes.txt")
	if [ "$non_loopback_interfaces" -ne 0 ] || [ "$default_routes" -ne 0 ] \
			|| [ "$non_loopback_routes" -ne 0 ]; then
		printf '%s\n' '[error] network namespace is not loopback-only' >&2
		return 1
	fi
	{
		printf 'networkEgress=PASS\n'
		printf 'nonLoopbackInterfaces=%s\n' "$non_loopback_interfaces"
		printf 'defaultRoutes=%s\n' "$default_routes"
		printf 'nonLoopbackRoutes=%s\n' "$non_loopback_routes"
	} > "$result_file"

	(cd "$runtime" && exec setsid java -Djava.awt.headless=true -cp "$classes:$jar" \
		org.qortium.controller.PirateUnifiedLoopbackLightwalletdMain \
		"$runtime/lightwalletd.ready" "$runtime/lightwalletd.audit") \
		> "$runtime/lightwalletd.log" 2>&1 &
	fixture_pid=$!
	wait_count=0
	while [ ! -s "$runtime/lightwalletd.ready" ] && [ "$wait_count" -lt 30 ]; do
		kill -0 "$fixture_pid" 2>/dev/null || return 1
		sleep 1
		wait_count=$((wait_count + 1))
	done
	if [ ! -s "$runtime/lightwalletd.ready" ] \
			|| [ "$(property port "$runtime/lightwalletd.ready" 2>/dev/null || true)" != 9067 ] \
			|| [ "$(property nativeChainName "$runtime/lightwalletd.ready" 2>/dev/null || true)" != main ]; then
		printf '%s\n' '[error] loopback fixture did not become ready' >&2
		return 1
	fi
	printf 'loopbackFixture=PASS\n' >> "$result_file"

	first_encrypted=$fixture_directory/legacy-encrypted-a.json
	wrong_case=$runtime/cases/wrong-password
	mkdir -p "$wrong_case"
	wrong_wallet_name=$(wallet_filename "$first_encrypted") || {
		printf '%s\n' '[error] protected fixture wallet filename is invalid' >&2
		return 1
	}
	if [ ! -f "$fixture_directory/$wrong_wallet_name" ] \
			|| [ -L "$fixture_directory/$wrong_wallet_name" ]; then
		printf '%s\n' '[error] protected fixture wallet is missing or invalid' >&2
		return 1
	fi
	cp "$fixture_directory/$wrong_wallet_name" "$wrong_case/wallet.dat"
	wrong_wallet_hash=$(file_sha256 "$wrong_case/wallet.dat")
	printf '%s' 'deliberately-wrong-fixture-password' > "$wrong_case/password.input"
	set +e
	(cd "$wrong_case" && java -Xmx1g -Djava.awt.headless=true -cp "$classes:$jar" \
			org.qortium.tools.pirate.PirateLegacyV8Inspector \
			"$runtime/legacy/librust-linux-x86_64.so" \
			"$runtime/legacy/coinparams.json" \
			"$runtime/legacy/saplingoutput_base64" \
			"$runtime/legacy/saplingspend_base64" \
			"$wrong_case/wallet.dat" 'http://127.0.0.1:9067' 3 "$wrong_case/result.json" \
			3< "$wrong_case/password.input") > "$wrong_case/inspection.log" 2>&1
	wrong_status=$?
	set -e
	rm -f "$wrong_case/password.input"
	if [ "$wrong_status" -ne 3 ] || [ -e "$wrong_case/result.json" ] \
			|| [ "$(file_sha256 "$wrong_case/wallet.dat")" != "$wrong_wallet_hash" ] \
			|| ! grep -F '[error] password-rejected' "$wrong_case/inspection.log" >/dev/null; then
		printf '%s\n' '[error] wrong-password negative did not fail closed' >&2
		return 1
	fi
	printf 'wrongPassword=PASS\n' >> "$result_file"

	inspect_case legacy-encrypted-a "$first_encrypted" true \
		"$runtime" "$jar" "$classes" "$result_file"
	inspect_case legacy-encrypted-b "$fixture_directory/legacy-encrypted-b.json" true \
		"$runtime" "$jar" "$classes" "$result_file"
	inspect_case legacy-unencrypted "$fixture_directory/legacy-unencrypted.json" false \
		"$runtime" "$jar" "$classes" "$result_file"

	terminate_group "$fixture_pid" || return 1
	fixture_pid=
	if [ "$(property result "$runtime/lightwalletd.audit" 2>/dev/null || true)" != PASS ] \
			|| [ "$(property forbiddenRpcs "$runtime/lightwalletd.audit" 2>/dev/null || true)" != 0 ] \
			|| [ "$(property unexpectedRpcs "$runtime/lightwalletd.audit" 2>/dev/null || true)" != 0 ]; then
		printf '%s\n' '[error] loopback fixture audit failed' >&2
		return 1
	fi
	{
		printf 'forbiddenRpcs=0\n'
		printf 'unexpectedRpcs=0\n'
		printf 'result=PASS\n'
	} >> "$result_file"
	trap - 0 HUP INT TERM
}

if [ "${1:-}" = '--inside-network-namespace' ]; then
	[ "$#" -eq 6 ] || exit 2
	shift
	run_inside_namespace "$@"
	exit $?
fi

if [ "$#" -ne 4 ]; then
	usage
	exit 2
fi

jar=$1
legacy_bundle=$2
fixture_directory=$3
receipt=$4
require_absolute 'Packaged JAR' "$jar"
require_absolute 'Legacy bundle' "$legacy_bundle"
require_absolute 'Protected fixture directory' "$fixture_directory"
require_absolute 'Receipt' "$receipt"
case "$(uname -s):$(uname -m)" in
	Linux:x86_64) ;;
	*) printf '%s\n' 'Legacy v8 inspection acceptance currently supports Linux x86_64 only' >&2; exit 1 ;;
esac
for command_name in awk cat cp find grep ip java javac jq ln od ps sed setsid sha256sum tr unshare wc; do
	require_command "$command_name"
done
if [ ! -f "$jar" ] || [ -L "$jar" ] || [ ! -d "$legacy_bundle" ] || [ -L "$legacy_bundle" ] \
		|| [ ! -d "$fixture_directory" ] || [ -L "$fixture_directory" ]; then
	printf '%s\n' 'Acceptance inputs must be regular non-symlink files/directories' >&2
	exit 1
fi

script_directory=$(CDPATH='' cd "$(dirname "$0")" && pwd)
repository=$(CDPATH='' cd "$script_directory/.." && pwd)
inspector_source=$repository/src/test/java/org/qortium/tools/pirate/PirateLegacyV8Inspector.java
password_source=$repository/src/test/java/org/qortium/tools/pirate/QortalLegacyFixturePassword.java
fixture_source=$repository/src/test/java/org/qortium/controller/PirateUnifiedLoopbackLightwalletd.java
fixture_main_source=$repository/src/test/java/org/qortium/controller/PirateUnifiedLoopbackLightwalletdMain.java
for required_input in "$inspector_source" "$password_source" "$fixture_source" "$fixture_main_source" \
		"$legacy_bundle/librust-linux-x86_64.so" "$legacy_bundle/coinparams.json" \
		"$legacy_bundle/saplingoutput_base64" "$legacy_bundle/saplingspend_base64" \
		"$fixture_directory/legacy-encrypted-a.json" \
		"$fixture_directory/legacy-encrypted-b.json" \
		"$fixture_directory/legacy-unencrypted.json"; do
	if [ ! -f "$required_input" ] || [ -L "$required_input" ]; then
		printf '%s\n' 'An acceptance input is missing, non-regular, or a symlink' >&2
		exit 1
	fi
done
if [ "$(file_sha256 "$legacy_bundle/librust-linux-x86_64.so")" != "$LEGACY_LINUX_SHA256" ] \
		|| [ "$(file_sha256 "$legacy_bundle/coinparams.json")" != "$LEGACY_COINPARAMS_SHA256" ] \
		|| [ "$(file_sha256 "$legacy_bundle/saplingoutput_base64")" != "$LEGACY_OUTPUT_SHA256" ] \
		|| [ "$(file_sha256 "$legacy_bundle/saplingspend_base64")" != "$LEGACY_SPEND_SHA256" ]; then
	printf '%s\n' 'Legacy bundle does not match the reviewed published inputs' >&2
	exit 1
fi
for case_name in legacy-encrypted-a legacy-encrypted-b legacy-unencrypted; do
	fixture_expectations "$case_name" || exit 1
	metadata=$fixture_directory/$case_name.json
	if [ "$(file_sha256 "$metadata")" != "$expected_metadata_sha256" ]; then
		printf '%s\n' 'Protected fixture metadata does not match the reviewed input' >&2
		exit 1
	fi
	if ! jq -e '(.serializedVersion == 8) and (.funded == false) and
		(.walletFile | type == "string") and (.receiveAddress | type == "string") and
		(.birthdayHeight | type == "number") and (.encrypted | type == "boolean")' \
		"$metadata" >/dev/null; then
		printf '%s\n' 'Protected fixture metadata contract is invalid' >&2
		exit 1
	fi
	wallet_name=$(wallet_filename "$metadata") || {
		printf '%s\n' 'Protected fixture wallet filename is invalid' >&2
		exit 1
	}
	wallet_path=$fixture_directory/$wallet_name
	if [ ! -f "$wallet_path" ] || [ -L "$wallet_path" ] \
			|| [ "$(file_sha256 "$wallet_path")" != "$expected_wallet_sha256" ]; then
		printf '%s\n' 'Protected fixture wallet does not match the reviewed input' >&2
		exit 1
	fi
	if [ "$(jq -er '.encrypted' "$metadata")" != "$expected_encrypted" ]; then
		printf '%s\n' 'Protected fixture encryption state does not match its case' >&2
		exit 1
	fi
done

receipt_parent=$(dirname "$receipt")
receipt_name=$(basename "$receipt")
mkdir -p "$receipt_parent"
receipt_parent=$(CDPATH='' cd "$receipt_parent" && pwd -P)
receipt=$receipt_parent/$receipt_name
if [ -e "$receipt" ] || [ -e "$receipt.log" ]; then
	printf '%s\n' 'Refusing to overwrite receipt or log' >&2
	exit 1
fi
lock_directory=$receipt.lock
if ! mkdir "$lock_directory" 2>/dev/null; then
	printf '%s\n' 'Another acceptance run owns this receipt' >&2
	exit 1
fi
work_directory=
cleanup_outer() {
	if [ -n "$work_directory" ] && [ -d "$work_directory" ]; then
		rm -rf "$work_directory"
	fi
	rm -f "$lock_directory/receipt.md" "$lock_directory/sanitized.log"
	rmdir "$lock_directory" 2>/dev/null || true
}
trap 'cleanup_outer' 0
trap 'exit 130' HUP INT TERM
work_directory=$(mktemp -d "$receipt_parent/.pirate-v8-inspection.XXXXXX")
classes=$work_directory/classes
runtime=$work_directory/runtime
input_directory=$work_directory/input
input_legacy=$input_directory/legacy
input_fixtures=$input_directory/fixtures
input_sources=$input_directory/sources
input_jar=$input_directory/qortium.jar
input_runner=$input_directory/runner.sh
mkdir -p "$classes" "$runtime/legacy" "$runtime/cases" \
	"$input_legacy" "$input_fixtures" "$input_sources"
cp "$jar" "$input_jar"
cp "$script_directory/$(basename "$0")" "$input_runner"
chmod 700 "$input_runner"
cp "$legacy_bundle/librust-linux-x86_64.so" "$legacy_bundle/coinparams.json" \
	"$legacy_bundle/saplingoutput_base64" "$legacy_bundle/saplingspend_base64" "$input_legacy/"
cp "$inspector_source" "$password_source" "$fixture_source" "$fixture_main_source" "$input_sources/"
for case_name in legacy-encrypted-a legacy-encrypted-b legacy-unencrypted; do
	metadata=$fixture_directory/$case_name.json
	wallet_name=$(wallet_filename "$metadata") || exit 1
	cp "$metadata" "$input_fixtures/$case_name.json"
	cp "$fixture_directory/$wallet_name" "$input_fixtures/$wallet_name"
done
if [ "$(file_sha256 "$input_jar")" != "$(file_sha256 "$jar")" ] \
		|| [ "$(file_sha256 "$input_legacy/librust-linux-x86_64.so")" != "$LEGACY_LINUX_SHA256" ] \
		|| [ "$(file_sha256 "$input_legacy/coinparams.json")" != "$LEGACY_COINPARAMS_SHA256" ] \
		|| [ "$(file_sha256 "$input_legacy/saplingoutput_base64")" != "$LEGACY_OUTPUT_SHA256" ] \
		|| [ "$(file_sha256 "$input_legacy/saplingspend_base64")" != "$LEGACY_SPEND_SHA256" ]; then
	printf '%s\n' 'An executable snapshot changed during acceptance setup' >&2
	exit 1
fi
for case_name in legacy-encrypted-a legacy-encrypted-b legacy-unencrypted; do
	fixture_expectations "$case_name" || exit 1
	metadata=$input_fixtures/$case_name.json
	wallet_name=$(wallet_filename "$metadata") || exit 1
	if [ "$(file_sha256 "$metadata")" != "$expected_metadata_sha256" ] \
			|| [ "$(file_sha256 "$input_fixtures/$wallet_name")" != "$expected_wallet_sha256" ]; then
		printf '%s\n' 'A protected fixture snapshot changed during acceptance setup' >&2
		exit 1
	fi
done
cp "$input_legacy/librust-linux-x86_64.so" "$input_legacy/coinparams.json" \
	"$input_legacy/saplingoutput_base64" "$input_legacy/saplingspend_base64" "$runtime/legacy/"
if [ "$(file_sha256 "$runtime/legacy/librust-linux-x86_64.so")" != "$LEGACY_LINUX_SHA256" ] \
		|| [ "$(file_sha256 "$runtime/legacy/coinparams.json")" != "$LEGACY_COINPARAMS_SHA256" ] \
		|| [ "$(file_sha256 "$runtime/legacy/saplingoutput_base64")" != "$LEGACY_OUTPUT_SHA256" ] \
		|| [ "$(file_sha256 "$runtime/legacy/saplingspend_base64")" != "$LEGACY_SPEND_SHA256" ]; then
	printf '%s\n' 'An executed legacy input changed during acceptance setup' >&2
	exit 1
fi
if ! javac -proc:none -cp "$input_jar" -d "$classes" \
		"$input_sources/PirateLegacyV8Inspector.java" \
		"$input_sources/QortalLegacyFixturePassword.java" \
		"$input_sources/PirateUnifiedLoopbackLightwalletd.java" \
		"$input_sources/PirateUnifiedLoopbackLightwalletdMain.java" \
		> "$work_directory/javac.log" 2>&1; then
	printf '%s\n' 'Could not compile the isolated inspection helpers against the packaged JAR' >&2
	exit 1
fi

result_file=$work_directory/result.properties
harness_log=$work_directory/harness.log
set +e
unshare -Urn "$input_runner" --inside-network-namespace \
	"$runtime" "$input_jar" "$classes" "$input_fixtures" "$result_file" \
	> "$harness_log" 2>&1
acceptance_status=$?
set -e

source_preserved=PASS
for case_name in legacy-encrypted-a legacy-encrypted-b legacy-unencrypted; do
	fixture_expectations "$case_name" || {
		source_preserved=FAIL
		break
	}
	metadata=$fixture_directory/$case_name.json
	wallet_filename=$(wallet_filename "$metadata") || {
		source_preserved=FAIL
		break
	}
	if [ "$(file_sha256 "$metadata")" != "$expected_metadata_sha256" ] \
			|| [ "$(file_sha256 "$fixture_directory/$wallet_filename")" \
			!= "$expected_wallet_sha256" ]; then
		source_preserved=FAIL
		break
	fi
done

secret_scan=PASS
secret_scan_reason=none
secret_patterns=$work_directory/secret-patterns.txt
: > "$secret_patterns"
chmod 600 "$secret_patterns"
for case_name in legacy-encrypted-a legacy-encrypted-b legacy-unencrypted; do
	metadata=$input_fixtures/$case_name.json
	if ! jq -er '.entropy58, .receiveAddress' "$metadata" \
			>> "$secret_patterns"; then
		secret_scan=ERROR
		secret_scan_reason=fixture-metadata-pattern-read
		break
	fi
	if [ "$(jq -er '.encrypted' "$metadata")" = true ]; then
		scan_password_log=$work_directory/scan-password-$case_name.log
		scan_password_file=$work_directory/scan-password-$case_name.input
		: > "$scan_password_file"
		chmod 600 "$scan_password_file"
		if ! (cd "$work_directory" && java -cp "$classes:$input_jar" \
				org.qortium.tools.pirate.QortalLegacyFixturePassword \
				"$metadata" 3 3> "$scan_password_file") \
				> "$scan_password_log" 2>&1; then
			secret_scan=ERROR
			secret_scan_reason=fixture-password-pattern-derivation
			break
		fi
		cat "$scan_password_file" >> "$secret_patterns"
		printf '\n' >> "$secret_patterns"
		rm -f "$scan_password_file"
	fi
done
if [ "$secret_scan" = PASS ]; then
	pattern_lines=$(wc -l < "$secret_patterns" | tr -d ' ')
	if [ "$pattern_lines" -ne 8 ] || grep -q '^$' "$secret_patterns"; then
		secret_scan=ERROR
		secret_scan_reason=pattern-file-shape
	fi
fi

scan_inventory=$work_directory/scan-inventory.txt
: > "$scan_inventory"
for scan_file in "$work_directory/javac.log" "$harness_log" \
		"$result_file" "$work_directory"/scan-password-*.log; do
	[ -f "$scan_file" ] && printf '%s\n' "$scan_file" >> "$scan_inventory"
done
find "$runtime" -type f -print | while IFS= read -r scan_file; do
	case $scan_file in
		"$runtime"/legacy/librust-linux-x86_64.so|\
		"$runtime"/legacy/coinparams.json|\
		"$runtime"/legacy/saplingoutput_base64|\
		"$runtime"/legacy/saplingspend_base64|\
		"$runtime"/cases/*/wallet.dat|\
		"$runtime"/cases/*/password.input) ;;
		*) printf '%s\n' "$scan_file" >> "$scan_inventory" ;;
	esac
done
if [ "$secret_scan" = PASS ]; then
	while IFS= read -r scan_file; do
		set +e
		grep -E 'secret-extended-key-|zxviews1[[:alnum:]]{60,}|"(seed|seedPhrase|private_key|viewing_key|address)"[[:space:]]*:|zs1[[:alnum:]]{60,}|[a-z]{3,}( [a-z]{3,}){23}' \
			"$scan_file" >/dev/null 2>&1
		shape_status=$?
		grep -F -f "$secret_patterns" "$scan_file" >/dev/null 2>&1
		exact_status=$?
		set -e
		if [ "$shape_status" -eq 1 ] && [ "$exact_status" -eq 1 ]; then
			:
		elif [ "$shape_status" -eq 0 ] || [ "$exact_status" -eq 0 ]; then
			secret_scan=FAIL
			secret_scan_reason=secret-material-found
			break
		else
			secret_scan=ERROR
			secret_scan_reason=scan-command-error
			break
		fi
	done < "$scan_inventory"
fi

result=FAIL
network_result=$(property networkEgress "$result_file" 2>/dev/null || printf NOT_PROVEN)
fixture_result=$(property loopbackFixture "$result_file" 2>/dev/null || printf NOT_PROVEN)
wrong_password=$(property wrongPassword "$result_file" 2>/dev/null || printf NOT_PROVEN)
forbidden_rpcs=$(property forbiddenRpcs "$result_file" 2>/dev/null || printf UNKNOWN)
unexpected_rpcs=$(property unexpectedRpcs "$result_file" 2>/dev/null || printf UNKNOWN)
case_a=$(property case.legacy-encrypted-a "$result_file" 2>/dev/null || printf NOT_PROVEN)
case_b=$(property case.legacy-encrypted-b "$result_file" 2>/dev/null || printf NOT_PROVEN)
case_u=$(property case.legacy-unencrypted "$result_file" 2>/dev/null || printf NOT_PROVEN)
if [ "$acceptance_status" -eq 0 ] && [ "$secret_scan" = PASS ] \
		&& [ "$source_preserved" = PASS ] \
		&& [ "$(property result "$result_file" 2>/dev/null || true)" = PASS ]; then
	result=PASS
fi

core_commit=$(cd "$repository" && git rev-parse HEAD)
tree_state=clean
if [ -n "$(cd "$repository" && git status --porcelain)" ]; then
	tree_state=dirty
fi
jar_sha256=$(file_sha256 "$input_jar")
timestamp=$(date -u '+%Y-%m-%dT%H:%M:%SZ')
temporary_receipt=$lock_directory/receipt.md
temporary_log=$lock_directory/sanitized.log
{
	if [ "$secret_scan" = PASS ]; then
		printf '%s\n' 'Process output and raw file evidence passed the secret scan and is not retained.'
	else
		printf '%s\n' 'Process output and raw file evidence is not retained; the secret scan did not pass.'
	fi
	printf 'Harness exit: %s\n' "$acceptance_status"
	printf 'Secret scan: %s\n' "$secret_scan"
	printf 'Secret scan reason: %s\n' "$secret_scan_reason"
	printf 'Protected sources preserved: %s\n' "$source_preserved"
	if [ "$secret_scan" = PASS ]; then
		printf '%s\n' 'Safe harness diagnostics follow:'
		sed -n '1,120p' "$harness_log"
	else
		printf '%s\n' 'Harness diagnostics omitted because the secret scan did not pass.'
	fi
} > "$temporary_log"
{
	printf '# Pirate legacy-v8 inspect-only acceptance receipt\n\n'
	printf '%s `%s`\n' '- Timestamp:' "$timestamp"
	printf '%s `%s`\n' '- Result:' "$result"
	printf '%s `%s`\n' '- Core source commit:' "$core_commit"
	printf '%s `%s`\n' '- Core source tree:' "$tree_state"
	printf '%s `%s`\n' '- Packaged Core JAR SHA-256:' "$jar_sha256"
	printf '%s `%s`\n' '- Legacy Linux JNI SHA-256:' "$LEGACY_LINUX_SHA256"
	printf '%s `%s`\n' '- Fixture A metadata SHA-256:' "$FIXTURE_A_METADATA_SHA256"
	printf '%s `%s`\n' '- Fixture A wallet SHA-256:' "$FIXTURE_A_WALLET_SHA256"
	printf '%s `%s`\n' '- Fixture B metadata SHA-256:' "$FIXTURE_B_METADATA_SHA256"
	printf '%s `%s`\n' '- Fixture B wallet SHA-256:' "$FIXTURE_B_WALLET_SHA256"
	printf '%s `%s`\n' '- Unencrypted fixture metadata SHA-256:' "$FIXTURE_U_METADATA_SHA256"
	printf '%s `%s`\n' '- Unencrypted fixture wallet SHA-256:' "$FIXTURE_U_WALLET_SHA256"
	printf '%s `%s`\n' '- Normalized command:' 'tools/run-pirate-legacy-v8-inspection-acceptance.sh <absolute-packaged-core.jar> <absolute-legacy-bundle> <absolute-protected-v8-fixture-directory> <absolute-new-receipt.md>'
	printf '%s `%s`\n' '- Sanitized log:' "$receipt.log"
	printf '\n## Results\n\n'
	printf '| Boundary | Result |\n'
	printf '|---|---:|\n'
	printf '| Network egress blocked | %s |\n' "$network_result"
	printf '| Loopback lightwalletd | %s |\n' "$fixture_result"
	printf '| Encrypted fixture A | %s |\n' "$case_a"
	printf '| Encrypted fixture B | %s |\n' "$case_b"
	printf '| Unencrypted fixture | %s |\n' "$case_u"
	printf '| Wrong password fails closed | %s |\n' "$wrong_password"
	printf '| Source wallets byte-preserved | %s |\n' "$source_preserved"
	printf '| Forbidden transaction RPCs | `%s` |\n' "$forbidden_rpcs"
	printf '| Unexpected RPCs | `%s` |\n' "$unexpected_rpcs"
	printf '| Secret scan | %s |\n' "$secret_scan"
	if [ "$result" = PASS ]; then
		printf '\nThis proves inspect-only recovery metadata for all three exact protected, unfunded serialization-v8 fixtures through snapshotted inputs and the exact reviewed legacy JNI: encryption handling, explicit wrong-password rejection, wallet-wide birthday recovery, one nonempty Sapling spending-key candidate associated with the source-reviewed v8 default row, source preservation, and zero network egress. Index 0 is a suggestion for the later verified-import boundary, not a cryptographic ownership or spendability result from this inspector. It does not import a key, mutate Core, prove funded recovery, or provide a supported end-user interface.\n'
	else
		printf '\nThis failed receipt proves only the individual boundaries marked `PASS`.\n'
	fi
} > "$temporary_receipt"

if [ "$secret_scan" = PASS ]; then
	for scan_file in "$temporary_log" "$temporary_receipt"; do
		set +e
		grep -E 'secret-extended-key-|zxviews1[[:alnum:]]{60,}|"(seed|seedPhrase|private_key|viewing_key|address)"[[:space:]]*:|zs1[[:alnum:]]{60,}|[a-z]{3,}( [a-z]{3,}){23}' \
			"$scan_file" >/dev/null 2>&1
		shape_status=$?
		grep -F -f "$secret_patterns" "$scan_file" >/dev/null 2>&1
		exact_status=$?
		set -e
		if [ "$shape_status" -ne 1 ] || [ "$exact_status" -ne 1 ]; then
			printf '%s\n' 'Sanitized evidence did not pass the final secret scan' >&2
			exit 1
		fi
	done
fi

raw_work_directory=$work_directory
rm -rf "$raw_work_directory"
if [ -e "$raw_work_directory" ]; then
	printf '%s\n' 'Raw acceptance evidence could not be removed' >&2
	exit 1
fi
work_directory=
if ! ln -T "$temporary_log" "$receipt.log"; then
	printf '%s\n' 'Refusing to overwrite a receipt log created during acceptance' >&2
	exit 1
fi
if ! ln -T "$temporary_receipt" "$receipt"; then
	rm -f "$receipt.log"
	printf '%s\n' 'Refusing to overwrite a receipt created during acceptance' >&2
	exit 1
fi
rm -f "$temporary_log" "$temporary_receipt"
trap - 0 HUP INT TERM
cleanup_outer
if [ "$acceptance_status" -ne 0 ] || [ "$result" != PASS ]; then
	printf 'Legacy v8 inspection acceptance failed; receipt: %s\n' "$receipt" >&2
	exit 1
fi
printf 'Legacy v8 inspection acceptance passed; receipt: %s\n' "$receipt"
