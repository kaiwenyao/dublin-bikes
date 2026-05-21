#!/bin/sh
set -eu

if [ -z "${BACKEND_HOST:-}" ]; then
  echo "ERROR: BACKEND_HOST environment variable is not set" >&2
  exit 1
fi

if [ -z "${BACKEND_PORT:-}" ]; then
  echo "ERROR: BACKEND_PORT environment variable is not set" >&2
  exit 1
fi

envsubst '${BACKEND_HOST} ${BACKEND_PORT}' \
  < /etc/nginx/templates/default.conf.template \
  > /etc/nginx/conf.d/default.conf
