# External Power Network Service

This service is the authoritative traction-power simulator.  In split mode it
is the only component that calculates a train's section, contact-rail voltage,
available traction power and power-protection constraint.  The central Spring
Boot service keeps dispatch, signal and control orchestration only.

Current scope:

- Accept virtual power network bootstrap data from the central system
- Keep in-memory state for 10kV medium-voltage buses, ring feeders,
  substations, devices, third-rail sections, isolators, return-current devices,
  and stray-current monitor points
- Return device-authoritative snapshots for the central `PowerIntegrationService`
- Execute simple switch, breaker, and maintenance operations
- Solve simplified medium-voltage feeder current, bus voltage drop, DC contact
  rail voltage, single-end/cross-feed support mode, and stray-current risk

Run the service:

```bash
cd power-network-service
python3 -m venv .venv
.venv/bin/python -m pip install -r requirements.txt
.venv/bin/python -m app.http_server
```

Endpoints:

```text
GET  /health
GET  /power-network/state
GET  /power-network/topology
GET  /power-network/events
POST /power-network/bootstrap
POST /power-network/state/query
POST /power-network/constraints/query
POST /power-network/step
POST /power-network/operations
```

`POST /power-network/step` is the split-mode closed-loop endpoint.  It accepts
the complete fleet load snapshot and the post-step train positions, then returns
the next control-cycle `powerConstraints`. `vehicle-runtime-service:9300` is
the sole writer. `POST /power-network/constraints/query` provides the initial
constraint before the first vehicle step.  `state/query` remains for local
fallback and compatibility.

```json
{
  "sectionLoads": [
    {
      "powerSectionId": "P-CJG-W",
      "tractionPowerWatts": 1200000,
      "regenPowerWatts": 0,
      "currentAmps": 1600
    }
  ]
}
```

Run the local self-test:

```bash
cd power-network-service
python3 -m app.self_test
```

The FastAPI OpenAPI interface is available at `http://127.0.0.1:9200/docs`.
