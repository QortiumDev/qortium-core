#!/bin/sh
set -eu

# Prepares a disposable repository/data fixture through Core's production QDN
# writer and reader. It never signs, imports, broadcasts, confirms, or mints.

usage() {
	printf '%s\n' "Usage: $0 <absolute-staged-bundle-directory> <absolute-new-fixture-directory>" >&2
}

if [ "$#" -ne 2 ]; then
	usage
	exit 2
fi

bundle=$1
fixture=$2
case $bundle in
	/*) ;;
	*) printf '%s\n' 'Bundle path must be absolute' >&2; exit 2 ;;
esac
case $fixture in
	/*) ;;
	*) printf '%s\n' 'Fixture path must be absolute' >&2; exit 2 ;;
esac
if [ ! -d "$bundle" ]; then
	printf 'Bundle directory does not exist: %s\n' "$bundle" >&2
	exit 1
fi
if [ -e "$fixture" ]; then
	printf 'Refusing to overwrite existing fixture: %s\n' "$fixture" >&2
	exit 1
fi

script_directory=$(CDPATH='' cd "$(dirname "$0")" && pwd)
repository=$(CDPATH='' cd "$script_directory/.." && pwd)
fixture_parent=$(dirname "$fixture")
fixture_name=$(basename "$fixture")
mkdir -p "$fixture_parent"
fixture_parent=$(CDPATH='' cd "$fixture_parent" && pwd -P)
fixture=$fixture_parent/$fixture_name
if [ -e "$fixture" ]; then
	printf 'Refusing to overwrite fixture created during preparation: %s\n' "$fixture" >&2
	exit 1
fi

staging_directory=$(mktemp -d "$fixture_parent/.pirate-unified-qdn-fixture.XXXXXX")
trap 'rm -rf "$staging_directory"' 0 HUP INT TERM
report_suffix=$(basename "$staging_directory" | sed 's/[^A-Za-z0-9]/-/g')

(cd "$repository" && mvn -DskipTests=false \
	-Dqortium.preparePirateUnifiedLocalQdnFixture=true \
	-Dqortium.pirateUnifiedBundlePath="$bundle" \
	-Dqortium.pirateUnifiedQdnFixturePath="$staging_directory" \
	-Dsurefire.reportNameSuffix="$report_suffix" \
	-Dtest=PirateUnifiedLocalQdnFixtureTests test)

report="$repository/target/surefire-reports/TEST-org.qortium.controller.PirateUnifiedLocalQdnFixtureTests-$report_suffix.xml"
if [ ! -f "$report" ] || ! grep -q 'tests="1"' "$report" \
		|| ! grep -q 'errors="0"' "$report" || ! grep -q 'failures="0"' "$report" \
		|| ! grep -q 'skipped="0"' "$report"; then
	printf '%s\n' 'Fixture preparation did not produce one passing, non-skipped test' >&2
	exit 1
fi
if [ ! -f "$staging_directory/fixture.properties" ] \
		|| [ ! -f "$staging_directory/repository/blockchain.properties" ] \
		|| [ ! -d "$staging_directory/data" ]; then
	printf '%s\n' 'Fixture preparation output is incomplete' >&2
	exit 1
fi
if [ -e "$fixture" ]; then
	printf 'Refusing to replace fixture created during preparation: %s\n' "$fixture" >&2
	exit 1
fi

mv "$staging_directory" "$fixture"
trap - 0 HUP INT TERM
signature=$(awk -F= '$1 == "signature" { print $2 }' "$fixture/fixture.properties")
printf 'Prepared unsigned local-QDN fixture at %s (synthetic signature %s)\n' "$fixture" "$signature"
