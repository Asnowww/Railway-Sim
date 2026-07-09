package com.railwaysim.localnet.powerpoints;

import static org.assertj.core.api.Assertions.assertThat;

import com.railwaysim.power.PowerService;
import com.railwaysim.power.external.PowerNetworkOperationRequest;
import com.railwaysim.power.external.PowerNetworkOperationResult;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class PowerPointDomainMapperTests {

    @Test
    void mapsReadPointsToSyntheticPowerSnapshot() {
        PowerPointDomainMapper mapper = new PowerPointDomainMapper(null);
        List<PowerPointDefinition> definitions = List.of(
            new PowerPointDefinition("P01_V", "", "READ", "DOUBLE", "A1", 1, "1500", "THIRD_RAIL_SECTION:P01:voltage", "GOOD", "", "", "", "", ""),
            new PowerPointDefinition("P01_I", "", "READ", "DOUBLE", "A2", 1, "0", "THIRD_RAIL_SECTION:P01:current", "GOOD", "", "", "", "", "")
        );
        List<PowerPointValue> values = List.of(
            new PowerPointValue("P01_V", "1490", "GOOD", Instant.now()),
            new PowerPointValue("P01_I", "120", "GOOD", Instant.now())
        );

        var snapshot = mapper.toSnapshot(definitions, values);

        assertThat(snapshot.thirdRailSections()).singleElement()
            .satisfies(section -> {
                assertThat(section.powerSectionId()).isEqualTo("P01");
                assertThat(section.contactRailVoltage()).isEqualTo(1490);
                assertThat(section.tractionCurrentAmps()).isEqualTo(120);
            });
        assertThat(snapshot.dataQuality()).isEqualTo("GOOD");
    }

    @Test
    void mapsWritePointToPowerOperation() {
        CapturingPowerService powerService = new CapturingPowerService();
        PowerPointDomainMapper mapper = new PowerPointDomainMapper(powerService);
        PowerPointDefinition definition = new PowerPointDefinition(
            "ISO",
            "",
            "WRITE",
            "BOOLEAN",
            "A3",
            1,
            "CLOSED",
            "ISOLATOR:ISO-P01-A:state",
            "GOOD",
            "ISOLATOR",
            "ISO-P01-A",
            "state",
            "SET_ISOLATOR",
            "OPEN"
        );

        PowerNetworkOperationResult result = mapper.applyWrite(definition, "OPEN");

        assertThat(result.accepted()).isTrue();
        assertThat(powerService.request).isNotNull();
        assertThat(powerService.request.targetId()).isEqualTo("ISO-P01-A");
        assertThat(powerService.request.confirmToken()).isEqualTo("SIMULATION_CONFIRM");
    }

    private static class CapturingPowerService extends PowerService {

        private PowerNetworkOperationRequest request;

        CapturingPowerService() {
            super(null, null, null, null, null);
        }

        @Override
        public synchronized PowerNetworkOperationResult operate(PowerNetworkOperationRequest request) {
            this.request = request;
            return new PowerNetworkOperationResult(true, true, request.targetId(), request.desiredState(), "ok", request.traceId(), Instant.now());
        }
    }
}
