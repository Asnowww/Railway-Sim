# External Power Network Service

This service is the external device-level traction power simulator used by the
central Spring Boot backend.

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
python3 -m app.http_server
```

Endpoints:

```text
GET  /health
GET  /power-network/state
GET  /power-network/topology
GET  /power-network/events
POST /power-network/bootstrap
POST /power-network/state/query
POST /power-network/operations
```

`POST /power-network/state/query` accepts load snapshots:

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
