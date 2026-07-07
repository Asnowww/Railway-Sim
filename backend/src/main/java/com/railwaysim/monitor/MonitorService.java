package com.railwaysim.monitor;

import com.railwaysim.power.PowerSectionState;
import com.railwaysim.signal.MovementAuthority;
import com.railwaysim.simulation.SimulationSnapshot;
import com.railwaysim.simulation.SimulationStatus;
import com.railwaysim.simulation.event.DomainEvent;
import com.railwaysim.simulation.event.FmuFallbackActivatedEvent;
import com.railwaysim.simulation.event.FmuStepFailedEvent;
import com.railwaysim.simulation.event.PowerLimitTriggeredEvent;
import com.railwaysim.track.TrackSegmentState;
import com.railwaysim.train.TrainState;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class MonitorService {

    public SimulationSnapshot buildSnapshot(
        long tick,
        Instant simulatedTime,
        SimulationStatus status,
        List<TrainState> trains,
        List<TrackSegmentState> trackSegments,
        List<MovementAuthority> authorities,
        List<PowerSectionState> powerSections,
        List<DomainEvent> events
    ) {
        return new SimulationSnapshot(
            tick,
            simulatedTime,
            status,
            trains,
            trackSegments,
            authorities,
            powerSections,
            buildAlarms(tick, simulatedTime, trains, authorities, powerSections, events)
        );
    }

    private List<Alarm> buildAlarms(
        long tick,
        Instant simulatedTime,
        List<TrainState> trains,
        List<MovementAuthority> authorities,
        List<PowerSectionState> powerSections,
        List<DomainEvent> events
    ) {
        List<Alarm> alarms = new ArrayList<>();
        trains.stream()
            .filter(train -> !"OK".equals(train.faultCode()))
            .map(train -> new Alarm(
                "TRAIN-" + tick + "-" + train.id(),
                "train",
                train.id(),
                2,
                "车辆物理状态异常",
                "车辆物理模型返回故障码：" + train.faultCode(),
                simulatedTime,
                false
            ))
            .forEach(alarms::add);
        authorities.stream()
            .filter(authority -> authority.authorityEndMeters() <= 0 || "前方安全距离不足".equals(authority.reason()))
            .map(authority -> new Alarm(
                "SIGNAL-" + tick + "-" + authority.trainId(),
                "signal",
                authority.trainId(),
                3,
                "移动授权受限",
                authority.reason(),
                simulatedTime,
                false
            ))
            .forEach(alarms::add);
        powerSections.stream()
            .filter(section -> !"ENERGIZED".equals(section.status()))
            .map(section -> new Alarm(
                "POWER-" + tick + "-" + section.id(),
                "power",
                section.id(),
                "DEENERGIZED".equals(section.status()) ? 3 : 2,
                "接触轨供电异常",
                "供电分区电压 " + section.voltage() + " V，状态 " + section.status(),
                simulatedTime,
                false
            ))
            .forEach(alarms::add);
        events.stream()
            .map(event -> alarmFromEvent(tick, simulatedTime, event))
            .filter(alarm -> alarm != null)
            .forEach(alarms::add);
        return alarms;
    }

    private Alarm alarmFromEvent(long tick, Instant simulatedTime, DomainEvent event) {
        if (event instanceof FmuStepFailedEvent fmuStepFailed) {
            return new Alarm(
                "FMU-FAILED-" + tick + "-" + fmuStepFailed.trainId(),
                "vehicle",
                fmuStepFailed.trainId(),
                3,
                "FMU 步进失败",
                fmuStepFailed.detail(),
                simulatedTime,
                false
            );
        }
        if (event instanceof FmuFallbackActivatedEvent fallbackActivated) {
            return new Alarm(
                "FMU-FALLBACK-" + tick,
                "vehicle",
                fallbackActivated.scope(),
                2,
                "车辆模型降级运行",
                fallbackActivated.detail(),
                simulatedTime,
                false
            );
        }
        if (event instanceof PowerLimitTriggeredEvent powerLimit) {
            return new Alarm(
                "POWER-LIMIT-" + tick + "-" + powerLimit.sectionId(),
                "power",
                powerLimit.sectionId(),
                powerLimit.voltage() < 900 ? 3 : 2,
                "接触轨牵引受限",
                powerLimit.reason(),
                simulatedTime,
                false
            );
        }
        return null;
    }
}
