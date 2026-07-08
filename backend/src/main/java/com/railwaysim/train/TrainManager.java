package com.railwaysim.train;

import com.railwaysim.dispatch.DispatchConstraint;
import com.railwaysim.infrastructure.StaticInfrastructureCatalog;
import com.railwaysim.power.PowerConstraint;
import com.railwaysim.signal.MovementAuthority;
import com.railwaysim.simulation.RealtimeStateCache;
import com.railwaysim.simulation.TickContext;
import com.railwaysim.simulation.event.BrakeForceChangedEvent;
import com.railwaysim.simulation.event.RegenerativePowerGeneratedEvent;
import com.railwaysim.simulation.event.SimpleEventBus;
import com.railwaysim.simulation.event.TractionPowerChangedEvent;
import com.railwaysim.simulation.event.VehiclePhysicsUpdatedEvent;
import com.railwaysim.track.TrackConstraint;
import com.railwaysim.vehicle.TcmsAtoAdapterService;
import com.railwaysim.vehicle.TrainStateReport;
import com.railwaysim.vehicle.VehiclePhysicsClient;
import com.railwaysim.vehicle.VehiclePhysicsInput;
import com.railwaysim.vehicle.VehiclePhysicsOutput;
import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
    }

    public synchronized List<VehiclePhysicsOutput> tickAll(
        TickContext context,
        List<MovementAuthority> authorities,
        List<TrackConstraint> trackConstraints,
        List<PowerConstraint> powerConstraints,
        List<DispatchConstraint> dispatchConstraints
    ) {
        Map<String, MovementAuthority> authorityByTrain = authorities.stream()
            .collect(Collectors.toMap(MovementAuthority::trainId, Function.identity(), (left, right) -> right));
        Map<String, TrackConstraint> trackByTrain = trackConstraints.stream()
            .collect(Collectors.toMap(TrackConstraint::trainId, Function.identity(), (left, right) -> right));
        Map<String, PowerConstraint> powerByTrain = powerConstraints.stream()
            .collect(Collectors.toMap(PowerConstraint::trainId, Function.identity(), (left, right) -> right));
        Map<String, DispatchConstraint> dispatchByTrain = dispatchConstraints.stream()
            .collect(Collectors.toMap(DispatchConstraint::trainId, Function.identity(), (left, right) -> right));

        List<TrainState> currentStates = states();
        List<VehiclePhysicsInput> inputs = currentStates.stream()
            .map(train -> tcmsAtoAdapterService.buildVehiclePhysicsInput(
                train,
                context,
                authorityByTrain.get(train.id()),
                trackByTrain.get(train.id()),
                powerByTrain.get(train.id()),
                dispatchByTrain.get(train.id())
            ))
            .toList();
        List<VehiclePhysicsOutput> outputs = vehiclePhysicsClient.stepFleet(inputs);

        Map<String, VehiclePhysicsInput> inputByTrain = inputs.stream()
            .collect(Collectors.toMap(VehiclePhysicsInput::trainId, Function.identity(), (left, right) -> right));
        Map<String, VehiclePhysicsOutput> outputByTrain = outputs.stream()
            .collect(Collectors.toMap(VehiclePhysicsOutput::trainId, Function.identity(), (left, right) -> right));
        for (TrainEntity train : trains) {
            VehiclePhysicsOutput output = outputByTrain.get(train.state().id());
            VehiclePhysicsInput input = inputByTrain.get(train.state().id());
            DispatchConstraint dispatch = dispatchByTrain.get(train.state().id());
            if (output != null && input != null) {
                TrainStateReport report = tcmsAtoAdapterService.buildTrainStateReport(input, output, dispatch);
                train.applyPhysicsOutput(output, report);
                realtimeStateCache.updateTrainTcmsState(report);
                publishVehicleEvents(output);
            }
        }
        return outputs;
    }

    public synchronized List<TrainState> states() {
        return trains.stream().map(TrainEntity::state).toList();
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
