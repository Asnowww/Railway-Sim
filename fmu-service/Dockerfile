ARG OPENMODELICA_IMAGE=openmodelica/openmodelica@sha256:80fbff1a66fb6a6ade64a158415a45e022363249982c9f3ade07df2a369a357e
FROM ${OPENMODELICA_IMAGE} AS fmu-builder

WORKDIR /workspace
COPY fmu-service/modelica /workspace/fmu-service/modelica
RUN mkdir -p /workspace/fmu-service/build \
    && omc /workspace/fmu-service/modelica/build_fmu.mos \
    && test -f /workspace/fmu-service/build/TrainTractionBrake.fmu

FROM python:3.12-slim@sha256:423ed6ab25b1921a477529254bfeeabf5855151dc2c3141699a1bfc852199fbf AS runtime-base

ARG SOURCE_COMMIT=unknown
ARG OPENMODELICA_DIGEST=sha256:80fbff1a66fb6a6ade64a158415a45e022363249982c9f3ade07df2a369a357e

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    TRAIN_PARAMS_PATH=/config/train_params.yaml \
    FMU_PATH=/app/fmu/TrainTractionBrake.fmu \
    FMU_MANIFEST_PATH=/app/fmu/fmu-manifest.json \
    FMU_MAPPING_PATH=/config/fmu_mapping.yaml \
    FMU_MANIFEST_SCHEMA_PATH=/app/fmu-manifest.schema.json

WORKDIR /app
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl libgomp1 \
    && rm -rf /var/lib/apt/lists/*

COPY fmu-service/requirements.txt /app/requirements.txt
RUN python -m pip install --no-cache-dir -r /app/requirements.txt

COPY fmu-service/app /app/app
COPY fmu-service/contracts /app/contracts
COPY fmu-service/fmu-manifest.schema.json /app/fmu-manifest.schema.json
COPY fmu-service/scripts/generate_fmu_manifest.py /app/scripts/generate_fmu_manifest.py
COPY config/train_params.yaml /config/train_params.yaml
COPY config/fmu_mapping.yaml /config/fmu_mapping.yaml
COPY --from=fmu-builder /workspace/fmu-service/build/TrainTractionBrake.fmu /app/fmu/TrainTractionBrake.fmu

RUN python /app/scripts/generate_fmu_manifest.py \
      --fmu /app/fmu/TrainTractionBrake.fmu \
      --parameter-file /config/train_params.yaml \
      --output /app/fmu/fmu-manifest.json \
      --openmodelica-digest "$OPENMODELICA_DIGEST" \
      --source-commit "$SOURCE_COMMIT"

FROM runtime-base AS test
COPY fmu-service/requirements-test.txt /app/requirements-test.txt
RUN python -m pip install --no-cache-dir -r /app/requirements-test.txt
COPY fmu-service/tests /app/tests
CMD ["pytest", "-q", "/app/tests"]

FROM runtime-base AS runtime
EXPOSE 9000
HEALTHCHECK --interval=5s --timeout=2s --retries=10 \
  CMD curl -fsS http://127.0.0.1:9000/health || exit 1
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "9000", "--workers", "1"]
