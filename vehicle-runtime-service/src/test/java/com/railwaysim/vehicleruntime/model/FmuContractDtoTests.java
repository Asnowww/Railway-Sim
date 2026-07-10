package com.railwaysim.vehicleruntime.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class FmuContractDtoTests {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void frozenExamplesDeserializeIntoVehicleRuntimeDtos() throws Exception {
        FmuStepFleetRequestDto request = objectMapper.readValue(
            Files.readString(projectPath("fmu-service/contracts/examples/step-fleet-request.example.json")),
            FmuStepFleetRequestDto.class
        );
        FmuStepFleetResponseDto response = objectMapper.readValue(
            Files.readString(projectPath("fmu-service/contracts/examples/step-fleet-response.example.json")),
            FmuStepFleetResponseDto.class
        );

        assertThat(request.tick()).isEqualTo(12001);
        assertThat(request.stepSizeSeconds()).isEqualTo(0.1);
        assertThat(request.trains()).singleElement().satisfies(train -> {
            assertThat(train.lifecycleCommand()).isEqualTo("STEP");
            assertThat(train.regenPowerAvailableWatts()).isZero();
            assertThat(train.currentCollectionAvailable()).isTrue();
        });
        assertThat(response.trainOutputs()).singleElement().satisfies(output -> {
            assertThat(output.mechanicalTractionPowerWatts()).isEqualTo(3_150_000);
            assertThat(output.tractionPowerWatts()).isEqualTo(3_579_545.45);
            assertThat(output.instanceState()).isEqualTo("ACTIVE");
        });
    }

    private Path projectPath(String relativePath) {
        Path workingDirectory = Path.of("").toAbsolutePath().normalize();
        Path direct = workingDirectory.resolve(relativePath);
        return Files.exists(direct) ? direct : workingDirectory.getParent().resolve(relativePath);
    }
}
