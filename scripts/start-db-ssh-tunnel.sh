#!/usr/bin/env bash

set -euo pipefail

LOCAL_PORT="${LOCAL_DB_PORT:-3307}"
REMOTE_HOST="${REMOTE_DB_HOST:-127.0.0.1}"
REMOTE_PORT="${REMOTE_DB_PORT:-3306}"
SSH_HOST="${SSH_HOST_ALIAS:-railway-cloud}"

exec ssh -N -L "${LOCAL_PORT}:${REMOTE_HOST}:${REMOTE_PORT}" "${SSH_HOST}"
