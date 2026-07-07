package com.railwaysim.signal;

import com.railwaysim.config.SimulationProperties;
import com.railwaysim.infrastructure.StaticInfrastructureCatalog;
import com.railwaysim.track.TrackConstraint;
import com.railwaysim.train.TrainState;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class SignalService {

    private final SimulationProperties simulationProperties;
    private final StaticInfrastructureCatalog infrastructureCatalog;
    private List<MovementAuthority> authorities = List.of();

    public SignalService(
        SimulationProperties simulationProperties,
        StaticInfrastructureCatalog infrastructureCatalog
    ) {
        this.simulationProperties = simulationProperties;
        this.infrastructureCatalog = infrastructureCatalog;
    }

    public synchronized void reset() {
        authorities = List.of();
    }

    public synchronized void calculateAuthorities(List<TrainState> trains, List<TrackConstraint> trackConstraints) {
        Map<String, TrackConstraint> trackByTrain = trackConstraints.stream()
            .collect(Collectors.toMap(TrackConstraint::trainId, Function.identity(), (left, right) -> right));
        List<TrainState> ordered = new ArrayList<>(trains);
        ordered.sort(Comparator.comparingDouble(TrainState::positionMeters));
        double lineLengthMeters = infrastructureCatalog.lineData().lineLengthMeters() > 0
            ? infrastructureCatalog.lineData().lineLengthMeters()
            : simulationProperties.getDefaultLineLengthMeters();

        List<MovementAuthority> nextAuthorities = new ArrayList<>();
        for (int i = 0; i < ordered.size(); i++) {
            TrainState train = ordered.get(i);
            double authorityEnd = i + 1 < ordered.size()
                ? ordered.get(i + 1).positionMeters() - simulationProperties.getSafetyGapMeters()
                : lineLengthMeters;
            authorityEnd = Math.max(train.positionMeters(), Math.min(authorityEnd, lineLengthMeters));

            TrackConstraint track = trackByTrain.get(train.id());
            double speedLimit = track == null
                ? simulationProperties.getDefaultSpeedLimitMetersPerSecond()
                : track.speedLimitMetersPerSecond();
            String reason = authorityEnd <= train.positionMeters() ? "前方安全距离不足" : "前方区段可用";
            nextAuthorities.add(new MovementAuthority(train.id(), authorityEnd, speedLimit, reason));
        }
        authorities = List.copyOf(nextAuthorities);
    }

    public synchronized List<MovementAuthority> authorities() {
        return authorities;
    }
}
