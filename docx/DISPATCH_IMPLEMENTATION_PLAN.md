# 运营调度模块代码实现计划

> 文档版本：v1.0  
> 所属项目：Railway-Sim 上京地铁仿真系统  
> 模块负责人：运营调度  
> 设计依据：[DISPATCH_OUTLINE_DESIGN.md](./DISPATCH_OUTLINE_DESIGN.md)、[DISPATCH_DETAILED_DESIGN.md](./DISPATCH_DETAILED_DESIGN.md)  
> 当前分支：lq

---

## 0. 现状与实现策略

| 项 | 现状 | 实现策略 |
|----|------|----------|
| 三张 DB 表 | `schema.sql` 已有 DDL，无 seed | 补 seed；第一期用**内存仓储**，接口预留 JDBC |
| MySQL 访问层 | 无 JDBC/JPA | 不引入半套 ORM；历史记录走 `InMemory*Store` |
| 运行计划 | 无 | `config/dispatch-plan.yaml`，风格对齐 `line-demo.yaml` |
| 调度阈值 | 无 | `application.yml` + `DispatchProperties` |
| `SimulationRuntime` | 无 tick、无限流 | **需改**（与组长协调）：加 `tick()` + 推送限流 |
| `TrainEntity.tick()` | 空 TODO | 做**最小运动模型**（恒速 + 停站），够调度联调 |
| 前端 | 骨架页 | P4 做调度专页 |

**原则：** 调度业务逻辑一次写对；持久化、车辆物理以后可替换，不动核心。

---

## 1. 分阶段总览

```text
P0  计划 + 快照 + tick 骨架          → 前端能看到运行模式
P1  运行监控 + 到发 + 最小车辆运动    → 偏差可算
P2  扰动识别 + 策略 + 指令队列        → 能生成指令
P3  联动 TrainManager + 恢复闭环      → 指令能执行
P4  REST + 前端调度页                 → 完整演示
(后续) JDBC 落库                      → 接 MySQL
```

---

## 2. P0：计划加载 + 快照 + 仿真 tick 骨架

### 2.1 目标

跑通 `start → tick → snapshot`，快照含 `dispatch` 段。

### 2.2 新增文件

```text
config/dispatch-plan.yaml

backend/src/main/java/com/railwaysim/dispatch/
├── config/DispatchProperties.java          @ConfigurationProperties("railway.dispatch")
├── plan/
│   ├── RunModePeriod.java
│   ├── CurrentRunPlan.java
│   └── OperationPlanLoader.java            读 YAML
├── DispatchSnapshot.java
└── DispatchService.java                    骨架：getCurrentPlan(), buildSnapshot()

backend/src/main/java/com/railwaysim/config/
└── DispatchConfig.java                     注册 DispatchProperties、加载 YAML
```

### 2.3 修改文件

| 文件 | 改动 |
|------|------|
| `SimulationSnapshot.java` | 增加 `DispatchSnapshot dispatch` |
| `MonitorService.java` | 注入 `DispatchService`，快照带上 dispatch |
| `SimulationRuntime.java` | 注入 `DispatchService`、`SimulationProperties`；实现 `tick()`；推送限流 |
| `SimulationController.java` | 增加 `POST /tick` |
| `application.yml` | 增加 `railway.dispatch.*` 默认值 |
| `database/schema.sql` | seed：`running_plan_config`、dispatch 相关 `system_config` |

### 2.4 配置设计

**`config/dispatch-plan.yaml`：**

```yaml
planId: RP-demo-001
lineId: demo-line-1
periods:
  - { type: PEAK, start: "07:00", end: "09:00", intervalSec: 180, dwellSec: 30 }
  - { type: PEAK, start: "17:00", end: "19:00", intervalSec: 180, dwellSec: 30 }
  - { type: FLAT, start: "09:00", end: "17:00", intervalSec: 300, dwellSec: 25 }
  - { type: OFF_PEAK, start: "19:00", end: "07:00", intervalSec: 420, dwellSec: 20 }
```

**`application.yml`：**

```yaml
railway:
  dispatch:
    average-speed-ratio: 0.8
    dwell-tolerance-sec: 15
    departure-delay-sec: 30
    headway-shrink-ratio: 0.7
    headway-expand-ratio: 1.5
    crowding-load-rate: 0.8
    confirm-ticks: 3
    cooldown-sec: 60
    evaluate-interval-ms: 1000
```

### 2.5 SimulationRuntime.tick() 伪代码

```java
synchronized SimulationSnapshot tick() {
    if (status != RUNNING) return snapshot();

    tick++;
    var ctx = new TickContext(tick, tickMillis, tickMillis/1000.0, Instant.now());

    signalService.calculateAuthorities();          // 现有/占位
    trainManager.tickAll(ctx, dispatchService.drainCommands());
    trackService.updateOccupancy(trainManager.states());
    powerService.update();

    if (shouldEvaluateDispatch()) {
        dispatchService.evaluate(ctx, trainManager.states(), signalService.authorities());
    }

    var snap = buildSnapshot();

    if (shouldPush()) {
        webSocketHandler.broadcast(snap);
        lastPushAt = now;
    }
    return snap;
}
```

**限流策略：**

- 内存快照：每个 tick 更新
- WebSocket：`now - lastPushAt >= pushIntervalMillis` 才 broadcast

### 2.6 P0 验收标准

- [ ] `POST /api/simulation/start` + 多次 `POST /tick`
- [ ] `GET /snapshot` 含 `dispatch.runMode`、`targetHeadwaySeconds`
- [ ] WebSocket 约 1 秒一条，不是每个 tick 都推

---

## 3. P1：运行监控 + 到发检测 + 最小车辆运动

### 3.1 目标

能算 `dwellDeviation`、`headwayDeviation`，到发事件进内存。

### 3.2 新增文件

```text
backend/src/main/java/com/railwaysim/dispatch/
├── monitor/
│   ├── TrainRunProfile.java
│   ├── TrainRunMonitor.java
│   ├── ArrivalDepartureDetector.java
│   ├── StationInfo.java                    从 YAML/硬编码加载 5 站
│   └── StationRecordStore.java             接口
│       └── InMemoryStationRecordStore.java
└── plan/
    └── PlannedScheduleCalculator.java      headway 递推 + averageSpeedRatio
```

### 3.3 修改文件（需联调）

| 文件 | 改动 | 负责人 |
|------|------|--------|
| `TrainState.java` | +`currentStationId`, `dwellElapsedSeconds`, `lastDepartureAt` | 调度主导，车辆确认 |
| `TrainEntity.java` | 最小 tick：恒速 15 m/s，到站停 `defaultDwell + dwellAdjust` | 调度先写简化版 |
| `TrainManager.java` | +`tickAll(ctx, commands)` | 调度 |
| `frontend/src/types/simulation.ts` | 同步 TrainState 字段 | 调度 |

### 3.4 TrainEntity 最小运动（MVP）

```text
无指令时：以 15 m/s 向前运行
接近 station（|pos - stationPos| < 5m）且 speed→0：
  → status=DWELLING，累计 dwellElapsed
  dwellElapsed >= effectiveDwell（计划 + dwellAdjust）：
  → 离站，status=RUNNING
收到 SHORTEN_DWELL / EXTEND_DWELL：
  → 调整 effectiveDwell（限制 [15, 60]）
收到 SPEED_BIAS：
  → targetSpeed = baseSpeed × ratio（后续 P3 校验 MA）
```

> 方首岳后续可替换为完整 FMU/物理模型；调度只认 `TrainState` + `applyCommands`。

### 3.5 PlannedScheduleCalculator

```java
plannedSpeed = segmentSpeedLimit * properties.getAverageSpeedRatio();
travelSec = segmentLength / plannedSpeed;
// 递推 planned_arrival / planned_departure
```

### 3.6 P1 验收标准

- [ ] 车跑起来，经过 S02 时有 `actual_arrival`
- [ ] `TrainRunProfile.headwayDeviationSec` 有值
- [ ] `dispatch.trainProfiles` 出现在快照

---

## 4. P2：扰动识别 + 策略 + 指令生成

### 4.1 目标

停站超时能识别扰动并生成指令（尚未执行）。

### 4.2 新增文件

```text
backend/src/main/java/com/railwaysim/dispatch/
├── disturbance/
│   ├── DisturbanceType.java
│   ├── DisturbanceEvent.java
│   ├── DisturbanceDetector.java
│   └── DisturbanceRecordStore.java + InMemoryDisturbanceRecordStore
├── strategy/
│   ├── DispatchStrategy.java
│   ├── DwellAdjustStrategy.java
│   ├── SpeedBiasStrategy.java
│   ├── HeadwayAdjustStrategy.java
│   ├── CrowdingStrategy.java
│   └── StrategySelector.java
├── command/
│   ├── CommandStatus.java
│   ├── CommandQueue.java
│   └── CommandValidator.java
└── DispatchService.java                    扩展 evaluate()
```

### 4.3 DispatchService.evaluate() 流程

```text
1. currentPlan = planLoader.resolve(simulatedTime)
2. profiles = trainRunMonitor.update(trains, currentPlan)
3. detector.detect(profiles) → events
4. store.openDisturbances(events)
5. commands = strategySelector.select(events, profiles)
6. commands = validator.validate(commands, authorities)
7. commandQueue.enqueue(commands)
8. 更新 dispatchSnapshot
```

### 4.4 防抖逻辑

```java
// 每个 trainId+type 维护 consecutiveHits、lastTriggeredAt
if (consecutiveHits >= confirmTicks && !inCooldown) createDisturbance();
```

### 4.5 P2 验收标准

- [ ] 人为让 TR-002 停站 >45s（或 mock dwell）→ 出现 `DWELL_EXTENDED`
- [ ] 快照 `openDisturbances` 非空
- [ ] 生成 `SHORTEN_DWELL` 指令，status=PENDING

---

## 5. P3：指令联动 + 恢复闭环

### 5.1 目标

指令真正影响车辆，扰动能 RECOVERED。

### 5.2 新增文件

```text
backend/src/main/java/com/railwaysim/dispatch/
├── integration/
│   ├── DispatchCommandPublisher.java
│   └── TrainManagerPublisher.java
└── command/
    └── CommandRecordStore.java + InMemoryCommandRecordStore
```

### 5.3 修改文件

| 文件 | 改动 |
|------|------|
| `DispatchCommand.java` | 扩展 payload、`status`、`reason` |
| `TrainManager.java` | `applyCommands()`、`drainCommands()` 供 tick 消费 |
| `TrainEntity.java` | `applyDwellAdjust()`、`applySpeedBias()` |
| `DispatchService.java` | `drainCommands()`、恢复检测、更新指令状态 |

### 5.4 指令生命周期

```text
evaluate() 生成 → PENDING 入队
tick() 开头 trainManager.tickAll(..., drainCommands()) → APPLIED
偏差恢复 → disturbance RECOVERED，command EXPIRED
MA 不允许 → SKIPPED
```

### 5.5 P3 验收标准

- [ ] TR-002 停站超时 → TR-003 收到 SHORTEN_DWELL → 实际 dwell 变短
- [ ] 间隔逐步回归，`interventionActive` 变 false
- [ ] `activeCommands` 状态正确

---

## 6. P4：REST + 前端调度页

### 6.1 后端

```text
backend/src/main/java/com/railwaysim/dispatch/DispatchController.java
  GET  /api/dispatch/plan
  GET  /api/dispatch/plan/current
  GET  /api/dispatch/status
  GET  /api/dispatch/disturbances
  GET  /api/dispatch/commands
  GET  /api/dispatch/station-records
```

### 6.2 前端

```text
frontend/src/types/dispatch.ts
frontend/src/api/dispatch.ts
frontend/src/views/dispatch/DispatchView.vue
frontend/src/components/dispatch/
  RunPlanPanel.vue
  HeadwayChart.vue          echarts
  DisturbanceList.vue
  CommandTimeline.vue
  DispatchStatusBar.vue
frontend/src/App.vue          增加 Tab 到调度页（简单 tab 切换，不引入 vue-router 若项目未用）
```

### 6.3 文档

| 文件 | 改动 |
|------|------|
| `docs/API_CONTRACT.md` | dispatch 段、TrainState 扩展、REST 列表 |

### 6.4 P4 验收标准

- [ ] 调度页展示模式、间隔图、扰动、指令
- [ ] 综合监控仍可用原快照（`dispatch` 段只读）

---

## 7. 文件改动总览

### 7.1 调度模块负责（主要新增/修改）

```text
config/dispatch-plan.yaml
backend/src/main/java/com/railwaysim/dispatch/**          (~25 个 Java 文件)
backend/src/main/java/com/railwaysim/config/DispatchConfig.java
frontend/src/types/dispatch.ts
frontend/src/api/dispatch.ts
frontend/src/views/dispatch/**
frontend/src/components/dispatch/**
database/schema.sql                                       seed 数据
```

### 7.2 公共文件（需组长知晓，最小改动）

| 文件 | 改动范围 | 原因 |
|------|----------|------|
| `SimulationRuntime.java` | tick + 限流 + 注入 dispatch | 无 tick 调度无法跑 |
| `SimulationSnapshot.java` | +dispatch 字段 | 快照推送 |
| `MonitorService.java` | 聚合 dispatch | 注入 DispatchService |
| `SimulationController.java` | +POST /tick | API 契约已有 |
| `TrainState.java` | +3 字段 | 到发监控 |
| `TrainManager.java` | tickAll + applyCommands | 指令执行 |
| `TrainEntity.java` | 最小 tick + apply* | MVP 演示 |
| `docs/API_CONTRACT.md` | dispatch 段 | 前后端对齐 |

### 7.3 暂不改动

- 信号/轨道/供电模块内部逻辑
- FMU / Python Sidecar
- JDBC（后续单独 PR）

---

## 8. 关键类职责

| 类 | 职责 |
|----|------|
| `OperationPlanLoader` | 读 YAML，按时间 resolve CurrentRunPlan |
| `PlannedScheduleCalculator` | 递推计划到发（用 averageSpeedRatio） |
| `TrainRunMonitor` | 每 evaluate 周期更新 TrainRunProfile |
| `ArrivalDepartureDetector` | 到发边沿检测 |
| `DisturbanceDetector` | 阈值 + 防抖 + 冷却 |
| `StrategySelector` | 扰动 → 指令 |
| `CommandValidator` | MA 限速、dwell 边界 |
| `CommandQueue` | pending → drain |
| `TrainManagerPublisher` | 下发到 TrainManager |
| `DispatchService` | 编排 evaluate / reset / buildSnapshot |
| `InMemory*Store` | 历史查询（REST），后期换 JDBC |

---

## 9. 测试计划

| 层级 | 内容 |
|------|------|
| 单元测试 | `OperationPlanLoader` 时段匹配、`DisturbanceDetector` 防抖、`PlannedScheduleCalculator` |
| 集成测试 | `DispatchService.evaluate()` 给定 mock TrainState 列表 |
| 手动 | start → tick 30 次 → 观察 snapshot.dispatch |

```bash
cd backend && mvn test
cd frontend && pnpm build
```

---

## 10. 提交顺序（建议）

```text
第 1 批（P0）
  dispatch-plan.yaml, DispatchProperties, plan 包
  DispatchSnapshot, DispatchService 骨架
  SimulationSnapshot + MonitorService
  SimulationRuntime.tick + 限流 + Controller /tick
  schema seed

第 2 批（P1）
  StationInfo, TrainRunMonitor, PlannedScheduleCalculator
  TrainState/TrainEntity/TrainManager 最小运动

第 3 批（P2）
  DisturbanceDetector, strategy 包, CommandQueue

第 4 批（P3）
  TrainManagerPublisher, applyCommands, 恢复闭环

第 5 批（P4）
  DispatchController, 前端调度页, API_CONTRACT
```

---

## 11. 风险与应对

| 风险 | 应对 |
|------|------|
| 车辆组迟迟不接指令 | P1 调度自己写最小 `TrainEntity.tick()` |
| 组长不让改 SimulationSnapshot | 临时 REST `/api/dispatch/status`，快照后补 |
| 无 MySQL | InMemoryStore，接口不变 |
| 信号 MA 为空 | Validator 跳过 MA 校验，有 MA 再校验 |

---

## 12. 默认决策（待确认）

开始编码前默认按以下执行：

1. **计划配置**：第一期用 `config/dispatch-plan.yaml`，不连 MySQL
2. **历史记录**：InMemoryStore，REST 可查，后期换 JDBC
3. **车辆运动**：调度侧写最小 `TrainEntity.tick()` 做演示
4. **公共文件**：会改 `SimulationRuntime`、`SimulationSnapshot`、`TrainManager`（最小 diff）

---

## 13. 相关文档

| 文档 | 说明 |
|------|------|
| [DISPATCH_OUTLINE_DESIGN.md](./DISPATCH_OUTLINE_DESIGN.md) | 概要设计 |
| [DISPATCH_DETAILED_DESIGN.md](./DISPATCH_DETAILED_DESIGN.md) | 详细设计 |
| [../docs/API_CONTRACT.md](../docs/API_CONTRACT.md) | 接口契约（P4 更新） |
| [../docs/TEAM_GUIDE.md](../docs/TEAM_GUIDE.md) | 协作与目录归属 |

---

**文档结束**
