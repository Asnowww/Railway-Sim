package com.railwaysim.vehicleruntime.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.railwaysim.vehicleruntime.config.VehicleParameters;
import com.railwaysim.vehicleruntime.config.VehicleParametersLoader;
import com.railwaysim.vehicleruntime.config.VehicleRuntimeProperties;
import com.railwaysim.vehicleruntime.model.VehiclePhysicsInputDto;
import com.railwaysim.vehicleruntime.model.VehiclePhysicsOutputDto;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class VehicleCurvePhysicsTests {

    private final VehicleParameters parameters = VehicleParametersLoader.load(
        projectPath("config/train_params.yaml").toString()
    );

    @Test
    void allCurveKnotsAndMidpointsUseTheSameLinearInterpolationContract() {
        List<Double> speed = parameters.curves().speedRpm();
        assertThat(speed).hasSize(52);
        for (int index = 0; index < speed.size(); index++) {
            assertThat(VehicleSimulationQueue.interpolateCurve(
                speed.get(index), speed, parameters.curves().tractionTorqueNmPerMotor()
            )).isCloseTo(parameters.curves().tractionTorqueNmPerMotor().get(index), within(1e-9));
            assertThat(VehicleSimulationQueue.interpolateCurve(
                speed.get(index), speed, parameters.curves().brakeTorqueNmPerMotor()
            )).isCloseTo(parameters.curves().brakeTorqueNmPerMotor().get(index), within(1e-9));
        }
        for (int index = 0; index < speed.size() - 1; index++) {
            double midpoint = (speed.get(index) + speed.get(index + 1)) * 0.5;
            double expectedTraction = (
                parameters.curves().tractionTorqueNmPerMotor().get(index)
                    + parameters.curves().tractionTorqueNmPerMotor().get(index + 1)
            ) * 0.5;
            double expectedBrake = (
                parameters.curves().brakeTorqueNmPerMotor().get(index)
                    + parameters.curves().brakeTorqueNmPerMotor().get(index + 1)
            ) * 0.5;
            assertThat(VehicleSimulationQueue.interpolateCurve(
                midpoint, speed, parameters.curves().tractionTorqueNmPerMotor()
            )).isCloseTo(expectedTraction, within(1e-9));
            assertThat(VehicleSimulationQueue.interpolateCurve(
                midpoint, speed, parameters.curves().brakeTorqueNmPerMotor()
            )).isCloseTo(expectedBrake, within(1e-9));
        }
    }

    @Test
    void dynamicDavisFormulaTracksAw0Aw2AndAw3Masses() {
        for (double mass : List.of(225_000.0, 301_000.0, 329_000.0)) {
            double speedMps = 10.0;
            double speedKph = speedMps * 3.6;
            double expected = 6.4 * mass / 1000
                + 130 * 24
                + 0.14 * mass / 1000 * speedKph
                + (0.046 + 0.0065 * 5) * 10.6 * speedKph * speedKph;
            assertThat(parameters.resistance().forceNewtons(
                mass, parameters.formation().axleCount(), parameters.formation().order().size(), speedMps
            )).isCloseTo(expected, within(1e-9));
        }
    }

    @Test
    void serviceBrakeBlendsAtLowSpeedAndEmergencyBrakeIsPureAir() {
        VehicleSimulationQueue queue = new VehicleSimulationQueue(new VehicleRuntimeProperties(), parameters);
        VehiclePhysicsOutputDto lowSpeed = queue.step(1, input("LOW", 0.0, false));
        assertThat(lowSpeed.brakeForceNewtons()).isEqualTo(225_000.0);
        assertThat(lowSpeed.regenBrakeForceNewtons()).isZero();
        assertThat(lowSpeed.airBrakeForceNewtons()).isEqualTo(225_000.0);

        VehiclePhysicsOutputDto emergency = queue.step(2, input("EMERGENCY", 10.0, true));
        assertThat(emergency.brakeForceNewtons()).isEqualTo(292_500.0);
        assertThat(emergency.regenBrakeForceNewtons()).isZero();
        assertThat(emergency.airBrakeForceNewtons()).isEqualTo(292_500.0);
    }

    private VehiclePhysicsInputDto input(String trainId, double speed, boolean emergency) {
        return new VehiclePhysicsInputDto(
            trainId, "INIT", "P-TEST", 0, speed, 225_000, 0, 1, emergency,
            30, 1000, 0, 1000, 1500, 10_000_000, 10_000_000,
            true, true, 0.9, 0, 0, 0.1, "BRAKING", "NONE", 1000, 100
        );
    }

    private static Path projectPath(String relativePath) {
        Path workingDirectory = Path.of("").toAbsolutePath().normalize();
        Path direct = workingDirectory.resolve(relativePath);
        return Files.exists(direct) ? direct : workingDirectory.getParent().resolve(relativePath);
    }
}
