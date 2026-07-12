package com.railwaysim.monitor;

import com.railwaysim.dispatch.DispatchSnapshot;
import com.railwaysim.power.PowerSectionState;
import com.railwaysim.power.external.PowerNetworkStateSnapshot;
import com.railwaysim.power.external.ExternalPowerNetworkHealth;
import com.railwaysim.power.external.ExternalPowerNetworkMode;
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

    private final AlarmLifecycleService alarmLifecycleService;
    private final ServiceHealthService serviceHealthService;

    public MonitorService(
        AlarmLifecycleService alarmLifecycleService, ServiceHealthService serviceHealthService
    ) {
        this.alarmLifecycleService = alarmLifecycleService;
        this.serviceHealthService = serviceHealthService;
    }

    public SimulationSnapshot buildSnapshot(
        String simulationRunId,
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
        PowerNetworkStateSnapshot powerNetwork,
        ExternalPowerNetworkHealth powerHealth,
        List<DomainEvent> events,
        DispatchSnapshot dispatch
    ) {
        observeServiceHealth(simulationRunId, tick, vehicleRuntime, powerNetwork, powerHealth);
        return new SimulationSnapshot(
            simulationRunId,
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
            alarmLifecycleService.reconcile(
                simulationRunId,
                buildAlarms(simulatedTime, trains, trackSegments, authorities, powerSections, events),
                simulatedTime
            ),
            dispatch
        );
    }

    private void observeServiceHealth(
        String simulationRunId, long tick, VehicleRuntimeHealth vehicleRuntime,
        PowerNetworkStateSnapshot powerNetwork, ExternalPowerNetworkHealth powerHealth
    ) {
        Instant now = Instant.now();
        boolean externalVehicle = vehicleRuntime.mode()
            != com.railwaysim.vehicle.runtime.VehicleRuntimeMode.LOCAL;
        ServiceHealthRecord vehicleHealthRecord = serviceHealthService.observe(new ServiceHealthObservation(
            "vehicle-runtime-9300", externalVehicle, vehicleRuntime.heartbeatStatus(),
            vehicleRuntime.dataQuality(), vehicleRuntime.sourceTimestamp(),
            externalVehicle ? vehicleRuntime.simulationRunId() : simulationRunId,
            externalVehicle ? vehicleRuntime.lastAcceptedTick() : tick,
            externalVehicle ? vehicleRuntime.topologyHash() : "NOT_APPLICABLE",
            externalVehicle ? vehicleRuntime.configHash() : "LOCAL",
            externalVehicle ? vehicleRuntime.fmuModelVersion() : "LOCAL_JAVA",
            externalVehicle
                ? vehicleRuntime.parameterSetId() + "/" + vehicleRuntime.stoppingParameterVersion()
                : "LOCAL",
            vehicleRuntime.reason()), now);
        autoCheckRecovery(vehicleHealthRecord, simulationRunId, tick, now);

        boolean externalPower = powerHealth != null && powerHealth.mode() != ExternalPowerNetworkMode.LOCAL;
        if (powerNetwork != null && powerHealth != null) {
            ServiceHealthRecord powerHealthRecord = serviceHealthService.observe(new ServiceHealthObservation(
                "power-network-9200", externalPower, powerHealth.heartbeatStatus(),
                powerHealth.dataQuality(), powerHealth.lastPacketAt(),
                externalPower ? powerNetwork.simulationRunId() : simulationRunId,
                externalPower ? powerNetwork.lastAcceptedTick() : tick,
                externalPower ? powerNetwork.topologyHash() : "LOCAL",
                externalPower ? powerNetwork.configHash() : "LOCAL",
                externalPower ? powerNetwork.modelVersion() : "LOCAL_POWER",
                externalPower ? powerNetwork.parameterVersion() : "LOCAL",
                powerHealth.heartbeatStatus()), now);
            autoCheckRecovery(powerHealthRecord, simulationRunId, tick, now);
        }
    }

    private void autoCheckRecovery(
        ServiceHealthRecord record, String expectedRunId, long expectedTick, Instant now
    ) {
        if (record.state() == ServiceHealthState.RECOVERING) {
            serviceHealthService.checkRecovery(record.serviceId(), expectedRunId, expectedTick, now);
        }
    }

    private List<Alarm> buildAlarms(
        Instant simulatedTime,
        List<TrainState> trains,
        List<TrackSegmentState> trackSegments,
        List<MovementAuthority> authorities,
        List<PowerSectionState> powerSections,
        List<DomainEvent> events
    ) {
        List<Alarm> alarms = new ArrayList<>();
        trains.stream()
            .filter(train -> !"OK".equals(train.faultCode()) || train.faultLevel() > 0)
            .map(train -> new Alarm(
                "TRAIN_FAULT:" + train.id() + ":" + train.faultCode(),
                "train",
                train.id(),
                train.faultLevel() <= 0 ? 2 : train.faultLevel(),
                "车辆物理状态异常",
                "车辆故障码：" + train.faultCode() + "，自检：" + train.selfCheckStatus() + "，可用模式：" + train.availableOperationMode(),
                simulatedTime,
                false
            ))
            .forEach(alarms::add);
        trackSegments.stream()
            .filter(seg -> seg.occupancy() == com.railwaysim.track.TrackOccupancy.FAULT)
            .map(seg -> new Alarm(
                "TRACK_FAULT:" + seg.id(),
                "track",
                seg.id(),
                3,
                "轨道区段故障",
                "区段 " + seg.id() + " 处于 FAULT 状态，前方信号降红、MA 截断",
                simulatedTime,
                false
            ))
            .forEach(alarms::add);
        authorities.stream()
            .filter(authority -> authority.authorityEndMeters() <= 0 || "前方安全距离不足".equals(authority.reason()))
            .map(authority -> new Alarm(
                "SIGNAL_MA_LIMIT:" + authority.trainId(),
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
                "POWER_STATE:" + section.id() + ":" + section.status(),
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
            .map(event -> alarmFromEvent(simulatedTime, event))
            .filter(alarm -> alarm != null)
            .forEach(alarms::add);
        return alarms;
    }

    private Alarm alarmFromEvent(Instant simulatedTime, DomainEvent event) {
        if (event instanceof FmuStepFailedEvent fmuStepFailed) {
            return new Alarm(
                "FMU_STEP_FAILED:" + fmuStepFailed.trainId(),
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
                "FMU_FALLBACK:" + fallbackActivated.scope(),
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
                "POWER_LIMIT:" + powerLimit.sectionId(),
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
            if ("CLEARED".equals(powerFault.state())) {
                return null;
            }
            return new Alarm(
                "POWER_FAULT:" + powerFault.sectionId() + ":" + powerFault.faultType(),
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
            if ("NONE".equals(maintenanceLock.maintenanceState())) {
                return null;
            }
            return new Alarm(
                "POWER_LOCK:" + maintenanceLock.sectionId(),
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
                "REGEN_UNABSORBED:" + regenerativeEnergy.sectionId(),
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
            if ("CLEARED".equals(trainFault.state())) {
                return null;
            }
            return new Alarm(
                "TRAIN_FAULT_EVENT:" + trainFault.trainId() + ":" + trainFault.faultCode(),
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
