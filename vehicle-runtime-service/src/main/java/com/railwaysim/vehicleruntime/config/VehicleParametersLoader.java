package com.railwaysim.vehicleruntime.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class VehicleParametersLoader {

    private static final int CURVE_POINT_COUNT = 52;
    private static final double CURVE_POWER_RELATIVE_TOLERANCE = 0.005;
    private static final double EFFICIENCY_ABSOLUTE_TOLERANCE = 0.01;
    private static final YAMLMapper YAML_MAPPER = YAMLMapper.builder()
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build();

    @Bean
    public VehicleParameters vehicleParameters(VehicleRuntimeProperties properties) {
        return load(properties.getTrainParamsPath());
    }

    public static VehicleParameters load(String configuredPath) {
        Path sourcePath = resolve(configuredPath);
        try {
            byte[] rawBytes = Files.readAllBytes(sourcePath);
            ParameterFile file = YAML_MAPPER.readValue(rawBytes, ParameterFile.class);
            validate(file, sourcePath);
            VehicleParameters.Curves curves = new VehicleParameters.Curves(
                file.curves().speedRpm(),
                file.curves().tractionTorqueNmPerMotor(),
                file.curves().brakeTorqueNmPerMotor(),
                file.curves().referenceTractionCurrentAmps(),
                file.curves().referenceBrakeCurrentAmps(),
                file.curves().referenceTractionMechanicalPowerKwPerMotor(),
                file.curves().referenceBrakeMechanicalPowerKwPerMotor(),
                file.curves().referenceVoltageVolts()
            );
            return new VehicleParameters(
                file.parameterSchemaVersion(),
                file.trainType(),
                file.lengthMeters(),
                new VehicleParameters.Formation(
                    file.formation().order(),
                    file.formation().motorCount(),
                    file.formation().axleCount(),
                    file.formation().hardMassLimitKg(),
                    loadCases(file.formation().loadCasesKg()),
                    carMasses(file.formation().carMassKg())
                ),
                new VehicleParameters.Drivetrain(
                    file.drivetrain().motorCount(),
                    file.drivetrain().gearRatio(),
                    file.drivetrain().wheelRadiusMeters(),
                    file.drivetrain().tractionTotalEfficiency(),
                    file.drivetrain().regenTotalEfficiency()
                ),
                curves,
                new VehicleParameters.Resistance(
                    file.resistance().davisMassCoefficient(),
                    file.resistance().davisAxleConstant(),
                    file.resistance().davisSpeedMassCoefficient(),
                    file.resistance().davisAeroBase(),
                    file.resistance().davisAeroVehicleCoefficient(),
                    file.resistance().frontalAreaSquareMeters()
                ),
                new VehicleParameters.Brake(
                    file.brake().serviceDecelerationMps2(),
                    file.brake().emergencyDecelerationMps2()
                ),
                new VehicleParameters.Power(
                    file.power().nominalVoltage(),
                    file.power().minVoltage(),
                    file.power().cutoffVoltage()
                ),
                "sha256:" + sha256(rawBytes),
                curveSetId(curves),
                sourcePath
            );
        } catch (IOException exception) {
            throw new IllegalStateException(
                "Failed to load vehicle parameters from " + sourcePath + ": " + exception.getMessage(),
                exception
            );
        }
    }

    private static VehicleParameters.LoadCases loadCases(LoadCasesFile file) {
        return new VehicleParameters.LoadCases(file.aw0(), file.aw2(), file.aw3());
    }

    private static Map<String, VehicleParameters.CarMass> carMasses(Map<String, CarMassFile> files) {
        Map<String, VehicleParameters.CarMass> result = new LinkedHashMap<>();
        files.forEach((name, file) -> result.put(
            name,
            new VehicleParameters.CarMass(file.aw0(), file.aw2(), file.aw3(), file.hardLimit())
        ));
        return result;
    }

    private static Path resolve(String configuredPath) {
        String effectivePath = configuredPath == null || configuredPath.isBlank()
            ? "config/train_params.yaml"
            : configuredPath;
        Path requested = Path.of(effectivePath);
        if (requested.isAbsolute()) {
            if (Files.isRegularFile(requested)) {
                return requested.normalize();
            }
            throw new IllegalStateException("Vehicle parameter file does not exist: " + requested);
        }
        Path workingDirectory = Path.of("").toAbsolutePath().normalize();
        List<Path> candidates = List.of(
            workingDirectory.resolve(requested).normalize(),
            workingDirectory.resolve("..").resolve(requested).normalize()
        );
        return candidates.stream()
            .filter(Files::isRegularFile)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "Vehicle parameter file does not exist; checked " + candidates
            ));
    }

    private static void validate(ParameterFile file, Path sourcePath) {
        if (file == null) {
            invalid(sourcePath, "$", "document is empty");
        }
        if (!"2".equals(file.parameterSchemaVersion())) {
            invalid(sourcePath, "parameterSchemaVersion", "must equal \"2\"");
        }
        requireText(sourcePath, "trainType", file.trainType());
        requirePositive(sourcePath, "lengthMeters", file.lengthMeters());
        validateFormation(file.formation(), sourcePath);
        validateDrivetrain(file.drivetrain(), file.formation(), sourcePath);
        validateCurves(file.curves(), file.drivetrain(), sourcePath);
        validateResistance(file.resistance(), sourcePath);
        validateBrake(file.brake(), sourcePath);
        validatePower(file.power(), sourcePath);
    }

    private static void validateFormation(FormationFile formation, Path path) {
        if (formation == null) {
            invalid(path, "formation", "section is required");
        }
        if (formation.order() == null || formation.order().isEmpty()) {
            invalid(path, "formation.order", "must contain at least one vehicle");
        }
        requirePositiveInteger(path, "formation.motorCount", formation.motorCount());
        requirePositiveInteger(path, "formation.axleCount", formation.axleCount());
        requirePositive(path, "formation.hardMassLimitKg", formation.hardMassLimitKg());
        if (formation.loadCasesKg() == null) {
            invalid(path, "formation.loadCasesKg", "section is required");
        }
        LoadCasesFile loads = formation.loadCasesKg();
        requirePositive(path, "formation.loadCasesKg.AW0", loads.aw0());
        requirePositive(path, "formation.loadCasesKg.AW2", loads.aw2());
        requirePositive(path, "formation.loadCasesKg.AW3", loads.aw3());
        if (!(loads.aw0() < loads.aw2() && loads.aw2() < loads.aw3()
            && loads.aw3() <= formation.hardMassLimitKg())) {
            invalid(path, "formation.loadCasesKg", "must satisfy AW0 < AW2 < AW3 <= hardMassLimitKg");
        }
        Map<String, CarMassFile> cars = formation.carMassKg();
        if (cars == null || !cars.keySet().equals(java.util.Set.of("Tc", "M"))) {
            invalid(path, "formation.carMassKg", "must contain exactly Tc and M");
        }
        cars.forEach((name, car) -> validateCarMass(path, name, car));
        for (String carType : formation.order()) {
            if (!cars.containsKey(carType)) {
                invalid(path, "formation.order", "unknown car type " + carType);
            }
        }
        assertMassSum(path, formation, "AW0", loads.aw0(), CarMassFile::aw0);
        assertMassSum(path, formation, "AW2", loads.aw2(), CarMassFile::aw2);
        assertMassSum(path, formation, "AW3", loads.aw3(), CarMassFile::aw3);
        double hardSum = formation.order().stream()
            .map(formation.carMassKg()::get)
            .mapToDouble(CarMassFile::hardLimit)
            .sum();
        if (Math.abs(hardSum - formation.hardMassLimitKg()) > 1e-6) {
            invalid(path, "formation.hardMassLimitKg", "does not equal the per-car hard-limit sum");
        }
    }

    private static void validateCarMass(Path path, String name, CarMassFile car) {
        if (car == null) {
            invalid(path, "formation.carMassKg." + name, "section is required");
        }
        requirePositive(path, "formation.carMassKg." + name + ".AW0", car.aw0());
        requirePositive(path, "formation.carMassKg." + name + ".AW2", car.aw2());
        requirePositive(path, "formation.carMassKg." + name + ".AW3", car.aw3());
        requirePositive(path, "formation.carMassKg." + name + ".hardLimit", car.hardLimit());
        if (!(car.aw0() < car.aw2() && car.aw2() < car.aw3() && car.aw3() <= car.hardLimit())) {
            invalid(path, "formation.carMassKg." + name, "must satisfy AW0 < AW2 < AW3 <= hardLimit");
        }
    }

    private static void assertMassSum(
        Path path,
        FormationFile formation,
        String label,
        double expected,
        java.util.function.ToDoubleFunction<CarMassFile> extractor
    ) {
        double actual = formation.order().stream()
            .map(formation.carMassKg()::get)
            .mapToDouble(extractor)
            .sum();
        if (Math.abs(actual - expected) > 1e-6) {
            invalid(path, "formation.loadCasesKg." + label, "does not equal the per-car mass sum " + actual);
        }
    }

    private static void validateDrivetrain(DrivetrainFile drivetrain, FormationFile formation, Path path) {
        if (drivetrain == null) {
            invalid(path, "drivetrain", "section is required");
        }
        requirePositiveInteger(path, "drivetrain.motorCount", drivetrain.motorCount());
        if (drivetrain.motorCount() != formation.motorCount()) {
            invalid(path, "drivetrain.motorCount", "must equal formation.motorCount");
        }
        requirePositive(path, "drivetrain.gearRatio", drivetrain.gearRatio());
        requirePositive(path, "drivetrain.wheelRadiusMeters", drivetrain.wheelRadiusMeters());
        requireEfficiency(path, "drivetrain.tractionTotalEfficiency", drivetrain.tractionTotalEfficiency());
        requireEfficiency(path, "drivetrain.regenTotalEfficiency", drivetrain.regenTotalEfficiency());
    }

    private static void validateCurves(CurvesFile curves, DrivetrainFile drivetrain, Path path) {
        if (curves == null) {
            invalid(path, "curves", "section is required");
        }
        Map<String, List<Double>> arrays = curves.arrays();
        arrays.forEach((name, values) -> validateCurveArray(path, "curves." + name, values));
        List<Double> speed = curves.speedRpm();
        int duplicateCount = 0;
        for (int index = 1; index < speed.size(); index++) {
            if (speed.get(index) < speed.get(index - 1)) {
                invalid(path, "curves.speedRpm[" + index + "]", "must be nondecreasing");
            }
            if (Double.compare(speed.get(index), speed.get(index - 1)) == 0) {
                duplicateCount++;
                if (index != CURVE_POINT_COUNT - 1) {
                    invalid(path, "curves.speedRpm[" + index + "]", "only the terminal source duplicate is allowed");
                }
                for (Map.Entry<String, List<Double>> entry : arrays.entrySet()) {
                    if (Double.compare(entry.getValue().get(index), entry.getValue().get(index - 1)) != 0) {
                        invalid(path, "curves." + entry.getKey() + "[" + index + "]", "must match across terminal duplicate speed");
                    }
                }
            }
        }
        if (duplicateCount != 1) {
            invalid(path, "curves.speedRpm", "must preserve the single duplicated 4160.1 rpm terminal point");
        }
        requirePositive(path, "curves.referenceVoltageVolts", curves.referenceVoltageVolts());
        for (int index = 0; index < CURVE_POINT_COUNT; index++) {
            double omega = speed.get(index) * 2 * Math.PI / 60;
            validatePowerPoint(
                path,
                "curves.referenceTractionMechanicalPowerKwPerMotor[" + index + "]",
                curves.tractionTorqueNmPerMotor().get(index) * omega / 1000,
                curves.referenceTractionMechanicalPowerKwPerMotor().get(index)
            );
            validatePowerPoint(
                path,
                "curves.referenceBrakeMechanicalPowerKwPerMotor[" + index + "]",
                curves.brakeTorqueNmPerMotor().get(index) * omega / 1000,
                curves.referenceBrakeMechanicalPowerKwPerMotor().get(index)
            );
            validateEfficiencyPoint(path, curves, drivetrain, index);
        }
    }

    private static void validateCurveArray(Path path, String field, List<Double> values) {
        if (values == null || values.size() != CURVE_POINT_COUNT) {
            invalid(path, field, "must contain exactly " + CURVE_POINT_COUNT + " points");
        }
        for (int index = 0; index < values.size(); index++) {
            Double value = values.get(index);
            if (value == null || !Double.isFinite(value) || value < 0) {
                invalid(path, field + "[" + index + "]", "must be finite and nonnegative");
            }
        }
    }

    private static void validatePowerPoint(Path path, String field, double calculatedKw, double referenceKw) {
        if (referenceKw == 0 && calculatedKw == 0) {
            return;
        }
        double relativeError = Math.abs(calculatedKw - referenceKw) / Math.max(referenceKw, 1e-9);
        if (relativeError > CURVE_POWER_RELATIVE_TOLERANCE) {
            invalid(path, field, "torque/rpm power mismatch " + relativeError);
        }
    }

    private static void validateEfficiencyPoint(
        Path path,
        CurvesFile curves,
        DrivetrainFile drivetrain,
        int index
    ) {
        double tractionMechanical = curves.referenceTractionMechanicalPowerKwPerMotor().get(index)
            * 1000 * drivetrain.motorCount();
        double tractionGrid = curves.referenceTractionCurrentAmps().get(index) * curves.referenceVoltageVolts();
        if (tractionMechanical > 0 && tractionGrid > 0) {
            double efficiency = tractionMechanical / tractionGrid;
            if (Math.abs(efficiency - drivetrain.tractionTotalEfficiency()) > EFFICIENCY_ABSOLUTE_TOLERANCE) {
                invalid(path, "curves.referenceTractionCurrentAmps[" + index + "]", "inconsistent traction efficiency " + efficiency);
            }
        }
        double brakeMechanical = curves.referenceBrakeMechanicalPowerKwPerMotor().get(index)
            * 1000 * drivetrain.motorCount();
        double brakeGrid = curves.referenceBrakeCurrentAmps().get(index) * curves.referenceVoltageVolts();
        if (brakeMechanical > 0 && brakeGrid > 0) {
            double efficiency = brakeGrid / brakeMechanical;
            if (Math.abs(efficiency - drivetrain.regenTotalEfficiency()) > EFFICIENCY_ABSOLUTE_TOLERANCE) {
                invalid(path, "curves.referenceBrakeCurrentAmps[" + index + "]", "inconsistent regen efficiency " + efficiency);
            }
        }
    }

    private static void validateResistance(ResistanceFile resistance, Path path) {
        if (resistance == null) {
            invalid(path, "resistance", "section is required");
        }
        requireNonNegative(path, "resistance.davisMassCoefficient", resistance.davisMassCoefficient());
        requireNonNegative(path, "resistance.davisAxleConstant", resistance.davisAxleConstant());
        requireNonNegative(path, "resistance.davisSpeedMassCoefficient", resistance.davisSpeedMassCoefficient());
        requireNonNegative(path, "resistance.davisAeroBase", resistance.davisAeroBase());
        requireNonNegative(path, "resistance.davisAeroVehicleCoefficient", resistance.davisAeroVehicleCoefficient());
        requirePositive(path, "resistance.frontalAreaSquareMeters", resistance.frontalAreaSquareMeters());
    }

    private static void validateBrake(BrakeFile brake, Path path) {
        if (brake == null) {
            invalid(path, "brake", "section is required");
        }
        requirePositive(path, "brake.serviceDecelerationMps2", brake.serviceDecelerationMps2());
        requirePositive(path, "brake.emergencyDecelerationMps2", brake.emergencyDecelerationMps2());
        if (brake.emergencyDecelerationMps2() < brake.serviceDecelerationMps2()) {
            invalid(path, "brake", "emergencyDecelerationMps2 must be >= serviceDecelerationMps2");
        }
    }

    private static void validatePower(PowerFile power, Path path) {
        if (power == null) {
            invalid(path, "power", "section is required");
        }
        requirePositive(path, "power.nominalVoltage", power.nominalVoltage());
        requirePositive(path, "power.minVoltage", power.minVoltage());
        requirePositive(path, "power.cutoffVoltage", power.cutoffVoltage());
        if (!(power.cutoffVoltage() < power.minVoltage() && power.minVoltage() <= power.nominalVoltage())) {
            invalid(path, "power", "must satisfy cutoffVoltage < minVoltage <= nominalVoltage");
        }
    }

    public static String curveSetId(VehicleParameters.Curves curves) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            List<List<Double>> arrays = List.of(
                curves.speedRpm(),
                curves.tractionTorqueNmPerMotor(),
                curves.brakeTorqueNmPerMotor(),
                curves.referenceTractionCurrentAmps(),
                curves.referenceBrakeCurrentAmps(),
                curves.referenceTractionMechanicalPowerKwPerMotor(),
                curves.referenceBrakeMechanicalPowerKwPerMotor()
            );
            for (List<Double> values : arrays) {
                digest.update(ByteBuffer.allocate(4).putInt(values.size()).array());
                for (double value : values) {
                    digest.update(ByteBuffer.allocate(8).putLong(Double.doubleToLongBits(value)).array());
                }
            }
            digest.update(ByteBuffer.allocate(8).putLong(
                Double.doubleToLongBits(curves.referenceVoltageVolts())
            ).array());
            return "sha256:" + HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private static void requireText(Path path, String field, String value) {
        if (value == null || value.isBlank()) {
            invalid(path, field, "must not be blank");
        }
    }

    private static void requirePositiveInteger(Path path, String field, int value) {
        if (value <= 0) {
            invalid(path, field, "must be greater than 0");
        }
    }

    private static void requirePositive(Path path, String field, double value) {
        if (!Double.isFinite(value) || value <= 0) {
            invalid(path, field, "must be a finite value greater than 0");
        }
    }

    private static void requireNonNegative(Path path, String field, double value) {
        if (!Double.isFinite(value) || value < 0) {
            invalid(path, field, "must be a finite value greater than or equal to 0");
        }
    }

    private static void requireEfficiency(Path path, String field, double value) {
        if (!Double.isFinite(value) || value <= 0 || value > 1) {
            invalid(path, field, "must be in (0, 1]");
        }
    }

    private static void invalid(Path path, String field, String reason) {
        throw new IllegalStateException("Invalid vehicle parameter " + field + " in " + path + ": " + reason);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private record ParameterFile(
        String parameterSchemaVersion,
        String trainType,
        double lengthMeters,
        FormationFile formation,
        DrivetrainFile drivetrain,
        CurvesFile curves,
        ResistanceFile resistance,
        BrakeFile brake,
        PowerFile power
    ) {
    }

    private record FormationFile(
        List<String> order,
        int motorCount,
        int axleCount,
        double hardMassLimitKg,
        LoadCasesFile loadCasesKg,
        Map<String, CarMassFile> carMassKg
    ) {
    }

    private record LoadCasesFile(
        @JsonProperty("AW0") double aw0,
        @JsonProperty("AW2") double aw2,
        @JsonProperty("AW3") double aw3
    ) {
    }

    private record CarMassFile(
        @JsonProperty("AW0") double aw0,
        @JsonProperty("AW2") double aw2,
        @JsonProperty("AW3") double aw3,
        double hardLimit
    ) {
    }

    private record DrivetrainFile(
        int motorCount,
        double gearRatio,
        double wheelRadiusMeters,
        double tractionTotalEfficiency,
        double regenTotalEfficiency
    ) {
    }

    private record CurvesFile(
        List<Double> speedRpm,
        List<Double> tractionTorqueNmPerMotor,
        List<Double> brakeTorqueNmPerMotor,
        List<Double> referenceTractionCurrentAmps,
        List<Double> referenceBrakeCurrentAmps,
        List<Double> referenceTractionMechanicalPowerKwPerMotor,
        List<Double> referenceBrakeMechanicalPowerKwPerMotor,
        double referenceVoltageVolts
    ) {
        Map<String, List<Double>> arrays() {
            Map<String, List<Double>> result = new LinkedHashMap<>();
            result.put("speedRpm", speedRpm);
            result.put("tractionTorqueNmPerMotor", tractionTorqueNmPerMotor);
            result.put("brakeTorqueNmPerMotor", brakeTorqueNmPerMotor);
            result.put("referenceTractionCurrentAmps", referenceTractionCurrentAmps);
            result.put("referenceBrakeCurrentAmps", referenceBrakeCurrentAmps);
            result.put("referenceTractionMechanicalPowerKwPerMotor", referenceTractionMechanicalPowerKwPerMotor);
            result.put("referenceBrakeMechanicalPowerKwPerMotor", referenceBrakeMechanicalPowerKwPerMotor);
            return result;
        }
    }

    private record ResistanceFile(
        double davisMassCoefficient,
        double davisAxleConstant,
        double davisSpeedMassCoefficient,
        double davisAeroBase,
        double davisAeroVehicleCoefficient,
        double frontalAreaSquareMeters
    ) {
    }

    private record BrakeFile(double serviceDecelerationMps2, double emergencyDecelerationMps2) {
    }

    private record PowerFile(double nominalVoltage, double minVoltage, double cutoffVoltage) {
    }
}
