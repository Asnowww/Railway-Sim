#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BUILD_DIR="$ROOT_DIR/fmu-service/build"
OPENMODELICA_DIGEST="sha256:80fbff1a66fb6a6ade64a158415a45e022363249982c9f3ade07df2a369a357e"
OPENMODELICA_IMAGE="openmodelica/openmodelica@$OPENMODELICA_DIGEST"
SOURCE_COMMIT="$(git -C "$ROOT_DIR" rev-parse HEAD)"

rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR"

docker run --rm --platform linux/amd64 \
  -v "$ROOT_DIR:/workspace" \
  -w /workspace \
  "$OPENMODELICA_IMAGE" \
  omc fmu-service/modelica/build_fmu.mos

test -f "$BUILD_DIR/TrainTractionBrake.fmu"

python3 "$ROOT_DIR/fmu-service/scripts/generate_fmu_manifest.py" \
  --fmu "$BUILD_DIR/TrainTractionBrake.fmu" \
  --parameter-file "$ROOT_DIR/config/train_params.yaml" \
  --output "$BUILD_DIR/fmu-manifest.json" \
  --openmodelica-digest "$OPENMODELICA_DIGEST" \
  --source-commit "$SOURCE_COMMIT"

echo "FMU: $BUILD_DIR/TrainTractionBrake.fmu"
echo "Manifest: $BUILD_DIR/fmu-manifest.json"
