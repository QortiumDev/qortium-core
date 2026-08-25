#!/bin/sh
set -eu
umask 077

# Starts one separately packaged Core JAR exactly twice against the retained
# local-QDN bundle and a test-only loopback lightwalletd. It creates no funded
# transaction, publishes nothing, and runs without a non-loopback route.

usage() {
	printf '%s\n' "Usage: $0 <absolute-packaged-core.jar> <absolute-staged-bundle-directory> <absolute-local-qdn-fixture-directory> <new-receipt.md> [--cutover]" >&2
}

property() {
	key=$1
	file=$2
	count=$(awk -F= -v key="$key" '$1 == key { count++ } END { print count + 0 }' "$file")
	[ "$count" -eq 1 ] || return 1
	awk -F= -v key="$key" '$1 == key { print substr($0, length(key) + 2) }' "$file"
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

state_field() {
	field=$1
	state_file=$2
	sed -n "s/.*\"$field\"[[:space:]]*:[[:space:]]*\"\{0,1\}\([^\",}]*\).*/\1/p" "$state_file" \
		| sed -n '1p'
}

run_inside_namespace() {
	runtime=$1
	jar=$2
	helper_classes=$3
	signature=$4
	result_file=$5
	mode=$6
	cutover_mode=false
	if [ "$mode" = '--cutover' ]; then
		cutover_mode=true
	elif [ -n "$mode" ]; then
		printf 'Unsupported packaged lifecycle mode: %s\n' "$mode" >&2
		return 2
	fi
	core_pid=
	fixture_pid=
	start_count=0
	api_port=62391
	expected_library=$runtime/wallets/PirateChain/lib/$signature/librust-linux-x86_64.so
	unified_root=$runtime/wallets/PirateChain/unified

	# Invoked indirectly by traps; ShellCheck cannot follow those call edges.
	# shellcheck disable=SC2317
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
	# shellcheck disable=SC2317
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

	# Keep any library-default relative files inside disposable runtime storage.
	# The isolated shell expands its own positional parameters.
	# shellcheck disable=SC2016
	if [ "$cutover_mode" = true ]; then
		setsid sh -c 'cd "$1" && exec java -Djava.awt.headless=true -cp "$2:$3" org.qortium.controller.PirateUnifiedLoopbackLightwalletdMain "$4" "$5" "$6" cutover' \
			sh "$runtime" "$helper_classes" "$jar" "$runtime/lightwalletd.ready" \
			"$runtime/lightwalletd-a.audit" "$runtime/lightwalletd-b.audit" \
			> "$runtime/lightwalletd.log" 2>&1 &
	else
		setsid sh -c 'cd "$1" && exec java -Djava.awt.headless=true -cp "$2:$3" org.qortium.controller.PirateUnifiedLoopbackLightwalletdMain "$4" "$5"' \
			sh "$runtime" "$helper_classes" "$jar" "$runtime/lightwalletd.ready" "$runtime/lightwalletd.audit" \
			> "$runtime/lightwalletd.log" 2>&1 &
	fi
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
			|| [ "$(property javaChainName "$runtime/lightwalletd.ready" 2>/dev/null || true)" != regtest ] \
			|| [ "$(property nativeChainName "$runtime/lightwalletd.ready" 2>/dev/null || true)" != main ]; then
		printf '%s\n' 'Loopback lightwalletd did not prove its fixed dual-service endpoint' >&2
		return 1
	fi
	if [ "$cutover_mode" = true ]; then
		if [ "$(property mode "$runtime/lightwalletd.ready" 2>/dev/null || true)" != cutover ] \
				|| [ "$(property portA "$runtime/lightwalletd.ready" 2>/dev/null || true)" != 9067 ] \
				|| [ "$(property tipA "$runtime/lightwalletd.ready" 2>/dev/null || true)" != 152858 ] \
				|| [ "$(property portB "$runtime/lightwalletd.ready" 2>/dev/null || true)" != 9068 ] \
				|| [ "$(property tipB "$runtime/lightwalletd.ready" 2>/dev/null || true)" != 152862 ]; then
			printf '%s\n' 'Loopback fixture pair did not prove the fixed A/B cutover contract' >&2
			return 1
		fi
	elif [ "$(property port "$runtime/lightwalletd.ready" 2>/dev/null || true)" != 9067 ]; then
		printf '%s\n' 'Loopback lightwalletd did not prove its fixed single endpoint' >&2
		return 1
	fi
	printf 'loopbackFixture=PASS\n' >> "$result_file"

	set_current_server() {
		server_port=$1
		response_file=$2
		payload_file=$runtime/server-$server_port.json
		printf '{"hostName":"127.0.0.1","port":%s,"connectionType":"TCP"}\n' \
			"$server_port" > "$payload_file"
		curl --fail --silent --show-error --max-time 30 \
				--config "$runtime/curl-json-api.conf" \
				--data-binary "@$payload_file" \
				"http://127.0.0.1:$api_port/crosschain/arrr/setcurrentserver" \
				> "$response_file" 2>/dev/null \
			&& grep -Eq '"success"[[:space:]]*:[[:space:]]*true' "$response_file" \
			&& grep -Eq '"port"[[:space:]]*:[[:space:]]*'"$server_port" "$response_file"
	}

	wallet_address_sha256() {
		wallet_address=$(curl --fail --silent --show-error --max-time 30 \
				--config "$runtime/curl-api.conf" \
				--data-binary "@$runtime/entropy.txt" \
				"http://127.0.0.1:$api_port/crosschain/arrr/walletaddress" 2>/dev/null) || return 1
		case $wallet_address in
			zs[1-9A-HJ-NP-Za-km-z]*) ;;
			*) wallet_address=; return 1 ;;
		esac
		wallet_address_hash_line=$(printf '%s' "$wallet_address" | sha256sum)
		wallet_address=
		printf '%s\n' "${wallet_address_hash_line%% *}"
	}

	assert_a_barrier() {
		observed_a_count=$(property nativeRpcCount "$runtime/lightwalletd-a.audit" 2>/dev/null || true)
		case $observed_a_count in
			''|*[!0-9]*) return 1 ;;
		esac
		[ -n "$server_a_barrier" ] && [ "$observed_a_count" -eq "$server_a_barrier" ]
	}

	run_core_start() {
		start_number=$1
		expected_state=$2
		core_log=$runtime/core-start-$start_number.log
		status_response=$runtime/status-start-$start_number.json
		start_count=$((start_count + 1))
		# The isolated shell expands its own positional parameters.
		# shellcheck disable=SC2016
		setsid sh -c 'cd "$1" && exec java -Djava.awt.headless=true -Dlog4j.configurationFile="$1/log4j2-acceptance.properties" -jar "$2" "$1/settings.json"' \
			sh "$runtime" "$jar" > "$core_log" 2>&1 &
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
		if [ "$api_ready" != true ]; then
			printf 'Timed out waiting for packaged Core start %s API\n' "$start_number" >&2
			return 1
		fi
		if [ "$cutover_mode" = true ] && [ "$start_number" -eq 1 ]; then
			if ! set_current_server 9067 "$runtime/set-server-a.json"; then
				printf '%s\n' 'Packaged Core did not select server A before first wallet initialization' >&2
				return 1
			fi
		fi

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
		if [ "$ready" != true ]; then
			printf 'Packaged Core start %s did not reach READY\n' "$start_number" >&2
			return 1
		fi

		if [ ! -d "$unified_root" ] || [ -L "$unified_root" ]; then
			printf 'Packaged Core start %s created no valid Unified root\n' "$start_number" >&2
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
		persisted_state_ready=false
		wait_count=0
		while [ "$wait_count" -lt 90 ]; do
			if ! kill -0 "$core_pid" 2>/dev/null; then
				printf 'Packaged Core start %s exited before durable wallet state\n' "$start_number" >&2
				return 1
			fi
			if [ -f "$state_path" ] && [ ! -L "$state_path" ] \
					&& [ -f "$registry_path" ] && [ ! -L "$registry_path" ] \
					&& grep -Eq '"state"[[:space:]]*:[[:space:]]*"'"$expected_state"'"' "$state_path" \
					&& grep -Eq '"syncValidated"[[:space:]]*:[[:space:]]*true' "$state_path" \
					&& grep -Eq '"identityHash"[[:space:]]*:[[:space:]]*"[1-9A-HJ-NP-Za-km-z]+"' "$state_path"; then
				persisted_state_ready=true
				break
			fi
			sleep 1
			wait_count=$((wait_count + 1))
		done
		if [ "$persisted_state_ready" != true ]; then
			printf 'Packaged Core start %s did not persist expected %s state\n' "$start_number" "$expected_state" >&2
			return 1
		fi
		identity_hash=$(state_field identityHash "$state_path")
		if [ -z "$identity_hash" ]; then
			printf 'Packaged Core start %s state identity could not be parsed\n' "$start_number" >&2
			return 1
		fi
		address_sha256=$(wallet_address_sha256) || {
			printf 'Packaged Core start %s did not expose a valid wallet address\n' "$start_number" >&2
			return 1
		}
		if [ "$start_number" -eq 1 ]; then
			first_namespace=$namespace
			first_identity_hash=$identity_hash
			first_address_sha256=$address_sha256
		else
			if [ "$namespace" != "$first_namespace" ] || [ "$identity_hash" != "$first_identity_hash" ] \
					|| [ "$address_sha256" != "$first_address_sha256" ]; then
				printf '%s\n' 'Wallet namespace or identity changed across packaged restart' >&2
				return 1
			fi
		fi
		address_sha256=

		if [ "$cutover_mode" = true ]; then
			selected_server_uri=$(state_field selectedServerUri "$state_path")
			if [ "$start_number" -eq 1 ]; then
				if [ "$selected_server_uri" != 'http://127.0.0.1:9067' ]; then
					printf 'First packaged start did not synchronize on server A: %s\n' "$selected_server_uri" >&2
					return 1
				fi
				# Flush every native A RPC preceding the explicit Java B-selection boundary.
				sleep 1
				server_a_barrier=$(property nativeRpcCount "$runtime/lightwalletd-a.audit" 2>/dev/null || true)
				case $server_a_barrier in
					''|*[!0-9]*) printf '%s\n' 'Live server A cutover audit counter is invalid' >&2; return 1 ;;
				esac
				if ! set_current_server 9068 "$runtime/set-server-b.json"; then
					printf '%s\n' 'Packaged Core did not accept the Java selection of server B' >&2
					return 1
				fi
				applied_address_sha256=$(wallet_address_sha256) || {
					printf '%s\n' 'Packaged Core did not apply server B through a wallet operation' >&2
					return 1
				}
				if [ "$applied_address_sha256" != "$first_address_sha256" ] \
						|| [ "$(state_field selectedServerUri "$state_path")" != 'http://127.0.0.1:9068' ]; then
					printf '%s\n' 'Wallet identity or persisted endpoint changed during native server B application' >&2
					return 1
				fi
				applied_address_sha256=
				if ! assert_a_barrier; then
					printf '%s\n' 'Server A received native traffic during native server B application' >&2
					return 1
				fi

				cutover_ready=false
				wait_count=0
				while [ "$wait_count" -lt 300 ]; do
					if ! kill -0 "$core_pid" 2>/dev/null; then
						printf '%s\n' 'First packaged Core exited during endpoint cutover' >&2
						return 1
					fi
					b_tip_ranges=$(property pirateTipRanges "$runtime/lightwalletd-b.audit" 2>/dev/null || true)
					b_tip_blocks=$(property pirateTipBlocks "$runtime/lightwalletd-b.audit" 2>/dev/null || true)
					b_native_count=$(property nativeRpcCount "$runtime/lightwalletd-b.audit" 2>/dev/null || true)
					case $b_tip_ranges:$b_tip_blocks:$b_native_count in
						*[!0-9:]*|*::*|:*) b_tip_evidence=false ;;
						*)
							b_tip_evidence=false
							if [ "$b_tip_ranges" -gt 0 ] || [ "$b_tip_blocks" -gt 0 ]; then
								b_tip_evidence=true
							fi
							;;
					esac
					if ! assert_a_barrier; then
						printf '%s\n' 'Server A received native traffic after the confirmed B barrier' >&2
						return 1
					fi
					if curl --fail --silent --show-error --max-time 15 \
							--config "$runtime/curl-api.conf" \
							--data-binary "@$runtime/entropy.txt" \
							"http://127.0.0.1:$api_port/crosschain/arrr/syncstatus?json=true" \
							> "$runtime/status-cutover.json" 2>/dev/null \
							&& grep -Eq '"state"[[:space:]]*:[[:space:]]*"READY"' "$runtime/status-cutover.json" \
							&& grep -Eq '"restartRequired"[[:space:]]*:[[:space:]]*false' "$runtime/status-cutover.json" \
							&& [ "$(state_field selectedServerUri "$state_path")" = 'http://127.0.0.1:9068' ] \
							&& [ "$(state_field identityHash "$state_path")" = "$first_identity_hash" ] \
							&& [ "$b_tip_evidence" = true ] && [ "$b_native_count" -gt 0 ]; then
						cutover_ready=true
						break
					fi
					sleep 1
					wait_count=$((wait_count + 1))
				done
				if [ "$cutover_ready" != true ]; then
					cutover_state=$(state_field state "$runtime/status-cutover.json" 2>/dev/null || true)
					cutover_restart=$(state_field restartRequired "$runtime/status-cutover.json" 2>/dev/null || true)
					cutover_selected=$(state_field selectedServerUri "$state_path" 2>/dev/null || true)
					cutover_identity=$(state_field identityHash "$state_path" 2>/dev/null || true)
					identity_continuity=FAIL
					[ "$cutover_identity" = "$first_identity_hash" ] && identity_continuity=PASS
					printf 'First packaged Core did not complete server B sync: state=%s restart=%s selected=%s identity=%s B-ranges=%s B-tip-blocks=%s B-native=%s\n' \
						"${cutover_state:-UNKNOWN}" "${cutover_restart:-UNKNOWN}" \
						"${cutover_selected:-UNKNOWN}" "$identity_continuity" \
						"${b_tip_ranges:-UNKNOWN}" "${b_tip_blocks:-UNKNOWN}" \
						"${b_native_count:-UNKNOWN}" >&2
					return 1
				fi
				post_cutover_address_sha256=$(wallet_address_sha256) || return 1
				if [ "$post_cutover_address_sha256" != "$first_address_sha256" ]; then
					printf '%s\n' 'Wallet address changed during packaged endpoint cutover' >&2
					return 1
				fi
				post_cutover_address_sha256=
				first_b_native_rpc_count=$(property nativeRpcCount "$runtime/lightwalletd-b.audit" 2>/dev/null || true)
				case $first_b_native_rpc_count in
					''|*[!0-9]*) printf '%s\n' 'Live server B cutover audit counter is invalid' >&2; return 1 ;;
				esac
				printf 'endpointCutover=PASS\n' >> "$result_file"
			else
				if [ "$selected_server_uri" != 'http://127.0.0.1:9068' ] || ! assert_a_barrier; then
					printf '%s\n' 'Restarted packaged Core did not reopen server B without server A traffic' >&2
					return 1
				fi
				second_b_native_rpc_count=$(property nativeRpcCount "$runtime/lightwalletd-b.audit" 2>/dev/null || true)
				case $second_b_native_rpc_count in
					''|*[!0-9]*) printf '%s\n' 'Restarted server B audit count is invalid' >&2; return 1 ;;
				esac
				if [ "$second_b_native_rpc_count" -le "$first_b_native_rpc_count" ]; then
					printf '%s\n' 'Restarted packaged Core produced no new native server B evidence' >&2
					return 1
				fi
				printf 'coldRestartEndpoint=PASS\naddressContinuity=PASS\n' >> "$result_file"
			fi
		fi

		if [ ! -r "/proc/$core_pid/maps" ] \
				|| ! grep -F " $expected_library" "/proc/$core_pid/maps" >/dev/null 2>&1; then
			printf 'Packaged Core start %s did not map the QDN-derived native library\n' "$start_number" >&2
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
				|| ! grep -Eq '"syncValidated"[[:space:]]*:[[:space:]]*true' "$state_path"; then
			printf 'Packaged Core start %s changed persisted state during shutdown\n' "$start_number" >&2
			return 1
		fi
		if [ "$cutover_mode" = true ] && ! assert_a_barrier; then
			printf 'Packaged Core start %s contacted server A after the cutover barrier\n' "$start_number" >&2
			return 1
		fi
		printf 'start%s=PASS\n' "$start_number" >> "$result_file"
	}

	first_namespace=
	first_identity_hash=
	first_address_sha256=
	server_a_barrier=
	first_b_native_rpc_count=
	run_core_start 1 MIGRATING
	run_core_start 2 UNIFIED_READY
	if [ "$start_count" -ne 2 ]; then
		printf 'Expected exactly two packaged Core starts, observed %s\n' "$start_count" >&2
		return 1
	fi
	printf 'packagedStarts=2\nnamespaceContinuity=PASS\n' >> "$result_file"

	if ! terminate_group "$fixture_pid"; then
		printf '%s\n' 'Loopback lightwalletd process group did not terminate' >&2
		return 1
	fi
	fixture_pid=
	if [ "$cutover_mode" = true ]; then
		if [ ! -f "$runtime/lightwalletd-a.audit" ] || [ ! -f "$runtime/lightwalletd-b.audit" ] \
				|| [ "$(property result "$runtime/lightwalletd-a.audit" 2>/dev/null || true)" != PASS ] \
				|| [ "$(property result "$runtime/lightwalletd-b.audit" 2>/dev/null || true)" != PASS ] \
				|| ! assert_a_barrier; then
			printf '%s\n' 'Loopback fixture pair did not retain a complete cutover audit' >&2
			return 1
		fi
		a_tip_ranges=$(property pirateTipRanges "$runtime/lightwalletd-a.audit")
		b_tip_ranges=$(property pirateTipRanges "$runtime/lightwalletd-b.audit")
		a_tip_blocks=$(property pirateTipBlocks "$runtime/lightwalletd-a.audit")
		b_tip_blocks=$(property pirateTipBlocks "$runtime/lightwalletd-b.audit")
		a_tip_height=$(property tipHeight "$runtime/lightwalletd-a.audit")
		b_tip_height=$(property tipHeight "$runtime/lightwalletd-b.audit")
		a_tip_requests=$(property pirateTipRequests "$runtime/lightwalletd-a.audit")
		b_tip_requests=$(property pirateTipRequests "$runtime/lightwalletd-b.audit")
		a_scanned_blocks=$(property pirateScannedBlocks "$runtime/lightwalletd-a.audit")
		b_scanned_blocks=$(property pirateScannedBlocks "$runtime/lightwalletd-b.audit")
		a_forbidden_rpcs=$(property forbiddenRpcs "$runtime/lightwalletd-a.audit")
		b_forbidden_rpcs=$(property forbiddenRpcs "$runtime/lightwalletd-b.audit")
		a_unexpected_rpcs=$(property unexpectedRpcs "$runtime/lightwalletd-a.audit")
		b_unexpected_rpcs=$(property unexpectedRpcs "$runtime/lightwalletd-b.audit")
		a_subtree_probes=$(property subtreeProbes "$runtime/lightwalletd-a.audit")
		b_subtree_probes=$(property subtreeProbes "$runtime/lightwalletd-b.audit")
		dual_audit_counts=$a_tip_ranges:$b_tip_ranges:$a_tip_blocks:$b_tip_blocks:$a_tip_height:$b_tip_height:$a_tip_requests:$b_tip_requests:$a_scanned_blocks:$b_scanned_blocks:$a_forbidden_rpcs:$b_forbidden_rpcs:$a_unexpected_rpcs:$b_unexpected_rpcs:$a_subtree_probes:$b_subtree_probes
		case $dual_audit_counts in
			*[!0-9:]*|*::*|:*) printf '%s\n' 'Loopback cutover audit contains invalid counts' >&2; return 1 ;;
		esac
		pirate_tip_requests=$((a_tip_requests + b_tip_requests))
		pirate_tip_ranges=$((a_tip_ranges + b_tip_ranges))
		pirate_tip_blocks=$((a_tip_blocks + b_tip_blocks))
		pirate_scanned_blocks=$((a_scanned_blocks + b_scanned_blocks))
		forbidden_rpcs=$((a_forbidden_rpcs + b_forbidden_rpcs))
		unexpected_rpcs=$((a_unexpected_rpcs + b_unexpected_rpcs))
		subtree_probes=$((a_subtree_probes + b_subtree_probes))
		if { [ "$a_tip_ranges" -lt 1 ] && [ "$a_tip_blocks" -lt 1 ]; } \
				|| { [ "$b_tip_ranges" -lt 1 ] && [ "$b_tip_blocks" -lt 1 ]; } \
				|| [ "$a_tip_height" -ne 152858 ] \
				|| [ "$b_tip_height" -ne 152862 ]; then
			printf 'Loopback cutover sync evidence is incomplete: A-ranges=%s A-tip-blocks=%s A-tip=%s B-ranges=%s B-tip-blocks=%s B-tip=%s\n' \
				"$a_tip_ranges" "$a_tip_blocks" "$a_tip_height" "$b_tip_ranges" \
				"$b_tip_blocks" "$b_tip_height" >&2
			return 1
		fi
		printf 'noNativeServerAAfterSelectionBarrier=PASS\n' >> "$result_file"
	else
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
	fi
	case $pirate_tip_requests:$pirate_tip_ranges:$pirate_tip_blocks:$pirate_scanned_blocks:$forbidden_rpcs:$unexpected_rpcs:$subtree_probes in
		*[!0-9:]*|*::*|:*) printf '%s\n' 'Loopback lightwalletd audit contains invalid counts' >&2; return 1 ;;
	esac
	if [ "$pirate_tip_requests" -lt 1 ] \
			|| { [ "$pirate_tip_ranges" -lt 1 ] && [ "$pirate_tip_blocks" -lt 1 ]; } \
			|| [ "$forbidden_rpcs" -ne 0 ] || [ "$unexpected_rpcs" -ne 0 ] \
			|| [ "$subtree_probes" -lt 1 ]; then
		printf 'Loopback lightwalletd audit mismatch: tip=%s ranges=%s tip-blocks=%s blocks=%s forbidden=%s unexpected=%s subtree=%s\n' \
			"$pirate_tip_requests" "$pirate_tip_ranges" "$pirate_tip_blocks" "$pirate_scanned_blocks" \
			"$forbidden_rpcs" "$unexpected_rpcs" "$subtree_probes" >&2
		return 1
	fi
	{
		printf 'birthdayRange=PASS\n'
		printf 'forbiddenTransactionRpcs=%s\n' "$forbidden_rpcs"
		printf 'unexpectedRpcs=%s\n' "$unexpected_rpcs"
		printf 'gracefulShutdowns=2\n'
		printf 'result=PASS\n'
	} >> "$result_file"
	trap - 0 HUP INT TERM
	return 0
}

if [ "${1:-}" = '--inside-network-namespace' ]; then
	[ "$#" -eq 7 ] || exit 2
	shift
	run_inside_namespace "$@"
	exit $?
fi

if [ "$#" -lt 4 ] || [ "$#" -gt 5 ]; then
	usage
	exit 2
fi

jar=$1
bundle=$2
fixture=$3
receipt=$4
mode=${5:-}
if [ -n "$mode" ] && [ "$mode" != '--cutover' ]; then
	usage
	exit 2
fi
require_absolute 'Packaged JAR' "$jar"
require_absolute 'Bundle' "$bundle"
require_absolute 'Fixture' "$fixture"
require_absolute 'Receipt' "$receipt"
case "$(uname -s):$(uname -m)" in
	Linux:x86_64) ;;
	*) printf '%s\n' 'Packaged lifecycle acceptance currently supports Linux x86_64 only' >&2; exit 1 ;;
esac
for command_name in awk cmp cp curl find grep ip java javac od ps sed setsid sha256sum tr unshare wc; do
	require_command "$command_name"
done

if [ ! -f "$jar" ] || [ -L "$jar" ] \
		|| [ ! -d "$bundle" ] || [ -L "$bundle" ] \
		|| [ ! -d "$fixture" ] || [ -L "$fixture" ]; then
	printf '%s\n' 'Packaged lifecycle inputs must be regular non-symlink files/directories' >&2
	exit 1
fi

script_directory=$(CDPATH='' cd "$(dirname "$0")" && pwd)
repository=$(CDPATH='' cd "$script_directory/.." && pwd)
test_chain=$repository/src/test/resources/test-chain-v2.json
fixture_source=$repository/src/test/java/org/qortium/controller/PirateUnifiedLoopbackLightwalletd.java
fixture_main_source=$repository/src/test/java/org/qortium/controller/PirateUnifiedLoopbackLightwalletdMain.java
fixture_properties=$fixture/fixture.properties
manifest=$bundle/QORTIUM-MANIFEST.txt
if [ ! -f "$test_chain" ] || [ ! -f "$fixture_source" ] || [ ! -f "$fixture_main_source" ] \
		|| [ ! -f "$fixture_properties" ] || [ ! -f "$manifest" ] \
		|| [ ! -f "$fixture/repository/blockchain.properties" ] || [ ! -d "$fixture/data" ]; then
	printf '%s\n' 'Packaged lifecycle inputs are incomplete' >&2
	exit 1
fi

format=$(property format "$fixture_properties") || exit 1
signature=$(property signature "$fixture_properties") || exit 1
fixture_manifest_sha256=$(property bundleManifestSha256 "$fixture_properties") || exit 1
transaction_state=$(property transactionState "$fixture_properties") || exit 1
manifest_hash_line=$(sha256sum "$manifest")
manifest_sha256=${manifest_hash_line%% *}
if [ "$format" != qortium-pirate-unified-local-qdn-fixture-v2 ] \
		|| [ "$transaction_state" != synthetic-direct-repository-row ] \
		|| ! printf '%s\n' "$signature" | grep -Eq '^[1-9A-HJ-NP-Za-km-z]{80,100}$' \
		|| [ "$manifest_sha256" != "$fixture_manifest_sha256" ]; then
	printf '%s\n' 'Local-QDN fixture provenance contract is invalid' >&2
	exit 1
fi
for expected_property in \
	'service=ARBITRARY_DATA' \
	'repositoryPath=repository' \
	'dataPath=data' \
	'arbitraryResourceCacheReady=true' \
	'unconfirmedPoolEntry=false' \
	'blockHeight=null'; do
	expected_key=${expected_property%%=*}
	expected_value=${expected_property#*=}
	if [ "$(property "$expected_key" "$fixture_properties" 2>/dev/null || true)" != "$expected_value" ]; then
		printf 'Fixture property is missing, duplicated, or invalid: %s\n' "$expected_property" >&2
		exit 1
	fi
done

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
	printf 'Another packaged lifecycle run owns this receipt: %s\n' "$receipt" >&2
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
work_directory=$(mktemp -d "$receipt_parent/.pirate-unified-packaged-lifecycle.XXXXXX")
helper_classes=$work_directory/helper-classes
mkdir -p "$helper_classes"
if ! javac -cp "$jar" -d "$helper_classes" "$fixture_source" "$fixture_main_source" \
		> "$work_directory/javac.log" 2>&1; then
	printf '%s\n' 'Could not compile the test-only loopback fixture against the packaged JAR' >&2
	exit 1
fi

runtime=$work_directory/runtime
mkdir -p "$runtime/repository" "$runtime/data" "$runtime/temp" "$runtime/api" \
	"$runtime/lists" "$runtime/export"
cp -a --reflink=auto "$fixture/repository/." "$runtime/repository/"
cp -a --reflink=auto "$fixture/data/." "$runtime/data/"
cp "$test_chain" "$runtime/test-chain-v2.json"
api_key=$(od -An -N32 -tx1 /dev/urandom | tr -d ' \n')
entropy='5oSXF53qENtdUyKhqSxYzP57m6RhVFP9BJKRr9E5kRGV'
printf '%s' "$api_key" > "$runtime/api/apikey.txt"
printf '%s\n' "header = \"X-API-KEY: $api_key\"" \
	'header = "Content-Type: text/plain"' > "$runtime/curl-api.conf"
printf '%s\n' "header = \"X-API-KEY: $api_key\"" \
	'header = "Content-Type: application/json"' > "$runtime/curl-json-api.conf"
printf '%s' "$entropy" > "$runtime/entropy.txt"
chmod 600 "$runtime/api/apikey.txt" "$runtime/curl-api.conf" "$runtime/curl-json-api.conf" \
	"$runtime/entropy.txt"

cat > "$runtime/log4j2-acceptance.properties" <<'EOF'
rootLogger.level = info
rootLogger.appenderRef.console.ref = stdout
appender.console.type = Console
appender.console.name = stdout
appender.console.layout.type = PatternLayout
appender.console.layout.pattern = %d{yyyy-MM-dd HH:mm:ss} %-5p %c{1}:%L - %m%n
EOF

cat > "$runtime/settings.json" <<EOF
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
  "arrrDefaultBirthday": 152855,
  "pirateChainWalletUnified": true,
  "pirateChainWalletQdnSignature": "$signature",
  "pirateChainWalletDebugLogging": false,
  "wallets": {
    "BTC": false,
    "BCH": false,
    "LTC": false,
    "DOGE": false,
    "DGB": false,
    "RVN": false,
    "DASH": false,
    "PPC": false,
    "NMC": false,
    "FIRO": false,
    "KMD": false,
    "VRSC": false,
    "ZEC": false,
    "LBC": false,
    "XVG": false,
    "ARRR": true
  }
}
EOF

result_file=$work_directory/result.properties
harness_log=$work_directory/harness.log
set +e
unshare -Urn "$script_directory/$(basename "$0")" --inside-network-namespace \
	"$runtime" "$jar" "$helper_classes" "$signature" "$result_file" "$mode" \
	> "$harness_log" 2>&1
acceptance_status=$?
set -e

secret_scan=PASS
for scan_file in "$work_directory/javac.log" "$harness_log" \
		"$runtime"/core-start-*.log "$runtime/lightwalletd.log" \
		"$runtime"/status-*.json "$runtime"/set-server-*.json "$runtime"/lightwalletd*.audit \
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
first_start=NOT_PROVEN
second_start=NOT_PROVEN
namespace_result=NOT_PROVEN
birthday_result=NOT_PROVEN
packaged_starts=UNKNOWN
forbidden_rpcs=UNKNOWN
unexpected_rpcs=UNKNOWN
shutdowns=UNKNOWN
non_loopback_interfaces=UNKNOWN
default_routes=UNKNOWN
non_loopback_routes=UNKNOWN
endpoint_cutover=NOT_APPLICABLE
cold_restart_endpoint=NOT_APPLICABLE
address_continuity=NOT_APPLICABLE
no_native_server_a_after_selection_barrier=NOT_APPLICABLE
if [ -f "$result_file" ]; then
	network_result=$(property networkEgress "$result_file" 2>/dev/null || printf 'NOT_PROVEN')
	fixture_result=$(property loopbackFixture "$result_file" 2>/dev/null || printf 'NOT_PROVEN')
	first_start=$(property start1 "$result_file" 2>/dev/null || printf 'NOT_PROVEN')
	second_start=$(property start2 "$result_file" 2>/dev/null || printf 'NOT_PROVEN')
	namespace_result=$(property namespaceContinuity "$result_file" 2>/dev/null || printf 'NOT_PROVEN')
	birthday_result=$(property birthdayRange "$result_file" 2>/dev/null || printf 'NOT_PROVEN')
	packaged_starts=$(property packagedStarts "$result_file" 2>/dev/null || printf 'UNKNOWN')
	forbidden_rpcs=$(property forbiddenTransactionRpcs "$result_file" 2>/dev/null || printf 'UNKNOWN')
	unexpected_rpcs=$(property unexpectedRpcs "$result_file" 2>/dev/null || printf 'UNKNOWN')
	shutdowns=$(property gracefulShutdowns "$result_file" 2>/dev/null || printf 'UNKNOWN')
	non_loopback_interfaces=$(property nonLoopbackInterfaces "$result_file" 2>/dev/null || printf 'UNKNOWN')
	default_routes=$(property defaultRoutes "$result_file" 2>/dev/null || printf 'UNKNOWN')
	non_loopback_routes=$(property nonLoopbackRoutes "$result_file" 2>/dev/null || printf 'UNKNOWN')
	if [ "$mode" = '--cutover' ]; then
		endpoint_cutover=$(property endpointCutover "$result_file" 2>/dev/null || printf 'NOT_PROVEN')
		cold_restart_endpoint=$(property coldRestartEndpoint "$result_file" 2>/dev/null || printf 'NOT_PROVEN')
		address_continuity=$(property addressContinuity "$result_file" 2>/dev/null || printf 'NOT_PROVEN')
		no_native_server_a_after_selection_barrier=$(property noNativeServerAAfterSelectionBarrier "$result_file" 2>/dev/null || printf 'NOT_PROVEN')
	fi
	cutover_contract=PASS
	if [ "$mode" = '--cutover' ] && { [ "$endpoint_cutover" != PASS ] \
			|| [ "$cold_restart_endpoint" != PASS ] || [ "$address_continuity" != PASS ] \
			|| [ "$no_native_server_a_after_selection_barrier" != PASS ]; }; then
		cutover_contract=FAIL
	fi
	if [ "$acceptance_status" -eq 0 ] && [ "$secret_scan" = PASS ] && [ "$cutover_contract" = PASS ] \
			&& [ "$(property result "$result_file" 2>/dev/null || true)" = PASS ]; then
		result=PASS
	fi
fi

core_commit=$(cd "$repository" && git rev-parse HEAD)
tree_state=clean
if [ -n "$(cd "$repository" && git status --porcelain)" ]; then
	tree_state=dirty
fi
jar_hash_line=$(sha256sum "$jar")
jar_sha256=${jar_hash_line%% *}
timestamp=$(date -u '+%Y-%m-%dT%H:%M:%SZ')
host=$(uname -srm)
temporary_receipt=$work_directory/receipt.md
temporary_log=$work_directory/sanitized.log
# Backticks below are literal Markdown delimiters.
# shellcheck disable=SC2016
{
	printf '%s\n' 'Raw Core, JNI, fixture, API, and wallet-state evidence is intentionally not retained.'
	printf 'Harness exit: %s\n' "$acceptance_status"
	printf 'Secret scan: %s\n' "$secret_scan"
	printf '%s\n' 'Safe diagnostics follow:'
	sed -n '1,120p' "$harness_log"
} > "$temporary_log"
{
	if [ "$mode" = '--cutover' ]; then
		printf '# Pirate Unified packaged cold-restart cutover acceptance receipt\n\n'
	else
		printf '# Pirate Unified packaged lifecycle acceptance receipt\n\n'
	fi
	printf '%s `%s`\n' '- Timestamp:' "$timestamp"
	printf '%s `%s`\n' '- Result:' "$result"
	printf '%s `%s`\n' '- Core source commit:' "$core_commit"
	printf '%s `%s`\n' '- Core source tree:' "$tree_state"
	printf '%s `%s`\n' '- Host:' "$host"
	printf '%s `%s`\n' '- Packaged Core JAR:' "$jar"
	printf '%s `%s`\n' '- Packaged Core JAR SHA-256:' "$jar_sha256"
	printf '%s `%s`\n' '- Local-QDN fixture:' "$fixture"
	printf '%s `%s`\n' '- Bundle manifest SHA-256:' "$manifest_sha256"
	printf '%s `%s`\n' '- Normalized command:' "tools/run-pirate-unified-packaged-lifecycle-acceptance.sh <absolute-packaged-core.jar> <absolute-staged-bundle-directory> <absolute-local-qdn-fixture-directory> <new-receipt.md>${mode:+ $mode}"
	if [ "$mode" = '--cutover' ]; then
		printf '%s `%s`\n' '- Test counts:' 'Maven tests N/A; 12 scripted lifecycle/cutover boundaries'
	else
		printf '%s `%s`\n' '- Test counts:' 'Maven tests N/A; 8 scripted lifecycle boundaries'
	fi
	printf '%s `%s`\n' '- Sanitized log:' "$receipt.log"
	printf '\n## Results\n\n'
	printf '| Boundary | Result | Evidence |\n'
	printf '|---|---:|---|\n'
	printf '| Network egress | %s | rootless namespace; non-loopback interfaces `%s`; default routes `%s`; non-loopback routes `%s` |\n' "$network_result" "$non_loopback_interfaces" "$default_routes" "$non_loopback_routes"
	if [ "$mode" = '--cutover' ]; then
		printf '| Loopback lightwalletd | %s | fixed IPv4 plaintext A/B pair; Java service `regtest`, native Pirate service `main` |\n' "$fixture_result"
		printf '| Fresh packaged start | %s | created on A, cut over and freshly synchronized to newer B, persisted `MIGRATING` |\n' "$first_start"
		printf '| Packaged restart | %s | reopened B in a clean second Core process and promoted the same namespace to `UNIFIED_READY` |\n' "$second_start"
	else
		printf '| Loopback lightwalletd | %s | fixed IPv4 plaintext endpoint; Java service `regtest`, native Pirate service `main` |\n' "$fixture_result"
		printf '| Fresh packaged start | %s | created, synchronized, and persisted `MIGRATING` with validated sync |\n' "$first_start"
		printf '| Packaged restart | %s | reopened the same namespace and promoted it to `UNIFIED_READY` |\n' "$second_start"
	fi
	printf '| Exact start count | %s | required exactly `2` packaged Core starts |\n' "$packaged_starts"
	printf '| Namespace and identity continuity | %s | one namespace, one one-way identity hash, and one wallet-address hash across both starts |\n' "$namespace_result"
	if [ "$mode" = '--cutover' ]; then
		printf '| Endpoint cutover | %s | process 1 moved from A `152858` to B `152862`; each served tip-ending native compact-block retrieval |\n' "$endpoint_cutover"
		printf '| Cold-restart endpoint | %s | process 2 restored configured persisted B before native use |\n' "$cold_restart_endpoint"
		printf '| Address continuity | %s | wallet address hash remained exact before/after cutover and across restart |\n' "$address_continuity"
		printf '| No native server A traffic after B-selection barrier | %s | barrier established immediately before Java selected B remained exact through native B application and sync, both shutdowns, and process 2 |\n' "$no_native_server_a_after_selection_barrier"
		printf '| Conservative birthday and exact-tip sync | %s | settings pin birthday `152855`; process 1 reached A `152858` then B `152862` before durable validation |\n' "$birthday_result"
	else
		printf '| Conservative birthday and exact-tip sync | %s | settings pin birthday `152855`; Pirate compact-block data reached exact tip `152858` before durable validation |\n' "$birthday_result"
	fi
	printf '| Unfunded/no transaction RPCs | %s | forbidden transaction read/send RPC count `%s`; unexpected RPC count `%s` |\n' "$( [ "$forbidden_rpcs" = 0 ] && printf PASS || printf NOT_PROVEN )" "$forbidden_rpcs" "$unexpected_rpcs"
	printf '| Graceful shutdowns | %s | required `2` packaged Core shutdown confirmations |\n' "$shutdowns"
	printf '| Secret scan | %s | raw outputs checked before deletion; none retained |\n' "$secret_scan"
	if [ "$mode" = '--cutover' ]; then
		printf '\nThis receipt proves one unfunded Linux x86_64 packaged two-process cold-restart endpoint cutover against deterministic local fixtures. '
	else
		printf '\nThis receipt proves one unfunded Linux x86_64 packaged lifecycle against deterministic local fixtures. '
	fi
	printf 'It does not prove historical restore before the configured birthday, legacy migration, real-network interoperability, funded behavior, QDN publication, deployment, default enablement, or Home behavior.\n'
} > "$temporary_receipt"

mv "$temporary_log" "$receipt.log"
mv "$temporary_receipt" "$receipt"
trap - 0 HUP INT TERM
cleanup_outer
work_directory=
if [ "$acceptance_status" -ne 0 ] || [ "$result" != PASS ]; then
	printf 'Packaged lifecycle acceptance failed; receipt: %s, log: %s\n' "$receipt" "$receipt.log" >&2
	exit 1
fi
printf 'Packaged lifecycle acceptance passed; receipt: %s, log: %s\n' "$receipt" "$receipt.log"
