#!/bin/sh
set -eu

# Runs a separately packaged Core JAR against the unsigned local-QDN fixture in
# a rootless network namespace. It triggers native loading exactly once and
# never initializes a wallet, publishes data, or contacts a peer.

usage() {
	printf '%s\n' "Usage: $0 <absolute-packaged-core.jar> <absolute-staged-bundle-directory> <absolute-local-qdn-fixture-directory> <new-receipt.md>" >&2
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
}

require_command() {
	command -v "$1" >/dev/null 2>&1 || {
		printf 'Required command not found: %s\n' "$1" >&2
		exit 1
	}
}

run_inside_namespace() {
	runtime=$1
	jar=$2
	bundle=$3
	signature=$4
	log=$5
	result_file=$6
	api_port=62391
	settings=$runtime/settings.json
	expected_cache=$runtime/wallets/PirateChain/lib/$signature
	expected_library=$expected_cache/librust-linux-x86_64.so
	unified_wallets=$runtime/wallets/PirateChain/unified
	core_pid=

	# Invoked indirectly by trap; ShellCheck cannot follow that call edge.
	# shellcheck disable=SC2317
	cleanup_core() {
		if [ -n "$core_pid" ] && kill -0 "$core_pid" 2>/dev/null; then
			kill -TERM "$core_pid" 2>/dev/null || true
			wait_count=0
			while kill -0 "$core_pid" 2>/dev/null && [ "$wait_count" -lt 60 ]; do
				sleep 1
				wait_count=$((wait_count + 1))
			done
			if kill -0 "$core_pid" 2>/dev/null; then
				kill -KILL "$core_pid" 2>/dev/null || true
			fi
			wait "$core_pid" 2>/dev/null || true
			core_pid=
		fi
	}
	trap 'cleanup_core' 0
	trap 'exit 130' HUP INT TERM

	ip link set lo up
	non_loopback_interfaces=$(ip -o link show | awk -F': ' '$2 !~ /^lo(@|$)/ { count++ } END { print count + 0 }')
	default_routes=$(ip route show table all | awk '$1 == "default" { count++ } END { print count + 0 }')
	non_loopback_routes=$(ip route show table all | awk '$0 !~ / dev lo( |$)/ { count++ } END { print count + 0 }')
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
	if [ -e "$expected_cache" ] || [ -e "$unified_wallets" ]; then
		printf '%s\n' 'Runtime wallet cache/storage was not fresh before startup' >&2
		return 1
	fi

	(
		cd "$runtime"
		exec java -Djava.awt.headless=true \
			-Dlog4j.configurationFile="$runtime/log4j2-acceptance.properties" \
			-jar "$jar" "$settings"
	) > "$log" 2>&1 &
	core_pid=$!

	ready=false
	wait_count=0
	while [ "$wait_count" -lt 120 ]; do
		if ! kill -0 "$core_pid" 2>/dev/null; then
			printf '%s\n' 'Packaged Core exited before its API became ready' >&2
			return 1
		fi
		if curl --fail --silent --show-error --max-time 2 \
				"http://127.0.0.1:$api_port/admin/status" >/dev/null 2>&1; then
			ready=true
			break
		fi
		sleep 1
		wait_count=$((wait_count + 1))
	done
	if [ "$ready" != true ]; then
		printf '%s\n' 'Timed out waiting for the packaged Core API' >&2
		return 1
	fi

	# This is the sole entropy-bearing request. On an unloaded controller it
	# only sets shouldLoadWallet=true; repeating it after loading would create a
	# persistent wallet and is deliberately forbidden in this acceptance.
	if [ ! -s "$runtime/curl-api.conf" ] || [ ! -s "$runtime/trigger-body.txt" ]; then
		printf '%s\n' 'Ephemeral API request files are missing' >&2
		return 1
	fi
	if [ -e "$expected_cache" ] || grep -F " $expected_library" \
			"/proc/$core_pid/maps" >/dev/null 2>&1; then
		printf '%s\n' 'Native cache/library was unexpectedly present before the one-shot trigger' >&2
		return 1
	fi
	if ! curl --fail --silent --show-error --max-time 10 \
			--config "$runtime/curl-api.conf" \
			--data-binary "@$runtime/trigger-body.txt" \
			"http://127.0.0.1:$api_port/crosschain/arrr/syncstatus?json=true" \
			> "$runtime/trigger-response.json"; then
		printf '%s\n' 'The one-shot native-load trigger failed' >&2
		return 1
	fi
	if ! grep -Eq '"state"[[:space:]]*:[[:space:]]*"LOADING"' \
			"$runtime/trigger-response.json" \
			|| ! grep -F 'Pirate Chain wallet isn'"'"'t initialized yet' \
			"$runtime/trigger-response.json" >/dev/null 2>&1 \
			|| ! grep -Eq '"restartRequired"[[:space:]]*:[[:space:]]*false' \
			"$runtime/trigger-response.json"; then
		printf '%s\n' 'The one-shot response did not prove the non-initializing load path' >&2
		return 1
	fi
	printf 'triggerSafety=PASS\n' >> "$result_file"

	mapped=false
	wait_count=0
	while [ "$wait_count" -lt 180 ]; do
		if ! kill -0 "$core_pid" 2>/dev/null; then
			printf '%s\n' 'Packaged Core exited before the native library was mapped' >&2
			return 1
		fi
		if grep -F " $expected_library" "/proc/$core_pid/maps" >/dev/null 2>&1; then
			mapped=true
			break
		fi
		sleep 1
		wait_count=$((wait_count + 1))
	done
	if [ "$mapped" != true ]; then
		printf '%s\n' 'Timed out waiting for the QDN-derived native library mapping' >&2
		return 1
	fi

	if [ ! -d "$expected_cache" ] || [ ! -f "$expected_library" ]; then
		printf '%s\n' 'The expected native cache was not installed' >&2
		return 1
	fi
	library_sha256=$(sha256sum "$expected_library" | awk '{ print $1 }')
	library_inode=$(stat -Lc '%d:%i' "$expected_library")
	{
		printf 'localQdnResolution=PASS\n'
		printf 'nativeMapping=PASS\n'
		printf 'mappedLibraryPath=%s\n' "$expected_library"
		printf 'mappedLibrarySha256=%s\n' "$library_sha256"
		printf 'mappedLibraryDeviceInode=%s\n' "$library_inode"
	} >> "$result_file"
	find "$bundle" -mindepth 1 -maxdepth 1 -type f -printf '%f\n' | LC_ALL=C sort \
		> "$runtime/source-inventory.txt"
	find "$expected_cache" -mindepth 1 -maxdepth 1 -type f -printf '%f\n' | LC_ALL=C sort \
		> "$runtime/cache-inventory.txt"
	if ! cmp -s "$runtime/source-inventory.txt" "$runtime/cache-inventory.txt"; then
		printf '%s\n' 'Installed cache inventory differs from the staged bundle' >&2
		return 1
	fi
	while IFS= read -r filename; do
		source_hash=$(sha256sum "$bundle/$filename" | awk '{ print $1 }')
		cache_hash=$(sha256sum "$expected_cache/$filename" | awk '{ print $1 }')
		if [ "$source_hash" != "$cache_hash" ]; then
			printf 'Installed cache hash differs for %s\n' "$filename" >&2
			return 1
		fi
	done < "$runtime/source-inventory.txt"
	printf 'cacheInstallation=PASS\n' >> "$result_file"
	if [ -e "$unified_wallets" ]; then
		printf '%s\n' 'A persistent Unified wallet storage path was created' >&2
		return 1
	fi
	printf 'walletStorage=ABSENT\n' >> "$result_file"

	kill -TERM "$core_pid"
	wait_count=0
	while kill -0 "$core_pid" 2>/dev/null && [ "$wait_count" -lt 60 ]; do
		sleep 1
		wait_count=$((wait_count + 1))
	done
	if kill -0 "$core_pid" 2>/dev/null; then
		printf '%s\n' 'Packaged Core did not stop after SIGTERM' >&2
		return 1
	fi
	wait "$core_pid" 2>/dev/null || true
	core_pid=
	if ! grep -F 'Shutdown complete!' "$log" >/dev/null 2>&1; then
		printf '%s\n' 'Packaged Core log does not contain graceful-shutdown confirmation' >&2
		return 1
	fi
	printf 'gracefulShutdown=PASS\nresult=PASS\n' >> "$result_file"
	trap - 0 HUP INT TERM
	return 0
}

if [ "${1:-}" = '--inside-network-namespace' ]; then
	[ "$#" -eq 7 ] || exit 2
	shift
	run_inside_namespace "$@"
	exit $?
fi

if [ "$#" -ne 4 ]; then
	usage
	exit 2
fi

jar=$1
bundle=$2
fixture=$3
receipt=$4
require_absolute 'Packaged JAR' "$jar"
require_absolute 'Bundle' "$bundle"
require_absolute 'Fixture' "$fixture"
require_absolute 'Receipt' "$receipt"

case "$(uname -s):$(uname -m)" in
	Linux:x86_64) ;;
	*) printf '%s\n' 'Packaged loader acceptance currently supports Linux x86_64 only' >&2; exit 1 ;;
esac
for command_name in awk cat cmp cp curl find grep ip java od sed sha256sum sort stat tr unshare; do
	require_command "$command_name"
done
# The command substitutions are intentionally evaluated by the isolated shell.
# shellcheck disable=SC2016
if ! unshare -Urn sh -c '
	ip link set lo up || exit 1
	[ "$(ip -o link show | grep -Evc "^[0-9]+: lo(:|@)")" -eq 0 ] || exit 1
	! ip route show table all | grep -Eq "^default "
'; then
	printf '%s\n' 'Rootless loopback-only network namespace preflight failed' >&2
	exit 1
fi
if [ ! -f "$jar" ] || [ -L "$jar" ]; then
	printf 'Packaged JAR must be a regular, non-symlink file: %s\n' "$jar" >&2
	exit 1
fi
if [ ! -d "$bundle" ] || [ -L "$bundle" ]; then
	printf 'Bundle must be a directory, not a symlink: %s\n' "$bundle" >&2
	exit 1
fi
if [ ! -d "$fixture" ] || [ -L "$fixture" ]; then
	printf 'Fixture must be a directory, not a symlink: %s\n' "$fixture" >&2
	exit 1
fi

script_directory=$(CDPATH='' cd "$(dirname "$0")" && pwd)
repository=$(CDPATH='' cd "$script_directory/.." && pwd)
test_chain=$repository/src/test/resources/test-chain-v2.json
fixture_properties=$fixture/fixture.properties
manifest=$bundle/QORTIUM-MANIFEST.txt
if [ ! -f "$test_chain" ] || [ ! -f "$fixture_properties" ] || [ ! -f "$manifest" ] \
		|| [ ! -f "$fixture/repository/blockchain.properties" ] || [ ! -d "$fixture/data" ]; then
	printf '%s\n' 'Packaged loader inputs are incomplete' >&2
	exit 1
fi

format=$(property format "$fixture_properties") || {
	printf '%s\n' 'Fixture format property must appear exactly once' >&2; exit 1;
}
signature=$(property signature "$fixture_properties") || {
	printf '%s\n' 'Fixture signature property must appear exactly once' >&2; exit 1;
}
fixture_manifest_sha256=$(property bundleManifestSha256 "$fixture_properties") || {
	printf '%s\n' 'Fixture manifest hash property must appear exactly once' >&2; exit 1;
}
transaction_state=$(property transactionState "$fixture_properties") || {
	printf '%s\n' 'Fixture transaction state property must appear exactly once' >&2; exit 1;
}
if [ "$format" != 'qortium-pirate-unified-local-qdn-fixture-v2' ] \
		|| [ "$transaction_state" != 'synthetic-direct-repository-row' ] \
		|| ! printf '%s\n' "$signature" | grep -Eq '^[1-9A-HJ-NP-Za-km-z]{80,100}$' \
		|| ! printf '%s\n' "$fixture_manifest_sha256" | grep -Eq '^[0-9a-f]{64}$'; then
	printf '%s\n' 'Fixture provenance contract is invalid' >&2
	exit 1
fi
for expected_property in \
	'service=ARBITRARY_DATA' \
	'repositoryPath=repository' \
	'dataPath=data' \
	'tempDataPath=temp' \
	'walletsPath=wallets' \
	'arbitraryResourceCacheReady=true' \
	'unconfirmedPoolEntry=false' \
	'blockHeight=null'; do
	expected_key=${expected_property%%=*}
	expected_value=${expected_property#*=}
	actual_value=$(property "$expected_key" "$fixture_properties" 2>/dev/null || true)
	if [ "$actual_value" != "$expected_value" ]; then
		printf 'Fixture property is missing, duplicated, or invalid: %s\n' "$expected_property" >&2
		exit 1
	fi
done
manifest_sha256=$(sha256sum "$manifest" | awk '{ print $1 }')
if [ "$manifest_sha256" != "$fixture_manifest_sha256" ]; then
	printf '%s\n' 'Staged bundle manifest does not match fixture provenance' >&2
	exit 1
fi

receipt_parent=$(dirname "$receipt")
receipt_name=$(basename "$receipt")
mkdir -p "$receipt_parent"
receipt_parent=$(CDPATH='' cd "$receipt_parent" && pwd -P)
if printf '%s' "$receipt_parent" | LC_ALL=C grep -q '[\\"[:cntrl:]]'; then
	printf '%s\n' 'Receipt parent path contains characters unsafe for generated JSON settings' >&2
	exit 1
fi
receipt=$receipt_parent/$receipt_name
if [ -e "$receipt" ] || [ -e "$receipt.log" ]; then
	printf 'Refusing to overwrite receipt or log: %s\n' "$receipt" >&2
	exit 1
fi
lock_directory=$receipt.lock
if ! mkdir "$lock_directory" 2>/dev/null; then
	printf 'Another packaged acceptance run owns this receipt: %s\n' "$receipt" >&2
	exit 1
fi
work_directory=
# The cleanup function is invoked indirectly by EXIT.
# shellcheck disable=SC2317
cleanup_outer() {
	if [ -n "$work_directory" ] && [ -d "$work_directory" ]; then
		rm -rf "$work_directory"
	fi
	rmdir "$lock_directory" 2>/dev/null || true
}
trap 'cleanup_outer' 0
trap 'exit 130' HUP INT TERM
if [ -e "$receipt" ] || [ -e "$receipt.log" ]; then
	printf 'Refusing to overwrite receipt or log created while acquiring lock: %s\n' "$receipt" >&2
	exit 1
fi
work_directory=$(mktemp -d "$receipt_parent/.pirate-unified-packaged-loader.XXXXXX")

mkdir -p "$work_directory/runtime/repository" "$work_directory/runtime/data" \
	"$work_directory/runtime/temp" "$work_directory/runtime/api" \
	"$work_directory/runtime/lists" "$work_directory/runtime/export"
cp -a --reflink=auto "$fixture/repository/." "$work_directory/runtime/repository/"
cp -a --reflink=auto "$fixture/data/." "$work_directory/runtime/data/"
cp "$test_chain" "$work_directory/runtime/test-chain-v2.json"
api_key=$(od -An -N32 -tx1 /dev/urandom | tr -d ' \n')
printf '%s' "$api_key" > "$work_directory/runtime/api/apikey.txt"
chmod 600 "$work_directory/runtime/api/apikey.txt"
printf '%s\n' "header = \"X-API-KEY: $api_key\"" \
	'header = "Content-Type: text/plain"' > "$work_directory/runtime/curl-api.conf"
printf '%s' '5oSXF53qENtdUyKhqSxYzP57m6RhVFP9BJKRr9E5kRGV' \
	> "$work_directory/runtime/trigger-body.txt"
chmod 600 "$work_directory/runtime/curl-api.conf" "$work_directory/runtime/trigger-body.txt"
api_key=

runtime=$work_directory/runtime
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
  "pirateChainNet": "TEST3",
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
	"$runtime" "$jar" "$bundle" "$signature" "$receipt.log" "$result_file" \
	> "$harness_log" 2>&1
acceptance_status=$?
set -e
{
	printf '\n--- packaged-loader harness diagnostics ---\n'
	cat "$harness_log"
} >> "$receipt.log"

result=FAIL
network_result=NOT_PROVEN
trigger_result=NOT_PROVEN
qdn_result=NOT_PROVEN
cache_result=NOT_PROVEN
mapping_result=NOT_PROVEN
wallet_storage_result=UNKNOWN
shutdown_result=NOT_PROVEN
non_loopback_interfaces=UNKNOWN
default_routes=UNKNOWN
non_loopback_routes=UNKNOWN
mapped_library_path=UNAVAILABLE
mapped_library_sha256=UNAVAILABLE
mapped_library_device_inode=UNAVAILABLE
if [ -f "$result_file" ]; then
	network_result=$(property networkEgress "$result_file" 2>/dev/null || printf 'NOT_PROVEN')
	trigger_result=$(property triggerSafety "$result_file" 2>/dev/null || printf 'NOT_PROVEN')
	qdn_result=$(property localQdnResolution "$result_file" 2>/dev/null || printf 'NOT_PROVEN')
	cache_result=$(property cacheInstallation "$result_file" 2>/dev/null || printf 'NOT_PROVEN')
	mapping_result=$(property nativeMapping "$result_file" 2>/dev/null || printf 'NOT_PROVEN')
	wallet_storage_result=$(property walletStorage "$result_file" 2>/dev/null || printf 'UNKNOWN')
	shutdown_result=$(property gracefulShutdown "$result_file" 2>/dev/null || printf 'NOT_PROVEN')
	non_loopback_interfaces=$(property nonLoopbackInterfaces "$result_file" 2>/dev/null || printf 'UNKNOWN')
	default_routes=$(property defaultRoutes "$result_file" 2>/dev/null || printf 'UNKNOWN')
	non_loopback_routes=$(property nonLoopbackRoutes "$result_file" 2>/dev/null || printf 'UNKNOWN')
	mapped_library_path=$(property mappedLibraryPath "$result_file" 2>/dev/null || printf 'UNAVAILABLE')
	mapped_library_sha256=$(property mappedLibrarySha256 "$result_file" 2>/dev/null || printf 'UNAVAILABLE')
	mapped_library_device_inode=$(property mappedLibraryDeviceInode "$result_file" 2>/dev/null || printf 'UNAVAILABLE')
	if [ "$acceptance_status" -eq 0 ] \
			&& [ "$(property result "$result_file" 2>/dev/null || true)" = PASS ]; then
		result=PASS
	fi
fi

core_commit=$(cd "$repository" && git rev-parse HEAD)
tree_state=clean
if [ -n "$(cd "$repository" && git status --porcelain)" ]; then
	tree_state=dirty
fi
jar_sha256=$(sha256sum "$jar" | awk '{ print $1 }')
test_chain_sha256=$(sha256sum "$test_chain" | awk '{ print $1 }')
timestamp=$(date -u '+%Y-%m-%dT%H:%M:%SZ')
host=$(uname -srm)
temporary_receipt=$work_directory/receipt.md
# Backticks below are literal Markdown delimiters.
# shellcheck disable=SC2016
{
	printf '# Pirate Unified packaged-loader acceptance receipt\n\n'
	printf '%s `%s`\n' '- Timestamp:' "$timestamp"
	printf '%s `%s`\n' '- Result:' "$result"
	printf '%s `%s`\n' '- Core source commit:' "$core_commit"
	printf '%s `%s`\n' '- Core source tree:' "$tree_state"
	printf '%s `%s`\n' '- Host:' "$host"
	printf '%s `%s`\n' '- Packaged Core JAR:' "$jar"
	printf '%s `%s`\n' '- Packaged Core JAR SHA-256:' "$jar_sha256"
	printf '%s `%s`\n' '- Local-QDN fixture:' "$fixture"
	printf '%s `%s`\n' '- Synthetic QDN signature:' "$signature"
	printf '%s `%s`\n' '- Staged bundle:' "$bundle"
	printf '%s `%s`\n' '- Bundle manifest SHA-256:' "$manifest_sha256"
	printf '%s `%s`\n' '- Test-chain config SHA-256:' "$test_chain_sha256"
	printf '%s `%s`\n' '- Full log:' "$receipt.log"
	printf '\n## Results\n\n'
	printf '| Boundary | Result | Evidence |\n'
	printf '|---|---:|---|\n'
	printf '| Network egress | %s | rootless network namespace; non-loopback interfaces `%s`; default routes `%s`; non-loopback routes `%s` |\n' "$network_result" "$non_loopback_interfaces" "$default_routes" "$non_loopback_routes"
	printf '| One-shot trigger safety | %s | library absent before POST; structured response required `LOADING`, not initialized, and no restart |\n' "$trigger_result"
	printf '| Local-QDN resolution | %s | production `TRANSACTION_DATA` reader using retained repository/data fixture |\n' "$qdn_result"
	printf '| Cache installation | %s | fresh target; exact staged/cache inventory and SHA-256 equality |\n' "$cache_result"
	printf '| Native `System.load` | %s | expected cache pathname observed in `/proc/<pid>/maps` |\n' "$mapping_result"
	printf '| Persistent wallet storage | %s | `wallets/PirateChain/unified` checked before trigger and after mapping |\n' "$wallet_storage_result"
	printf '| Graceful shutdown | %s | SIGTERM followed by `Shutdown complete!` |\n' "$shutdown_result"
	printf '| Peer retrieval | NOT_PROVEN | sandbox contains no peer path; local fixture only |\n'
	if [ "$trigger_result" = PASS ] && [ "$wallet_storage_result" = ABSENT ]; then
		printf '| Wallet lifecycle | NOT_RUN | exactly one entropy-bearing trigger; no second wallet-creating call |\n'
	else
		printf '| Wallet lifecycle | UNKNOWN | one-shot safety or storage absence was not proven |\n'
	fi
	printf '\n## Native mapping\n\n'
	printf '%s `%s`\n' '- Path:' "$mapped_library_path"
	printf '%s `%s`\n' '- SHA-256:' "$mapped_library_sha256"
	printf '%s `%s`\n' '- Device and inode:' "$mapped_library_device_inode"
	printf '\n## Counterexamples\n\n'
	if [ "$result" = PASS ]; then
		printf 'None observed in this run.\n'
	else
		printf 'Acceptance failed. Inspect the retained full log before continuing.\n'
	fi
	printf '\nThis receipt proves a local-fixture packaged loader path on Linux x86_64 only. '
	printf 'It is not a QDN publication, peer-retrieval, wallet-creation, synchronization, migration, or funded-wallet result.\n'
} > "$temporary_receipt"

mv "$temporary_receipt" "$receipt"
trap - 0 HUP INT TERM
cleanup_outer
work_directory=
if [ "$acceptance_status" -ne 0 ] || [ "$result" != PASS ]; then
	printf 'Packaged loader acceptance failed; receipt: %s, log: %s\n' "$receipt" "$receipt.log" >&2
	exit 1
fi
printf 'Packaged loader acceptance passed; receipt: %s, log: %s\n' "$receipt" "$receipt.log"
