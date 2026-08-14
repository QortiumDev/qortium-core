#!/bin/sh
set -eu

if [ "$#" -ne 2 ]; then
    echo "Usage: docker-init-settings.sh <template> <settings-file>" >&2
    exit 64
fi

template_file=$1
settings_file=$2

# The mounted settings file belongs to the operator after first creation. This
# includes empty, malformed, and symlinked paths: Core should validate them,
# never silently replace them with a different network profile.
if [ -e "${settings_file}" ] || [ -L "${settings_file}" ]; then
    exit 0
fi

if [ ! -f "${template_file}" ] || [ ! -r "${template_file}" ]; then
    echo "ERROR: Docker settings template is not readable: ${template_file}" >&2
    exit 66
fi

settings_directory=$(dirname "${settings_file}")
if [ ! -d "${settings_directory}" ] || [ ! -w "${settings_directory}" ]; then
    echo "ERROR: Docker settings directory is not writable: ${settings_directory}" >&2
    exit 73
fi

umask 077
temporary_file=$(mktemp "${settings_file}.tmp.XXXXXX")
trap 'rm -f "${temporary_file}"' EXIT HUP INT TERM
cp "${template_file}" "${temporary_file}"

# A hard link installs the completed file without overwriting a path created by
# a concurrent initializer. Both paths are in the settings directory, so the
# operation stays on one filesystem.
if ! ln "${temporary_file}" "${settings_file}"; then
    if [ -e "${settings_file}" ] || [ -L "${settings_file}" ]; then
        exit 0
    fi

    echo "ERROR: Unable to initialize Docker settings: ${settings_file}" >&2
    exit 74
fi

rm -f "${temporary_file}"
trap - EXIT HUP INT TERM
echo "Initialized ${settings_file} from the bundled Previewnet profile."
