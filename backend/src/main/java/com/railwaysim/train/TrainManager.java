package com.railwaysim.train;

import com.railwaysim.infrastructure.StaticInfrastructureCatalog;
import com.railwaysim.power.PowerConstraint;
import com.railwaysim.signal.MovementAuthority;
import com.railwaysim.simulation.RealtimeStateCache;
import com.railwaysim.simulation.TickContext;
import com.railwaysim.simulation.event.BrakeForceChangedEvent;
import com.railwaysim.simulation.event.RegenerativePowerGeneratedEvent;
import com.railwaysim.simulation.event.SimpleEventBus;
import com.railwaysim.simulation.event.TractionPowerChangedEvent;
import com.railwaysim.simulation.event.TrainFaultStateChangedEvent;
import com.railwaysim.simulation.event.VehiclePhysicsUpdatedEvent;
import com.railwaysim.track.TrackConstraint;
import com.railwaysim.vehicle.TcmsAtoAdapterService;
import com.railwaysim.vehicle.TrainStateReport;
import com.railwaysim.vehicle.VehiclePhysicsClient;
import com.railwaysim.vehicle.VehiclePhysicsInput;
import com.railwaysim.vehicle.VehiclePhysicsOutput;
import com.railwaysim.vehicle.protocol.TrainOperationalTelemetry;
import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class TrainManager {

    private final List<TrainEntity> trains = new ArrayList<>();
    private final TcmsAtoAdapterService tcmsAtoAdapterService;
    private final VehiclePhysicsClient vehiclePhysicsClient;
    private final StaticInfrastructureCatalog infrastructureCatalog;
    private final RealtimeStateCache realtimeStateCache;
    private final SimpleEventBus eventBus;
    private final List<TrainFaultRecord> faultRecords = new ArrayList<>();

    public TrainManager(
        TcmsAtoAdapterService tcmsAtoAdapterService,
        VehiclePhysicsClient vehiclePhysicsClient,
        StaticInfrastructureCatalog infrastructureCatalog,
        RealtimeStateCache realtimeStateCache,
        SimpleEventBus eventBus
    ) {
        this.tcmsAtoAdapterService = tcmsAtoAdapterService;
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
        trains.add(new TrainEntity("TR-001", routeId, 100, 120, 0.42));
        trains.add(new TrainEntity("TR-002", routeId, 900, 120, 0.55));
        faultRecords.clear();
    }

    public synchronized List<VehiclePhysicsOutput> tickAll(
        TickContext context,
        List<MovementAuthority> authorities,
        List<TrackConstraint> trackConstraints,
        List<PowerConstraint> powerConstraints
    ) {
        Map<String, MovementAuthority> authorityByTrain = authorities.stream()
            .collect(Collectors.toMap(MovementAuthority::trainId, Function.identity(), (left, right) -> right));
        Map<String, TrackConstraint> trackByTrain = trackConstraints.stream()
            .collect(Collectors.toMap(TrackConstraint::trainId, Function.identity(), (left, right) -> right));
        Map<String, PowerConstraint> powerByTrain = powerConstraints.stream()
            .collect(Collectors.toMap(PowerConstraint::trainId, Function.identity(), (left, right) -> right));
        List<TrainState> currentStates = states();
        List<VehiclePhysicsInput> inputs = currentStates.stream()
            .map(train -> tcmsAtoAdapterService.buildVehiclePhysicsInput(
                train,
                context,
                authorityByTrain.get(train.id()),
                trackByTrain.get(train.id()),
                powerByTrain.get(train.id())
            ))
            .toList();
        List<VehiclePhysicsOutput> outputs = vehiclePhysicsClient.stepFleet(inputs);

        Map<String, VehiclePhysicsInput> inputByTrain = inputs.stream()
            .collect(Collectors.toMap(VehiclePhysicsInput::trainId, Function.identity(), (left, right) -> right));
        Map<String, VehiclePhysicsOutput> outputByTrain = outputs.stream()
            .collect(Collectors.toMap(VehiclePhysicsOutput::trainId, Function.identity(), (left, right) -> right));
        for (TrainEntity train : trains) {
            TrainState currentState = train.state();
            VehiclePhysicsOutput output = outputByTrain.get(currentState.id());
            VehiclePhysicsInput input = inputByTrain.get(currentState.id());
            if (output != null && input != null) {
                TrainStateReport report = tcmsAtoAdapterService.buildTrainStateReport(currentState, input, output);
                train.applyPhysicsOutput(output, report);
                realtimeStateCache.updateTrainTcmsState(report);
                publishVehicleEvents(output);
            }
        }
        return outputs;
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
        return trains.stream().map(TrainEntity::state).toList();
    }

    public synchronized Optional<TrainState> state(String trainId) {
        return trains.stream()
            .filter(train -> train.state().id().equals(trainId))
            .map(TrainEntity::state)
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
        return trains.stream()
            .filter(train -> train.state().id().equals(trainId))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Train not found: " + trainId));
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
