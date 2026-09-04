#!/bin/sh
# shellcheck disable=SC1003,SC2016,SC2317
set -eu
umask 077

# Runs a real protected Qortal-format v8 fixture through three separately
# packaged Core processes inside a rootless loopback-only network namespace:
# legacy identity capture, first Unified migration/sync, and Unified reopen.

LEGACY_SIGNATURE='EsfUw54perxkEtfoUoL7Z97XPrNsZRZXePVZPz3cwRm9qyEPSofD5KmgVpDqVitQp7LhnZRmL6z2V9hEe1YS45T'
LEGACY_LINUX_SHA256='7a1ce3b1e855e893f537ab927d135614ff492f5d1ee4b0a392be2a51488cead7'
LEGACY_COINPARAMS_SHA256='051bf1b840305d2cc6f82c75304d31afc613d6a0eff77c2e3e0f29946a14cfba'
LEGACY_OUTPUT_SHA256='59254099ef6622df3bd7b1b96467bb722edea72603cd34d21708214a0b9f6aba'
LEGACY_SPEND_SHA256='3fc70cb6b7beba436545d5b4210c903a9802f4b87f1bfc5a2faf5a0bea268fc5'

usage() {
	printf '%s\n' "Usage: $0 <absolute-packaged-core.jar> <absolute-unified-staged-bundle> <absolute-local-qdn-fixture> <absolute-legacy-bundle> <absolute-protected-v8-metadata.json> <new-receipt.md>" >&2
}

property() {
	key=$1
	file=$2
	count=$(awk -F= -v key="$key" '$1 == key { count++ } END { print count + 0 }' "$file")
	[ "$count" -eq 1 ] || return 1
	awk -F= -v key="$key" '$1 == key { print substr($0, length(key) + 2) }' "$file"
}

state_field() {
	field=$1
	state_file=$2
	sed -n "s/.*\"$field\"[[:space:]]*:[[:space:]]*\"\{0,1\}\([^\",}]*\).*/\1/p" "$state_file" \
		| sed -n '1p'
}

require_absolute() {
	label=$1
	value=$2
	case $value in
		/*) ;;
		*) printf '%s path must be absolute: %s\n' "$label" "$value" >&2; exit 2 ;;
	esac
	case $value in
		*'`'*|*'"'*|*'\'*|*'
'*) printf '%s path contains a backtick, quote, backslash, or newline\n' "$label" >&2; exit 2 ;;
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

terminate_group() {
	process_group=$1
	if [ -z "$process_group" ]; then
		return 0
	fi
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

run_inside_namespace() {
	runtime=$1
	jar=$2
	helper_classes=$3
	unified_signature=$4
	result_file=$5
	core_pid=
	fixture_pid=
	api_port=62391
	unified_root=$runtime/wallets/PirateChain/unified
	legacy_wallet=$runtime/wallets/PirateChain/$(cat "$runtime/wallet-filename.txt")
	legacy_library=$runtime/wallets/PirateChain/lib/$LEGACY_SIGNATURE/librust-linux-x86_64.so
	unified_library=$runtime/wallets/PirateChain/lib/$unified_signature/librust-linux-x86_64.so

	cleanup_inside() {
		cleanup_status=0
		if [ -n "$core_pid" ]; then
			terminate_group "$core_pid" || cleanup_status=1
			core_pid=
		fi
		if [ -n "$fixture_pid" ]; then
			terminate_group "$fixture_pid" || cleanup_status=1
			fixture_pid=
		fi
		return "$cleanup_status"
	}
	handle_inside_signal() {
		trap - HUP INT TERM
		cleanup_inside || true
		exit 143
	}
	trap 'cleanup_inside' 0
	trap 'handle_inside_signal' HUP INT TERM

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
		printf '%s\n' 'Network namespace is not loopback-only' >&2
		return 1
	fi
	{
		printf 'networkEgress=PASS\n'
		printf 'nonLoopbackInterfaces=%s\n' "$non_loopback_interfaces"
		printf 'defaultRoutes=%s\n' "$default_routes"
		printf 'nonLoopbackRoutes=%s\n' "$non_loopback_routes"
	} > "$result_file"

	setsid sh -c 'cd "$1" && exec java -Djava.awt.headless=true -cp "$2:$3" org.qortium.controller.PirateUnifiedLoopbackLightwalletdMain "$4" "$5"' \
		sh "$runtime" "$helper_classes" "$jar" "$runtime/lightwalletd.ready" "$runtime/lightwalletd.audit" \
		> "$runtime/lightwalletd.log" 2>&1 &
	fixture_pid=$!
	fixture_ready=false
	wait_count=0
	while [ "$wait_count" -lt 30 ]; do
		if ! kill -0 "$fixture_pid" 2>/dev/null; then
			printf '%s\n' 'Loopback lightwalletd exited before readiness' >&2
			return 1
		fi
		if [ -s "$runtime/lightwalletd.ready" ]; then
			fixture_ready=true
			break
		fi
		sleep 1
		wait_count=$((wait_count + 1))
	done
	if [ "$fixture_ready" != true ] \
			|| [ "$(property port "$runtime/lightwalletd.ready" 2>/dev/null || true)" != 9067 ] \
			|| [ "$(property javaChainName "$runtime/lightwalletd.ready" 2>/dev/null || true)" != regtest ] \
			|| [ "$(property nativeChainName "$runtime/lightwalletd.ready" 2>/dev/null || true)" != main ]; then
		printf '%s\n' 'Loopback lightwalletd did not prove its fixed dual-service endpoint' >&2
		return 1
	fi
	printf 'loopbackFixture=PASS\n' >> "$result_file"

	set_current_server() {
		response_file=$1
		printf '%s\n' '{"hostName":"127.0.0.1","port":9067,"connectionType":"TCP"}' \
			> "$runtime/server.json"
		curl --fail --silent --show-error --max-time 30 \
				--config "$runtime/curl-json-api.conf" \
				--data-binary "@$runtime/server.json" \
				"http://127.0.0.1:$api_port/crosschain/arrr/setcurrentserver" \
				> "$response_file" 2>/dev/null \
			&& grep -Eq '"success"[[:space:]]*:[[:space:]]*true' "$response_file" \
			&& grep -Eq '"port"[[:space:]]*:[[:space:]]*9067' "$response_file"
	}

	wallet_address_sha256() {
		start_number=$1
		wallet_response=$runtime/wallet-address-$start_number.txt
		wallet_http_status=$(curl --silent --show-error --max-time 30 \
				--config "$runtime/curl-api.conf" \
				--data-binary "@$runtime/entropy.txt" \
				--output "$wallet_response" \
				--write-out '%{http_code}' \
				"http://127.0.0.1:$api_port/crosschain/arrr/walletaddress" 2>/dev/null) || return 1
		printf '%s\n' "$wallet_http_status" > "$runtime/wallet-address-http-$start_number.txt"
		[ "$wallet_http_status" = 200 ] || return 1
		wallet_address=$(cat "$wallet_response")
		case $wallet_address in
			zs[1-9A-HJ-NP-Za-km-z]*) ;;
			*) wallet_address=; return 1 ;;
		esac
		wallet_address_hash_line=$(printf '%s' "$wallet_address" | sha256sum)
		wallet_address=
		rm -f "$wallet_response"
		printf '%s\n' "${wallet_address_hash_line%% *}"
	}

	wait_for_wallet_ready() {
		start_number=$1
		status_response=$runtime/status-start-$start_number.json
		ready=false
		wait_count=0
		while [ "$wait_count" -lt 300 ]; do
			if ! kill -0 "$core_pid" 2>/dev/null; then
				printf 'Packaged Core start %s exited before wallet readiness\n' "$start_number" >&2
				return 1
			fi
			if curl --fail --silent --show-error --max-time 15 \
					--config "$runtime/curl-api.conf" \
					--data-binary "@$runtime/entropy.txt" \
					"http://127.0.0.1:$api_port/crosschain/arrr/syncstatus?json=true" \
					> "$status_response" 2>/dev/null \
					&& grep -Eq '"state"[[:space:]]*:[[:space:]]*"READY"' "$status_response" \
					&& grep -Eq '"restartRequired"[[:space:]]*:[[:space:]]*false' "$status_response"; then
				ready=true
				break
			fi
			sleep 1
			wait_count=$((wait_count + 1))
		done
		[ "$ready" = true ] || {
			printf 'Packaged Core start %s did not reach READY\n' "$start_number" >&2
			return 1
		}
	}

	resolve_state_paths() {
		start_number=$1
		if [ ! -d "$unified_root" ] || [ -L "$unified_root" ]; then
			printf 'Packaged Core start %s created no valid migration-state root\n' "$start_number" >&2
			return 1
		fi
		find "$unified_root" -mindepth 1 -maxdepth 1 ! -type d -print > "$runtime/invalid-namespaces-$start_number.txt"
		find "$unified_root" -mindepth 1 -maxdepth 1 -type d -printf '%f\n' > "$runtime/namespaces-$start_number.txt"
		if [ -s "$runtime/invalid-namespaces-$start_number.txt" ] \
				|| [ "$(wc -l < "$runtime/namespaces-$start_number.txt")" -ne 1 ]; then
			printf 'Packaged Core start %s did not retain exactly one wallet namespace\n' "$start_number" >&2
			return 1
		fi
		namespace=$(sed -n '1p' "$runtime/namespaces-$start_number.txt")
		case $namespace in
			transient|*.failed-*) printf 'Packaged Core start %s retained a rejected/transient namespace\n' "$start_number" >&2; return 1 ;;
		esac
		state_path=$unified_root/$namespace/qortium-wallet-state.json
		registry_path=$unified_root/$namespace/wallet_registry.db
	}

	assert_state() {
		start_number=$1
		expected_state=$2
		expected_sync=$3
		expected_registry=$4
		state_ready=false
		wait_count=0
		while [ "$wait_count" -lt 90 ]; do
			registry_matches=false
			if [ "$expected_registry" = present ] && [ -f "$registry_path" ] && [ ! -L "$registry_path" ]; then
				registry_matches=true
			elif [ "$expected_registry" = absent ] && [ ! -e "$registry_path" ]; then
				registry_matches=true
			fi
			if [ -f "$state_path" ] && [ ! -L "$state_path" ] \
					&& grep -Eq '"state"[[:space:]]*:[[:space:]]*"'"$expected_state"'"' "$state_path" \
					&& grep -Eq '"syncValidated"[[:space:]]*:[[:space:]]*'"$expected_sync" "$state_path" \
					&& grep -Eq '"identityHash"[[:space:]]*:[[:space:]]*"[1-9A-HJ-NP-Za-km-z]+"' "$state_path" \
					&& [ "$registry_matches" = true ]; then
				state_ready=true
				break
			fi
			if ! kill -0 "$core_pid" 2>/dev/null; then
				break
			fi
			sleep 1
			wait_count=$((wait_count + 1))
		done
		[ "$state_ready" = true ] || {
			printf 'Packaged Core start %s did not persist expected %s migration state\n' "$start_number" "$expected_state" >&2
			return 1
		}
	}

	run_core_phase() {
		start_number=$1
		settings_file=$2
		expected_state=$3
		expected_sync=$4
		expected_registry=$5
		expected_library=$6
		core_log=$runtime/core-start-$start_number.log
		setsid sh -c 'cd "$1" && exec java -Djava.awt.headless=true -Dlog4j.configurationFile="$1/log4j2-acceptance.properties" -jar "$2" "$3"' \
			sh "$runtime" "$jar" "$settings_file" > "$core_log" 2>&1 &
		core_pid=$!

		api_ready=false
		wait_count=0
		while [ "$wait_count" -lt 120 ]; do
			if ! kill -0 "$core_pid" 2>/dev/null; then
				printf 'Packaged Core start %s exited before API readiness\n' "$start_number" >&2
				return 1
			fi
			if curl --fail --silent --show-error --max-time 2 \
					"http://127.0.0.1:$api_port/admin/status" >/dev/null 2>&1; then
				api_ready=true
				break
			fi
			sleep 1
			wait_count=$((wait_count + 1))
		done
		[ "$api_ready" = true ] || {
			printf 'Timed out waiting for packaged Core start %s API\n' "$start_number" >&2
			return 1
		}
		set_current_server "$runtime/set-server-$start_number.json" || {
			printf 'Packaged Core start %s did not select the loopback server\n' "$start_number" >&2
			return 1
		}

		address_sha256=
		wait_count=0
		while [ "$wait_count" -lt 120 ]; do
			address_sha256=$(wallet_address_sha256 "$start_number" 2>/dev/null || true)
			[ -n "$address_sha256" ] && break
			if ! kill -0 "$core_pid" 2>/dev/null; then
				break
			fi
			sleep 1
			wait_count=$((wait_count + 1))
		done
		[ -n "$address_sha256" ] || {
			wallet_http_status=$(sed -n '1p' "$runtime/wallet-address-http-$start_number.txt" 2>/dev/null || printf UNKNOWN)
			library_mapped=false
			version8_decoded=false
			failure_class=unclassified
			api_message_class=unclassified
			if [ -r "/proc/$core_pid/maps" ] \
					&& grep -F " $expected_library" "/proc/$core_pid/maps" >/dev/null 2>&1; then
				library_mapped=true
			fi
			if grep -F 'Reading wallet version 8' "$core_log" >/dev/null 2>&1; then
				version8_decoded=true
			fi
			if grep -F 'seed phrases do not match, or are null' "$core_log" >/dev/null 2>&1; then
				failure_class=seed-identity-mismatch
			elif grep -F 'Unable to initialize Pirate Chain wallet at http://127.0.0.1:9067/' "$core_log" >/dev/null 2>&1; then
				failure_class=legacy-init-response-rejected
			elif grep -F 'Unable to initialize Pirate Chain wallet:' "$core_log" >/dev/null 2>&1; then
				failure_class=legacy-initialization-exception
			fi
			if jq -e ".message == \"Pirate Chain wallet controller isn't running\"" \
					"$runtime/wallet-address-$start_number.txt" >/dev/null 2>&1; then
				api_message_class=controller-not-running
			elif jq -e ".message == \"Pirate Chain wallet isn't initialized yet\"" \
					"$runtime/wallet-address-$start_number.txt" >/dev/null 2>&1; then
				api_message_class=wallet-not-initialized
			elif jq -e '.message == "Pirate Chain wallet endpoint is not validated yet"' \
					"$runtime/wallet-address-$start_number.txt" >/dev/null 2>&1; then
				api_message_class=endpoint-not-validated
			fi
			printf 'Packaged Core start %s did not initialize the fixture wallet (HTTP %s; library mapped=%s; version 8 decoded=%s; class=%s; API=%s)\n' \
				"$start_number" "$wallet_http_status" "$library_mapped" "$version8_decoded" \
				"$failure_class" "$api_message_class" >&2
			return 1
		}
		wait_for_wallet_ready "$start_number"
		resolve_state_paths "$start_number"
		assert_state "$start_number" "$expected_state" "$expected_sync" "$expected_registry"

		identity_hash=$(state_field identityHash "$state_path")
		if [ -z "$identity_hash" ]; then
			printf 'Packaged Core start %s state identity could not be parsed\n' "$start_number" >&2
			return 1
		fi
		if [ "$start_number" -eq 1 ]; then
			first_namespace=$namespace
			first_identity_hash=$identity_hash
			first_address_sha256=$address_sha256
		else
			if [ "$namespace" != "$first_namespace" ] || [ "$identity_hash" != "$first_identity_hash" ] \
					|| [ "$address_sha256" != "$first_address_sha256" ]; then
				printf '%s\n' 'Wallet namespace or identity changed across migration phases' >&2
				return 1
			fi
		fi
		address_sha256=

		if [ ! -f "$legacy_wallet" ] || [ -L "$legacy_wallet" ]; then
			printf 'Packaged Core start %s did not preserve the legacy wallet file\n' "$start_number" >&2
			return 1
		fi
		if [ ! -r "/proc/$core_pid/maps" ] \
				|| ! grep -F " $expected_library" "/proc/$core_pid/maps" >/dev/null 2>&1; then
			printf 'Packaged Core start %s did not map the expected phase-specific native library\n' "$start_number" >&2
			return 1
		fi
		if [ "$start_number" -eq 1 ] \
				&& ! grep -F 'Reading wallet version 8' "$core_log" >/dev/null 2>&1; then
			printf '%s\n' 'Legacy packaged phase did not prove real serialization-v8 decoding' >&2
			return 1
		fi

		if ! terminate_group "$core_pid"; then
			printf 'Packaged Core start %s process group did not terminate\n' "$start_number" >&2
			return 1
		fi
		core_pid=
		if ! grep -F 'Shutdown complete!' "$core_log" >/dev/null 2>&1; then
			printf 'Packaged Core start %s lacked graceful-shutdown confirmation\n' "$start_number" >&2
			return 1
		fi
		if ! grep -Eq '"state"[[:space:]]*:[[:space:]]*"'"$expected_state"'"' "$state_path" \
				|| ! grep -Eq '"syncValidated"[[:space:]]*:[[:space:]]*'"$expected_sync" "$state_path"; then
			printf 'Packaged Core start %s changed migration state during shutdown\n' "$start_number" >&2
			return 1
		fi
		printf 'start%s=PASS\n' "$start_number" >> "$result_file"
	}

	first_namespace=
	first_identity_hash=
	first_address_sha256=
	run_core_phase 1 "$runtime/settings-legacy.json" LEGACY false absent "$legacy_library"
	run_core_phase 2 "$runtime/settings-unified.json" MIGRATING true present "$unified_library"
	run_core_phase 3 "$runtime/settings-unified.json" UNIFIED_READY true present "$unified_library"
	printf 'packagedStarts=3\nnamespaceContinuity=PASS\nlegacyFilePreserved=PASS\n' >> "$result_file"

	if ! terminate_group "$fixture_pid"; then
		printf '%s\n' 'Loopback lightwalletd process group did not terminate' >&2
		return 1
	fi
	fixture_pid=
	if [ ! -f "$runtime/lightwalletd.audit" ] \
			|| [ "$(property result "$runtime/lightwalletd.audit" 2>/dev/null || true)" != PASS ]; then
		printf '%s\n' 'Loopback lightwalletd did not write a complete audit' >&2
		return 1
	fi
	pirate_tip_requests=$(property pirateTipRequests "$runtime/lightwalletd.audit")
	pirate_tip_ranges=$(property pirateTipRanges "$runtime/lightwalletd.audit")
	pirate_tip_blocks=$(property pirateTipBlocks "$runtime/lightwalletd.audit")
	pirate_scanned_blocks=$(property pirateScannedBlocks "$runtime/lightwalletd.audit")
	forbidden_rpcs=$(property forbiddenRpcs "$runtime/lightwalletd.audit")
	unexpected_rpcs=$(property unexpectedRpcs "$runtime/lightwalletd.audit")
	subtree_probes=$(property subtreeProbes "$runtime/lightwalletd.audit")
	case $pirate_tip_requests:$pirate_tip_ranges:$pirate_tip_blocks:$pirate_scanned_blocks:$forbidden_rpcs:$unexpected_rpcs:$subtree_probes in
		*[!0-9:]*|*::*|:*) printf '%s\n' 'Loopback lightwalletd audit contains invalid counts' >&2; return 1 ;;
	esac
	if [ "$pirate_tip_requests" -lt 3 ] \
			|| { [ "$pirate_tip_ranges" -lt 1 ] && [ "$pirate_tip_blocks" -lt 1 ]; } \
			|| [ "$pirate_scanned_blocks" -lt 1 ] || [ "$forbidden_rpcs" -ne 0 ] \
			|| [ "$unexpected_rpcs" -ne 0 ] || [ "$subtree_probes" -lt 1 ]; then
		printf 'Loopback migration audit mismatch: tip=%s ranges=%s tip-blocks=%s blocks=%s forbidden=%s unexpected=%s subtree=%s\n' \
			"$pirate_tip_requests" "$pirate_tip_ranges" "$pirate_tip_blocks" "$pirate_scanned_blocks" \
			"$forbidden_rpcs" "$unexpected_rpcs" "$subtree_probes" >&2
		return 1
	fi
	{
		printf 'birthdayRange=PASS\n'
		printf 'forbiddenTransactionRpcs=%s\n' "$forbidden_rpcs"
		printf 'unexpectedRpcs=%s\n' "$unexpected_rpcs"
		printf 'gracefulShutdowns=3\n'
		printf 'result=PASS\n'
	} >> "$result_file"
	trap - 0 HUP INT TERM
	return 0
}

if [ "${1:-}" = '--inside-network-namespace' ]; then
	[ "$#" -eq 6 ] || exit 2
	shift
	run_inside_namespace "$@"
	exit $?
fi

if [ "$#" -ne 6 ]; then
	usage
	exit 2
fi

jar=$1
bundle=$2
qdn_fixture=$3
legacy_bundle=$4
legacy_metadata=$5
receipt=$6
require_absolute 'Packaged JAR' "$jar"
require_absolute 'Unified bundle' "$bundle"
require_absolute 'Local-QDN fixture' "$qdn_fixture"
require_absolute 'Legacy bundle' "$legacy_bundle"
require_absolute 'Legacy metadata' "$legacy_metadata"
require_absolute 'Receipt' "$receipt"
case "$(uname -s):$(uname -m)" in
	Linux:x86_64) ;;
	*) printf '%s\n' 'Real legacy migration acceptance currently supports Linux x86_64 only' >&2; exit 1 ;;
esac
for command_name in awk cp curl find grep ip java javac jq od ps sed setsid sha256sum tr unshare wc; do
	require_command "$command_name"
done

if [ ! -f "$jar" ] || [ -L "$jar" ] || [ ! -d "$bundle" ] || [ -L "$bundle" ] \
		|| [ ! -d "$qdn_fixture" ] || [ -L "$qdn_fixture" ] \
		|| [ ! -d "$legacy_bundle" ] || [ -L "$legacy_bundle" ] \
		|| [ ! -f "$legacy_metadata" ] || [ -L "$legacy_metadata" ]; then
	printf '%s\n' 'Acceptance inputs must be regular non-symlink files/directories' >&2
	exit 1
fi

script_directory=$(CDPATH='' cd "$(dirname "$0")" && pwd)
repository=$(CDPATH='' cd "$script_directory/.." && pwd)
test_chain=$repository/src/test/resources/test-chain-v2.json
fixture_source=$repository/src/test/java/org/qortium/controller/PirateUnifiedLoopbackLightwalletd.java
fixture_main_source=$repository/src/test/java/org/qortium/controller/PirateUnifiedLoopbackLightwalletdMain.java
fixture_properties=$qdn_fixture/fixture.properties
manifest=$bundle/QORTIUM-MANIFEST.txt
for required_input in "$test_chain" "$fixture_source" "$fixture_main_source" \
		"$fixture_properties" "$manifest" "$qdn_fixture/repository/blockchain.properties" \
		"$legacy_bundle/librust-linux-x86_64.so" "$legacy_bundle/coinparams.json" \
		"$legacy_bundle/saplingoutput_base64" "$legacy_bundle/saplingspend_base64"; do
	if [ ! -f "$required_input" ] || [ -L "$required_input" ]; then
		printf 'Acceptance input is missing, non-regular, or a symlink: %s\n' "$required_input" >&2
		exit 1
	fi
done
if [ ! -d "$qdn_fixture/data" ] || [ -L "$qdn_fixture/data" ]; then
	printf '%s\n' 'Local-QDN fixture data directory is invalid' >&2
	exit 1
fi

if [ "$(file_sha256 "$legacy_bundle/librust-linux-x86_64.so")" != "$LEGACY_LINUX_SHA256" ] \
		|| [ "$(file_sha256 "$legacy_bundle/coinparams.json")" != "$LEGACY_COINPARAMS_SHA256" ] \
		|| [ "$(file_sha256 "$legacy_bundle/saplingoutput_base64")" != "$LEGACY_OUTPUT_SHA256" ] \
		|| [ "$(file_sha256 "$legacy_bundle/saplingspend_base64")" != "$LEGACY_SPEND_SHA256" ]; then
	printf '%s\n' 'Legacy bundle does not match the reviewed published inputs' >&2
	exit 1
fi

format=$(property format "$fixture_properties") || exit 1
signature=$(property signature "$fixture_properties") || exit 1
fixture_manifest_sha256=$(property bundleManifestSha256 "$fixture_properties") || exit 1
transaction_state=$(property transactionState "$fixture_properties") || exit 1
manifest_sha256=$(file_sha256 "$manifest")
if [ "$format" != qortium-pirate-unified-local-qdn-fixture-v2 ] \
		|| [ "$transaction_state" != synthetic-direct-repository-row ] \
		|| ! printf '%s\n' "$signature" | grep -Eq '^[1-9A-HJ-NP-Za-km-z]{80,100}$' \
		|| [ "$manifest_sha256" != "$fixture_manifest_sha256" ]; then
	printf '%s\n' 'Local-QDN fixture provenance contract is invalid' >&2
	exit 1
fi

if ! jq -e '
	(.serializedVersion == 8) and
	(.encrypted == true) and
	(.funded == false)
' "$legacy_metadata" >/dev/null; then
	printf '%s\n' 'Protected legacy fixture must be encrypted, unfunded, and serialization v8' >&2
	exit 1
fi
if ! jq -e '
	(.entropy58 | type == "string" and test("^[1-9A-HJ-NP-Za-km-z]{44}$")) and
	(.entropyHash58 | type == "string" and test("^[1-9A-HJ-NP-Za-km-z]{44}$")) and
	(.receiveAddress | type == "string" and test("^zs1[023456789acdefghjklmnpqrstuvwxyz]{75}$")) and
	(.walletFile | type == "string" and test("^wallet-[1-9A-HJ-NP-Za-km-z]+\\.dat$"))
' "$legacy_metadata" >/dev/null; then
	printf '%s\n' 'Protected legacy fixture metadata has invalid secret or identity field shapes' >&2
	exit 1
fi
wallet_filename=$(jq -er '.walletFile' "$legacy_metadata")
legacy_fixture_directory=$(CDPATH='' cd "$(dirname "$legacy_metadata")" && pwd -P)
legacy_wallet_source=$legacy_fixture_directory/$wallet_filename
if [ ! -f "$legacy_wallet_source" ] || [ -L "$legacy_wallet_source" ]; then
	printf '%s\n' 'Protected legacy fixture wallet is missing or invalid' >&2
	exit 1
fi
legacy_version=$(od -An -t u8 -N8 "$legacy_wallet_source" | tr -d ' ')
if [ "$legacy_version" != 8 ]; then
	printf '%s\n' 'Protected legacy fixture bytes are not serialization v8' >&2
	exit 1
fi
legacy_source_sha256=$(file_sha256 "$legacy_wallet_source")

receipt_parent=$(dirname "$receipt")
receipt_name=$(basename "$receipt")
mkdir -p "$receipt_parent"
receipt_parent=$(CDPATH='' cd "$receipt_parent" && pwd -P)
receipt=$receipt_parent/$receipt_name
if [ -e "$receipt" ] || [ -e "$receipt.log" ]; then
	printf 'Refusing to overwrite receipt or log: %s\n' "$receipt" >&2
	exit 1
fi
lock_directory=$receipt.lock
if ! mkdir "$lock_directory" 2>/dev/null; then
	printf 'Another migration acceptance owns this receipt: %s\n' "$receipt" >&2
	exit 1
fi
work_directory=
cleanup_outer() {
	if [ -n "$work_directory" ] && [ -d "$work_directory" ]; then
		rm -rf "$work_directory"
	fi
	rmdir "$lock_directory" 2>/dev/null || true
}
trap 'cleanup_outer' 0
trap 'exit 130' HUP INT TERM
work_directory=$(mktemp -d "$receipt_parent/.pirate-real-legacy-migration.XXXXXX")
helper_classes=$work_directory/helper-classes
mkdir -p "$helper_classes"
if ! javac -cp "$jar" -d "$helper_classes" "$fixture_source" "$fixture_main_source" \
		> "$work_directory/javac.log" 2>&1; then
	printf '%s\n' 'Could not compile the test-only loopback fixture against the packaged JAR' >&2
	exit 1
fi

runtime=$work_directory/runtime
mkdir -p "$runtime/repository" "$runtime/data" "$runtime/temp" "$runtime/api" \
	"$runtime/lists" "$runtime/export" "$runtime/wallets/PirateChain/lib/$LEGACY_SIGNATURE"
cp -a --reflink=auto "$qdn_fixture/repository/." "$runtime/repository/"
cp -a --reflink=auto "$qdn_fixture/data/." "$runtime/data/"
cp "$test_chain" "$runtime/test-chain-v2.json"
cp "$legacy_wallet_source" "$runtime/wallets/PirateChain/$wallet_filename"
cp "$legacy_bundle/librust-linux-x86_64.so" "$legacy_bundle/coinparams.json" \
	"$legacy_bundle/saplingoutput_base64" "$legacy_bundle/saplingspend_base64" \
	"$runtime/wallets/PirateChain/lib/$LEGACY_SIGNATURE/"
printf '%s\n' "$wallet_filename" > "$runtime/wallet-filename.txt"
jq -jer '.entropy58' "$legacy_metadata" > "$runtime/entropy.txt"
api_key=$(od -An -N32 -tx1 /dev/urandom | tr -d ' \n')
printf '%s' "$api_key" > "$runtime/api/apikey.txt"
printf '%s\n' "header = \"X-API-KEY: $api_key\"" \
	'header = "Content-Type: text/plain"' > "$runtime/curl-api.conf"
printf '%s\n' "header = \"X-API-KEY: $api_key\"" \
	'header = "Content-Type: application/json"' > "$runtime/curl-json-api.conf"
chmod 600 "$runtime/api/apikey.txt" "$runtime/curl-api.conf" "$runtime/curl-json-api.conf" \
	"$runtime/entropy.txt" "$runtime/wallet-filename.txt" "$runtime/wallets/PirateChain/$wallet_filename"

cat > "$runtime/log4j2-acceptance.properties" <<'EOF'
rootLogger.level = info
rootLogger.appenderRef.console.ref = stdout
appender.console.type = Console
appender.console.name = stdout
appender.console.layout.type = PatternLayout
appender.console.layout.pattern = %d{yyyy-MM-dd HH:mm:ss} %-5p %c{1}:%L - %m%n
EOF

write_settings() {
	settings_path=$1
	unified_enabled=$2
	cat > "$settings_path" <<EOF
{
  "bindAddress": "127.0.0.1",
  "listenPort": 62392,
  "listenDataPort": 62393,
  "apiEnabled": true,
  "apiPort": 62391,
  "apiWhitelistEnabled": true,
  "apiWhitelist": ["127.0.0.1"],
  "apiRestricted": true,
  "apiKeyRemoteAccessEnabled": false,
  "apiLoggingEnabled": false,
  "apiDocumentationEnabled": false,
  "apiKeyPath": "$runtime/api",
  "sslKeystorePathname": null,
  "isTestNet": true,
  "singleNodeTestnet": true,
  "lite": true,
  "testNtpOffset": 0,
  "blockchainConfig": "$runtime/test-chain-v2.json",
  "repositoryPath": "$runtime/repository",
  "repositoryConnectionPoolSize": 16,
  "dataPath": "$runtime/data",
  "tempDataPath": "$runtime/temp",
  "walletsPath": "$runtime/wallets",
  "listsPath": "$runtime/lists",
  "exportPath": "$runtime/export",
  "qdnEnabled": true,
  "storagePolicy": "ALL",
  "maxStorageCapacity": 4294967296,
  "qdnPushOnPublishEnabled": false,
  "directDataRetrievalEnabled": false,
  "autoUpdateMode": "OFF",
  "autoRestartEnabled": false,
  "archiveEnabled": false,
  "archiveServingEnabled": false,
  "dbCacheEnabled": false,
  "balanceRecorderEnabled": false,
  "rebuildArbitraryResourceCacheTaskEnabled": false,
  "gatewayEnabled": false,
  "domainMapEnabled": false,
  "allowedTransports": ["IP"],
  "uPnPEnabled": false,
  "minBlockchainPeers": 0,
  "minOutboundPeers": 0,
  "maxPeers": 1,
  "maxDataPeers": 1,
  "minDataPeers": 1,
  "maxNetworkThreadPoolSize": 8,
  "networkPoWComputePoolSize": 1,
  "initialPeers": [],
  "initialDataPeers": [],
  "fixedNetwork": [],
  "pirateChainNet": "REGTEST",
  "arrrDefaultBirthday": 152850,
  "pirateChainWalletUnified": $unified_enabled,
  "pirateChainWalletQdnSignature": "$signature",
  "pirateChainWalletDebugLogging": false,
  "wallets": {
    "BTC": false, "BCH": false, "LTC": false, "DOGE": false,
    "DGB": false, "RVN": false, "DASH": false, "PPC": false,
    "NMC": false, "FIRO": false, "KMD": false, "VRSC": false,
    "ZEC": false, "LBC": false, "XVG": false, "ARRR": true
  }
}
EOF
}
write_settings "$runtime/settings-legacy.json" false
write_settings "$runtime/settings-unified.json" true

result_file=$work_directory/result.properties
harness_log=$work_directory/harness.log
set +e
unshare -Urn "$script_directory/$(basename "$0")" --inside-network-namespace \
	"$runtime" "$jar" "$helper_classes" "$signature" "$result_file" \
	> "$harness_log" 2>&1
acceptance_status=$?
set -e

source_preserved=PASS
if [ "$(file_sha256 "$legacy_wallet_source")" != "$legacy_source_sha256" ]; then
	source_preserved=FAIL
fi

secret_scan=PASS
entropy=$(cat "$runtime/entropy.txt")
for scan_file in "$work_directory/javac.log" "$harness_log" \
		"$runtime"/core-start-*.log "$runtime/lightwalletd.log" \
		"$runtime"/status-*.json "$runtime"/set-server-*.json "$runtime"/lightwalletd*.audit \
		"$runtime"/wallet-address-*.txt \
		"$runtime"/wallets/PirateChain/unified/*/qortium-wallet-state.json; do
	[ -f "$scan_file" ] || continue
	set +e
	grep -E '"(seedPhrase|private_key|seed|address)"[[:space:]]*:|zs1[[:alnum:]]{60,}' \
		"$scan_file" >/dev/null 2>&1
	secret_pattern_status=$?
	grep -F "$entropy" "$scan_file" >/dev/null 2>&1
	entropy_status=$?
	grep -F "$api_key" "$scan_file" >/dev/null 2>&1
	api_key_status=$?
	set -e
	case $secret_pattern_status:$entropy_status:$api_key_status in
		1:1:1) ;;
		0:*|*:0:*|*:*:0) secret_scan=FAIL; break ;;
		*) secret_scan=ERROR; break ;;
	esac
done
api_key=
entropy=

result=FAIL
network_result=NOT_PROVEN
fixture_result=NOT_PROVEN
legacy_start=NOT_PROVEN
migration_start=NOT_PROVEN
ready_start=NOT_PROVEN
namespace_result=NOT_PROVEN
legacy_file_result=NOT_PROVEN
packaged_starts=UNKNOWN
forbidden_rpcs=UNKNOWN
unexpected_rpcs=UNKNOWN
shutdowns=UNKNOWN
non_loopback_interfaces=UNKNOWN
default_routes=UNKNOWN
non_loopback_routes=UNKNOWN
if [ -f "$result_file" ]; then
	network_result=$(property networkEgress "$result_file" 2>/dev/null || printf 'NOT_PROVEN')
	fixture_result=$(property loopbackFixture "$result_file" 2>/dev/null || printf 'NOT_PROVEN')
	legacy_start=$(property start1 "$result_file" 2>/dev/null || printf 'NOT_PROVEN')
	migration_start=$(property start2 "$result_file" 2>/dev/null || printf 'NOT_PROVEN')
	ready_start=$(property start3 "$result_file" 2>/dev/null || printf 'NOT_PROVEN')
	namespace_result=$(property namespaceContinuity "$result_file" 2>/dev/null || printf 'NOT_PROVEN')
	legacy_file_result=$(property legacyFilePreserved "$result_file" 2>/dev/null || printf 'NOT_PROVEN')
	packaged_starts=$(property packagedStarts "$result_file" 2>/dev/null || printf 'UNKNOWN')
	forbidden_rpcs=$(property forbiddenTransactionRpcs "$result_file" 2>/dev/null || printf 'UNKNOWN')
	unexpected_rpcs=$(property unexpectedRpcs "$result_file" 2>/dev/null || printf 'UNKNOWN')
	shutdowns=$(property gracefulShutdowns "$result_file" 2>/dev/null || printf 'UNKNOWN')
	non_loopback_interfaces=$(property nonLoopbackInterfaces "$result_file" 2>/dev/null || printf 'UNKNOWN')
	default_routes=$(property defaultRoutes "$result_file" 2>/dev/null || printf 'UNKNOWN')
	non_loopback_routes=$(property nonLoopbackRoutes "$result_file" 2>/dev/null || printf 'UNKNOWN')
	if [ "$acceptance_status" -eq 0 ] && [ "$secret_scan" = PASS ] \
			&& [ "$source_preserved" = PASS ] \
			&& [ "$(property result "$result_file" 2>/dev/null || true)" = PASS ]; then
		result=PASS
	fi
fi

core_commit=$(cd "$repository" && git rev-parse HEAD)
tree_state=clean
if [ -n "$(cd "$repository" && git status --porcelain)" ]; then
	tree_state=dirty
fi
jar_sha256=$(file_sha256 "$jar")
timestamp=$(date -u '+%Y-%m-%dT%H:%M:%SZ')
host=$(uname -srm)
temporary_receipt=$work_directory/receipt.md
temporary_log=$work_directory/sanitized.log
{
	printf '%s\n' 'Raw Core, JNI, fixture, API, and wallet-state evidence is intentionally not retained.'
	printf 'Harness exit: %s\n' "$acceptance_status"
	printf 'Secret scan: %s\n' "$secret_scan"
	printf 'Protected source preservation: %s\n' "$source_preserved"
	printf '%s\n' 'Safe diagnostics follow:'
	sed -n '1,120p' "$harness_log"
} > "$temporary_log"
{
	printf '# Pirate Unified packaged real-v8 migration acceptance receipt\n\n'
	printf '%s `%s`\n' '- Timestamp:' "$timestamp"
	printf '%s `%s`\n' '- Result:' "$result"
	printf '%s `%s`\n' '- Core source commit:' "$core_commit"
	printf '%s `%s`\n' '- Core source tree:' "$tree_state"
	printf '%s `%s`\n' '- Host:' "$host"
	printf '%s `%s`\n' '- Packaged Core JAR SHA-256:' "$jar_sha256"
	printf '%s `%s`\n' '- Unified bundle manifest SHA-256:' "$manifest_sha256"
	printf '%s `%s`\n' '- Legacy Linux JNI SHA-256:' "$LEGACY_LINUX_SHA256"
	printf '%s `%s`\n' '- Protected legacy source SHA-256:' "$legacy_source_sha256"
	printf '%s `%s`\n' '- Normalized command:' 'tools/run-pirate-unified-packaged-real-legacy-migration-acceptance.sh <absolute-packaged-core.jar> <absolute-unified-staged-bundle> <absolute-local-qdn-fixture> <absolute-legacy-bundle> <absolute-protected-v8-metadata.json> <new-receipt.md>'
	printf '%s `%s`\n' '- Test counts:' 'Maven tests N/A; 11 scripted migration boundaries'
	printf '%s `%s`\n' '- Sanitized log:' "$receipt.log"
	printf '\n## Results\n\n'
	printf '| Boundary | Result | Evidence |\n'
	printf '|---|---:|---|\n'
	printf '| Network egress | %s | rootless namespace; non-loopback interfaces `%s`; default routes `%s`; non-loopback routes `%s` |\n' "$network_result" "$non_loopback_interfaces" "$default_routes" "$non_loopback_routes"
	printf '| Loopback lightwalletd | %s | fixed IPv4 plaintext endpoint; Java service `regtest`, native Pirate service `main` |\n' "$fixture_result"
	printf '| Real v8 legacy start | %s | packaged Core mapped the reviewed legacy JNI, decoded serialization v8, reached READY, and persisted `LEGACY` identity without a Unified registry |\n' "$legacy_start"
	printf '| First Unified start | %s | packaged Core mapped the authenticated pinned QDN library, matched legacy identity, synchronized, and persisted validated `MIGRATING` |\n' "$migration_start"
	printf '| Unified reopen | %s | a clean third Core process reopened the same registry and promoted it to `UNIFIED_READY` |\n' "$ready_start"
	printf '| Exact start count | %s | required exactly `3` packaged Core starts |\n' "$packaged_starts"
	printf '| Namespace and identity continuity | %s | one namespace, one one-way identity hash, and one wallet-address hash across all phases |\n' "$namespace_result"
	printf '| Legacy wallet preservation | %s | runtime legacy file remained present; protected source hash remained exact (`%s`) |\n' "$legacy_file_result" "$source_preserved"
	printf '| Unfunded/no transaction RPCs | %s | forbidden transaction RPC count `%s`; unexpected RPC count `%s` |\n' "$( [ "$forbidden_rpcs" = 0 ] && printf PASS || printf NOT_PROVEN )" "$forbidden_rpcs" "$unexpected_rpcs"
	printf '| Graceful shutdowns | %s | required `3` packaged Core shutdown confirmations |\n' "$shutdowns"
	printf '| Secret scan | %s | raw outputs checked before deletion; none retained |\n' "$secret_scan"
	if [ "$result" = PASS ]; then
		printf '\nThis receipt proves one unfunded Linux x86_64 packaged migration from an encrypted real Qortal-format serialization-v8 fixture through Core `LEGACY` → `MIGRATING` → `UNIFIED_READY`, with authenticated pinned-bundle loading and deterministic loopback synchronization. It does not prove arbitrary foreign-wallet password extraction, real-network interoperability, funded behavior, QDN publication, deployment, or Home behavior.\n'
	else
		printf '\nThis failed receipt proves only the individual boundaries marked `PASS`; it does not establish a complete packaged migration. Raw evidence was still scanned and deleted.\n'
	fi
} > "$temporary_receipt"

mv "$temporary_log" "$receipt.log"
mv "$temporary_receipt" "$receipt"
trap - 0 HUP INT TERM
cleanup_outer
work_directory=
if [ "$acceptance_status" -ne 0 ] || [ "$result" != PASS ]; then
	printf 'Packaged real legacy migration acceptance failed; receipt: %s, log: %s\n' "$receipt" "$receipt.log" >&2
	exit 1
fi
printf 'Packaged real legacy migration acceptance passed; receipt: %s, log: %s\n' "$receipt" "$receipt.log"
