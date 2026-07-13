package com.railwaysim.vehicle.control;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 车辆控制仲裁器 — 安全优先级仲裁的核心。
 * <p>
 * 仲裁规则（优先级从高到低）：
 * <ol>
 *   <li>紧急制动 — 牵引=0, 制动=1, emergencyBrake=true</li>
 *   <li>MA/ATP — 行车许可终点优先</li>
 *   <li>门/钥匙/自检/制动可用性 — 任一项不可用则禁止牵引</li>
 *   <li>供电约束 — 失电/欠压/过流/降额</li>
 *   <li>{@link VehicleOperationMode#MANUAL 人工命令} — 最高非安全候选</li>
 *   <li>{@link VehicleOperationMode#AUTO 自动/智能命令} — 次高非安全候选</li>
 *   <li>巡航/惰行 — 最低优先级</li>
 * </ol>
 * <p>
 * 任一更高优先级制动生效时 {@code traction=0}。
 * 紧急制动必须输出 {@code brake=1} 和 {@code emergencyBrake=true}。
 * MANUAL 只提高人工命令在非安全候选中的优先级，不能绕过安全层。
 */
/** @deprecated LOCAL mode only. Replaced by 9300's VehicleControlQueue. */
@Deprecated(forRemoval=true, since="2.0")
public class VehicleControlArbiter {

    private static final Logger logger = LoggerFactory.getLogger(VehicleControlArbiter.class);

    private final String runId;
    private final long tick;
    private final String trainId;
    private final String traceId;
    private final List<VehicleControlCandidate> candidates;
    private final int decisionVersion;

    private boolean doorClosed = true;
    private boolean currentCollectionAvailable = true;
    private boolean tractionAvailable = true;
    private boolean brakeAvailable = true;

    public VehicleControlArbiter(String runId, long tick, String trainId) {
        this(runId, tick, trainId, UUID.randomUUID().toString(), 1);
    }

    public VehicleControlArbiter(String runId, long tick, String trainId, String traceId, int decisionVersion) {
        this.runId = runId;
        this.tick = tick;
        this.trainId = trainId;
        this.traceId = traceId;
        this.decisionVersion = decisionVersion;
        this.candidates = new ArrayList<>();
    }

    // ── 候选注册 ──────────────────────────────────────

    /**
     * 注册一个安全约束候选（紧急制动、MA、门/钥匙、供电约束等）。
     */
    public VehicleControlArbiter withSafetyCandidate(
        VehicleSafetyConstraint constraint,
        double tractionCommand,
        double brakeCommand,
        boolean emergencyBrake,
        String reasonCode
    ) {
        candidates.add(new VehicleControlCandidate(
            candidateId("SAFETY", constraint.name()),
            trainId,
            "SAFETY_LAYER",
            constraint,
            null,
            tractionCommand,
            brakeCommand,
            emergencyBrake,
            0.0,
            false,
            Instant.now()
        ));
        return this;
    }

    /**
     * 注册一个司机台控制命令候选。
     */
    public VehicleControlArbiter withDriverCandidate(
        DriverControlCommand cmd,
        boolean doorClosed,
        boolean currentCollectionAvailable,
        boolean tractionAvailable,
        boolean brakeAvailable
    ) {
        // Save state for decision
        this.doorClosed = doorClosed;
        this.currentCollectionAvailable = currentCollectionAvailable;
        this.tractionAvailable = tractionAvailable;
        this.brakeAvailable = brakeAvailable;
        // 验证命令合法性
        double traction = cmd.isTractionCommandValid() ? cmd.tractionCommand() : 0.0;
        double brake = cmd.isBrakeCommandValid() ? cmd.brakeCommand() : 0.0;
        double direction = cmd.hasValidDirection() ? cmd.direction() : 0.0;
        boolean eb = cmd.emergencyBrake();

        // 安全层强制：禁止牵引条件
        if (!doorClosed || !tractionAvailable || !brakeAvailable || !currentCollectionAvailable) {
            if (traction > 0.001) {
                logger.warn("Driver traction overridden by safety: train={} doorClosed={} tractionAvail={} brakeAvail={} power={}",
                    trainId, doorClosed, tractionAvailable, brakeAvailable, currentCollectionAvailable);
                traction = 0.0;
            }
        }
        if (Math.abs(direction) < 0.5) {
            traction = 0.0;
        }

        VehicleOperationMode commandMode;
        try {
            commandMode = VehicleOperationMode.valueOf(cmd.operationMode());
        } catch (RuntimeException exception) {
            commandMode = VehicleOperationMode.MANUAL;
        }

        candidates.add(new VehicleControlCandidate(
            candidateId("DRIVER", cmd.commandId()),
            trainId,
            "DRIVER",
            null,
            commandMode,
            traction,
            brake,
            eb,
            direction,
            cmd.doorOpenRequest(),
            cmd.receivedAt()
        ));
        return this;
    }

    /**
     * 注册一个自动/ATO 控制命令候选。
     */
    public VehicleControlArbiter withAutoCandidate(
        AutomaticControlCommand cmd,
        VehicleOperationMode mode
    ) {
        candidates.add(new VehicleControlCandidate(
            candidateId("AUTO", cmd.commandId()),
            trainId,
            cmd.source(),
            null,
            mode,
            cmd.tractionCommand(),
            cmd.brakeCommand(),
            cmd.emergencyBrake(),
            cmd.direction(),
            false,
            Instant.now()
        ));
        return this;
    }

    // ── 仲裁 ──────────────────────────────────────────

    /**
     * 执行优先级仲裁，返回唯一最终决策。
     */
    public VehicleControlDecision arbitrate() {
        Instant now = Instant.now();

        // 1. 安全约束候选（按优先级排序）
        List<VehicleControlCandidate> safetyCandidates = candidates.stream()
            .filter(VehicleControlCandidate::isSafetyDerived)
            .sorted(Comparator.comparingInt(c -> c.safetyConstraint().priority()))
            .toList();

        // 2. 非安全候选（按操作模式排序：MANUAL > AUTO/INTELLIGENT > 巡航）
        List<VehicleControlCandidate> nonSafetyCandidates = candidates.stream()
            .filter(c -> !c.isSafetyDerived())
            .sorted(arbitrationComparator())
            .toList();

        // 3. 确定生效候选
        VehicleControlCandidate selected;
        VehicleControlCandidate highestSafety = safetyCandidates.isEmpty() ? null : safetyCandidates.get(0);
        VehicleControlCandidate highestNonSafety = nonSafetyCandidates.isEmpty() ? null : nonSafetyCandidates.get(0);

        // 安全约束覆盖非安全候选
        if (highestSafety != null) {
            // 如果最高安全约束不是紧急制动，且非安全候选存在，检查是否需要覆盖
            if (highestSafety.safetyConstraint() == VehicleSafetyConstraint.EMERGENCY_BRAKE) {
                selected = highestSafety;
            } else if (highestSafety.safetyConstraint() == VehicleSafetyConstraint.MA_ATP) {
                // MA/ATP 制动禁止牵引，但允许常用制动
                selected = highestSafety;
            } else if (highestSafety.safetyConstraint() == VehicleSafetyConstraint.DOOR_KEY_SELFCHECK
                || highestSafety.safetyConstraint() == VehicleSafetyConstraint.POWER_CONSTRAINT) {
                // 门/钥匙/供电约束：禁止牵引，允许常用制动
                selected = highestSafety;
            } else {
                selected = highestSafety;
            }

            // 但非安全候选的 direction 和 door 命令仍可保留
            if (highestNonSafety != null && !highestSafety.requiresBrake()) {
                // 如果安全层不要求制动，非安全候选的牵引仍被禁止（安全优先）
                // 但 direction 和 door 可从非安全候选获取
            }
        } else if (highestNonSafety != null) {
            selected = highestNonSafety;
        } else {
            // 无任何候选：安全制动
            selected = new VehicleControlCandidate(
                candidateId("FALLBACK", "NO_CANDIDATE"),
                trainId,
                "FALLBACK",
                VehicleSafetyConstraint.EMERGENCY_BRAKE,
                VehicleOperationMode.MANUAL,
                0.0, 1.0, true, 0.0, false, now
            );
        }

        // 4. 构建最终决策
        String reasonCode = resolveReasonCode(selected, highestSafety, highestNonSafety);
        List<String> overriddenIds = candidates.stream()
            .filter(c -> c != selected)
            .map(VehicleControlCandidate::candidateId)
            .collect(Collectors.toList());

        VehicleOperationMode mode = selected.operationMode() != null
            ? selected.operationMode()
            : VehicleOperationMode.MANUAL;

        return new VehicleControlDecision(
            null,
            runId,
            tick,
            trainId,
            mode,
            selected.source(),
            selected.tractionCommand(),
            selected.brakeCommand(),
            selected.emergencyBrake(),
            selected.direction(),
            doorClosed,
            currentCollectionAvailable,
            tractionAvailable,
            brakeAvailable,            overriddenIds,
            reasonCode,
            selected.createdAt(),
            now,
            traceId,
            decisionVersion
        );
    }

    // ── 原因码解析 ────────────────────────────────────

    private String resolveReasonCode(
        VehicleControlCandidate selected,
        VehicleControlCandidate highestSafety,
        VehicleControlCandidate highestNonSafety
    ) {
        if (selected.emergencyBrake()) {
            if (highestSafety != null && highestSafety.safetyConstraint() == VehicleSafetyConstraint.EMERGENCY_BRAKE) {
                return "EMERGENCY_BRAKE";
            }
            return "EMERGENCY_BRAKE";
        }
        if (highestSafety != null) {
            return switch (highestSafety.safetyConstraint()) {
                case EMERGENCY_BRAKE -> "EMERGENCY_BRAKE";
                case MA_ATP -> "MA_ATP_LIMIT";
                case COMMAND_STALE -> "DRIVER_COMMAND_STALE";
                case DOOR_KEY_SELFCHECK -> "DOOR_KEY_BLOCK";
                case POWER_CONSTRAINT -> "POWER_LOSS";
            };
        }
        if (selected.tractionCommand() > 0.001) {
            return "TRACTION_COMMANDED";
        }
        if (selected.brakeCommand() > 0.001) {
            return "BRAKE_COMMANDED";
        }
        return "COASTING";
    }

    private String candidateId(String prefix, String id) {
        return prefix + "-" + trainId + "-" + tick + "-" + id;
    }

    private Comparator<VehicleControlCandidate> arbitrationComparator() {
        return Comparator.<VehicleControlCandidate, Integer>comparing(c -> {
            if (c.operationMode() == null) return 2;
            return switch (c.operationMode()) {
                case MANUAL -> 0;
                case AUTO, INTELLIGENT -> 1;
            };
        }).thenComparing(c -> c.emergencyBrake() ? 0 : 1);
    }

    // ── 查询 ──────────────────────────────────────────

    public List<VehicleControlCandidate> candidates() {
        return List.copyOf(candidates);
    }

    // ── 工厂方法 ──────────────────────────────────────

    /**
     * 创建仲裁器并立即执行，简化单次仲裁流程。
     */
    public static VehicleControlDecision decide(
        String runId, long tick, String trainId,
        Optional<DriverControlCommand> driverCommand,
        Optional<AutomaticControlCommand> autoCommand,
        boolean emergencyBrakeRequired,
        boolean maBrakeRequired,
        boolean doorClosed,
        boolean currentCollectionAvailable,
        boolean tractionAvailable,
        boolean brakeAvailable,
        boolean powerAvailable,
        VehicleOperationMode mode,
        boolean overspeed
    ) {
        var arbiter = new VehicleControlArbiter(runId, tick, trainId);

        boolean manualMode = mode == VehicleOperationMode.MANUAL;
        boolean driverCommandStale = driverCommand
            .map(command -> manualMode && command.isExpired(Instant.now()))
            .orElse(false);

        // 1. 紧急制动 — 最高安全约束
        boolean driverEmergencyBrake = driverCommand
            .map(DriverControlCommand::emergencyBrake)
            .orElse(false);
        if (emergencyBrakeRequired || driverEmergencyBrake) {
            arbiter.withSafetyCandidate(
                VehicleSafetyConstraint.EMERGENCY_BRAKE,
                0.0, 1.0, true, "EMERGENCY_BRAKE"
            );
        }

        // 2. MA/ATP — 行车许可/速度约束
        if (maBrakeRequired || overspeed) {
            arbiter.withSafetyCandidate(
                VehicleSafetyConstraint.MA_ATP,
                0.0, 0.5, false, "MA_ATP_LIMIT"
            );
        }

        if (driverCommandStale) {
            arbiter.withSafetyCandidate(
                VehicleSafetyConstraint.COMMAND_STALE,
                0.0, 0.5, false, "DRIVER_COMMAND_STALE"
            );
        }

        // 3. 门/钥匙/自检/制动可用性
        if (!doorClosed || !tractionAvailable || !brakeAvailable) {
            arbiter.withSafetyCandidate(
                VehicleSafetyConstraint.DOOR_KEY_SELFCHECK,
                0.0, tractionAvailable && brakeAvailable ? 0.0 : 0.5, false,
                "DOOR_KEY_BLOCK"
            );
        }

        // 4. 供电约束
        if (!currentCollectionAvailable || !powerAvailable) {
            arbiter.withSafetyCandidate(
                VehicleSafetyConstraint.POWER_CONSTRAINT,
                0.0, 0.3, false, "POWER_LOSS"
            );
        }

        // 5. 人工命令
        driverCommand.filter(cmd -> manualMode && !driverCommandStale).ifPresent(cmd ->
            arbiter.withDriverCandidate(cmd, doorClosed, currentCollectionAvailable, tractionAvailable, brakeAvailable)
        );

        // 6. 自动命令
        autoCommand.ifPresent(cmd ->
            arbiter.withAutoCandidate(cmd, mode)
        );

        // 7. 巡航/惰行 — 如果没有命令，默认惰行
        if (driverCommand.isEmpty() && autoCommand.isEmpty()) {
            arbiter.withAutoCandidate(
                new AutomaticControlCommand("cruise-" + tick, trainId, "RULE_ENGINE", 0.0, 0.0, false, 1.0, UUID.randomUUID().toString()),
                VehicleOperationMode.AUTO
            );
        }

        return arbiter.arbitrate();
    }
}
