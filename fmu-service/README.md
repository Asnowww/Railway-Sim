# FMU Vehicle Physics Service

This service is the legacy Python prototype boundary for the Modelica/FMU vehicle physics model.
Spring Boot remains the simulation coordinator and sends one batch request per
simulation tick. The FMU service owns the traction, braking, resistance,
longitudinal dynamics, current draw, and regenerative braking calculations.

The current preferred external vehicle runtime is `vehicle-runtime-service`
on port `9300`. It is a Spring Boot service that manages per-train control and
simulation queues. This Python service is retained as an FMU prototype and
compatibility scaffold; it has not been wired into the new queue-based runtime.

Current scaffold:

```text
fmu-service
├── app
│   ├── fleet_stepper.py
│   ├── fmu_manager.py
│   ├── http_server.py
│   ├── input_mapper.py
│   ├── output_mapper.py
│   ├── schemas.py
│   ├── simple_fallback.py
│   └── train_fmu_instance.py
└── modelica
    └── TrainTractionBrake.mo
```

The backend currently uses a Java fallback model through `FmuVehiclePhysicsAdapter`
so the project can run before the exported FMU is available. Once the Modelica
model is exported as an FMI 2.0 Co-Simulation FMU, the adapter can call this
service over HTTP or gRPC without changing the TCMS/ATO adapter or the train
state contract.

Run the current fallback-backed HTTP service:

```bash
cd fmu-service
python3 -m app.http_server
```

Endpoints:

```text
GET  /health
POST /step-fleet
```

`/api/fleet/step` is kept as a compatibility alias for early adapter
experiments; the Spring Boot adapter uses `/step-fleet`.

The service keeps one `TrainFMUInstance` per train ID through `FleetStepper`, so
the future FMU runtime has a stable place to store per-train FMU state.

Run a local smoke test without starting the HTTP server:

```bash
cd fmu-service
python3 -m app.self_test
```
