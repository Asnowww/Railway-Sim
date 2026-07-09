# 运营调度模块详细设计

> 文档版本：v1.1  
> 所属项目：Railway-Sim 上京地铁仿真系统  
> 模块负责人：运营调度  
> 概要设计见：[DISPATCH_OUTLINE_DESIGN.md](./DISPATCH_OUTLINE_DESIGN.md)  
> 编写说明：本文以当前 Spring Boot 单体架构下的原方案为实施基准；与 FMU/TCMS Adapter 相关的接入方式见**附录 A**，待组长总架构定稿后对齐，不影响本文核心业务设计。

---

## 1. 文档目的与范围

### 1.1 目的

明确运营调度模块的功能边界、内部结构、数据模型、处理流程、接口约定及与其他模块的协作方式，为开发、联调、测试和答辩提供依据。

### 1.2 需求追溯

| 需求编号 | 需求摘要 | 本文对应章节 |
|----------|----------|--------------|
| OPS-FR-01 | 分时段运行计划配置 | §3.1、§4.1 |
| OPS-FR-02 | 计划参数可调整 | §3.1、§7.1 |
| OPS-FR-03 | 实时采集列车运行状态 | §3.2、§5.2 |
| OPS-FR-04 | 计划与实际对比 | §3.2、§6.1 |
| OPS-FR-05 | 识别并记录扰动 | §3.3、§4.3 |
| OPS-FR-06 | 扰动参数微调 | §3.4、§6.3 |
| OPS-FR-07 | 恢复合理行车间隔 | §3.4、§6.3 |
| OPS-FR-08 | 联动相关子系统 | §3.5、§8 |
| OPS-FR-09 | 符合安全约束 | §3.4、§6.4 |
| OPS-FR-10 | 可视化展示 | §3.6、§10 |
| OPS-FR-11 | 过程数据记录 | §4.2、§4.3、§4.4 |

---

## 2. 总体设计

### 2.1 数据分层

| 层次 | 内容 | 存储 |
|------|------|------|
| 配置层 | 运行计划、阈值 | `running_plan_config`、`system_config` |
| 实时层 | 列车偏差、开放扰动、待执行指令 | 内存对象 |
| 历史层 | 到发记录、扰动、指令 | `train_station_record`、`disturbance_record`、`dispatch_command_record` |
| 展示层 | 调度快照 | WebSocket `SimulationSnapshot.dispatch` |

### 2.2 后端包结构

```text
backend/src/main/java/com/railwaysim/dispatch/
├── plan/
│   ├── OperationPlanLoader.java
│   ├── OperationPlanCache.java
│   ├── RunModePeriod.java
│   └── CurrentRunPlan.java
├── monitor/
│   ├── TrainRunMonitor.java
│   ├── TrainRunProfile.java
│   ├── ArrivalDepartureDetector.java
│   └── StationRecordWriter.java
├── disturbance/
│   ├── DisturbanceDetector.java
│   ├── DisturbanceType.java
│   ├── DisturbanceEvent.java
│   └── DisturbanceRecordWriter.java
├── strategy/
│   ├── DispatchStrategy.java
│   ├── DwellAdjustStrategy.java
│   ├── SpeedBiasStrategy.java
│   ├── HeadwayAdjustStrategy.java
│   ├── CrowdingStrategy.java
│   └── StrategySelector.java
├── command/
│   ├── DispatchCommand.java
│   ├── CommandQueue.java
│   ├── CommandValidator.java
│   ├── CommandStatus.java
│   └── DispatchCommandRecordWriter.java
├── integration/
│   ├── DispatchCommandPublisher.java
│   └── TrainManagerPublisher.java
├── DispatchService.java
├── DispatchController.java
└── DispatchSnapshot.java
```

---

## 3. 子模块详细设计

### 3.1 运行计划管理子模块

#### 3.1.1 时段匹配规则

```text
输入：仿真时刻 T，线路 line_id
输出：CurrentRunPlan(periodType, departureIntervalSec, defaultDwellTimeSec)

规则：
  1. 查询 line_id 下 enabled=true 的全部时段
  2. 若 start_time <= end_time：T 在 [start, end) 内则命中
  3. 若 start_time > end_time（跨午夜）：T >= start 或 T < end 则命中
  4. 多段重叠时，按 period_type 优先级：PEAK > FLAT > OFF_PEAK
  5. 无命中时使用 FLAT 默认值
```

#### 3.1.2 核心数据结构

```java
public record CurrentRunPlan(
    String planId,
    String lineId,
    String periodType,
    int departureIntervalSec,
    int defaultDwellTimeSec,
    Instant effectiveFrom
) {}
```

---

### 3.2 列车运行状态监控子模块

#### 3.2.1 到发检测逻辑

```text
输入：TrainState(positionMeters, speedMps, status)
      station_config(positionMeters)

到站判定：
  |positionMeters - station.positionMeters| <= ARRIVAL_THRESHOLD(5m)
  且 speedMps < STOP_SPEED_THRESHOLD(0.5 m/s)
  且 上一 tick 未在该站停稳
  → 记录 actual_arrival，status 置 DWELLING

离站判定：
  上一 tick 在站停稳，本 tick speedMps > STOP_SPEED_THRESHOLD
  → 记录 actual_departure，计算 dwellElapsedSec
```

#### 3.2.2 计划到发时刻生成

本项目不做完整时刻表，采用递推 headway 生成计划时刻：

```text
首班车 planned_departure(S01) = 仿真开始时刻 + 偏移
后续站 planned_arrival = 上一站 planned_departure + 区间运行时间(估算)
planned_departure = planned_arrival + defaultDwellTimeSec
同向下一列车 planned_departure = 前车 planned_departure + departureIntervalSec
```

区间运行时间估算（启发式算法）：

```text
plannedSpeedMps = segmentSpeedLimitMps × dispatch.averageSpeedRatio
segmentTravelSec = segmentLengthMeters / plannedSpeedMps
```

- `segmentSpeedLimitMps`：取自 `track_segment_config` 或 `line_config.default_speed_limit_mps`
- `dispatch.averageSpeedRatio`：计划均速相对限速的比例，默认 **0.8**，存入 `system_config`（见 §4.6）
- 联调时若实际运行偏慢/偏快，只需改配置，无需重新编译

#### 3.2.3 偏差计算

| 指标 | 计算方式 |
|------|----------|
| `dwellDeviationSec` | 实际停站时长 − defaultDwellTimeSec |
| `departureDelaySec` | 实际发车 − 计划发车（正为延误） |
| `headwayActualSec` | 与前车实际发车间隔 |
| `headwayDeviationSec` | headwayActualSec − departureIntervalSec |

#### 3.2.4 核心数据结构

```java
public record TrainRunProfile(
    String trainId,
    double positionMeters,
    double speedMps,
    double loadRate,
    String status,
    String currentStationId,
    int dwellElapsedSec,
    int plannedDwellSec,
    int dwellDeviationSec,
    Double headwayToPrecedingSec,
    int headwayDeviationSec,
    Instant lastDepartureAt
) {}
```

---

### 3.3 运行扰动识别子模块

#### 3.3.1 扰动类型与触发条件

| 枚举值 | 含义 | 默认触发条件 |
|--------|------|--------------|
| `DWELL_EXTENDED` | 停站超时 | dwellDeviationSec > 15 |
| `DEPARTURE_DELAY` | 发车延误 | departureDelaySec > 30 |
| `HEADWAY_SHRINK` | 间隔过小 | headwayActual < target × 0.7 |
| `HEADWAY_EXPAND` | 间隔过大 | headwayActual > target × 1.5 |
| `CROWDING` | 客流拥挤 | loadRate > 0.80 或站滞留超容量 80% |

#### 3.3.2 防抖与冷却

```text
确认：同一条件连续 CONFIRM_TICKS(默认 3) 个评估周期满足，才创建扰动
冷却：同 trainId + disturbance_type 在 COOLDOWN_SEC(默认 60s) 内不重复创建
恢复：偏差回到 RECOVER_RATIO(默认 0.3) × 阈值 以内，持续 RECOVER_TICKS(默认 5) 周期
      → status 置 RECOVERED，写 resolved_at
```

#### 3.3.3 与 alarm_record 的分工

| 场景 | 写入表 |
|------|--------|
| 轻微扰动（停站超时 10～20s） | 仅 disturbance_record |
| 严重扰动（间隔过大 >2 倍、拥挤 >100%） | disturbance_record + 可选 alarm_record(level=2) |

---

### 3.4 调度策略执行子模块

#### 3.4.1 策略映射表

| 扰动类型 | 策略 | 指令类型 | 默认参数 |
|----------|------|----------|----------|
| DWELL_EXTENDED | 后续车缩短停站 | SHORTEN_DWELL | −5s |
| DEPARTURE_DELAY | 区间略提速 | SPEED_BIAS | ×1.05 |
| HEADWAY_SHRINK | 延长停站 | EXTEND_DWELL | +5s |
| HEADWAY_EXPAND | 缩短停站 | SHORTEN_DWELL | −3s |
| CROWDING | 缩短目标间隔 | HEADWAY_ADJUST | target × 0.85 |

> HEADWAY_ADJUST 仅调整目标间隔与展示建议，不重构运行图。

#### 3.4.2 安全约束

| 约束项 | 规则 |
|--------|------|
| 停站时间 | 调整后 ∈ [15, 60] 秒 |
| 速度调整 | 不超过 MovementAuthority.speedLimitMetersPerSecond |
| 单次调整幅度 | dwell 单次 ±10s；速度 ratio ∈ [0.9, 1.1] |
| 目标对象 | 优先调整后车，避免加剧前车延误 |

#### 3.4.3 指令状态机

```text
PENDING → APPLIED → EXPIRED
         ↘ SKIPPED（被安全约束拒绝）
         ↘ CANCELLED（恢复后撤销）
```

---

### 3.5 调度结果联动子模块

#### 3.5.1 联动流程

```text
1. StrategySelector 输出 List<DispatchCommand>
2. CommandValidator 读取 SignalService.authorities() 校验 SPEED_BIAS
3. CommandQueue 写入待执行队列
4. DispatchCommandPublisher.publish(commands) → TrainManager
5. 下一 tick TrainManager 应用并反馈
6. 更新 status，异步写 dispatch_command_record
7. 可选：SimpleEventBus.publish(DispatchCommandIssuedEvent)
```

#### 3.5.2 Publisher 抽象

```java
public interface DispatchCommandPublisher {
    void publish(List<DispatchCommand> commands);
    List<DispatchExecutionReport> lastReports();
}
```

| 实现类 | 场景 |
|--------|------|
| TrainManagerPublisher | **当前默认** |
| TcmsAdapterPublisher | 待定，见附录 A.2 |

---

### 3.6 调度信息展示子模块

#### 3.6.1 前端页面结构

```text
frontend/src/views/dispatch/DispatchView.vue
├── components/dispatch/RunPlanPanel.vue
├── components/dispatch/HeadwayChart.vue
├── components/dispatch/DisturbanceList.vue
├── components/dispatch/CommandTimeline.vue
└── components/dispatch/DispatchStatusBar.vue
```

#### 3.6.2 数据来源

| 数据 | 来源 |
|------|------|
| 实时调度状态 | WebSocket snapshot.dispatch |
| 运行计划 | REST GET /api/dispatch/plan |
| 历史到发/扰动/指令 | REST 分页查询 |

#### 3.6.3 推送频率与前端渲染

前端调度页**不单独建立 WebSocket**，统一订阅 `api/ws.ts` 的全局快照。

**计算与推送分离：**

| 环节 | 频率 | 配置项 | 说明 |
|------|------|--------|------|
| 仿真 tick | 如 200ms | `simulation.tickMillis` | 车辆/轨道/信号物理步进 |
| 调度评估 | 如 1000ms | `dispatch.evaluateIntervalMs` | `DispatchService.evaluate()` |
| WebSocket 推送 | ≤1000ms | `simulation.pushIntervalMillis` | 快照广播限流 |

即使 tick 为 100ms（每秒 10 次），`SimulationRuntime` 或 `SimulationWebSocketHandler` 也须按 `pushIntervalMillis` **限流推送**：内存状态每个 tick 更新，WebSocket 最多每秒 1 次，避免 Vue 因数据过密卡顿。

```text
每个 tick：
  更新内存状态（含 dispatch 段）
  if (now - lastPushAt >= pushIntervalMillis) {
      webSocketHandler.broadcast(snapshot)
      lastPushAt = now
  }
```

调度模块只负责维护最新 `DispatchSnapshot` 内存对象；**推送限流由仿真总控/WebSocket 层统一实现**，调度模块不重复推送。

---

## 4. 数据库设计

### 4.1 全库 18 张表与调度关系

**配置类（7）：** line_config、station_config、track_segment_config、switch_config、power_section_config、train_info、system_config

**运行记录类（7）：** train_run_record、track_occupancy_record、signal_state_record、power_state_record、passenger_flow_record、alarm_record、dispatch_command_record

**调度专用（3）：** running_plan_config、train_station_record、disturbance_record

**审计类（1）：** operation_log

---

### 4.2 running_plan_config

```sql
CREATE TABLE IF NOT EXISTS running_plan_config (
  id                     BIGINT PRIMARY KEY AUTO_INCREMENT,
  plan_id                VARCHAR(64)  NOT NULL COMMENT '计划包编号',
  line_id                VARCHAR(64)  NOT NULL COMMENT '关联线路',
  period_type            VARCHAR(32)  NOT NULL COMMENT 'PEAK/FLAT/OFF_PEAK',
  start_time             TIME         NOT NULL COMMENT '时段开始',
  end_time               TIME         NOT NULL COMMENT '时段结束',
  departure_interval_sec INT          NOT NULL COMMENT '基础发车间隔(秒)',
  default_dwell_time_sec INT          NOT NULL COMMENT '默认停站时间(秒)',
  enabled                BOOLEAN      NOT NULL DEFAULT TRUE,
  created_at             TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at             TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
                           ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_plan_line_period (plan_id, line_id, period_type),
  INDEX idx_plan_line_enabled (line_id, enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

**初始数据（示范线）：**

| plan_id | line_id | period_type | start | end | 间隔(s) | 停站(s) |
|---------|---------|-------------|-------|-----|---------|---------|
| RP-demo-001 | demo-line-1 | PEAK | 07:00 | 09:00 | 180 | 30 |
| RP-demo-001 | demo-line-1 | PEAK | 17:00 | 19:00 | 180 | 30 |
| RP-demo-001 | demo-line-1 | FLAT | 09:00 | 17:00 | 300 | 25 |
| RP-demo-001 | demo-line-1 | OFF_PEAK | 19:00 | 07:00 | 420 | 20 |

---

### 4.3 train_station_record

```sql
CREATE TABLE IF NOT EXISTS train_station_record (
  id                  BIGINT PRIMARY KEY AUTO_INCREMENT,
  simulation_run_id   VARCHAR(64)  NOT NULL COMMENT '仿真会话ID',
  train_id            VARCHAR(64)  NOT NULL,
  line_id             VARCHAR(64)  NOT NULL,
  station_id          VARCHAR(64)  NOT NULL,
  planned_arrival     TIMESTAMP    NULL,
  actual_arrival      TIMESTAMP    NULL,
  planned_departure   TIMESTAMP    NULL,
  actual_departure    TIMESTAMP    NULL,
  arrival_delay_sec   INT          DEFAULT 0,
  departure_delay_sec INT          DEFAULT 0,
  simulated_at        TIMESTAMP    NOT NULL COMMENT '仿真时刻',
  recorded_at         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_tsr_run_train (simulation_run_id, train_id, station_id),
  INDEX idx_tsr_train_time (train_id, simulated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

写入规则：仅在到发事件发生时异步 INSERT/UPDATE，不在每个 tick 写入。

---

### 4.4 disturbance_record

```sql
CREATE TABLE IF NOT EXISTS disturbance_record (
  id                BIGINT PRIMARY KEY AUTO_INCREMENT,
  simulation_run_id VARCHAR(64)  NOT NULL,
  train_id          VARCHAR(64)  NOT NULL,
  station_id        VARCHAR(64)  NULL,
  disturbance_type  VARCHAR(64)  NOT NULL,
  deviation_value   DOUBLE       NOT NULL,
  deviation_unit    VARCHAR(16)  NOT NULL DEFAULT 'SECONDS',
  status            VARCHAR(32)  NOT NULL DEFAULT 'OPEN',
  command_id        VARCHAR(64)  NULL,
  recorded_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  resolved_at       TIMESTAMP    NULL,
  INDEX idx_dist_train_time (train_id, recorded_at),
  INDEX idx_dist_status (status, recorded_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

status 枚举：OPEN | HANDLED | RECOVERED

---

### 4.5 dispatch_command_record（已有）

payload_json 示例：

```json
{
  "deltaDwellSec": -5,
  "speedBiasRatio": 1.05,
  "targetHeadwaySec": 153,
  "reason": "DWELL_EXTENDED",
  "disturbanceId": "DIST-1024"
}
```

---

### 4.6 阈值配置（system_config）

| config_key | 默认值 | 说明 |
|------------|--------|------|
| dispatch.dwellToleranceSec | 15 | 停站超时容忍 |
| dispatch.departureDelaySec | 30 | 发车延误容忍 |
| dispatch.headwayShrinkRatio | 0.7 | 间隔过小比例 |
| dispatch.headwayExpandRatio | 1.5 | 间隔过大比例 |
| dispatch.crowdingLoadRate | 0.80 | 拥挤阈值 |
| dispatch.confirmTicks | 3 | 扰动确认周期数 |
| dispatch.cooldownSec | 60 | 扰动冷却 |
| dispatch.evaluateIntervalMs | 1000 | 调度评估间隔 |
| dispatch.averageSpeedRatio | 0.8 | 计划均速相对区间限速的比例，用于区间运行时间估算 |
| simulation.pushIntervalMillis | 1000 | WebSocket 快照推送间隔（仿真总控限流，已有） |
| simulation.tickMillis | 200 | 仿真物理步长（已有） |

---

## 5. 核心数据结构

### 5.1 DispatchCommand

```java
public record DispatchCommand(
    String id,
    String trainId,
    String commandType,
    Map<String, Object> payload,
    String reason,
    String status,
    Instant createdAt,
    Instant appliedAt
) {}
```

commandType 枚举：EXTEND_DWELL | SHORTEN_DWELL | SPEED_BIAS | HEADWAY_ADJUST

### 5.2 DispatchSnapshot（WebSocket 推送段）

```json
{
  "runMode": "PEAK",
  "planId": "RP-demo-001",
  "targetHeadwaySeconds": 180,
  "defaultDwellSeconds": 30,
  "interventionActive": true,
  "trainProfiles": [
    {
      "trainId": "TR-001",
      "headwayActualSeconds": 195,
      "headwayDeviationSeconds": 15,
      "dwellDeviationSeconds": 0
    }
  ],
  "openDisturbances": [
    {
      "id": "DIST-1024",
      "trainId": "TR-002",
      "stationId": "S02",
      "disturbanceType": "DWELL_EXTENDED",
      "deviationValue": 18,
      "status": "OPEN"
    }
  ],
  "activeCommands": [
    {
      "id": "CMD-2001",
      "trainId": "TR-003",
      "commandType": "SHORTEN_DWELL",
      "status": "APPLIED",
      "reason": "DWELL_EXTENDED"
    }
  ]
}
```

---

## 6. 业务流程设计

### 6.1 正常运行业务流程

```text
1. OperationPlanCache 返回 CurrentRunPlan（PEAK, 180s, 30s）
2. TrainRunMonitor 更新各车 TrainRunProfile
3. 偏差均在阈值内 → DisturbanceDetector 无输出
4. CommandQueue 为空，interventionActive=false
5. 快照推送；前端显示「正常运行，维持计划」
```

### 6.2 扰动触发与调度干预流程

```text
1. TR-002 在 S02 停站 48s，计划 30s → dwellDeviation=18s
2. 连续 3 个评估周期超阈 → 创建 DWELL_EXTENDED
3. INSERT disturbance_record（status=OPEN）
4. StrategySelector → 对 TR-003 生成 SHORTEN_DWELL(-5s)
5. CommandValidator 通过 → status=PENDING
6. 下一 tick TrainManager 应用 → APPLIED
7. INSERT dispatch_command_record
8. 持续监控 headwayDeviation
9. 回归正常 → disturbance=RECOVERED，command=EXPIRED
```

### 6.3 客流拥挤驱动流程

```text
1. TR-001 loadRate=0.86，或 S02 waiting_count > capacity×0.8
2. 创建 CROWDING 扰动
3. HeadwayAdjustStrategy → HEADWAY_ADJUST(180×0.85=153s)
4. 快照推送建议；综合监控展示「建议加密发车」
5. 不触发运行图重构
```

### 6.4 仿真 Reset 流程

```text
1. SimulationRuntime.reset() 调用 DispatchService.reset(newRunId)
2. 清空 CommandQueue、TrainRunProfile 缓存、开放扰动内存表
3. 生成新 simulation_run_id
4. 不清除历史表数据，按 run_id 隔离查询
5. 重新加载 OperationPlanCache
```

---

## 7. 接口设计

### 7.1 REST 接口

前缀：`/api/dispatch`（需纳入 docs/API_CONTRACT.md）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /plan | 查询线路全部计划 |
| GET | /plan/current | 当前生效计划 |
| PUT | /plan/{planId} | 修改计划参数 |
| GET | /status | 调度状态摘要 |
| GET | /disturbances | 扰动列表 |
| GET | /commands | 指令历史 |
| GET | /station-records | 到发记录 |

仿真控制仍走 /api/simulation/*。

### 7.2 WebSocket

沿用 ws://localhost:8080/ws/simulation，在 payload 中扩展 dispatch 字段（见 §5.2）。

**推送限流（Rate Limiting）：**

- 后端计算：每个 tick 更新内存快照（含 `dispatch` 段）
- 前端推送：按 `simulation.pushIntervalMillis`（默认 1000ms）广播，满足 OPS-NFR「状态刷新 ≤1s」
- 实现位置：`SimulationRuntime` 或 `SimulationWebSocketHandler`（组长维护），调度模块仅提供最新 `DispatchSnapshot`
- 前端：收到推送后更新 Vue 响应式状态；图表组件可对 HeadwayChart 做防抖（可选）

### 7.3 内部模块接口

#### 调度 → 车辆（方首岳）

```java
void applyCommands(List<DispatchCommand> commands);
List<TrainState> states();
void reset();
```

TrainEntity 需支持：

```java
void applyDwellAdjust(int deltaSeconds);
void applySpeedBias(double ratio);
```

建议 TrainState 扩展字段（需 API_CONTRACT 同步）：

```json
{
  "currentStationId": "S02",
  "dwellElapsedSeconds": 25,
  "lastArrivalAt": "2026-07-07T10:05:00Z",
  "lastDepartureAt": "2026-07-07T10:05:30Z"
}
```

#### 调度 → 信号（黄旭涛，只读）

```java
List<MovementAuthority> authorities = signalService.authorities();
```

#### 调度 → 轨道（黄旭涛，只读）

使用 TrackService.states() 与 station_config 到发判定。

#### 调度 → 综合监控（廖宇轩）

通过 SimulationSnapshot.dispatch 推送，监控只读快照。

#### 调度 ← 客流

| 来源 | 字段 | 用途 |
|------|------|------|
| TrainState.loadRate | 车厢满载率 | CROWDING 判定 |
| passenger_flow_record.waiting_count | 站滞留 | CROWDING 判定（可选） |

---

## 8. 跨模块交互矩阵

| 模块 | 方向 | 数据 | 方式 |
|------|------|------|------|
| 仿真总控 | → 调度 | tick、reset、simulation_run_id | DispatchService.evaluate() |
| 车辆 | → 调度 | TrainState | TrainManager.states() |
| 调度 | → 车辆 | DispatchCommand | TrainManagerPublisher |
| 信号 | → 调度 | MovementAuthority | SignalService.authorities() |
| 轨道 | → 调度 | 区段状态、站配置 | TrackService + station_config |
| 供电 | ↔ 调度 | 无（第一期） | — |
| 监控 | ← 调度 | DispatchSnapshot | WebSocket |
| 监控/客流 | → 调度 | loadRate、waiting_count | 快照或 DB 只读 |
| 调度 | → DB | 计划/到发/扰动/指令 | 异步写入 |

---

## 9. 前端设计

### 9.1 类型与 API

- 类型：`frontend/src/types/dispatch.ts`（或扩展 simulation.ts）
- API：`frontend/src/api/dispatch.ts`

### 9.2 页面交互

```text
挂载：REST 加载计划 + WebSocket 订阅快照
运行中：HeadwayChart、DisturbanceList、CommandTimeline 随 snapshot.dispatch 刷新
暂停/Reset：随 simulation status 同步
```

---

## 10. 非功能设计

| 指标 | 要求 | 实现 |
|------|------|------|
| 实时性 | 状态刷新 ≤1s | WebSocket 按 pushIntervalMillis 限流推送；内存每 tick 更新 |
| 稳定性 | 避免策略震荡 | 确认 tick、冷却、恢复滞回 |
| 安全 | 不超 MA/停站边界 | CommandValidator |
| 可维护 | 过程可追溯 | 三张业务表 + 指令记录 |
| 可扩展 | 架构可演进 | DispatchCommandPublisher |
| 仿真同步 | 支持 pause/reset | reset 清内存、保留历史 |

---

## 11. 实施计划

| 阶段 | 内容 | 产出 | 周期 |
|------|------|------|------|
| P0 | 三张表 + 计划加载 + 快照 dispatch 段 | 前端可见运行模式 | 2 天 |
| P1 | TrainRunMonitor + 到发检测 | 偏差可算 | 2 天 |
| P2 | DisturbanceDetector + 落库 | 扰动可识别 | 2 天 |
| P3 | Strategy + CommandQueue + 车辆联调 | 指令可执行 | 2 天 |
| P4 | 调度前端 + 监控联调 | 完整演示 | 1 天 |

**演示场景：**

1. 正常运行 — 无扰动、无指令
2. 停站超时 — 识别扰动、后续车缩短停站、间隔恢复
3. 客流拥挤 — loadRate 超阈、headway 建议缩短

---

# 附录 A：待定接口与架构演进（非当前实施基准）

> **状态：待组长总架构确认**  
> 以下内容仅供参考，不作为当前开发验收依据。总架构定稿后，仅调整 §3.5 联动层与 §7.3 内部接口，§3.1～3.4 业务逻辑保持不变。

## A.1 待定：仿真主循环顺序

**当前实现（本文基准）：**

```text
信号 → 车辆(执行上周期指令) → 轨道 → 供电 → 调度(生成本周期指令) → 监控
```

**可能的目标架构（讨论稿，未确认）：**

```text
轨道 → 信号 → 供电 → 调度 → TCMS/ATO Adapter → FMU/简化车辆 → 监控
```

## A.2 待定：指令下发路径

**当前实现：**

```text
DispatchService → DispatchCommand → TrainManager.applyCommands()
```

**可能的目标架构：**

```text
DispatchService → DispatchConstraint → TcmsAdapterPublisher
  → Adapter 合成 tractionCmd / brakeCmd / targetSpeed / dwellTime
  → FMU 或简化车辆模型
```

迁移方式：新增 TcmsAdapterPublisher implements DispatchCommandPublisher，不改 DispatchService 核心逻辑。

## A.3 待定：DispatchConstraint 与 DispatchCommand 映射

| 当前 commandType | 未来 constraintType（草案） |
|------------------|----------------------------|
| EXTEND_DWELL / SHORTEN_DWELL | DWELL_ADJUST |
| SPEED_BIAS | TARGET_SPEED_BIAS |
| HEADWAY_ADJUST | HEADWAY_TARGET |
| — | HOLD_TRAIN（演示可选） |
| — | TEMP_SPEED_LIMIT → 信号模块（建议型） |

## A.4 待定：与 FMU 车辆模型关系

- 调度不直接调用 FMU
- 调度只产出运行组织约束，物理执行由 Adapter + FMU 完成
- FMU 不可用时，Adapter 降级至 TrainManagerPublisher

## A.5 待定：与供电模块联动

当前无自动联动。可能扩展：接收 PowerFaultEvent → 记录扰动 → 生成 HOLD_TRAIN 或降速建议。

## A.6 待定：配置文件双轨

当前计划存 running_plan_config（MySQL）。可能扩展 config/timetable.yaml 作为开发期快速加载。

## A.7 架构确认后的变更清单

| 变更项 | 影响范围 | 是否影响业务表 |
|--------|----------|----------------|
| 替换 Publisher | integration/ 包 | 否 |
| 调整主循环顺序 | SimulationRuntime | 否 |
| 调度降频评估 | DispatchService | 否 |
| 新增 Constraint 字段 | DispatchCommand/API | 仅扩展 payload |
| FMU Sidecar 接入 | 车辆模块 | 否 |

---

**文档结束**
