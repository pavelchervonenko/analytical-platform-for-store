#!/bin/sh

set -eu

secret_source='/run/secrets'
secret_target='/tmp/configtree'

mkdir -p "${secret_target}"
if [ -d "${secret_source}" ]; then
  for secret_file in "${secret_source}"/*; do
    [ -f "${secret_file}" ] || continue
    cp "${secret_file}" "${secret_target}/$(basename -- "${secret_file}")"
  done
fi

chmod 0700 "${secret_target}"
find "${secret_target}" -type f -exec chmod 0400 {} \;
chown -R app:app "${secret_target}"

export SPRING_CONFIG_IMPORT="configtree:${secret_target}/"
exec su-exec app "$@"
