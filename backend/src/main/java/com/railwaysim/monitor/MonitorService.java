package com.railwaysim.monitor;

import com.railwaysim.dispatch.DispatchSnapshot;
import com.railwaysim.power.PowerSectionState;
import com.railwaysim.signal.MovementAuthority;
import com.railwaysim.signal.RouteState;
import com.railwaysim.signal.SignalState;
import com.railwaysim.track.SwitchState;
import com.railwaysim.simulation.SimulationSnapshot;
import com.railwaysim.simulation.SimulationStatus;
import com.railwaysim.simulation.event.DomainEvent;
import com.railwaysim.simulation.event.FmuFallbackActivatedEvent;
import com.railwaysim.simulation.event.FmuStepFailedEvent;
import com.railwaysim.simulation.event.PowerFaultStateChangedEvent;
import com.railwaysim.simulation.event.PowerLimitTriggeredEvent;
import com.railwaysim.simulation.event.PowerMaintenanceLockChangedEvent;
import com.railwaysim.simulation.event.RegenerativeEnergyAbsorbedEvent;
import com.railwaysim.simulation.event.TrainFaultStateChangedEvent;
import com.railwaysim.track.TrackSegmentState;
import com.railwaysim.train.TrainState;
import com.railwaysim.vehicle.runtime.VehicleRuntimeHealth;
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
        List<SignalState> signalStates,
        List<SwitchState> switchStates,
        List<RouteState> routeStates,
        List<PowerSectionState> powerSections,
        VehicleRuntimeHealth vehicleRuntime,
        List<DomainEvent> events,
        DispatchSnapshot dispatch
    ) {
        return new SimulationSnapshot(
            tick,
            simulatedTime,
            status,
            trains,
            trackSegments,
            authorities,
            signalStates,
            switchStates,
            routeStates,
            powerSections,
            vehicleRuntime,
            buildAlarms(tick, simulatedTime, trains, authorities, powerSections, events),
            dispatch
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
            .filter(train -> !"OK".equals(train.faultCode()) || train.faultLevel() > 0)
            .map(train -> new Alarm(
                "TRAIN-" + tick + "-" + train.id(),
                "train",
                train.id(),
                train.faultLevel() <= 0 ? 2 : train.faultLevel(),
                "车辆物理状态异常",
                "车辆故障码：" + train.faultCode() + "，自检：" + train.selfCheckStatus() + "，可用模式：" + train.availableOperationMode(),
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
                "供电分区电压 " + section.voltage() + " V，状态 " + section.status() + "，影响列车 " + section.affectedTrainIds(),
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
        if (event instanceof PowerFaultStateChangedEvent powerFault) {
            return new Alarm(
                "POWER-FAULT-" + tick + "-" + powerFault.sectionId(),
                "power",
                powerFault.sectionId(),
                3,
                "供电故障状态变化",
                "故障类型 " + powerFault.faultType() + "，状态 " + powerFault.state(),
                simulatedTime,
                false
            );
        }
        if (event instanceof PowerMaintenanceLockChangedEvent maintenanceLock) {
            return new Alarm(
                "POWER-LOCK-" + tick + "-" + maintenanceLock.sectionId(),
                "power",
                maintenanceLock.sectionId(),
                2,
                "供电检修闭锁状态变化",
                "闭锁状态 " + maintenanceLock.lockoutState() + "，检修状态 " + maintenanceLock.maintenanceState(),
                simulatedTime,
                false
            );
        }
        if (event instanceof RegenerativeEnergyAbsorbedEvent regenerativeEnergy && regenerativeEnergy.unabsorbedPowerWatts() > 0) {
            return new Alarm(
                "REGEN-UNABSORBED-" + tick + "-" + regenerativeEnergy.sectionId(),
                "power",
                regenerativeEnergy.sectionId(),
                1,
                "再生制动能量未完全吸收",
                "回馈 " + regenerativeEnergy.regenPowerWatts() + " W，未吸收 " + regenerativeEnergy.unabsorbedPowerWatts() + " W，处理方式 " + regenerativeEnergy.unabsorbedMode(),
                simulatedTime,
                false
            );
        }
        if (event instanceof TrainFaultStateChangedEvent trainFault) {
            return new Alarm(
                "TRAIN-FAULT-" + tick + "-" + trainFault.trainId(),
                "vehicle",
                trainFault.trainId(),
                "CLEARED".equals(trainFault.state()) ? 1 : 3,
                "车辆故障状态变化",
                "故障码 " + trainFault.faultCode() + "，状态 " + trainFault.state() + "，说明 " + trainFault.detail(),
                simulatedTime,
                false
            );
        }
        return null;
    }
}
