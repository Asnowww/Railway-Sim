#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${1:-${ROOT_DIR}/deploy/server.env}"
REPORT_DIR="${ROOT_DIR}/docs/真实FMU集成实施计划/验收记录"
POWER_PYTHON="${ROOT_DIR}/power-network-service/.venv/bin/python"

if [[ ! -f "${ENV_FILE}" ]]; then
  echo "missing deployment environment: ${ENV_FILE}" >&2
  exit 1
fi

set -a
# shellcheck disable=SC1090
source "${ENV_FILE}"
set +a

if [[ "${VEHICLE_PHYSICS_MODE:-FMU_HTTP}" != "FMU_HTTP" ]]; then
  echo "WP7-WP8 FMU acceptance requires VEHICLE_PHYSICS_MODE=FMU_HTTP" >&2
  exit 1
fi

BIND_ADDRESS="${INTERNAL_BIND_ADDRESS:-127.0.0.1}"
if [[ "${BIND_ADDRESS}" == "0.0.0.0" ]]; then
  BIND_ADDRESS="127.0.0.1"
fi
FMU_URL="http://${BIND_ADDRESS}:${FMU_PORT:-9000}"
POWER_URL="http://${BIND_ADDRESS}:${POWER_NETWORK_PORT:-9200}"
VEHICLE_URL="http://${BIND_ADDRESS}:${VEHICLE_RUNTIME_PORT:-9300}"

if [[ ! -x "${POWER_PYTHON}" ]]; then
  POWER_PYTHON=python3
fi

mkdir -p "${REPORT_DIR}"
cd "${ROOT_DIR}"
export SOURCE_COMMIT="$(git rev-parse HEAD 2>/dev/null || echo unknown)"

docker compose --env-file "${ENV_FILE}" config --quiet
docker compose --env-file "${ENV_FILE}" build
docker compose --env-file "${ENV_FILE}" up -d --wait --wait-timeout 240

# A fresh 9200/9300 process deliberately starts unbootstrapped. Drive one
# authoritative central tick so 8080 reapplies topology/runtime configuration.
curl -fsS -X POST "${CENTRAL_BASE_URL}/api/simulation/tick" >/dev/null

python3 scripts/check-deployment.py \
  --vehicle "${VEHICLE_URL}" \
  --power "${POWER_URL}" \
  --fmu "${FMU_URL}" \
  --output "${REPORT_DIR}/wp7-health.json"

python3 scripts/test-p1-end-to-end.py \
  --env-file "${ENV_FILE}" \
  --backend "${CENTRAL_BASE_URL}" \
  --vehicle "${VEHICLE_URL}" \
  --power "${POWER_URL}" \
  --output "${REPORT_DIR}/p1-end-to-end.json"

python3 scripts/test-deployment-recovery.py \
  --env-file "${ENV_FILE}" \
  --vehicle "${VEHICLE_URL}" \
  --fmu "${FMU_URL}" \
  --output "${REPORT_DIR}/wp7-recovery.json"

# Fault injection intentionally increments counters and advances the power tick.
# Recreate only the three simulation services before the clean performance run.
docker compose --env-file "${ENV_FILE}" down
docker compose --env-file "${ENV_FILE}" up -d --wait --wait-timeout 240

# Prove process-restart recovery rather than relying on stale central bootstrap state.
curl -fsS -X POST "${CENTRAL_BASE_URL}/api/simulation/tick" >/dev/null

python3 scripts/test-vehicle-runtime-performance.py \
  --vehicle "${VEHICLE_URL}" \
  --power "${POWER_URL}" \
  --samples "${BENCHMARK_SAMPLES:-100}" \
  --warmup "${BENCHMARK_WARMUP:-10}" \
  --endurance-ticks "${ENDURANCE_TICKS:-6000}" \
  --output "${REPORT_DIR}/wp8-performance.json"

mvn -q -f vehicle-runtime-service/pom.xml test
mvn -q -f backend/pom.xml test
PYTHONPATH=power-network-service "${POWER_PYTHON}" -m app.self_test
PYTHONPATH=power-network-service "${POWER_PYTHON}" -m unittest discover -s power-network-service/tests -v

FMU_TEST_IMAGE="${FMU_TEST_IMAGE:-railway-sim-fmu-test:local}"
# Always rebuild: mounting only app/tests onto an older image can silently mix a
# new parser with stale FMU/config/manifest generations.
docker build --platform "${TARGET_PLATFORM:-linux/amd64}" \
  -f fmu-service/Dockerfile --target test -t "${FMU_TEST_IMAGE}" .
docker run --rm --platform "${TARGET_PLATFORM:-linux/amd64}" \
  -v "${ROOT_DIR}/fmu-service/app:/app/app:ro" \
  -v "${ROOT_DIR}/fmu-service/tests:/app/tests:ro" \
  "${FMU_TEST_IMAGE}" pytest -q /app/tests

if [[ "${KEEP_ACCEPTANCE_STACK_RUNNING:-0}" != "1" ]]; then
  scripts/stop-server.sh "${ENV_FILE}" down
fi

echo "WP7-WP8 simulation-side deployment and acceptance passed"
