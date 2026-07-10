#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
POWER_PYTHON="${ROOT_DIR}/power-network-service/.venv/bin/python"
FMU_TEST_IMAGE="${FMU_TEST_IMAGE:-railway-sim-fmu-test:local}"

if [[ ! -x "${POWER_PYTHON}" ]]; then
  POWER_PYTHON="python3"
fi

cd "${ROOT_DIR}"

mvn -q -f vehicle-runtime-service/pom.xml test

PYTHONPATH=power-network-service "${POWER_PYTHON}" -m app.self_test
PYTHONPATH=power-network-service "${POWER_PYTHON}" -m unittest discover -s power-network-service/tests -v

if ! docker image inspect "${FMU_TEST_IMAGE}" >/dev/null 2>&1; then
  docker build --platform linux/amd64 \
    -f fmu-service/Dockerfile \
    --target test \
    -t "${FMU_TEST_IMAGE}" \
    .
fi

docker run --rm --platform linux/amd64 \
  -v "${ROOT_DIR}/fmu-service/app:/app/app:ro" \
  -v "${ROOT_DIR}/fmu-service/tests:/app/tests:ro" \
  "${FMU_TEST_IMAGE}" pytest -q /app/tests

mvn -q -f backend/pom.xml \
  -Dtest=VehicleRuntimeIntegrationServiceTests,PowerIntegrationServiceTests,PowerNetworkStateSnapshotTests \
  test

echo "WP5-WP6 vehicle/FMU/power closed-loop tests passed"
