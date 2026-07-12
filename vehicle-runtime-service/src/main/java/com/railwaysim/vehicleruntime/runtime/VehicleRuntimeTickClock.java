package com.railwaysim.vehicleruntime.runtime;

import com.railwaysim.vehicleruntime.config.VehicleRuntimeProperties;
import com.railwaysim.vehicleruntime.model.DispatchConstraintSnapshot;
import com.railwaysim.vehicleruntime.model.MovementAuthoritySnapshot;
import com.railwaysim.vehicleruntime.model.PowerConstraintSnapshot;
import com.railwaysim.vehicleruntime.model.TrackConstraintSnapshot;
import com.railwaysim.vehicleruntime.model.VehicleRuntimeStepRequest;
import com.railwaysim.vehicleruntime.model.VehicleRuntimeStepResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 自主 tick 时钟，用于 9300 独立运行模式。
 * <p>
 * 中央驱动模式（默认）下自主时钟关闭，由中央发 step-fleet 驱动。
 * 自主模式下通过 @Scheduled 或手动 API 推进所有注册列车的仿真。
 */
@Component
@EnableScheduling
public class VehicleRuntimeTickClock {

    private static final Logger log = LoggerFactory.getLogger(VehicleRuntimeTickClock.class);

    private final VehicleRuntimeManager manager;
    private final VehicleRuntimeProperties properties;
    private final Object sessionLock = new Object();
    private final AtomicBoolean tickInProgress = new AtomicBoolean();

    private volatile long autonomousTick = 0;
    private volatile boolean autonomousMode;
    private volatile String autonomousRunId = "";

    @Autowired
    public VehicleRuntimeTickClock(VehicleRuntimeManager manager, VehicleRuntimeProperties properties) {
        this.manager = manager;
        this.properties = properties;
    }

    /**
     * 定时自主 tick，仅当 autonomousTickEnabled 时生效。
     */
    @Scheduled(fixedRateString = "${vehicle-runtime.autonomous-tick-interval-ms:100}")
    public void autonomousTick() {
        if (!properties.isAutonomousTickEnabled()) {
            return;
        }
        if (manager.instances().isEmpty()) {
            return;
        }
        if (!tickInProgress.compareAndSet(false, true)) {
            log.warn("[TickClock] skipped overlapping autonomous tick, runId={}, currentTick={}",
                autonomousRunId, autonomousTick);
            return;
        }
        try {
            doAutonomousTick();
        } finally {
            tickInProgress.set(false);
        }
    }

    private void doAutonomousTick() {
        final long tick;
        final String runId;
        synchronized (sessionLock) {
            if (!properties.isAutonomousTickEnabled()) {
                return;
            }
            ensureAutonomousSessionLocked();
            tick = ++autonomousTick;
            runId = autonomousRunId;
        }
        VehicleRuntimeStepResponse response = manager.stepFleet(new VehicleRuntimeStepRequest(
            tick,
            0.1,
            Instant.now(),
            List.of(),
            generateDefaultAuthorities(),
            generateDefaultTracks(),
            generateDefaultDispatches(),
            generateDefaultPowers(),
            runId,
            List.of()
        ));
        log.debug("[TickClock] autonomous tick {} complete: {} trains, dataQuality={}",
            tick,
            response.trainStates() != null ? response.trainStates().size() : 0,
            response.dataQuality());
    }

    /** 启用自主模式。 */
    public void enable() {
        synchronized (sessionLock) {
            if (properties.isAutonomousTickEnabled() && !autonomousRunId.isBlank()) {
                return;
            }
            autonomousTick = 0;
            autonomousRunId = newAutonomousRunId();
            autonomousMode = true;
            properties.setAutonomousTickEnabled(true);
        }
        log.info("[TickClock] autonomous mode ENABLED, runId={}", autonomousRunId);
    }

    /** 禁用自主模式。 */
    public void disable() {
        String stoppedRunId;
        synchronized (sessionLock) {
            stoppedRunId = autonomousRunId;
            autonomousMode = false;
            properties.setAutonomousTickEnabled(false);
            autonomousRunId = "";
            autonomousTick = 0;
        }
        log.info("[TickClock] autonomous mode DISABLED, runId={}", stoppedRunId);
    }

    public boolean isAutonomousMode() {
        return autonomousMode || properties.isAutonomousTickEnabled();
    }

    public long getCurrentTick() {
        return autonomousTick;
    }

    String getCurrentRunId() {
        return autonomousRunId;
    }

    private void ensureAutonomousSessionLocked() {
        if (autonomousRunId.isBlank()) {
            autonomousTick = 0;
            autonomousRunId = newAutonomousRunId();
            autonomousMode = true;
            log.info("[TickClock] autonomous session initialized from configuration, runId={}",
                autonomousRunId);
        }
    }

    private String newAutonomousRunId() {
        return "autonomous-run-" + UUID.randomUUID();
    }

    // ========== 默认约束生成 ==========

    public List<MovementAuthoritySnapshot> generateDefaultAuthorities() {
        return manager.instances().stream()
            .map(inst -> new MovementAuthoritySnapshot(
                inst.trainId(),
                properties.getDefaultLineLengthMeters(),
                properties.getDefaultSpeedLimitMetersPerSecond(),
                "AUTONOMOUS_DEFAULT"))
            .toList();
    }

    public List<TrackConstraintSnapshot> generateDefaultTracks() {
        return manager.instances().stream()
            .map(inst -> new TrackConstraintSnapshot(
                inst.trainId(), "",
                properties.getDefaultSpeedLimitMetersPerSecond(),
                0, 1000, 1_000_000.0))
            .toList();
    }

    public List<DispatchConstraintSnapshot> generateDefaultDispatches() {
        return manager.instances().stream()
            .map(inst -> new DispatchConstraintSnapshot(
                inst.trainId(), false, 1.0, null, false, "AUTONOMOUS"))
            .toList();
    }

    public List<PowerConstraintSnapshot> generateDefaultPowers() {
        return manager.instances().stream()
            .map(inst -> new PowerConstraintSnapshot(
                inst.trainId(), "", 750, 2_000_000.0, true, 1.0, true, true, "AUTONOMOUS"))
            .toList();
    }
}
