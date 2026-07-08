package com.railwaysim.vehicle.external;

import com.railwaysim.config.ExternalSimulatorProperties;
import com.railwaysim.vehicle.VehiclePhysicsInput;
import com.railwaysim.vehicle.VehiclePhysicsOutput;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public class RtLabApiVehicleAdapter implements ExternalVehicleSimulationAdapter {

    private final ExternalSimulatorProperties properties;
    private final ExternalVehicleCommandMapper commandMapper;
    private final RtLabVariablePathMapper pathMapper;
    private final RtLabClient rtLabClient;
    private final LocalFallbackVehicleAdapter localFallback;
    private volatile boolean started;
    private volatile ExternalSimulatorHealth health = ExternalSimulatorHealth.fallback(
        ExternalSimulatorMode.EXTERNAL_RTLAB_API,
        "NOT_STARTED"
    );

    public RtLabApiVehicleAdapter(
        ExternalSimulatorProperties properties,
        ExternalVehicleCommandMapper commandMapper,
        RtLabVariablePathMapper pathMapper,
        RtLabClient rtLabClient,
        LocalFallbackVehicleAdapter localFallback
    ) {
        this.properties = properties;
        this.commandMapper = commandMapper;
        this.pathMapper = pathMapper;
        this.rtLabClient = rtLabClient;
        this.localFallback = localFallback;
    }

    @Override
    public List<VehiclePhysicsOutput> stepFleet(List<VehiclePhysicsInput> inputs) {
        if (inputs.isEmpty()) {
            return List.of();
        }

        Instant startedAt = Instant.now();
        try {
            ensureStarted();
            List<ExternalTrainCommand> commands = commandMapper.toCommands(inputs);
            rtLabClient.opalSetParametersByName(parameterValues(commands));
            List<VehiclePhysicsOutput> localOutputs = localFallback.stepFleet(inputs);
            List<ExternalTrainTelemetry> telemetry = telemetryFromLocalStub(commands, localOutputs);
            health = new ExternalSimulatorHealth(
                ExternalSimulatorMode.EXTERNAL_RTLAB_API,
                "OK",
                Instant.now(),
                Duration.between(startedAt, Instant.now()).toMillis(),
                properties.getRtLab().isStubEnabled() ? "STUB" : "GOOD"
            );
            return mergeWithLocalOutputs(localOutputs, commands, telemetry);
        } catch (RuntimeException exception) {
            health = ExternalSimulatorHealth.fallback(
                ExternalSimulatorMode.EXTERNAL_RTLAB_API,
                exception.getClass().getSimpleName()
            );
            throw new IllegalStateException("RT-LAB API vehicle simulator step failed", exception);
        }
    }

    @Override
    public ExternalSimulatorHealth health() {
        return health;
    }

    public void reset() {
        rtLabClient.opalReset();
        started = false;
    }

    public void close() {
        rtLabClient.opalCloseProject();
        started = false;
    }

    private void ensureStarted() {
        if (started) {
            return;
        }
        rtLabClient.opalOpenProject(properties.getRtLab().getProjectPath());
        rtLabClient.opalLoad();
        rtLabClient.opalExecute();
        started = true;
    }

    private Map<String, Double> parameterValues(List<ExternalTrainCommand> commands) {
        Map<String, Double> values = new LinkedHashMap<>();
        commands.forEach(command -> values.putAll(pathMapper.inputParameterValues(command)));
        return values;
    }

    private List<ExternalTrainTelemetry> telemetryFromLocalStub(
        List<ExternalTrainCommand> commands,
        List<VehiclePhysicsOutput> localOutputs
    ) {
        Map<Integer, ExternalTrainCommand> commandByTrain = commands.stream()
            .collect(Collectors.toMap(ExternalTrainCommand::trainNo, Function.identity(), (left, right) -> right));
        return localOutputs.stream()
            .map(output -> {
                int trainNo = ExternalVehicleCommandMapper.resolveTrainNo(output.trainId(), properties.getMaxTrains());
                ExternalTrainCommand command = commandByTrain.get(trainNo);
                return new ExternalTrainTelemetry(
                    trainNo,
                    command == null ? 1 : command.activeCab(),
                    command == null ? ExternalTrainDirection.UNKNOWN : command.direction(),
                    output.accelerationMetersPerSecondSquared(),
                    output.newSpeedMetersPerSecond(),
                    output.newPositionMeters()
                );
            })
            .toList();
    }

    private List<VehiclePhysicsOutput> mergeWithLocalOutputs(
        List<VehiclePhysicsOutput> localOutputs,
        List<ExternalTrainCommand> commands,
        List<ExternalTrainTelemetry> telemetry
    ) {
        Map<Integer, ExternalTrainTelemetry> telemetryByTrain = telemetry.stream()
            .collect(Collectors.toMap(ExternalTrainTelemetry::trainNo, Function.identity(), (left, right) -> right));
        return localOutputs.stream()
            .map(output -> {
                int trainNo = ExternalVehicleCommandMapper.resolveTrainNo(output.trainId(), properties.getMaxTrains());
                ExternalTrainTelemetry trainTelemetry = telemetryByTrain.get(trainNo);
                if (trainTelemetry == null) {
                    throw new IllegalStateException("Missing RT-LAB telemetry for train " + trainNo);
                }
                return ExternalVehicleOutputMerger.mergeTelemetry(output, trainTelemetry);
            })
            .toList();
    }
}
