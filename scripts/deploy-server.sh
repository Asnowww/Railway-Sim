#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${1:-${ROOT_DIR}/deploy/server.env}"

if [[ ! -f "${ENV_FILE}" ]]; then
  echo "missing deployment environment: ${ENV_FILE}" >&2
  echo "copy deploy/server.env.example to deploy/server.env and replace CHANGE_ME values" >&2
  exit 1
fi
if rg -q "CHANGE_ME" "${ENV_FILE}"; then
  echo "deployment environment still contains CHANGE_ME values: ${ENV_FILE}" >&2
  exit 1
fi

set -a
# shellcheck disable=SC1090
source "${ENV_FILE}"
set +a

BIND_ADDRESS="${INTERNAL_BIND_ADDRESS:-127.0.0.1}"
if [[ "${BIND_ADDRESS}" == "0.0.0.0" ]]; then
  BIND_ADDRESS="127.0.0.1"
fi
FMU_URL="http://${BIND_ADDRESS}:${FMU_PORT:-9000}"
POWER_URL="http://${BIND_ADDRESS}:${POWER_NETWORK_PORT:-9200}"
VEHICLE_URL="http://${BIND_ADDRESS}:${VEHICLE_RUNTIME_PORT:-9300}"

cd "${ROOT_DIR}"
export SOURCE_COMMIT="$(git rev-parse HEAD 2>/dev/null || echo unknown)"
docker compose --env-file "${ENV_FILE}" config --quiet
docker compose --env-file "${ENV_FILE}" build
docker compose --env-file "${ENV_FILE}" up -d --wait --wait-timeout 240
CHECK_ARGS=(--vehicle "${VEHICLE_URL}" --power "${POWER_URL}" --fmu "${FMU_URL}")
if [[ "${VEHICLE_PHYSICS_MODE:-FMU_HTTP}" == "JAVA_FALLBACK" ]]; then
  CHECK_ARGS+=(--allow-java-fallback)
fi
python3 scripts/check-deployment.py "${CHECK_ARGS[@]}"

echo "Railway-Sim simulation services are healthy: ${FMU_URL}, ${POWER_URL}, ${VEHICLE_URL}"
