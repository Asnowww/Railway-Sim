package com.railwaysim.train;

import com.railwaysim.dispatch.DispatchConstraint;
import com.railwaysim.infrastructure.StaticInfrastructureCatalog;
import com.railwaysim.power.PowerConstraint;
import com.railwaysim.signal.MovementAuthority;
import com.railwaysim.signal.vehicle.SignalTrainLifecycleAction;
import com.railwaysim.signal.vehicle.SignalTrainLifecycleCommand;
import com.railwaysim.signal.vehicle.SignalTrainLifecycleTrainSpec;
import com.railwaysim.simulation.RealtimeStateCache;
import com.railwaysim.simulation.TickContext;
import com.railwaysim.simulation.event.BrakeForceChangedEvent;
import com.railwaysim.simulation.event.RegenerativePowerGeneratedEvent;
import com.railwaysim.simulation.event.SimpleEventBus;
import com.railwaysim.simulation.event.TractionPowerChangedEvent;
import com.railwaysim.simulation.event.TrainFaultStateChangedEvent;
import com.railwaysim.simulation.event.VehiclePhysicsUpdatedEvent;
import com.railwaysim.track.TrackConstraint;
import com.railwaysim.vehicle.TrainStateReport;
import com.railwaysim.vehicle.VehiclePhysicsClient;
import com.railwaysim.vehicle.VehiclePhysicsInput;
import com.railwaysim.vehicle.VehiclePhysicsOutput;
import com.railwaysim.vehicle.onboard.OnboardTrainControlInput;
import com.railwaysim.vehicle.onboard.OnboardTrainSubsystemManager;
import com.railwaysim.vehicle.protocol.TrainOperationalTelemetry;
import com.railwaysim.vehicle.external.ExternalTrainDirection;
import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class TrainManager {

    private final List<TrainEntity> trains = new ArrayList<>();
    private final Map<String, ExternalTrainControlSession> controlSessions = new LinkedHashMap<>();
    private final OnboardTrainSubsystemManager onboardTrainSubsystemManager;
    private final VehiclePhysicsClient vehiclePhysicsClient;
    private final StaticInfrastructureCatalog infrastructureCatalog;
    private final RealtimeStateCache realtimeStateCache;
    private final SimpleEventBus eventBus;
    private final List<TrainFaultRecord> faultRecords = new ArrayList<>();

    public TrainManager(
        OnboardTrainSubsystemManager onboardTrainSubsystemManager,
        VehiclePhysicsClient vehiclePhysicsClient,
        StaticInfrastructureCatalog infrastructureCatalog,
        RealtimeStateCache realtimeStateCache,
        SimpleEventBus eventBus
    ) {
        this.onboardTrainSubsystemManager = onboardTrainSubsystemManager;
        this.vehiclePhysicsClient = vehiclePhysicsClient;
        this.infrastructureCatalog = infrastructureCatalog;
        this.realtimeStateCache = realtimeStateCache;
        this.eventBus = eventBus;
    }

    @PostConstruct
    public void init() {
        reset();
    }

    public synchronized void reset() {
        String routeId = infrastructureCatalog.lineData().lineId();
        trains.clear();
        controlSessions.clear();
        onboardTrainSubsystemManager.clear();
        addInitialTrain("TR-001", routeId, 100, 0.42, 1, ExternalTrainDirection.DOWN);
        addInitialTrain("TR-002", routeId, 900, 0.55, 2, ExternalTrainDirection.DOWN);
        faultRecords.clear();
    }

    public synchronized List<VehiclePhysicsOutput> tickAll(
        TickContext context,
        List<MovementAuthority> authorities,
        List<TrackConstraint> trackConstraints,
        List<DispatchConstraint> dispatchConstraints,
        List<PowerConstraint> powerConstraints
    ) {
        Map<String, MovementAuthority> authorityByTrain = authorities.stream()
            .collect(Collectors.toMap(MovementAuthority::trainId, Function.identity(), (left, right) -> right));
        Map<String, TrackConstraint> trackByTrain = trackConstraints.stream()
            .collect(Collectors.toMap(TrackConstraint::trainId, Function.identity(), (left, right) -> right));
        Map<String, DispatchConstraint> dispatchByTrain = dispatchConstraints.stream()
            .collect(Collectors.toMap(DispatchConstraint::trainId, Function.identity(), (left, right) -> right));
        Map<String, PowerConstraint> powerByTrain = powerConstraints.stream()
            .collect(Collectors.toMap(PowerConstraint::trainId, Function.identity(), (left, right) -> right));
        advanceControlSessions(authorityByTrain, powerByTrain);
        removeDisconnectedTrains();
        List<TrainState> currentStates = states();
        List<VehiclePhysicsInput> inputs = currentStates.stream()
            .map(train -> onboardTrainSubsystemManager.control(new OnboardTrainControlInput(
                train,
                context,
                authorityByTrain.get(train.id()),
                trackByTrain.get(train.id()),
                dispatchByTrain.get(train.id()),
                powerByTrain.get(train.id())
            )).physicsInput())
            .toList();
        List<VehiclePhysicsOutput> outputs = vehiclePhysicsClient.stepFleet(inputs);

        Map<String, VehiclePhysicsInput> inputByTrain = inputs.stream()
            .collect(Collectors.toMap(VehiclePhysicsInput::trainId, Function.identity(), (left, right) -> right));
        Map<String, VehiclePhysicsOutput> outputByTrain = outputs.stream()
            .collect(Collectors.toMap(VehiclePhysicsOutput::trainId, Function.identity(), (left, right) -> right));
        for (TrainEntity train : trains) {
            TrainState currentState = train.state(controlSessions.get(train.id()));
            VehiclePhysicsOutput output = outputByTrain.get(currentState.id());
            VehiclePhysicsInput input = inputByTrain.get(currentState.id());
            if (output != null && input != null) {
                TrainStateReport report = onboardTrainSubsystemManager.buildTrainStateReport(currentState, input, output);
                train.applyPhysicsOutput(output, report);
                realtimeStateCache.updateTrainTcmsState(report);
                publishVehicleEvents(output);
            }
        }
        return outputs;
    }

    public synchronized List<TrainState> applyLifecycleCommand(SignalTrainLifecycleCommand command) {
        if (command.action() == SignalTrainLifecycleAction.CLEAR) {
            requestClearTrains("SIGNAL_CLEAR_ALL_TRAINS");
            return states();
        }
        if (command.action() == SignalTrainLifecycleAction.ADD) {
            return command.trains().stream().map(this::addTrain).toList();
        }
        return command.trains().stream()
            .map(spec -> requestRemoveTrain(spec.trainId(), "SIGNAL_DELETE_TRAIN"))
            .flatMap(Optional::stream)
            .toList();
    }

    public synchronized TrainState addTrain(SignalTrainLifecycleTrainSpec spec) {
        String trainId = spec.trainId();
        Optional<TrainEntity> existing = findTrainEntity(trainId);
        if (existing.isPresent()) {
            ExternalTrainControlSession existingNode = controlSessions.get(trainId);
            return existing.get().state(existingNode);
        }
        String routeId = infrastructureCatalog.lineData().lineId();
        TrainEntity train = new TrainEntity(trainId, routeId, spec.offsetMeters(), 120, 0.35);
        trains.add(train);
        onboardTrainSubsystemManager.register(trainId);
        controlSessions.put(trainId, ExternalTrainControlSession.connecting(
            trainId,
            spec.linkId(),
            spec.offsetMeters(),
            spec.direction()
        ));
        return train.state(controlSessions.get(trainId));
    }

    public synchronized Optional<TrainState> requestRemoveTrain(String trainId, String reason) {
        Optional<TrainEntity> train = findTrainEntity(trainId);
        if (train.isEmpty()) {
            return Optional.empty();
        }
        controlSessions.computeIfAbsent(
            trainId,
            id -> ExternalTrainControlSession.inService(id, 0, train.get().state().positionMeters(), ExternalTrainDirection.UNKNOWN)
        ).requestDetach(reason);
        return Optional.of(train.get().state(controlSessions.get(trainId)));
    }

    public synchronized void requestClearTrains(String reason) {
        for (TrainEntity train : trains) {
            controlSessions.computeIfAbsent(
                train.id(),
                id -> ExternalTrainControlSession.inService(id, 0, train.state().positionMeters(), ExternalTrainDirection.UNKNOWN)
            ).requestDetach(reason);
        }
    }

    public synchronized void applyOperationalTelemetry(List<TrainOperationalTelemetry> telemetries) {
        Map<String, TrainOperationalTelemetry> telemetryByTrain = telemetries.stream()
            .collect(Collectors.toMap(TrainOperationalTelemetry::trainId, Function.identity(), (left, right) -> right));
        for (TrainEntity train : trains) {
            TrainOperationalTelemetry telemetry = telemetryByTrain.get(train.state().id());
            if (telemetry != null) {
                train.applyOperationalTelemetry(telemetry);
            }
        }
    }

    public synchronized List<TrainState> states() {
        return trains.stream()
            .map(train -> train.state(controlSessions.get(train.id())))
            .toList();
    }

    public synchronized Optional<TrainState> state(String trainId) {
        return trains.stream()
            .filter(train -> train.id().equals(trainId))
            .map(train -> train.state(controlSessions.get(train.id())))
            .findFirst();
    }

    public synchronized TrainFaultRecord injectFault(String trainId, String faultCode, String detail, String traceId) {
        TrainEntity train = trainEntity(trainId);
        train.injectFault(faultCode);
        TrainState state = train.state();
        TrainFaultRecord record = new TrainFaultRecord(
            trainId,
            state.faultCode(),
            state.faultLevel(),
            state.selfCheckStatus(),
            state.availableOperationMode(),
            "ACTIVE",
            detail,
            traceId,
            Instant.now(),
            null
        );
        faultRecords.add(record);
        eventBus.publish(new TrainFaultStateChangedEvent(trainId, state.faultCode(), "INJECTED", detail, record.raisedAt()));
        return record;
    }

    public synchronized TrainFaultRecord clearFault(String trainId, String detail, String traceId) {
        TrainEntity train = trainEntity(trainId);
        String clearedFaultCode = train.injectedFaultCode() == null ? "NONE" : train.injectedFaultCode();
        train.clearFault();
        TrainState state = train.state();
        TrainFaultRecord record = new TrainFaultRecord(
            trainId,
            clearedFaultCode,
            state.faultLevel(),
            state.selfCheckStatus(),
            state.availableOperationMode(),
            "CLEARED",
            detail,
            traceId,
            Instant.now(),
            Instant.now()
        );
        faultRecords.add(record);
        eventBus.publish(new TrainFaultStateChangedEvent(trainId, clearedFaultCode, "CLEARED", detail, record.raisedAt()));
        return record;
    }

    public synchronized List<TrainFaultRecord> faultRecords(String trainId) {
        return faultRecords.stream()
            .filter(record -> record.trainId().equals(trainId))
            .toList();
    }

    private TrainEntity trainEntity(String trainId) {
        return findTrainEntity(trainId)
            .orElseThrow(() -> new IllegalArgumentException("Train not found: " + trainId));
    }

    private Optional<TrainEntity> findTrainEntity(String trainId) {
        return trains.stream()
            .filter(train -> train.id().equals(trainId))
            .findFirst();
    }

    private void addInitialTrain(
        String trainId,
        String routeId,
        double positionMeters,
        double loadRate,
        int linkId,
        ExternalTrainDirection direction
    ) {
        TrainEntity train = new TrainEntity(trainId, routeId, positionMeters, 120, loadRate);
        trains.add(train);
        onboardTrainSubsystemManager.register(trainId);
        controlSessions.put(trainId, ExternalTrainControlSession.inService(trainId, linkId, positionMeters, direction));
    }

    private void advanceControlSessions(
        Map<String, MovementAuthority> authorityByTrain,
        Map<String, PowerConstraint> powerByTrain
    ) {
        for (TrainEntity train : trains) {
            ExternalTrainControlSession node = controlSessions.get(train.id());
            if (node != null) {
                node.advance(authorityByTrain.get(train.id()), powerByTrain.get(train.id()));
            }
        }
    }

    private void removeDisconnectedTrains() {
        Iterator<TrainEntity> iterator = trains.iterator();
        while (iterator.hasNext()) {
            TrainEntity train = iterator.next();
            ExternalTrainControlSession node = controlSessions.get(train.id());
            if (node != null && node.disconnected()) {
                iterator.remove();
                controlSessions.remove(train.id());
                onboardTrainSubsystemManager.remove(train.id());
            }
        }
    }

    private void publishVehicleEvents(VehiclePhysicsOutput output) {
        Instant now = Instant.now();
        realtimeStateCache.updateTrainPhysics(output);
        eventBus.publish(new VehiclePhysicsUpdatedEvent(
            output.trainId(),
            output.newPositionMeters(),
            output.newSpeedMetersPerSecond(),
            output.accelerationMetersPerSecondSquared(),
            now
        ));
        if (output.tractionPowerWatts() > 0) {
            eventBus.publish(new TractionPowerChangedEvent(
                output.trainId(),
                output.tractionPowerWatts(),
                output.railCurrentAmps(),
                now
            ));
        }
        if (output.brakeForceNewtons() > 0) {
            eventBus.publish(new BrakeForceChangedEvent(output.trainId(), output.brakeForceNewtons(), now));
        }
        if (output.regenPowerWatts() > 0) {
            eventBus.publish(new RegenerativePowerGeneratedEvent(
                output.trainId(),
                output.regenPowerWatts(),
                output.energyRegeneratedKwh(),
                now
            ));
        }
    }
}
