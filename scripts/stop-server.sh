#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${1:-${ROOT_DIR}/deploy/server.env}"
ACTION="${2:-stop}"

if [[ ! -f "${ENV_FILE}" ]]; then
  echo "missing deployment environment: ${ENV_FILE}" >&2
  exit 1
fi

cd "${ROOT_DIR}"
docker compose --env-file "${ENV_FILE}" stop vehicle-runtime
docker compose --env-file "${ENV_FILE}" stop fmu power-network
if [[ "${ACTION}" == "down" ]]; then
  docker compose --env-file "${ENV_FILE}" down
fi
