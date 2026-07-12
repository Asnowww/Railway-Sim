#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if [[ "${1:-}" == "--bootstrap" ]]; then
  "$ROOT_DIR/scripts/bootstrap-test-env.sh"
elif [[ $# -gt 0 ]]; then
  echo "Usage: $0 [--bootstrap]" >&2
  exit 2
fi

require_executable() {
  if [[ ! -x "$1" ]]; then
    echo "Required executable not found: $1. Run $0 --bootstrap first." >&2
    exit 1
  fi
}

require_executable "$ROOT_DIR/power-network-service/.venv/bin/python"
require_executable "$ROOT_DIR/fmu-service/.venv/bin/pytest"

if ! command -v docker >/dev/null 2>&1; then
  echo "Docker is required to test the Linux/amd64 FMU artifact." >&2
  exit 1
fi

run_group() {
  local label="$1"
  shift
  echo
  echo "==> $label"
  "$@"
}

run_group "8080 backend Maven tests" \
  mvn -f "$ROOT_DIR/backend/pom.xml" test

run_group "9300 vehicle runtime Maven tests" \
  mvn -f "$ROOT_DIR/vehicle-runtime-service/pom.xml" test

run_group "9200 power network unittest" \
  env PYTHONPATH="$ROOT_DIR/power-network-service" \
  "$ROOT_DIR/power-network-service/.venv/bin/python" -m unittest discover \
  -s "$ROOT_DIR/power-network-service/tests" -v

FMU_TEST_IMAGE="railway-sim-fmu-test:verify-${GITHUB_RUN_ID:-local}"
run_group "9000 FMU pytest image build" \
  docker build --platform linux/amd64 \
  -f "$ROOT_DIR/fmu-service/Dockerfile" \
  --target test \
  -t "$FMU_TEST_IMAGE" \
  "$ROOT_DIR"
run_group "9000 FMU pytest" \
  docker run --rm --platform linux/amd64 "$FMU_TEST_IMAGE"

run_group "Frontend strict TypeScript build" \
  pnpm --dir "$ROOT_DIR/frontend" build

echo
echo "All five Railway-Sim verification groups passed."

