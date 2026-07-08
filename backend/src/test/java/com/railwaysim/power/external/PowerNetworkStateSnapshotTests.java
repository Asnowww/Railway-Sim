package com.railwaysim.power.external;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class PowerNetworkStateSnapshotTests {

    @Test
    void deserializesExternalSnapshotWithUnknownFieldsAndMissingLists() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

        PowerNetworkStateSnapshot snapshot = objectMapper.readValue("""
            {
              "sourceTimestamp": "2026-07-08T00:00:00Z",
              "heartbeatStatus": "OK",
              "dataQuality": "GOOD",
              "mediumVoltageBuses": [
                { "id": "MV-CJG", "voltage": 10000.0 }
              ],
              "substations": [
                {
                  "id": "SS-CJG",
                  "name": "Chegongzhuang traction substation",
                  "supplyMode": "DOUBLE_END",
                  "availability": "AVAILABLE",
                  "extraFieldFromSimulator": "ignored"
                }
              ],
              "thirdRailSections": [
                {
                  "id": "TR-P01",
                  "powerSectionId": "P01",
                  "energizationState": "ENERGIZED",
                  "feederState": "AVAILABLE",
                  "recommendedSupplyMode": "DOUBLE_END",
                  "calculatedContactRailVoltage": 728.0
                }
              ],
              "isolators": [
                {
                  "id": "ISO-P01-A",
                  "thirdRailSectionId": "TR-P01",
                  "state": "CLOSED",
                  "positionMeters": 120.0
                }
              ],
              "strayCurrentMonitors": [
                {
                  "id": "SCM-P01",
                  "sectionId": "P01",
                  "cabinetState": "NORMAL",
                  "polarizedPotentialVolts": -0.4,
                  "riskLevel": "NORMAL",
                  "riskReason": "within configured threshold",
                  "suggestedAction": "monitor",
                  "netCurrentAmps": 100.0
                }
              ],
              "events": [
                {
                  "eventType": "HEARTBEAT",
                  "targetType": "SYSTEM",
                  "targetId": "power-network",
                  "level": "INFO",
                  "detail": "ok",
                  "occurredAt": "2026-07-08T00:00:00Z",
                  "raw": {}
                }
              ]
            }
            """, PowerNetworkStateSnapshot.class);

        assertThat(snapshot.heartbeatStatus()).isEqualTo("OK");
        assertThat(snapshot.substations()).hasSize(1);
        assertThat(snapshot.substations().get(0).devices()).isEmpty();
        assertThat(snapshot.thirdRailSections()).extracting(PowerNetworkStateSnapshot.ThirdRailSectionSnapshot::powerSectionId)
            .containsExactly("P01");
        assertThat(snapshot.isolators()).hasSize(1);
        assertThat(snapshot.strayCurrentMonitors()).extracting(PowerNetworkStateSnapshot.StrayCurrentSnapshot::riskLevel)
            .containsExactly("NORMAL");
        assertThat(snapshot.events()).hasSize(1);
    }
}
