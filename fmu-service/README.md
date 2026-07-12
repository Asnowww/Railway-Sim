# FMU Vehicle Physics Service (9000)

This service executes the real `TrainTractionBrake.fmu` through FMPy. It is the
vehicle-physics execution boundary only: Spring Boot on port 9300 remains the
owner of train control state, MA/speed-limit/station decisions, and fleet tick
orchestration.

The current WP4 boundary is intentionally not connected to the 9300 production
loop yet. That integration belongs to WP5.

## Runtime invariants

- FMI 2.0 Co-Simulation with a fixed `0.1 s` communication step.
- Linux/amd64, Python 3.12, FMPy 0.3.30, FastAPI 0.139.0, Uvicorn 0.51.0.
- One extracted FMU artifact per process and one persistent `FMU2Slave` per
  `trainId`.
- One Uvicorn worker. Multiple workers would split the in-memory instance map.
- `config/train_params.yaml` is the only vehicle calibration source. The FMU
  manifest, request, and loaded YAML must have the same `parameterSetId`.
- The Python `SimpleFallbackModel` is retained only as an offline reference; no
  production HTTP route invokes it.

`simulationTimeSeconds` is the start time of the requested communication step.
For example, an INIT at `0.0` advances the FMU to `0.1`; the next STEP must use
`simulationTimeSeconds=0.1`.

## Build and test

Python support is fixed to CPython 3.12 (`pyproject.toml` and `.python-version`).
Create the local tool/test environment from the repository root with:

```bash
PYTHON_312=python3.12 ./scripts/bootstrap-test-env.sh
```

The real FMU artifact targets Linux/amd64, so the authoritative pytest run is
the Docker test stage below rather than a native macOS invocation.

Run from the repository root:

```bash
./scripts/build-fmu.sh

docker build --platform linux/amd64 \
  -f fmu-service/Dockerfile \
  --target test \
  -t railway-sim-fmu-test:local \
  .

docker run --rm --platform linux/amd64 railway-sim-fmu-test:local
```

The generated `.fmu`, OpenModelica intermediates, SHA and manifest stay under
the ignored `fmu-service/build/` directory. Git tracks the Modelica source,
build scripts, manifest schema, and tests, not the FMU binary.

## Run

```bash
docker build --platform linux/amd64 \
  -f fmu-service/Dockerfile \
  --target runtime \
  -t railway-sim-fmu-runtime:local \
  .

docker run --rm --platform linux/amd64 \
  -p 9000:9000 \
  railway-sim-fmu-runtime:local
```

Compatibility entry point for an environment that already has all pinned
dependencies and artifact paths configured:

```bash
python -m app.http_server
```

## API

```text
GET    /health
GET    /fmu/metadata
POST   /fmu/validate
POST   /step-fleet
DELETE /instances/{trainId}
POST   /instances/{trainId}/reset
POST   /instances/reset-all
```

`/api/fleet/step` and `/parameters` remain compatibility aliases.

`POST /instances/{trainId}/reset` accepts the frozen `/step-fleet` request
envelope with exactly one matching train and `lifecycleCommand=RESET`. The main
`/step-fleet` endpoint supports `INIT`, `STEP`, `RESET`, and `RESYNC` directly.

Batch validation is completed before any `doStep`. Repeating the same tick with
the same canonical request returns the cached response. A conflicting or
out-of-order tick returns HTTP 409 without advancing any instance. A native FMI
failure affects only that train: successful trains remain in `trainOutputs`, the
failed train appears in `trainErrors`, and it can resume only through RESET or
RESYNC.
