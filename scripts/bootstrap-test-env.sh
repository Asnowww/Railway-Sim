#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PYTHON_312="${PYTHON_312:-python3.12}"

if ! command -v "$PYTHON_312" >/dev/null 2>&1; then
  echo "Python 3.12 is required. Set PYTHON_312 to a Python 3.12 executable." >&2
  exit 1
fi

"$PYTHON_312" - <<'PY'
import sys

if sys.version_info[:2] != (3, 12):
    raise SystemExit(
        f"Python 3.12 is required, got {sys.version_info.major}.{sys.version_info.minor}"
    )
PY

ensure_venv() {
  local service_dir="$1"
  local requirements_file="$2"
  local venv_dir="$service_dir/.venv"

  if [[ -x "$venv_dir/bin/python" ]]; then
    if ! "$venv_dir/bin/python" - <<'PY' >/dev/null 2>&1
import sys
raise SystemExit(0 if sys.version_info[:2] == (3, 12) else 1)
PY
    then
      echo "Recreating $venv_dir because it is not Python 3.12"
      rm -rf "$venv_dir"
    fi
  fi

  if [[ ! -x "$venv_dir/bin/python" ]]; then
    "$PYTHON_312" -m venv "$venv_dir"
  fi

  "$venv_dir/bin/python" -m pip install --disable-pip-version-check -r "$requirements_file"
}

ensure_venv "$ROOT_DIR/power-network-service" "$ROOT_DIR/power-network-service/requirements.txt"
ensure_venv "$ROOT_DIR/fmu-service" "$ROOT_DIR/fmu-service/requirements-test.txt"

if ! command -v pnpm >/dev/null 2>&1; then
  echo "pnpm is required for the frontend. Install the version declared in frontend/package.json." >&2
  exit 1
fi

pnpm --dir "$ROOT_DIR" install --frozen-lockfile

echo "Test environments are ready (Python 3.12 and frontend dependencies)."

