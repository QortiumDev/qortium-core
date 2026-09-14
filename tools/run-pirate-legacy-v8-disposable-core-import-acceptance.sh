#!/usr/bin/env bash
# shellcheck disable=SC2016,SC2317
set -euo pipefail
umask 077
ulimit -c 0

# Runs one encrypted, unfunded Qortal-format v8 wallet through the private reader
# pipe into a disposable packaged Core, including pending retry, cancellation,
# resume, recovery completion, and a completed retry after a full Core restart.

LEGACY_LINUX_SHA256='7a1ce3b1e855e893f537ab927d135614ff492f5d1ee4b0a392be2a51488cead7'
LEGACY_COINPARAMS_SHA256='051bf1b840305d2cc6f82c75304d31afc613d6a0eff77c2e3e0f29946a14cfba'
LEGACY_OUTPUT_SHA256='59254099ef6622df3bd7b1b96467bb722edea72603cd34d21708214a0b9f6aba'
LEGACY_SPEND_SHA256='3fc70cb6b7beba436545d5b4210c903a9802f4b87f1bfc5a2faf5a0bea268fc5'
LEGACY_METADATA_SHA256='6ed64092d8abe2f492d3f512cb5e9811b7871814eeddc3745bdc59405d838d76'
LEGACY_WALLET_SHA256='3e7495a5c3f398eef48784906df4b82747c29c980911dc2e3fa35ec65383b4cc'
TARGET_FIXTURE_SHA256='a172a915a6de4776156d497d1059c56dfac8dccf48cf597ea0d1be953119edd1'

usage() {
	printf '%s\n' "Usage: $0 <absolute-packaged-core.jar> <absolute-unified-staged-bundle> <absolute-local-qdn-fixture> <absolute-legacy-bundle> <absolute-protected-v8-metadata.json> <absolute-target-unified-fixture.json> <new-receipt.md>" >&2
}

require_absolute() {
	local label=$1 value=$2
	[[ $value == /* ]] || { printf '%s path must be absolute\n' "$label" >&2; exit 2; }
	case $value in
		*'`'*|*'"'*|*'\'*|*$'\n'*)
			printf '%s path contains an unsupported character\n' "$label" >&2; exit 2 ;;
	esac
}

require_command() { command -v "$1" >/dev/null || { printf 'Required command not found: %s\n' "$1" >&2; exit 1; }; }
file_sha256() { sha256sum "$1" | awk '{print $1}'; }
property() {
	local key=$1 file=$2 count
	count=$(awk -F= -v key="$key" '$1 == key { count++ } END { print count + 0 }' "$file")
	[[ $count -eq 1 ]] || return 1
	awk -F= -v key="$key" '$1 == key { print substr($0, length(key) + 2) }' "$file"
}

terminate_group() {
	local group=$1 count=0
	[[ -n $group ]] || return 0
	group_live() { ps -eo pgid=,stat= | awk -v g="$group" '$1 == g && $2 !~ /^Z/ { f=1 } END { exit !f }'; }
	if group_live; then /bin/kill -TERM -- "-$group" 2>/dev/null || true; fi
	while group_live && [[ $count -lt 60 ]]; do sleep 1; ((count+=1)); done
	if group_live; then /bin/kill -KILL -- "-$group" 2>/dev/null || true; fi
	wait "$group" 2>/dev/null || true
	! group_live
}

	if [[ ${1:-} == --inside-network-namespace ]]; then
		shift
		[[ $# -eq 12 ]] || exit 2
		runtime=$1; jar=$2; classes=$3; signature=$4; legacy_bundle=$5
		legacy_metadata=$6; legacy_wallet=$7; target_fixture=$8; result_file=$9; expected_birthday=${10}
		expected_range_start=${11}; expected_unified_library_sha256=${12}
		api_port=62391
		core_pid=''
		fixture_pid=''
		phase=namespace-setup
		report_inside_failure() {
			printf '[error] disposable-import phase=%s line=%s\n' "$phase" "$1" >&2
			for phase_log in "$runtime"/reader-*.log "$runtime"/import-*.log; do
				[[ -f $phase_log ]] || continue
				grep -E '^\[(error|ok)\] ' "$phase_log" | tail -10 >&2 || true
			done
			for core_log in "$runtime"/core-*.log; do
				[[ -f $core_log ]] || continue
				grep -E ' (ERROR|WARN) |Sync (failed|error)|Unable to initialize Pirate Unified wallet' \
					"$core_log" | tail -40 >&2 || true
				done
			if [[ -f $runtime/lightwalletd.audit ]]; then
				for audit_key in recoveryBarrierEntries recoveryBarrierCancellations \
						recoveryBarrierCompletions recoveryBarrierLastStart recoveryBarrierLastEnd \
						recoveryTreeStateLastHeight pirateTreeStateRequests observedRanges \
						forbiddenRpcs unexpectedRpcs; do
					audit_value=$(property "$audit_key" "$runtime/lightwalletd.audit" 2>/dev/null || true)
					printf '[diagnostic] fixture.%s=%s\n' "$audit_key" "${audit_value:-missing}" >&2
				done
			fi
			if [[ -f $runtime/status-recovery-wait.json ]]; then
				jq -c '{state,message,restartRequired,recoveryState}' \
					"$runtime/status-recovery-wait.json" | sed 's/^/[diagnostic] status=/' >&2 || true
			fi
			if [[ -n $core_pid ]]; then
				terminate_group "$core_pid" || true
				core_pid=''
			fi
		}
		trap 'report_inside_failure "$LINENO"' ERR
	cleanup_inside() {
		[[ -z $core_pid ]] || terminate_group "$core_pid" || true
		[[ -z $fixture_pid ]] || terminate_group "$fixture_pid" || true
	}
	trap cleanup_inside EXIT HUP INT TERM

	ip link set lo up
	ip -o link show > "$runtime/network-links.txt"
	ip route show table all > "$runtime/network-routes.txt"
	non_loopback_interfaces=$(awk -F': ' '$2 !~ /^lo(@|$)/ { n++ } END { print n + 0 }' "$runtime/network-links.txt")
	default_routes=$(awk '$1 == "default" { n++ } END { print n + 0 }' "$runtime/network-routes.txt")
	non_loopback_routes=$(awk '$0 !~ / dev lo( |$)/ { n++ } END { print n + 0 }' "$runtime/network-routes.txt")
	[[ $non_loopback_interfaces -eq 0 && $default_routes -eq 0 && $non_loopback_routes -eq 0 ]] || exit 1
	printf 'networkEgress=PASS\nnonLoopbackInterfaces=%s\ndefaultRoutes=%s\nnonLoopbackRoutes=%s\n' \
		"$non_loopback_interfaces" "$default_routes" "$non_loopback_routes" > "$result_file"

	mkdir -p "$runtime/barrier"
	printf '%s' "$expected_range_start" > "$runtime/barrier/expected-start"
	setsid java -Djava.awt.headless=true -cp "$classes:$jar" \
		org.qortium.controller.PirateUnifiedLoopbackLightwalletdMain \
		"$runtime/lightwalletd.ready" "$runtime/lightwalletd.audit" "$runtime/barrier" recovery \
		> "$runtime/lightwalletd.log" 2>&1 &
	fixture_pid=$!
	for _ in {1..300}; do [[ -s $runtime/lightwalletd.ready ]] && break; kill -0 "$fixture_pid" 2>/dev/null || exit 1; sleep .1; done
	[[ $(property mode "$runtime/lightwalletd.ready") == recovery ]] || exit 1

	start_core() {
		local phase=$1
			setsid sh -c 'cd "$1" && exec java -Djava.awt.headless=true -Dlog4j.configurationFile="$1/log4j2-acceptance.properties" -jar "$2" "$1/settings.json"' \
				sh "$runtime" "$jar" "$phase" > "$runtime/core-$phase.log" 2>&1 &
		core_pid=$!
		printf '%s\n' "$core_pid" > "$runtime/core-$phase.pid"
		for _ in {1..1200}; do
			kill -0 "$core_pid" 2>/dev/null || return 1
			curl --fail --silent --max-time 2 "http://127.0.0.1:$api_port/admin/status" >/dev/null 2>&1 && return 0
			sleep .1
		done
		return 1
	}

	api_post() {
		local path=$1 output=$2
		curl_with_api text --fail --silent --show-error --max-time 60 \
			--request POST "http://127.0.0.1:$api_port$path" > "$output" 2>/dev/null
	}

	curl_with_api() {
		local content_type=$1
		shift
		local mime=application/json
		[[ $content_type == text ]] && mime=text/plain
		curl --config /proc/self/fd/3 "$@" 3< <(
			printf 'header = "X-API-KEY: %s"\nheader = "Content-Type: %s"\n' \
				"$(<"$runtime/api/apikey.txt")" "$mime"
		)
	}

	set_server() {
		printf '%s' '{"hostName":"127.0.0.1","port":9067,"connectionType":"TCP"}' | \
			curl_with_api json --fail --silent --show-error --max-time 30 \
				--data-binary @- "http://127.0.0.1:$api_port/crosschain/arrr/setcurrentserver" \
				> "$runtime/set-server-$1.json" 2>/dev/null
		grep -Eq '"success"[[:space:]]*:[[:space:]]*true' "$runtime/set-server-$1.json"
	}

	status_for_target() {
		local output=$1
		jq -jer '.entropy58' "$target_fixture" | curl_with_api text --fail --silent --show-error --max-time 15 \
			--data-binary @- \
			"http://127.0.0.1:$api_port/crosschain/arrr/syncstatus?json=true" > "$output" 2>/dev/null
	}

	wait_status() {
		local output=$1 state=$2 recovery=${3:-}
		for _ in {1..3000}; do
			kill -0 "$core_pid" 2>/dev/null || return 1
			if status_for_target "$output" && jq -e --arg state "$state" --arg recovery "$recovery" \
				'.state == $state and .restartRequired == false and ($recovery == "" or .recoveryState == $recovery)' \
				"$output" >/dev/null; then return 0; fi
			sleep .1
		done
		return 1
	}

	run_reader_import() {
		local phase=$1 inspection="$runtime/inspection-$1.json" result="$runtime/import-$1.json"
		set +e
		java -cp "$classes:$jar" org.qortium.tools.pirate.QortalLegacyFixturePassword \
			"$legacy_metadata" 3 3>&1 1>"$runtime/password-$phase.log" | \
		java -Xmx1g -Djava.awt.headless=true -cp "$classes:$jar" \
			org.qortium.tools.pirate.PirateLegacyV8Inspector \
			"$legacy_bundle/librust-linux-x86_64.so" "$legacy_bundle/coinparams.json" \
			"$legacy_bundle/saplingoutput_base64" "$legacy_bundle/saplingspend_base64" \
			"$legacy_wallet" http://127.0.0.1:9067 3 "$inspection" 4 \
			3<&0 4>&1 1>"$runtime/reader-$phase.log" | \
		java -Djava.awt.headless=true -cp "$classes:$jar" \
			org.qortium.tools.pirate.PirateLegacyV8CoreImportPipe \
			"http://127.0.0.1:$api_port" "$target_fixture" "$runtime/api/apikey.txt" "$phase" "$result" \
			> "$runtime/import-$phase.log" 2>&1
		local pipeline_status=("${PIPESTATUS[@]}")
		set -e
		if [[ ${pipeline_status[0]} -ne 0 || ${pipeline_status[1]} -ne 0 || ${pipeline_status[2]} -ne 0 ]]; then
			printf '[error] reader-import phase=%s statuses=%s,%s,%s\n' "$phase" \
				"${pipeline_status[0]}" "${pipeline_status[1]}" "${pipeline_status[2]}" >&2
			grep -E '^\[(error|ok)\] ' "$runtime/reader-$phase.log" "$runtime/import-$phase.log" >&2 || true
			return 1
		fi
		jq -e --arg walletHash "$LEGACY_WALLET_SHA256" --argjson birthday "$expected_birthday" \
			'.walletSha256 == $walletHash and .birthdayHeight == $birthday' "$inspection" >/dev/null
		jq -e --arg walletHash "$LEGACY_WALLET_SHA256" --argjson birthday "$expected_birthday" \
			'.walletSha256 == $walletHash and .birthdayHeight == $birthday' "$result" >/dev/null
	}

	phase=start-first-core
	start_core first
	phase=select-first-server
	set_server first
	phase=initialize-target
	initialization_ready=false
	for _ in {1..480}; do
		initialization_http=$(jq -c '{entropy58, initializationMode:"NEW_AT_CURRENT_TIP"}' "$target_fixture" | \
			curl_with_api json --silent --show-error --max-time 30 --data-binary @- \
				--output "$runtime/initialize.json" --write-out '%{http_code}' \
				"http://127.0.0.1:$api_port/crosschain/arrr/initialize" 2>/dev/null || true)
		if [[ $initialization_http == 200 ]] && jq -e \
			'.initializationMode == "NEW_AT_CURRENT_TIP" and .birthdayHeight == 152858' \
			"$runtime/initialize.json" >/dev/null; then initialization_ready=true; break; fi
		kill -0 "$core_pid" 2>/dev/null || break
		sleep .25
	done
	[[ $initialization_ready == true ]]
	phase=baseline-ready
	wait_status "$runtime/status-baseline.json" READY
	installed_unified_library="$runtime/wallets/PirateChain/lib/$signature/librust-linux-x86_64.so"
	[[ -f $installed_unified_library && ! -L $installed_unified_library ]]
	[[ $(file_sha256 "$installed_unified_library") == "$expected_unified_library_sha256" ]]
	printf 'unifiedLibrarySha256=%s\n' "$expected_unified_library_sha256" >> "$result_file"
	phase=baseline-stop
	api_post /crosschain/arrr/stop "$runtime/stop-baseline.txt"
	grep -Fx true "$runtime/stop-baseline.txt" >/dev/null
	phase=first-controller-start
	api_post /crosschain/arrr/start "$runtime/start-first.txt"
	grep -Fx true "$runtime/start-first.txt" >/dev/null
	wait_status "$runtime/status-first-ready.json" READY
	: > "$runtime/barrier/armed"
	phase=first-import
	run_reader_import first
	[[ $(property recoveryBarrierEntries "$runtime/lightwalletd.audit") == 0 ]]
	phase=stop-after-first
	api_post /crosschain/arrr/stop "$runtime/stop-after-first.txt"
	grep -Fx true "$runtime/stop-after-first.txt" >/dev/null
	[[ $(property recoveryBarrierEntries "$runtime/lightwalletd.audit") == 0 ]]
	[[ $(property recoveryBarrierCancellations "$runtime/lightwalletd.audit") == 0 ]]
	curl --fail --silent --max-time 5 "http://127.0.0.1:$api_port/admin/status" >/dev/null

	# Prepare the secret candidate producer while stopped. It blocks on the private
	# FIFO until the just-started controller can service the pending exact retry.
	phase=prepare-pending-reader
	mkfifo -m 600 "$runtime/pending.pipe"
	(java -cp "$classes:$jar" org.qortium.tools.pirate.QortalLegacyFixturePassword "$legacy_metadata" 3 \
		3>&1 1>"$runtime/password-pending.log" | \
		java -Xmx1g -Djava.awt.headless=true -cp "$classes:$jar" org.qortium.tools.pirate.PirateLegacyV8Inspector \
			"$legacy_bundle/librust-linux-x86_64.so" "$legacy_bundle/coinparams.json" \
			"$legacy_bundle/saplingoutput_base64" "$legacy_bundle/saplingspend_base64" \
			"$legacy_wallet" http://127.0.0.1:9067 3 "$runtime/inspection-pending.json" 4 \
			3<&0 4>"$runtime/pending.pipe" >"$runtime/reader-pending.log" 2>&1) &
	producer_pid=$!
	for _ in {1..300}; do [[ -s $runtime/inspection-pending.json ]] && break; kill -0 "$producer_pid" 2>/dev/null || exit 1; sleep .1; done
	phase=pending-controller-start
	api_post /crosschain/arrr/start "$runtime/start-pending.txt"
	phase=pending-import
	java -Djava.awt.headless=true -cp "$classes:$jar" org.qortium.tools.pirate.PirateLegacyV8CoreImportPipe \
		"http://127.0.0.1:$api_port" "$target_fixture" "$runtime/api/apikey.txt" pending \
		"$runtime/import-pending.json" < "$runtime/pending.pipe" > "$runtime/import-pending.log" 2>&1
	wait "$producer_pid"
	rm "$runtime/pending.pipe"

	phase=recovery-observation
	recovery_path=''
	for _ in {1..600}; do
		status_for_target "$runtime/status-recovery-wait.json" || true
		if [[ $(property recoveryBarrierEntries "$runtime/lightwalletd.audit" || true) == 1 ]]; then
			recovery_path=RANGED_STOP_RESUME
			break
		fi
		if jq -e '.state == "READY" and .restartRequired == false and .recoveryState == "RECOVERED"' \
				"$runtime/status-recovery-wait.json" >/dev/null 2>&1; then
			recovery_path=ZERO_RANGE_COMPLETE
			break
		fi
		sleep .1
	done
	if [[ $recovery_path == RANGED_STOP_RESUME ]]; then
		phase=first-recovery-barrier
		api_post /crosschain/arrr/stop "$runtime/stop-recovery.txt"
		grep -Fx true "$runtime/stop-recovery.txt" >/dev/null
		for _ in {1..600}; do [[ $(property recoveryBarrierCancellations "$runtime/lightwalletd.audit" || true) == 1 ]] && break; sleep .1; done
		[[ $(property recoveryBarrierCancellations "$runtime/lightwalletd.audit") == 1 ]]
		phase=recovery-restart
		api_post /crosschain/arrr/start "$runtime/start-recovery.txt"
		for _ in {1..600}; do [[ $(property recoveryBarrierEntries "$runtime/lightwalletd.audit" || true) == 2 ]] && break; sleep .1; done
		[[ $(property recoveryBarrierEntries "$runtime/lightwalletd.audit") == 2 ]]
		: > "$runtime/barrier/release"
		phase=recovery-completion
		wait_status "$runtime/status-recovered.json" READY RECOVERED
	elif [[ $recovery_path == ZERO_RANGE_COMPLETE ]]; then
		[[ $(property recoveryBarrierEntries "$runtime/lightwalletd.audit") == 0 ]]
		[[ $(property recoveryBarrierCancellations "$runtime/lightwalletd.audit") == 0 ]]
		[[ $(property recoveryBarrierCompletions "$runtime/lightwalletd.audit") == 0 ]]
		cp "$runtime/status-recovery-wait.json" "$runtime/status-recovered.json"
	else
		false
	fi

	first_key_hash=$(jq -er '.keyIdSha256' "$runtime/import-first.json")
	[[ $(jq -er '.keyIdSha256' "$runtime/import-pending.json") == "$first_key_hash" ]]
	terminate_group "$core_pid"; core_pid=
	grep -F 'Shutdown complete!' "$runtime/core-first.log" >/dev/null
	phase=second-core-start
	start_core second
	set_server second
	wait_status "$runtime/status-reopen.json" READY
	phase=completed-import
	run_reader_import completed
	[[ $(jq -er '.keyIdSha256' "$runtime/import-completed.json") == "$first_key_hash" ]]
	terminate_group "$core_pid"; core_pid=
	grep -F 'Shutdown complete!' "$runtime/core-second.log" >/dev/null
	[[ $(cat "$runtime/core-first.pid") != $(cat "$runtime/core-second.pid") ]]

	terminate_group "$fixture_pid"; fixture_pid=
	[[ $(property result "$runtime/lightwalletd.audit") == PASS ]]
	if [[ $recovery_path == RANGED_STOP_RESUME ]]; then
		[[ $(property recoveryBarrierEntries "$runtime/lightwalletd.audit") == 2 ]]
		[[ $(property recoveryBarrierCancellations "$runtime/lightwalletd.audit") == 1 ]]
		[[ $(property recoveryBarrierCompletions "$runtime/lightwalletd.audit") == 1 ]]
		[[ $(property recoveryBarrierLastStart "$runtime/lightwalletd.audit") == "$expected_range_start" ]]
		[[ $(property recoveryBarrierLastEnd "$runtime/lightwalletd.audit") == 152858 ]]
	else
		[[ $(property recoveryBarrierEntries "$runtime/lightwalletd.audit") == 0 ]]
		[[ $(property recoveryBarrierCancellations "$runtime/lightwalletd.audit") == 0 ]]
		[[ $(property recoveryBarrierCompletions "$runtime/lightwalletd.audit") == 0 ]]
	fi
	[[ $(property pirateTreeStateRequests "$runtime/lightwalletd.audit") -ge 1 ]]
	[[ $(property forbiddenRpcs "$runtime/lightwalletd.audit") == 0 ]]
	[[ $(property unexpectedRpcs "$runtime/lightwalletd.audit") == 0 ]]
	printf 'loopbackFixture=PASS\nfirstImport=PASS\npendingRetry=PASS\nrecoveryPath=%s\nterminalSpendability=PASS\ncoreRestart=PASS\ncompletedRetry=PASS\npackagedStarts=2\nresult=PASS\n' \
		"$recovery_path" >> "$result_file"
	phase=complete
	trap - ERR
	trap - EXIT HUP INT TERM
	exit 0
fi

[[ $# -eq 7 ]] || { usage; exit 2; }
jar=$1; bundle=$2; qdn_fixture=$3; legacy_bundle=$4; legacy_metadata=$5; target_fixture=$6; receipt=$7
for pair in "JAR:$jar" "Unified bundle:$bundle" "QDN fixture:$qdn_fixture" "Legacy bundle:$legacy_bundle" \
	"Legacy metadata:$legacy_metadata" "Target fixture:$target_fixture" "Receipt:$receipt"; do require_absolute "${pair%%:*}" "${pair#*:}"; done
[[ $(uname -s):$(uname -m) == Linux:x86_64 ]] || exit 1
for command_name in awk cp curl find grep ip java javac jq mkfifo od ps sed setsid sha256sum tr unshare; do require_command "$command_name"; done

script_directory=$(CDPATH='' cd "$(dirname "$0")" && pwd)
repository=$(CDPATH='' cd "$script_directory/.." && pwd)
manifest=$bundle/QORTIUM-MANIFEST.txt
fixture_properties=$qdn_fixture/fixture.properties
test_chain=$repository/src/test/resources/test-chain-v2.json
inspector_source=$repository/src/test/java/org/qortium/tools/pirate/PirateLegacyV8Inspector.java
import_source=$repository/src/test/java/org/qortium/tools/pirate/PirateLegacyV8CoreImportPipe.java
password_source=$repository/src/test/java/org/qortium/tools/pirate/QortalLegacyFixturePassword.java
fixture_source=$repository/src/test/java/org/qortium/controller/PirateUnifiedLoopbackLightwalletd.java
fixture_main_source=$repository/src/test/java/org/qortium/controller/PirateUnifiedLoopbackLightwalletdMain.java
for input in "$jar" "$manifest" "$fixture_properties" "$test_chain" "$inspector_source" "$import_source" \
	"$password_source" "$fixture_source" "$fixture_main_source" "$legacy_metadata" "$target_fixture" \
	"$legacy_bundle/librust-linux-x86_64.so" "$legacy_bundle/coinparams.json" \
	"$legacy_bundle/saplingoutput_base64" "$legacy_bundle/saplingspend_base64"; do
	[[ -f $input && ! -L $input ]] || { printf 'Invalid input: %s\n' "$input" >&2; exit 1; }
done
[[ -d $bundle && ! -L $bundle && -d $qdn_fixture/data && ! -L $qdn_fixture/data ]] || exit 1
[[ $(file_sha256 "$legacy_bundle/librust-linux-x86_64.so") == "$LEGACY_LINUX_SHA256" ]]
[[ $(file_sha256 "$legacy_bundle/coinparams.json") == "$LEGACY_COINPARAMS_SHA256" ]]
[[ $(file_sha256 "$legacy_bundle/saplingoutput_base64") == "$LEGACY_OUTPUT_SHA256" ]]
[[ $(file_sha256 "$legacy_bundle/saplingspend_base64") == "$LEGACY_SPEND_SHA256" ]]
[[ $(file_sha256 "$legacy_metadata") == "$LEGACY_METADATA_SHA256" ]]
[[ $(file_sha256 "$target_fixture") == "$TARGET_FIXTURE_SHA256" ]]
wallet_name=$(jq -er '.walletFile' "$legacy_metadata")
[[ $wallet_name != */* ]]
legacy_wallet=$(dirname "$legacy_metadata")/$wallet_name
[[ -f $legacy_wallet && ! -L $legacy_wallet && $(file_sha256 "$legacy_wallet") == "$LEGACY_WALLET_SHA256" ]]
expected_birthday=$(jq -er '.birthdayHeight' "$legacy_metadata")
[[ $expected_birthday =~ ^[0-9]+$ && $expected_birthday -ge 1 && $expected_birthday -le 152858 ]]
	expected_range_start=$expected_birthday
	[[ $expected_range_start -ge 152855 ]] || expected_range_start=152855
	expected_unified_library_sha256=$(awk '$1 == "file:" && $4 == "librust-linux-x86_64.so" { count++; hash=$3 } END { if (count == 1) print hash }' "$manifest")
	[[ $expected_unified_library_sha256 =~ ^[0-9a-f]{64}$ ]]
	[[ $(file_sha256 "$bundle/librust-linux-x86_64.so") == "$expected_unified_library_sha256" ]]
	signature=$(property signature "$fixture_properties")
[[ $(property bundleManifestSha256 "$fixture_properties") == $(file_sha256 "$manifest") ]]
[[ $(property transactionState "$fixture_properties") == synthetic-direct-repository-row ]]

receipt_parent=$(dirname "$receipt"); receipt_name=$(basename "$receipt"); mkdir -p "$receipt_parent"
receipt_parent=$(CDPATH='' cd "$receipt_parent" && pwd -P); receipt=$receipt_parent/$receipt_name
[[ ! -e $receipt && ! -e $receipt.log ]] || exit 1
lock=$receipt.lock; mkdir "$lock" 2>/dev/null || exit 1
work_directory=
cleanup_outer() { [[ -z $work_directory || ! -d $work_directory ]] || rm -rf "$work_directory"; rmdir "$lock" 2>/dev/null || true; }
trap cleanup_outer EXIT HUP INT TERM
work_directory=$(mktemp -d "$receipt_parent/.pirate-v8-core-import.XXXXXX")
classes=$work_directory/classes; runtime=$work_directory/runtime
mkdir -p "$classes" "$runtime"/{repository,data,temp,api,lists,export,wallets}
cp -a --reflink=auto "$qdn_fixture/repository/." "$runtime/repository/"
cp -a --reflink=auto "$qdn_fixture/data/." "$runtime/data/"
cp "$test_chain" "$runtime/test-chain-v2.json"
if ! javac -proc:none -cp "$jar" -d "$classes" "$inspector_source" "$import_source" "$password_source" \
	"$fixture_source" "$fixture_main_source" > "$work_directory/javac.log" 2>&1; then exit 1; fi
api_key=$(od -An -N32 -tx1 /dev/urandom | tr -d ' \n')
printf '%s' "$api_key" > "$runtime/api/apikey.txt"
printf '%s\n' 'rootLogger.level = info' 'rootLogger.appenderRef.console.ref = stdout' \
	'appender.console.type = Console' 'appender.console.name = stdout' \
	'appender.console.layout.type = PatternLayout' \
	'appender.console.layout.pattern = %d{yyyy-MM-dd HH:mm:ss} %-5p %c{1}:%L - %m%n' \
	> "$runtime/log4j2-acceptance.properties"
settings_template='{"bindAddress":"127.0.0.1","listenPort":62392,"listenDataPort":62393,"apiEnabled":true,"apiPort":62391,"apiWhitelistEnabled":true,"apiWhitelist":["127.0.0.1"],"apiRestricted":true,"apiKeyRemoteAccessEnabled":false,"apiLoggingEnabled":false,"apiDocumentationEnabled":false,"sslKeystorePathname":null,"isTestNet":true,"singleNodeTestnet":true,"lite":true,"testNtpOffset":0,"qdnEnabled":true,"storagePolicy":"ALL","qdnPushOnPublishEnabled":false,"directDataRetrievalEnabled":false,"autoUpdateMode":"OFF","autoRestartEnabled":false,"archiveEnabled":false,"archiveServingEnabled":false,"dbCacheEnabled":false,"balanceRecorderEnabled":false,"rebuildArbitraryResourceCacheTaskEnabled":false,"gatewayEnabled":false,"domainMapEnabled":false,"allowedTransports":["IP"],"uPnPEnabled":false,"minBlockchainPeers":0,"minOutboundPeers":0,"maxPeers":1,"maxDataPeers":1,"minDataPeers":1,"maxNetworkThreadPoolSize":8,"networkPoWComputePoolSize":1,"initialPeers":[],"initialDataPeers":[],"fixedNetwork":[],"pirateChainNet":"REGTEST","arrrDefaultBirthday":152850,"pirateChainWalletUnified":true,"pirateChainWalletDebugLogging":false,"wallets":{"BTC":false,"BCH":false,"LTC":false,"DOGE":false,"DGB":false,"RVN":false,"DASH":false,"PPC":false,"NMC":false,"FIRO":false,"KMD":false,"VRSC":false,"ZEC":false,"LBC":false,"XVG":false,"ARRR":true}}'
jq --arg root "$runtime" --arg signature "$signature" '. + {apiKeyPath:($root+"/api"),blockchainConfig:($root+"/test-chain-v2.json"),repositoryPath:($root+"/repository"),repositoryConnectionPoolSize:16,dataPath:($root+"/data"),tempDataPath:($root+"/temp"),walletsPath:($root+"/wallets"),listsPath:($root+"/lists"),exportPath:($root+"/export"),maxStorageCapacity:4294967296,pirateChainWalletQdnSignature:$signature}' \
	<<<"$settings_template" > "$runtime/settings.json"

result_file=$work_directory/result.properties; harness_log=$work_directory/harness.log
set +e
unshare -Urn "$script_directory/$(basename "$0")" --inside-network-namespace "$runtime" "$jar" "$classes" \
		"$signature" "$legacy_bundle" "$legacy_metadata" "$legacy_wallet" "$target_fixture" "$result_file" "$expected_birthday" \
		"$expected_range_start" "$expected_unified_library_sha256" \
	> "$harness_log" 2>&1
acceptance_status=$?
set -e

source_preserved=PASS
[[ $(file_sha256 "$legacy_metadata") == "$LEGACY_METADATA_SHA256" && $(file_sha256 "$legacy_wallet") == "$LEGACY_WALLET_SHA256" \
	&& $(file_sha256 "$target_fixture") == "$TARGET_FIXTURE_SHA256" ]] || source_preserved=FAIL
secret_patterns=$work_directory/secret-patterns
jq -jer '.entropy58, "\n", .receiveAddress, "\n"' "$legacy_metadata" > "$secret_patterns"
jq -jer '.entropy58, "\n", .spendingKey, "\n", .expectedAddress, "\n"' "$target_fixture" >> "$secret_patterns"
printf '%s\n' "$api_key" >> "$secret_patterns"
java -cp "$classes:$jar" org.qortium.tools.pirate.QortalLegacyFixturePassword "$legacy_metadata" 3 \
	3>> "$secret_patterns" > "$work_directory/password-scan.log" 2>&1
secret_scan=PASS
while IFS= read -r pattern; do
	[[ -n $pattern ]] || continue
	if grep -R -a -F --exclude=apikey.txt --exclude=secret-patterns -- "$pattern" "$runtime" "$harness_log" "$work_directory/javac.log" >/dev/null 2>&1; then
		secret_scan=FAIL; break
	fi
done < "$secret_patterns"
api_key=

result=FAIL
if [[ $acceptance_status -eq 0 && $source_preserved == PASS && $secret_scan == PASS \
	&& $(property result "$result_file" 2>/dev/null || true) == PASS ]]; then result=PASS; fi
	jar_sha256=$(file_sha256 "$jar"); manifest_sha256=$(file_sha256 "$manifest"); timestamp=$(date -u '+%Y-%m-%dT%H:%M:%SZ')
	core_tree_state=clean
	git -C "$repository" diff --quiet --ignore-submodules -- && git -C "$repository" diff --cached --quiet --ignore-submodules -- \
		|| core_tree_state=dirty
temporary_log=$work_directory/receipt.log; temporary_receipt=$work_directory/receipt.md
reported_recovery_path=$(property recoveryPath "$result_file" 2>/dev/null || printf NOT_PROVEN)
printf 'Harness exit: %s\nSecret scan: %s\nSource preservation: %s\n' "$acceptance_status" "$secret_scan" "$source_preserved" > "$temporary_log"
sed -n '1,120p' "$harness_log" >> "$temporary_log"
{
	printf '# Pirate legacy-v8 disposable Core import acceptance\n\n'
		printf -- '- Timestamp: `%s`\n- Result: `%s`\n- Core base commit: `%s`\n- Core source tree at run: `%s`\n- Packaged JAR SHA-256: `%s`\n' \
			"$timestamp" "$result" "$(git -C "$repository" rev-parse HEAD)" "$core_tree_state" "$jar_sha256"
	printf -- '- Unified bundle manifest SHA-256: `%s`\n- Legacy JNI SHA-256: `%s`\n' "$manifest_sha256" "$LEGACY_LINUX_SHA256"
	printf -- '- Protected source wallet SHA-256: `%s`\n- Protected target fixture SHA-256: `%s`\n' "$LEGACY_WALLET_SHA256" "$TARGET_FIXTURE_SHA256"
	printf -- '- Network egress: `%s`\n- First import / pending retry / recovery / completed retry: `%s`\n- Recovery path: `%s`\n' \
		"$(property networkEgress "$result_file" 2>/dev/null || printf NOT_PROVEN)" "$result" "$reported_recovery_path"
	printf -- '- Secret scan: `%s`; source preservation: `%s`; raw runtime deleted before publication\n\n' "$secret_scan" "$source_preserved"
		if [[ $result == PASS ]]; then
			if [[ $reported_recovery_path == RANGED_STOP_RESUME ]]; then
				printf '%s\n' 'This proves one unfunded encrypted serialization-v8 candidate can cross the private reader pipe into a disposable packaged Core, retain exact-retry idempotency while recovery is pending, cancel and resume an observed block-range replay without stopping Core, survive a Core restart, and reach the native terminal spendability gate. It does not prove funded balance or sending, arbitrary wallets, production lightwalletd interoperability, deployment, or Home behavior.'
			else
				printf '%s\n' 'This proves one unfunded encrypted serialization-v8 candidate can cross the private reader pipe into a disposable packaged Core, retain exact-retry idempotency while recovery is pending, complete a zero-range recovery, survive a Core restart, and reach the native terminal spendability gate. This fixture issued no block range, so it does not claim recovery cancellation/resume. It also does not prove funded balance or sending, arbitrary wallets, production lightwalletd interoperability, deployment, or Home behavior.'
			fi
		else
			printf '%s\n' 'This failed run proves only the reported isolation, source-preservation, and secret-scan results. It does not prove import, retry, recovery, restart, terminal spendability, deployment, or Home behavior.'
		fi
} > "$temporary_receipt"
rm -rf "$runtime" "$classes" "$secret_patterns" "$work_directory/password-scan.log"
[[ ! -e $runtime && ! -e $classes ]]
ln "$temporary_log" "$receipt.log"
ln "$temporary_receipt" "$receipt"
trap - EXIT HUP INT TERM
cleanup_outer; work_directory=
[[ $result == PASS ]] || { printf 'Disposable Core import acceptance failed: %s\n' "$receipt" >&2; exit 1; }
printf 'Disposable Core import acceptance passed: %s\n' "$receipt"
