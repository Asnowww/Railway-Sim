# 运营调度模块道岔与进路调度详细设计 2.0

> 文档版本：v2.0  
> 所属项目：Railway-Sim 上京地铁仿真系统  
> 模块负责人：运营调度  
> 关联文档：[DISPATCH_ROUTE_SWITCH_DETAILED_DESIGN.md](./DISPATCH_ROUTE_SWITCH_DETAILED_DESIGN.md)、[DISPATCH_OUTLINE_DESIGN.md](./DISPATCH_OUTLINE_DESIGN.md)、[DISPATCH_DETAILED_DESIGN.md](./DISPATCH_DETAILED_DESIGN.md)、[../docs/API_CONTRACT.md](../docs/API_CONTRACT.md)  
> 编写说明：本文在 v1.0 的“调度只做进路级运营决策，不直接扳道岔、不替代联锁”的基础上，结合当前代码实现，细化调度模块内部设计、主循环接入方式、状态闭环、冲突仲裁算法和跨模块配合需求。

---

## 0. 2.0 版核心结论

当前系统已经具备道岔与进路调度的基础链路：

```text
DispatchCommand(REQUEST_ROUTE/REROUTE)
  -> SimulationRuntime.drainCommandsOfType(...)
  -> RouteInterlockingService.applyDispatchCommand(...)
  -> RouteInterlockingService.establishRoute(...)
  -> TrackService.throwSwitch/lockSwitch
  -> SignalService.calculateAuthorities(...)
  -> TrainManager.tickAll(...)
```

也就是说，调度模块不需要新建一套设备控制通道。2.0 版设计应当重点补齐：

1. **调度侧 route intent**：每列车下一步应该申请哪条进路。
2. **调度侧 route reservation**：进路请求是否已经计划、请求、接受、拒绝、释放。
3. **调度侧 route conflict arbitration**：多车争用同一道岔、进路、咽喉区时谁先谁后。
4. **调度侧 route-aware constraints**：未获批车辆如何扣停、限速接近、等待释放。
5. **调度快照可解释展示**：为什么选这辆车、为什么另一辆车等、联锁拒绝原因是什么。

本文建议将第一版实现目标控制在调度模块内：

```text
调度负责：
  - 读线路进路、读列车状态、读信号/轨道状态
  - 生成 route intent
  - 构建 route request
  - 检测冲突组
  - 计算优先级
  - 发 REQUEST_ROUTE/REROUTE/HOLD/SPEED_LIMIT
  - 记录 decision/reservation/snapshot

调度不负责：
  - 直接扳道岔
  - 直接锁闭/解锁进路
  - 直接改 MovementAuthority
  - 直接控制车辆牵引/制动
  - 直接修改 TrackSegmentState/SwitchState
```

---

## 1. 当前代码现状与 v1.0 设计差距

### 1.1 当前已可复用能力

#### 调度模块

当前 `DispatchService` 已具备：

- 运行计划解析：`OperationPlanLoader`、`CurrentRunPlan`。
- 时间间隔闭环：`TrainRunMonitor`、`DisturbanceDetector`、`StrategySelector`。
- 调度命令生命周期：`CommandQueue`、`CommandRecordStore`、`CommandStatus`、`acceptFeedback(...)`。
- 约束输出：`DispatchConstraint`，可表达 `holdTrain`、`speedFactor`、`targetSpeedMetersPerSecond`、`releaseStationStop`。
- 人工 route 命令入口：`submit(...)` 对 `REROUTE`、`REQUEST_ROUTE` 会入队。
- 命令反馈闭环：`SimulationRuntime` 中联锁执行后回传 `DispatchCommandFeedback`。

这些能力可以直接承载 2.0 的进路调度决策。

#### 信号/联锁模块

当前 `RouteInterlockingService` 已具备：

- 进路状态：`RouteState(routeId, status, lockedSwitchIds, establishedByTrainId, axleSegmentIds)`。
- 进路建立：`establishRoute(routeId, trainId)`。
- 进路释放：`releaseRoute(routeId)`、`releaseAllForTrain(trainId)`。
- 道岔需求推导：根据 route 包含的区段判断 switch 需要 `NORMAL` 还是 `REVERSE`。
- 联锁执行：预检查道岔、转换道岔、锁闭道岔。
- route 命令消费：`applyDispatchCommand(commandType, detail, trainId)` 支持 `REQUEST_ROUTE`、`REROUTE`。
- route 查询：`queryRoutes()` 可给调度/前端展示可选进路。

#### 轨道模块

当前 `TrackService` 已具备：

- 区段状态：`FREE / RESERVED / OCCUPIED / FAULT`。
- 道岔状态：`SwitchState(id, nodeId, position, locked, activeSegmentId)`。
- 道岔动作：`throwSwitch(...)`、`lockSwitch(...)`、`unlockSwitch(...)`。
- 区段预留：`applyReservations(...)`。
- 拓扑邻接：`forwardNeighborMap()`。

#### 仿真主循环

当前 `SimulationRuntime.advanceOneTick()` 的顺序已经适合接入进路调度：

```text
1. 读取 beforeTrainStates
2. TrackService.updateOccupancy(...)
3. TrackService.constraintsForTrains(...)
4. SignalService.calculateAuthorities(..., List.of())
5. DispatchService.evaluate(...)
6. drain REROUTE/REQUEST_ROUTE -> interlocking.applyDispatchCommand(...)
7. autoDispatchTrains / handleDepartures
8. publish generated dispatch commands
9. dispatchService.constraintsForTrains(...)
10. SignalService.calculateAuthorities(..., dispatchConstraints)
11. TrainManager.tickAll(...)
```

因此，进路调度应当放在 `DispatchService.evaluate(...)` 内部完成，输出 route 命令；route 命令随后在同一个 tick 中被 `SimulationRuntime` 交给联锁办理，并影响第二次 MA 计算。

### 1.2 当前主要缺口

#### 缺口 A：dispatch 没有 route intent

当前调度知道列车间隔、停站、晚点，但不知道：

- 这辆车下一条计划进路是什么。
- 这辆车是否接近道岔/咽喉区。
- 这辆车是不是正在等待某个 route。
- 这辆车的 route 请求是否被拒绝过。

#### 缺口 B：dispatch 没有 route reservation 状态机

当前 `DispatchCommand` 能记录命令状态，但不能表达一条进路调度决策的完整过程：

```text
PLANNED -> REQUESTED -> ACCEPTED -> ACTIVE -> RELEASED
                 \-> REJECTED -> WAITING/REROUTE
```

如果只看 command，很难解释：

- 为什么这个车发了 route 请求。
- 哪些车因此被等待。
- route 被拒绝后有没有重试或改路。
- route 释放后等待车是否进入下一轮。

#### 缺口 C：dispatch 没有多车冲突组

当前自动间隔调度是按线性 `positionMeters` 排序，适合前后车 headway，不适合：

- 两车从不同分支合流。
- 两条进路共用同一道岔。
- 两条进路共享咽喉区区段。
- 一条主线列车与一条出入库列车抢同一渡线。

#### 缺口 D：route 命令生成还不是自动策略

当前 `StrategySelector` 只生成：

- `EXTEND_DWELL`
- `SHORTEN_DWELL`
- `HEADWAY_ADJUST`
- `SPEED_BIAS`

route 类命令主要来自人工 `submit(...)`。2.0 需要新增自动 route strategy。

#### 缺口 E：复杂分支下基础定位仍不足

当前 `TrainState` 仍以 `positionMeters` 为核心位置字段。`TrackService.updateOccupancy(...)`、`SignalService`、`TrainRunMonitor` 也主要按线性里程处理。

这不阻止第一期做演示闭环，但要明确：

- 第一阶段可以基于 route 表、segment 范围、switch 定义做近似进路调度。
- 完整分支/折返/上下行需要后续扩展 `currentSegmentId + offsetInSegment + direction + plannedRouteId`。

---

## 2. 2.0 设计目标

### 2.0 现状判断

结合当前 README、架构文档和代码，运营调度模块的 2.0 设计建立在以下现状之上：

| 模块 | 当前能力 | 对进路/道岔调度的意义 |
|---|---|---|
| dispatch | 当前主职责是运行计划、扰动检测、策略生成、命令队列、`DispatchConstraint` 输出，主要控制停站、限速、行车间隔。 | 已有时间维度调度闭环，可在此基础上增加进路资源调度。 |
| track | 当前支持区段 `FREE / RESERVED / OCCUPIED / FAULT`，道岔 `NORMAL / REVERSE / locked`，并提供 `throwSwitch / lockSwitch / unlockSwitch`。 | 道岔实际动作和锁闭能力已经存在，但不应由 dispatch 直接调用。 |
| signal | `RouteInterlockingService.establishRoute()` 能根据进路涉及区段推导道岔位置、扳动并锁闭道岔；`SignalService.calculateAuthorities()` 会按前车、故障、进路冲突、停站控制截断 MA。 | 联锁与 MA 是安全执行边界，dispatch 只请求 route 和输出运营约束。 |
| runtime | 当前顺序是 `dispatchService.evaluate()` 后处理 route 命令，再第二次 `signalService.calculateAuthorities()`。 | 调度层的路线决策可以在同一 tick 影响第二次 MA 计算。 |
| train/vehicle | `TrainState` 当前仍以 `positionMeters` 标量为主。 | 第一阶段可做演示闭环，但完整分支、折返、上下行需要显式图定位。 |

最大短板是列车位置仍是 `positionMeters` 标量，很多排序、前后车、占用和冲突判断都是线性假设。README 已明确指出，后续完整消费上下行、折返和分支图结构，需要在列车状态里引入显式路由和区段图定位，而不是只用单一标量里程。

### 2.1 功能目标

| 编号 | 目标 | 说明 |
|---|---|---|
| G1 | 计划进路自动申请 | 正常运行时，调度根据车次/方向/计划 route，在列车接近办理窗口时自动提交 `REQUEST_ROUTE`。 |
| G2 | 人工 route 命令统一纳管 | 人工 `REQUEST_ROUTE/REROUTE` 也进入 route decision/reservation 体系，不再只是裸命令。 |
| G3 | 道岔/咽喉冲突仲裁 | 多车争用同一 switch、route、segment 时，调度按规则决定 winner 和 waiting trains。 |
| G4 | 等待与限速策略 | 未获批列车根据距离冲突点远近生成 `HOLD`、`HOLD_BEFORE_JUNCTION`、`SPEED_LIMIT` 或 `SPEED_FACTOR`。 |
| G5 | 联锁反馈闭环 | 联锁接受/拒绝 route 命令后，调度更新 reservation/decision 状态。 |
| G6 | 异常改路 | route 被拒绝、区段故障、道岔锁闭、等待超时时，调度可等待、限速或尝试备选 route。 |
| G7 | 快照可解释 | `DispatchSnapshot` 展示当前 route decision、冲突组、等待原因、拒绝原因、优先级依据。 |
| G8 | 与时间间隔调度兼容 | route 调度不替代已有 headway/dwell 闭环，而是在 `DispatchConstraint` 上叠加保守约束。 |

### 2.1.1 调度侧新增三层能力

道岔调度在 dispatch 侧应拆成三层，而不是直接进入轨道/信号内部安全规则：

| 层次 | 职责 | 示例 |
|---|---|---|
| 运行计划层 | 决定每辆车计划走哪条任务路径。 | `S01 -> S05`、是否进折返线、是否进库线、是否越行。 |
| 资源预约层 | 把车辆未来一段时间要经过的进路、道岔、区段变成时间窗资源申请。 | 某车预计 30 秒后申请 `R-main-down`，需要 `SW01=NORMAL` 和若干区段。 |
| 仲裁执行层 | 当多个车争同一进路、道岔或冲突区段时，决定谁先建路、谁等待、谁限速接近。 | winner 发 `REQUEST_ROUTE`，loser 发 `HOLD` 或 `SPEED_FACTOR`。 |

这三层均属于运营调度层。安全可行性仍由信号/联锁最终判定。

### 2.2 非目标

| 非目标 | 说明 |
|---|---|
| 不重写 `RouteInterlockingService` | 第一阶段复用现有联锁办理链路。 |
| 不直接改 `TrackService` 设备状态 | 调度不调用 `throwSwitch/lockSwitch/unlockSwitch`。 |
| 不直接生成车辆制动/牵引量 | 等待和限速通过 `DispatchConstraint -> SignalService -> MA` 间接影响车辆。 |
| 不一次性实现全图最优运行图 | 第一阶段做局部冲突仲裁和可解释规则，后续再考虑优化算法。 |
| 不依赖数据库落地 | 第一阶段内存状态即可，历史表为后续扩展。 |

---

## 3. 总体架构 2.0

### 3.1 新增 dispatch 内部分层

建议在 `backend/src/main/java/com/railwaysim/dispatch` 下新增逻辑分层：

```text
dispatch
├─ route
│  ├─ RouteDispatchService             进路调度总入口
│  ├─ RouteIntentResolver              解析列车 route intent
│  ├─ RouteCatalog                     调度侧 route/switch/segment 查询缓存
│  ├─ RouteRequestPlanner              生成 route resource request
│  ├─ RouteConflictDetector            构建冲突组
│  ├─ RoutePriorityScorer              计算优先级
│  ├─ RouteDecisionEngine              输出 winner/waiting/reroute 决策
│  ├─ RouteReservationStore            内存保存 reservation/decision
│  └─ RouteDecisionSnapshotMapper      转换为 DispatchSnapshot 视图
```

第一期也可以不拆这么多类，但设计上建议保持这些职责边界，避免把所有逻辑继续塞进 `DispatchService`。

### 3.2 调度主流程

每次 `DispatchService.evaluate(context, trains, authorities)` 中，时间间隔策略之外，新增：

```text
RouteDispatchService.evaluate(
    simulationRunId,
    simulatedTime,
    currentPlan,
    trains,
    authorities,
    latestProfiles,
    routeStates,
    switchStates,
    trackSegments
)
  -> List<DispatchCommand> routeCommands
  -> List<RouteDispatchDecision> decisions
  -> List<RouteReservation> reservations
```

主流程：

```text
1. refreshRouteCatalog()
2. resolveIntents(trains, plan, manualRouteCommands)
3. buildRouteRequests(intents, trains, authorities)
4. filterByApproachWindow(requests)
5. detectConflicts(requests, routeStates, switchStates, trackSegments)
6. arbitrateEachConflictGroup(conflictGroups)
7. generateDispatchCommands(decisions)
8. save decisions/reservations
9. enqueue route commands and waiting/speed commands
10. expose route decision snapshot
```

### 3.3 与现有命令队列的关系

route 类命令分两种：

```text
设备意图类：
  REQUEST_ROUTE
  REROUTE

运行约束类：
  HOLD
  HOLD_TRAIN
  SPEED_LIMIT
  TEMP_SPEED_LIMIT
  SPEED_FACTOR
  LIMIT_FACTOR
```

当前 `SimulationRuntime` 已对 `REQUEST_ROUTE/REROUTE` 有特殊处理：

```text
dispatchService.drainCommandsOfType("REROUTE")
dispatchService.drainCommandsOfType("REQUEST_ROUTE")
-> interlockingService.applyDispatchCommand(...)
```

因此：

- `REQUEST_ROUTE/REROUTE` 继续走联锁。
- `HOLD/SPEED_LIMIT/SPEED_FACTOR` 继续走 `DispatchConstraint`。
- 同一个 `RouteDispatchDecision` 可以生成一个 winner route command 和多个 loser constraint command。

---

## 4. 核心数据模型 2.0

### 4.1 RouteIntent

表示列车运营意图：这辆车“应该去哪里、下一条 route 是什么、意图来源是什么”。

```java
record RouteIntent(
    String trainId,
    String serviceNo,
    String direction,
    String originStationId,
    String destinationStationId,
    String currentRouteId,
    String nextRouteId,
    List<String> candidateRouteIds,
    RouteIntentSource source,
    int basePriority,
    String reason,
    Instant createdAt,
    Instant expiresAt
) {}
```

`RouteIntentSource` 建议：

```text
MANUAL
FAULT_REROUTE
DELAY_RECOVERY
TIMETABLE
SERVICE_PATTERN
DEFAULT_MAINLINE
```

解析优先级：

```text
1. 人工 REQUEST_ROUTE/REROUTE 指定 routeId
2. 已存在但未完成的 RouteReservation
3. 故障/拒绝触发的 fallback route
4. 运行计划/车次/目的地配置
5. 方向上的默认主线 route
```

第一期如果没有完整 service pattern 配置，可先使用：

- 人工 payload 中的 `routeId`。
- `fromStation/toStation` 匹配 `RouteInterlockingService.findBestRoute(...)` 的同类逻辑。
- 当前方向默认 MAIN route。

### 4.1.1 TrainRoutePlanMirror

由于当前 `TrainState` 还没有完整图定位和计划路径字段，dispatch 可先维护一份轻量的车辆路线调度镜像，避免每 tick 完全依赖 `positionMeters` 临时推导。

```java
record TrainRoutePlanMirror(
    String trainId,
    List<String> plannedRouteChain,
    String currentRouteReservationId,
    String currentRouteId,
    String nextRouteId,
    String nextDecisionPointId,
    double nextDecisionPointMeters,
    Instant updatedAt
) {}
```

字段说明：

| 字段 | 说明 |
|---|---|
| `plannedRouteChain` | 该车接下来若干个计划 routeId。 |
| `currentRouteReservationId` | 当前正在申请或使用的 route reservation。 |
| `currentRouteId` | 当前已建立或正在通过的 route。 |
| `nextRouteId` | 下一条准备申请的 route。 |
| `nextDecisionPointId` | 前方关键决策点，通常是道岔、折返站、出入库节点或咽喉区。 |
| `nextDecisionPointMeters` | 当前标量模型下的近似决策点里程。 |

调度策略：

```text
1. 每辆车维护 plannedRouteChain。
2. 每辆车维护 currentRouteReservation。
3. 每辆车维护 nextDecisionPoint。
4. 当列车接近 nextDecisionPoint 前 1-2 个区段，或 ETA 小于 30-60 秒，dispatch 开始申请进路。
5. 申请成功后，信号建立进路并锁闭道岔。
6. 申请失败后，dispatch 输出 HOLD 或限速，让 MA 收到当前冲突点附近。
```

### 4.2 RouteCatalogEntry

调度侧 route 目录。它不是联锁状态，只是调度做预判断的静态/半静态视图。

```java
record RouteCatalogEntry(
    String routeId,
    String routeName,
    String typeCode,
    String startSignalId,
    String endSignalId,
    List<String> axleSegmentIds,
    List<String> protectionSegmentIds,
    List<SwitchRequirement> switchRequirements,
    String fromStationId,
    String toStationId,
    boolean mainline,
    double entryMeters,
    double exitMeters
) {}
```

`SwitchRequirement`：

```java
record SwitchRequirement(
    String switchId,
    SwitchPosition requiredPosition,
    String requiredSegmentId
) {}
```

数据来源：

- `OperationalLineData.routes()`：routeId、信号机、轴计区段、保护区段。
- `OperationalLineData.switches()`：normal/reverse segment 与 position 的关系。
- `OperationalLineData.signals()/stations()`：起止站推断。
- `TrackService.states()`：segment 里程与状态。

### 4.3 RouteResourceRequest

表示“某列车希望在某个时间窗使用某条 route 资源”。

```java
record RouteResourceRequest(
    String requestId,
    String trainId,
    String routeId,
    String junctionId,
    List<String> requiredSegmentIds,
    List<String> protectionSegmentIds,
    List<SwitchRequirement> switchRequirements,
    double distanceToEntryMeters,
    double etaToEntrySeconds,
    Instant expectedEntryAt,
    Instant expectedReleaseAt,
    int basePriority,
    String reason
) {}
```

进入调度窗口的条件：

```text
distanceToEntryMeters <= approachWindowMeters
或
etaToEntrySeconds <= approachWindowSeconds
```

默认配置建议：

```yaml
railway:
  dispatch:
    route:
      enabled: true
      approach-window-meters: 500
      approach-window-seconds: 60
      conflict-window-seconds: 45
      hold-before-junction-meters: 120
      request-cooldown-seconds: 10
      rejection-cooldown-seconds: 20
      wait-timeout-seconds: 90
```

### 4.4 RouteConflictGroup

表示一组互斥请求。

```java
record RouteConflictGroup(
    String groupId,
    String junctionId,
    RouteConflictType conflictType,
    List<RouteResourceRequest> requests,
    Set<String> conflictSegmentIds,
    Set<String> conflictSwitchIds,
    String reason
) {}
```

`RouteConflictType`：

```text
SAME_SWITCH_DIFFERENT_POSITION
OVERLAP_AXLE_SEGMENT
OVERLAP_PROTECTION_SEGMENT
OPPOSING_DIRECTION
ESTABLISHED_ROUTE_CONFLICT
OCCUPIED_OR_RESERVED_SEGMENT
```

### 4.5 RouteDispatchDecision

一次进路调度决策。它解释“谁先走、谁等待、为什么”。

```java
record RouteDispatchDecision(
    String decisionId,
    String simulationRunId,
    String conflictGroupId,
    String junctionId,
    String selectedTrainId,
    String selectedRouteId,
    List<String> waitingTrainIds,
    Map<String, Double> priorityScores,
    String reason,
    RouteDecisionStatus status,
    String routeCommandId,
    List<String> waitingCommandIds,
    String rejectReason,
    Instant createdAt,
    Instant updatedAt
) {}
```

如果需要更贴近执行结果，也可在 decision 内保存分类后的仲裁输出：

```java
record RouteDispatchActions(
    List<DispatchCommand> grantRouteCommands,
    List<DispatchCommand> holdCommands,
    List<DispatchCommand> speedCommands,
    Map<String, String> rejectReasons
) {}
```

其中：

- `grantRouteCommands`：获批列车的 `REQUEST_ROUTE/REROUTE`。
- `holdCommands`：等待列车的 `HOLD/HOLD_TRAIN`。
- `speedCommands`：低速接近列车的 `SPEED_FACTOR/TEMP_SPEED_LIMIT`。
- `rejectReasons`：联锁拒绝、配置缺失、route 不可用等原因。

`RouteDecisionStatus`：

```text
PLANNED       已计算，尚未发命令
REQUESTED     已发 REQUEST_ROUTE/REROUTE
ACCEPTED      联锁接受，route 建立或准备建立
ACTIVE        列车正在使用该 route
REJECTED      联锁拒绝
WAITING       winner 未满足办理条件，或全部等待
REROUTING     尝试替代 route
RELEASED      route 已释放，决策闭环完成
CANCELLED     人工或策略取消
EXPIRED       超时失效
```

### 4.6 RouteReservation

reservation 是 per train/per route 的状态机，比 decision 更贴近“这列车的 route 请求进展”。

```java
record RouteReservation(
    String reservationId,
    String trainId,
    String routeId,
    String decisionId,
    RouteReservationState state,
    String commandId,
    String rejectReason,
    int retryCount,
    Instant requestedAt,
    Instant acceptedAt,
    Instant releasedAt,
    Instant expiresAt
) {}
```

`RouteReservationState`：

```text
PLANNED
REQUESTED
ACCEPTED
ACTIVE
REJECTED
WAITING
RELEASED
EXPIRED
```

### 4.7 DispatchSnapshot 扩展

建议在 `DispatchSnapshot` 增加 route dispatch 视图。

```java
record RouteDecisionView(
    String decisionId,
    String junctionId,
    String selectedTrainId,
    String selectedRouteId,
    List<String> waitingTrainIds,
    String status,
    String reason,
    String rejectReason,
    Map<String, Double> priorityScores
) {}
```

`DispatchSnapshot` 新增字段：

```java
boolean routeDispatchActive;
List<RouteDecisionView> routeDecisions;
List<RouteReservationView> routeReservations;
```

第一期为了少改接口，也可以先把 route decision 作为 `CommandView.reason` 的细节展示；但正式 2.0 建议结构化。

---

## 5. 决策算法 2.0

### 5.0 每 tick 进路调度流程

进路调度建议在 `DispatchService.evaluate(...)` 内部或之后增加一段“路线调度评估”，逻辑位置在现有策略生成之后、`SimulationRuntime` 处理 `REQUEST_ROUTE/REROUTE` 之前。

每 tick 详细流程：

```text
1. 读取输入
   TrainState、MovementAuthority、RouteState、SwitchState、TrackSegmentState、CurrentRunPlan。

2. 生成 TrainRouteIntent
   已有人工 REQUEST_ROUTE/REROUTE 优先；
   没有人工指令时，按运行计划、下一站、route plan 或默认主线推导。

3. 查询可选进路
   复用 RouteInterlockingService.queryRoutes() 的概念；
   调度层最好拿到 route 的区段、道岔需求、保护区段和当前状态。

4. 预测到达时间
   用当前位置、速度、MA、基础巡航速度估算 etaAtRouteEntry；
   零速/停站时用最小停站剩余时间修正。

5. 构建资源申请
   将 route 的 axleSectionIds、protectionSectionIds、相关道岔位置变成 RouteResourceRequest。

6. 冲突检测
   两个申请如果共享互斥区段、同一道岔要求不同位置、反向进入同一单线区段，
   则进入同一 RouteConflictGroup。

7. 仲裁排序
   对每个 conflict group 计算优先级；
   选一个 GRANT，其他进入 WAIT、APPROACH_CONTROL 或 REROUTE_CANDIDATE。

8. 输出命令
   获批列车发 REQUEST_ROUTE；
   被压后的列车发 HOLD 或 SPEED_FACTOR；
   靠近冲突点但尚未必须停车的列车临时限速接近。

9. 接受反馈
   Runtime 已经会把联锁接受/拒绝反馈回 dispatch；
   调度层据此更新 RouteReservation 状态。

10. 超时重算
   如果申请被拒绝、列车超过预估时间、路线长时间未建立，
   下一轮重新仲裁或改选备选 route。
```

### 5.1 计划进路解析

输入：

- `TrainState`
- `CurrentRunPlan`
- 人工 route 命令
- route catalog
- 当前 reservation store

输出：

- `RouteIntent`

解析顺序：

```text
for train in trains:
  if manual route command exists:
      intent = routeId from payload/detail
  else if active reservation exists:
      intent = reservation.routeId
  else if train has destination/servicePattern mapping:
      intent = mapped next route
  else if train is near known route entry:
      intent = default mainline route
  else:
      no intent
```

当前代码中 `TrainState` 暂时没有 `destinationStationId`，因此第一阶段建议支持三类 intent：

1. **人工指定 routeId**：payload 中有 `routeId`。
2. **发车命令派生 route**：`DEPART` payload 中有 `fromStation/toStation`。
3. **默认主线 route**：route `typeCode=MAIN`，且列车位置接近该 route 起点。

### 5.2 ETA 与办理窗口

ETA 计算建议先简单可解释：

```text
distance = routeEntryMeters - train.positionMeters
speed = max(train.speedMetersPerSecond, baseCruiseSpeed * 0.35)
eta = distance / speed
```

特殊处理：

- `distance < 0`：说明列车可能已经在 route 区段内，标记为 `INSIDE_ROUTE`，优先处理。
- `train.zeroSpeed && train.status=DWELLING`：ETA 加上剩余停站时间。
- `authority.speedLimitMetersPerSecond <= 0`：ETA 视为较大，但如果距离很近仍进入窗口，用于发 HOLD/等待。
- `train.dataQuality` 异常：只允许保守策略，不做抢进路。

进入窗口条件：

```text
distanceToEntryMeters <= approachWindowMeters
或 etaToEntrySeconds <= approachWindowSeconds
或 train already in route/protection segment
```

### 5.3 冲突检测

#### 5.3.1 route 与 route 冲突

两个请求冲突：

```text
axleSegmentIds 相交
或 protectionSegmentIds 相交
或 switchRequirements 中同 switchId 但 position 不同
或 route 方向相反且区段相交
```

#### 5.3.2 request 与既有联锁状态冲突

request 与当前 `RouteState` 冲突：

```text
existing.status == ESTABLISHED
且 existing.establishedByTrainId != request.trainId
且 existing.axleSegmentIds 与 request.requiredSegmentIds 相交
```

注意：同向追踪是否允许多车进入同一 established route，应由信号/联锁最终判断。调度第一阶段可以保守处理：

- 同一 train 的既有 route：不视作冲突。
- 不同 train 的同 route：如果距离足够且同向，可不生成互斥 decision，但仍依赖 MA 截断。
- 演示阶段建议简单：咽喉区/道岔区 route 一次只给一辆车，避免解释复杂。

#### 5.3.3 request 与轨道状态冲突

如果目标 route 内：

- 有 `FAULT`：标记 route unavailable，进入异常改路或 HOLD。
- 有 `OCCUPIED`：如果不是本车占用，降低优先级或等待。
- 有 `RESERVED`：如果不是本车 MA 预留，视作软冲突。
- 需要的 switch `locked=true` 且位置不是目标位置：等待当前 route 释放。

### 5.4 优先级评分

建议第一版同时保留“规则顺序”和“分值解释”。实际实现可以用分值，展示时给出主要 reason。

```text
score =
  manualBoost
+ establishedContinuationBoost
+ insideProtectionBoost
+ delayScore
+ mainlineScore
+ loadScore
+ etaScore
+ waitingAgingScore
- switchThrowPenalty
- dataQualityPenalty
- faultRiskPenalty
```

默认权重建议：

| 项 | 分值 | 说明 |
|---|---:|---|
| `manualBoost` | +1000 | 人工命令最高，但仍需联锁接受。 |
| `establishedContinuationBoost` | +500 | 已建立/正在使用的 route 不轻易打断。 |
| `insideProtectionBoost` | +300 | 已进保护区或太近，避免急停。 |
| `delayScore` | `min(delaySec, 300)` | 晚点越大越优先。 |
| `mainlineScore` | +80 | 主线优先于支线/出入库。 |
| `loadScore` | `loadRate * 50` | 满载率高略优先。 |
| `etaScore` | `max(0, 120 - etaSec)` | ETA 越近越优先。 |
| `waitingAgingScore` | `min(waitSec * 2, 120)` | 防止长期等待。 |
| `switchThrowPenalty` | -20 | 需要扳动道岔略降，减少频繁转换。 |
| `dataQualityPenalty` | -100 | 车辆状态降级时保守。 |
| `faultRiskPenalty` | -1000 | route 有故障时基本不可选。 |

稳定性规则：

```text
1. 已 ACCEPTED/ACTIVE 的 reservation 不被自动撤销。
2. 同一 conflictGroup winner 在 cooldown 内保持不变。
3. 分值差小于 threshold 时保持上一 tick 决策。
4. tie-break 使用 firstEnteredWindowAt，再用 trainId，避免抖动。
```

多车谁先谁后的可解释规则：

```text
1. 人工调度命令优先于自动计划。
2. 已经接近进路入口的车优先，避免临近停车。
3. 正在晚点的车优先，晚点秒数越大分越高。
4. 主线正线列车优先于出入库、折返、临时任务。
5. 已经获得上一段 MA 且速度较高的车优先，减少急停。
6. 已经等待较久的车增加 aging 分，避免长期饿死。
7. 前方站台拥堵时，后车不抢进路，优先 HOLD 或限速。
8. 安全兜底永远在信号/联锁层；只要联锁拒绝，dispatch 不能强行覆盖。
```

简化评分公式可写为：

```text
priorityScore =
  manualBoost
+ delayScore
+ proximityScore
+ mainlineScore
+ waitingAgingScore
- conflictRisk
- stopPenalty
```

### 5.5 决策输出

对每个冲突组：

```text
winner = maxScore(requests)

winner:
  commandType = REQUEST_ROUTE
  payload = {
    routeId,
    decisionId,
    reservationId,
    junctionId,
    priorityScore,
    expectedEntryAt
  }

losers:
  if distanceToEntry <= holdBeforeJunctionMeters + brakingBuffer:
      commandType = HOLD / HOLD_TRAIN
  else:
      commandType = SPEED_FACTOR or TEMP_SPEED_LIMIT
```

建议第一期使用现有 `DispatchConstraint` 已支持的命令，避免马上改 `constraintForTrain(...)`：

```text
HOLD 或 HOLD_TRAIN
SPEED_FACTOR
SPEED_LIMIT 或 TEMP_SPEED_LIMIT
```

`HOLD_BEFORE_JUNCTION` 和 `JUNCTION_SPEED_LIMIT` 可作为语义化 commandType，但如果不改 `DispatchService.constraintForTrain(...)`，它们不会生效。因此第一阶段推荐：

- 对外展示 reason 写 `HOLD_BEFORE_JUNCTION`。
- commandType 使用现有 `HOLD`。
- payload 增加 `junctionId`、`decisionId`、`stopBeforeMeters`。

例如：

```json
{
  "trainId": "TR-002",
  "commandType": "HOLD",
  "payload": {
    "decisionId": "RD-0001",
    "junctionId": "J-SW01",
    "semanticType": "HOLD_BEFORE_JUNCTION",
    "selectedTrainId": "TR-001",
    "selectedRouteId": "R-main-down",
    "stopBeforeMeters": 1180.0
  },
  "reason": "ROUTE_CONFLICT_WAIT"
}
```

### 5.6 联锁反馈处理

当前 `SimulationRuntime` 已经把 route 命令执行结果转为 `DispatchCommandFeedback`：

```text
feedbackSource = SIGNAL_INTERLOCKING
feedbackStatus = EFFECT_CONFIRMED 或 SKIPPED
reason = route established 或 rejectReason
```

2.0 需要在 `DispatchService.acceptFeedback(...)` 后同步 route reservation：

```text
if commandType in REQUEST_ROUTE/REROUTE:
  reservation = findByCommandId(commandId)
  if feedbackStatus == EFFECT_CONFIRMED:
      reservation.state = ACCEPTED
      decision.status = ACCEPTED
  else if feedbackStatus == SKIPPED:
      reservation.state = REJECTED
      decision.status = REJECTED
      decision.rejectReason = feedback.reason
```

拒绝后的策略：

```text
ROUTE_CONFLICT/SWITCH_LOCKED:
  -> WAITING，保持 HOLD/SPEED_LIMIT，等待释放后重试

ROUTE_NOT_FOUND/NO_MATCHING_ROUTE:
  -> REJECTED，不自动重试，等待人工或 fallback

SEGMENT_FAULT/SWITCH_CANNOT_THROW:
  -> REROUTING，尝试替代 route；无替代则 HOLD

UNKNOWN:
  -> rejection cooldown 后重试一次；超过 retryCount 则人工处理
```

### 5.7 route 释放与下一轮

route 释放的真实动作仍在 `RouteInterlockingService.releaseRoute(...)`，当前联锁会在 `touchRoutes(trains)` 中自动释放不再有列车占用的 route。

调度侧需要观察：

```text
RouteState 从 ESTABLISHED 变为 AVAILABLE
或 establishedByTrainId 不再是该 train
或列车位置已越过 route exitMeters
```

然后：

```text
reservation.state = RELEASED
decision.status = RELEASED
解除 waiting trains 的本轮等待原因
等待车重新进入下一轮 route request
```

如果等待命令已发出，需要避免永久 HOLD：

- route wait 命令要带 `decisionId`。
- `constraintForTrain(...)` 应只对 active decision 生效。
- decision released 后，对应 HOLD 命令应标记 `EFFECT_CONFIRMED` 或 `CANCELLED/EXPIRED`。

### 5.8 道岔调度策略

道岔不要由 dispatch 直接调用 `TrackService.throwSwitch(...)`，而是由 dispatch 发 `REQUEST_ROUTE(routeId)` 给信号/联锁。原因是当前 `RouteInterlockingService.establishRoute(...)` 已经负责：

```text
1. 判断 route 是否存在。
2. 判断 route 是否与既有 established route 冲突。
3. 根据 route 涉及区段推导道岔目标位置。
4. 预检查道岔是否锁闭、是否可转换。
5. 调用 TrackService.throwSwitch(...)。
6. 调用 TrackService.lockSwitch(...)。
7. 更新 RouteState。
```

因此 dispatch 应提前决定：

- 哪辆车可以申请该进路。
- 申请哪个 `routeId`。
- 其他车在进入冲突区前停车还是低速接近。
- 如果主路被占用，是否改选备选进路。
- 如果联锁拒绝，是否继续等待、重试、换路或降级限速。

### 5.9 冲突检测规则总结

调度层可以先用静态线路对象做轻量冲突检测，但不替代信号联锁：

| 规则 | 说明 | 第一阶段处理 |
|---|---|---|
| 区段重叠 | 两个 route 的 `axleSectionIds` 有交集。 | 默认互斥。 |
| 道岔互斥 | 同一个 switch，一个 route 要 `NORMAL`，另一个要 `REVERSE`。 | 默认互斥。 |
| 保护区段重叠 | 两个 route 的 `protectionSectionIds` 有交集。 | 软冲突，低优先级等待。 |
| 反向运行 | 同一 track/segment 上方向相反。 | 互斥，除非线路配置明确允许双向追踪。 |
| 同向追踪 | 同 route 同向多车。 | 可不完全互斥，但必须依赖 MA 前车尾部截断，并要求最小 headway/安全间隔。 |
| route locked | 目标 route 或道岔已被其他车锁闭。 | 等待当前 route 释放。 |

---

## 6. 与现有 DispatchService 的集成方案

### 6.1 最小侵入改造

第一阶段建议只改 dispatch 目录内逻辑，尽量不动信号/轨道：

1. 新增 route dispatch 子模块。
2. `DispatchService` 注入 `RouteDispatchService`。
3. 在 `evaluate(...)` 中调用 route dispatch。
4. route dispatch 返回 `DispatchCommand` 列表。
5. `commandValidator.validate(...)` 支持 route 命令基本校验。
6. `CommandQueue.enqueue(...)` 复用现有队列。
7. `acceptFeedback(...)` 后通知 route store 更新状态。
8. `buildSnapshot(...)` 增加 route decision view。

### 6.2 evaluate 中的推荐位置

当前 `evaluate(...)` 大致流程：

```text
currentPlan = planLoader.resolve(...)
latestProfiles = trainRunMonitor.update(...)
created = disturbanceDetector.detect(...)
progressActiveCommands(...)
generated = strategySelector.select(...)
validated = commandValidator.validate(...)
commandQueue.enqueue(validated)
```

建议加入：

```text
routeGenerated = routeDispatchService.evaluate(...)
allGenerated = generated + routeGenerated
validated = commandValidator.validate(allGenerated, authorities)
```

注意 route dispatch 需要读取 route/switch/track 状态，`DispatchService.evaluate(...)` 当前参数只有：

```java
TickContext context,
List<TrainState> trains,
List<MovementAuthority> authorities
```

因此有两种方案：

#### 方案 A：RouteDispatchService 直接注入依赖

`RouteDispatchService` 注入：

- `StaticInfrastructureCatalog`
- `TrackService`
- `RouteInterlockingService`

优点：少改 `SimulationRuntime` 方法签名。  
缺点：dispatch 对 signal/track 有更强读取依赖，但只读可接受。

#### 方案 B：SimulationRuntime 把状态传给 dispatch

修改 `DispatchService.evaluate(...)` 参数，增加：

- `List<RouteState>`
- `List<SwitchState>`
- `List<TrackSegmentState>`

优点：依赖更显式。  
缺点：要改公共调用签名。

第一阶段建议采用 **方案 A**，因为调度读取信号/轨道状态是设计允许的；但要约束只读，不调用设备级操作。

### 6.3 commands 与 activeCommands 的关系

route decision 可能每 tick 都评估，必须避免重复入队：

```text
已有同 trainId + routeId + decisionId 的 active reservation -> 不重复发 REQUEST_ROUTE
已有同 trainId 的 active HOLD route wait 命令 -> 不重复发 HOLD
route rejection cooldown 未过 -> 不重复发同 route
```

现有 `activeCommands` 主要按 command 生命周期保存。route dispatch 应额外维护自己的 reservation store，而不是完全依赖 activeCommands。

---

## 7. 命令与约束设计

### 7.1 REQUEST_ROUTE

用途：请求联锁为列车办理指定进路。

```json
{
  "commandType": "REQUEST_ROUTE",
  "trainId": "TR-001",
  "payload": {
    "routeId": "R-main-down",
    "decisionId": "RD-0001",
    "reservationId": "RR-0001",
    "junctionId": "J-SW01",
    "priorityScore": 245.5,
    "expectedEntryAt": "2026-07-11T10:00:12Z"
  },
  "reason": "ROUTE_DISPATCH"
}
```

消费方：`RouteInterlockingService.applyDispatchCommand(...)`。

### 7.2 REROUTE

用途：计划 route 不可用时，申请替代 route。

```json
{
  "commandType": "REROUTE",
  "trainId": "TR-001",
  "payload": {
    "routeId": "R-branch-down",
    "originalRouteId": "R-main-down",
    "decisionId": "RD-0002",
    "rerouteReason": "SEGMENT_FAULT"
  },
  "reason": "FAULT_REROUTE"
}
```

### 7.3 HOLD

用途：等待列车停在道岔保护区外或站台内，不进入冲突区。

现有 `DispatchService.constraintForTrain(...)` 已支持 `HOLD/HOLD_TRAIN`，因此第一阶段用它实现。

```json
{
  "commandType": "HOLD",
  "trainId": "TR-002",
  "payload": {
    "semanticType": "HOLD_BEFORE_JUNCTION",
    "decisionId": "RD-0001",
    "junctionId": "J-SW01",
    "selectedTrainId": "TR-001",
    "selectedRouteId": "R-main-down",
    "stopBeforeMeters": 1180.0,
    "releaseWhenDecisionStatus": "RELEASED"
  },
  "reason": "ROUTE_CONFLICT_WAIT"
}
```

### 7.4 SPEED_FACTOR / TEMP_SPEED_LIMIT

用途：列车距冲突区较远时不必立即停车，可以低速接近。

```json
{
  "commandType": "SPEED_FACTOR",
  "trainId": "TR-002",
  "payload": {
    "detail": 0.45,
    "semanticType": "JUNCTION_APPROACH_CONTROL",
    "decisionId": "RD-0001",
    "junctionId": "J-SW01"
  },
  "reason": "ROUTE_CONFLICT_APPROACH"
}
```

当前代码中 `SPEED_FACTOR/LIMIT_FACTOR` 从 payload `detail` 取值，所以 payload 要保持兼容。

### 7.5 可选命令类型

现有 `DispatchCommand` 是字符串类型，第一阶段可以先扩展命令语义，不必引入复杂继承模型。

| commandType | 阶段 | 说明 |
|---|---|---|
| `REQUEST_ROUTE` | P1 | 申请指定 routeId，payload 带 `routeId`、`reservationId`、`priorityScore`、`expectedEntryTime`。 |
| `REROUTE` | P1/P4 | 改选路线，payload 带 `fromStation/toStation`、`candidateRouteIds` 或直接带 `routeId`。 |
| `HOLD` / `HOLD_TRAIN` | P1 | 等待道岔或进路资源，当前代码已支持。 |
| `SPEED_FACTOR` | P1 | 冲突点前低速接近，例如 0.3-0.7，当前代码已支持。 |
| `TEMP_SPEED_LIMIT` | P1 | 冲突区前固定限速，当前代码已支持。 |
| `RELEASE_ROUTE` | 可选 | 当前信号已有自动释放，后续如果要调度显式释放再加。 |
| `CANCEL_ROUTE_REQUEST` | 可选 | 人工撤销或超时撤销 route request。 |

注意：`RELEASE_ROUTE` 和 `CANCEL_ROUTE_REQUEST` 第一阶段不是必须项，避免过早扩大信号/联锁接口。

---

## 8. 场景闭环设计

### 8.1 场景一：单车计划进路

```text
TR-001 接近 SW01
计划 route = R-main-down
SW01 当前 NORMAL，未锁闭
route 可用
```

流程：

```text
1. RouteIntentResolver 解析 TR-001 nextRouteId=R-main-down。
2. RouteRequestPlanner 判断进入 approach window。
3. 无冲突组或只有单 request。
4. RouteDecisionEngine 生成 REQUEST_ROUTE。
5. Runtime 交给联锁。
6. 联锁建立 route，锁闭 SW01。
7. 反馈 EFFECT_CONFIRMED。
8. reservation=ACCEPTED，decision=ACCEPTED。
9. SignalService 第二次计算 MA，车辆通过。
10. route 释放后 reservation=RELEASED。
```

### 8.2 场景二：两车争用同一道岔不同位置

```text
TR-001 -> R-main-down，需要 SW01 NORMAL
TR-002 -> R-branch-down，需要 SW01 REVERSE
两车 ETA 差 20s，小于 conflictWindowSeconds=45
```

流程：

```text
1. 两个 RouteResourceRequest 进入同一个 conflict group。
2. RoutePriorityScorer 计算分值。
3. TR-001 主线 + 晚点，得分更高。
4. TR-001 生成 REQUEST_ROUTE。
5. TR-002 生成 HOLD 或 SPEED_FACTOR。
6. 联锁接受 TR-001 route，SW01 转 NORMAL 并 locked。
7. TR-002 MA 被调度约束压住。
8. TR-001 通过后 route 释放。
9. 下一轮 TR-002 成为 winner，申请 R-branch-down。
10. SW01 转 REVERSE 并 locked，TR-002 通过。
```

### 8.3 场景三：合流

```text
TR-001 从主线进入 T04
TR-002 从支线进入 T04
route 均包含 T04 或保护区包含 T04
```

流程：

```text
1. conflict type = OVERLAP_AXLE_SEGMENT 或 OVERLAP_PROTECTION_SEGMENT。
2. 若一车已接近制动临界点，优先。
3. 否则按晚点、主线、载客、ETA 排序。
4. loser 不进入合流保护区。
5. winner route released 后 loser 重新申请。
```

### 8.4 场景四：联锁拒绝

```text
TR-001 请求 R-main-down
联锁返回 Switch SW01 is locked
```

流程：

```text
1. Runtime 反馈 SKIPPED，reason=Switch SW01 is locked。
2. RouteReservation state=REJECTED。
3. RouteDecision status=REJECTED，rejectReason 写入。
4. 如果 rejectReason 属于可等待类，生成 HOLD/SPEED_FACTOR。
5. rejectionCooldown 内不重复请求同 route。
6. SW01 解锁后重新进入 request planning。
```

### 8.5 场景五：故障改路

```text
R-main-down 含 T03
T03=FAULT
存在 R-branch-down 可到达替代目标
```

流程：

```text
1. RouteRequestPlanner 标记 R-main-down unavailable。
2. RouteIntentResolver 查找 fallback route。
3. 若 fallback route 可接受，生成 REROUTE。
4. 若 fallback route 也不可用，生成 HOLD。
5. Snapshot 展示 originalRouteId、fallbackRouteId、rerouteReason。
```

---

## 9. 配置设计

建议在 `DispatchProperties` 中扩展：

```yaml
railway:
  dispatch:
    route:
      enabled: true
      approach-window-meters: 500
      approach-window-seconds: 60
      conflict-window-seconds: 45
      hold-before-junction-meters: 120
      request-cooldown-seconds: 10
      rejection-cooldown-seconds: 20
      wait-timeout-seconds: 90
      max-route-retry-count: 2
      stable-decision-threshold: 20
      mainline-priority-bonus: 80
      manual-priority-bonus: 1000
      established-route-bonus: 500
      inside-protection-bonus: 300
```

后续可以新增独立 route plan 配置：

```yaml
dispatchRoutePlan:
  servicePatterns:
    MAINLINE_DOWN:
      direction: DOWN
      routes:
        - R-S01-S02-DOWN
        - R-S02-S03-DOWN
        - R-S03-S04-DOWN
    BRANCH_DOWN:
      direction: DOWN
      routes:
        - R-S01-S02-DOWN
        - R-S02-BRANCH-DOWN
  trainAssignments:
    TR-001: MAINLINE_DOWN
    TR-002: BRANCH_DOWN
```

第一阶段如果没有该文件，可以只支持 manual/default。

---

## 10. API 与快照 2.0

### 10.1 复用现有接口

当前已可用：

```http
POST /api/dispatch/commands
GET /api/dispatch/commands
GET /api/dispatch/status
GET /api/dispatch/route/list
POST /api/dispatch/route/establish
```

2.0 第一阶段仍可通过：

```json
{
  "trainId": "TR-001",
  "commandType": "REQUEST_ROUTE",
  "routeId": "R-main-down"
}
```

或：

```json
{
  "trainId": "TR-001",
  "commandType": "REROUTE",
  "payload": {
    "routeId": "R-main-down"
  }
}
```

### 10.2 新增查询接口建议

```http
GET /api/dispatch/route-decisions
GET /api/dispatch/route-reservations
GET /api/dispatch/route-conflicts/current
```

### 10.3 WebSocket snapshot 扩展

在 `SimulationSnapshot.dispatch` 中增加：

```json
{
  "routeDispatchActive": true,
  "routeDecisions": [
    {
      "decisionId": "RD-0001",
      "junctionId": "J-SW01",
      "selectedTrainId": "TR-001",
      "selectedRouteId": "R-main-down",
      "waitingTrainIds": ["TR-002"],
      "status": "ACCEPTED",
      "reason": "mainline priority; delay=80s; eta=18s",
      "rejectReason": null,
      "priorityScores": {
        "TR-001": 245.5,
        "TR-002": 132.0
      }
    }
  ],
  "routeReservations": [
    {
      "reservationId": "RR-0001",
      "trainId": "TR-001",
      "routeId": "R-main-down",
      "state": "ACCEPTED",
      "commandId": "CMD-a1b2c3d4",
      "rejectReason": null
    }
  ]
}
```

外层继续使用已有：

- `routeStates`：真实联锁状态。
- `switchStates`：真实道岔状态。
- `trackStates`：真实区段状态。
- `authorities`：真实 MA。

这样前端能区分：

```text
dispatch.routeDecisions = 调度为什么这么安排
routeStates = 信号联锁实际办理到什么状态
switchStates = 轨道道岔实际处于什么位置/锁闭
authorities = 车辆最终获得什么运行授权
```

### 10.4 DispatchSnapshot 展示重点

调度页面后续最好能直接回答“为什么这辆车被按住”。因此 `DispatchSnapshot` 应展示：

| 展示项 | 说明 |
|---|---|
| 每辆车当前 routeIntent | 展示下一目标 route、意图来源、目的地或 service pattern。 |
| assignedRouteId | 当前已分配或正在申请的 route。 |
| reservationState | `PLANNED / REQUESTED / ACCEPTED / ACTIVE / RELEASED / REJECTED / EXPIRED`。 |
| 当前冲突组 | 冲突资源、等待车辆、获批车辆、冲突类型。 |
| 道岔资源状态 | `switchId`、目标位置、锁闭列车、等待队列。 |
| 调度原因 | 例如 `WAIT_SWITCH_SW01`、`WAIT_ROUTE_R03`、`REROUTE_TO_DEPOT`。 |
| 联锁拒绝原因 | 直接展示 `RouteDispatchResult.rejectReason` 或标准 reject code。 |

---

## 11. 需要其他模块配合的事项

### 11.1 信号/联锁模块

建议反馈给信号/联锁负责人：

1. `RouteInterlockingService.applyDispatchCommand(...)` 当前通过 `detail` 简易解析 route，建议支持结构化 payload 或新增结构化方法：

   ```java
   applyRouteRequest(String routeId, String trainId, String decisionId)
   ```

2. `RouteInterlockingService.maLimitFromRouteConflict(String trainId)` 当前对列车所在区段判断不够精确，复杂多车/分支下可能误判。建议改为按 trainId 精确映射当前位置区段。

3. `establishRoute(...)` 中“同一进路允许多车先后使用”的设计与后续 route segment 冲突检测需要统一。调度侧第一期可保守一次只放一个，但信号侧最终要定义同向追踪规则。

4. 联锁拒绝原因建议标准化，不要只返回自由文本：

   ```text
   ROUTE_NOT_FOUND
   ROUTE_CONFLICT
   SWITCH_LOCKED
   SWITCH_CANNOT_THROW
   SEGMENT_OCCUPIED
   SEGMENT_FAULT
   INVALID_SWITCH_REQUIREMENT
   ```

5. 建议 `RouteState` 增加：

   - `establishedAt`
   - `routeDirection`
   - `activeTrainIds` 或更明确的 owner 模型
   - `releaseReason`

### 11.2 轨道模块

建议反馈给轨道负责人：

1. `TrackService.updateOccupancy(...)` 当前基于 `positionMeters` 与区段 `[startMeters,endMeters]` 重叠判断，分支同里程区段会不准确。

2. 后续需要提供更明确的 route/switch 查询：

   ```java
   switchRequirementForRoute(routeId)
   segmentsForRoute(routeId)
   segmentStateById(segmentId)
   switchStateById(switchId)
   ```

3. 道岔保护区建议配置化，不要长期只用固定 `holdBeforeJunctionMeters`。

4. 若支持道岔故障，应在 `SwitchState` 或独立状态中暴露：

   - `faulted`
   - `movable`
   - `lastThrowFailedReason`

### 11.3 车辆模块

建议反馈给车辆负责人：

1. `TrainState` 后续最好增加图定位字段：

   ```text
   currentSegmentId
   offsetInSegmentMeters
   routeProgressIndex
   destinationStationId
   servicePattern
   nextPlannedRouteId
   ```

2. 外部车辆服务 fallback 时，调度要能通过 `dataQuality` 或健康状态识别，采用保守 route 策略。

3. 车辆仍不直接消费 route command。路径选择通过 MA 体现；扣车/限速通过 `DispatchConstraint -> SignalService -> MovementAuthority` 体现。

### 11.4 仿真总控

建议组长/总控配合：

1. 维持当前 tick 顺序，不要把 route command 放到第二次 MA 之后。
2. reset 时清空 route decision/reservation store。
3. snapshot 增加 route decision 视图后，同步前端类型和 API 文档。

### 11.5 调度负责人可独立完成的部分

以下内容基本属于 dispatch 模块内部，可以由运营调度负责人先独立设计和实现：

| 工作 | 说明 |
|---|---|
| 设计 `TrainRouteIntent / RouteResourceRequest / RouteReservation / ConflictGroup / RouteDispatchDecision` | 不需要改信号/轨道底层动作。 |
| 建立调度侧 `RouteCatalog` | 从 `RouteInterlockingService.queryRoutes()` 或 `OperationalLineData.routes/switches` 读取 route 列表。 |
| 构建冲突组 | 根据 route 区段、道岔需求、保护区段做轻量冲突检测。 |
| 优先级仲裁 | 按人工、晚点、主线、ETA、载客、等待 aging 等规则选择 winner。 |
| 生成命令 | 输出 `REQUEST_ROUTE / HOLD / SPEED_FACTOR / REROUTE`。 |
| 扩展 `DispatchSnapshot` | 增加 route decision、reservation、conflict group 展示状态。 |
| 扩展 `CommandValidator` | 校验 routeId 非空、priority 合法、过期命令跳过、payload 基本字段存在。 |

如果涉及 `RouteInterlockingService` 的结构化接口、`TrainState` 图定位字段、`TrackService` 精确分支占用，则需要反馈给对应模块负责人协作。

---

## 12. 实施路线

### P0：文档与边界确认

目标：

- 确认调度只做进路意图、冲突仲裁、等待/限速约束。
- 确认 route command 仍由联锁执行。
- 确认第一期使用现有 `REQUEST_ROUTE/REROUTE/HOLD/SPEED_FACTOR`。

产出：

- 本 v2.0 文档。
- API payload 约定。
- 与信号/轨道/车辆负责人确认缺口。

### P1：route decision 记录与人工 route 纳管

目标：

- 人工提交 `REQUEST_ROUTE/REROUTE` 后，dispatch 创建 `RouteReservation` 和 `RouteDispatchDecision`。
- 联锁反馈后更新状态。
- Snapshot 能展示 decision/rejectReason。

验收：

```text
POST /api/dispatch/commands REQUEST_ROUTE
-> command queued
-> interlocking accepted/rejected
-> dispatch route decision 状态更新
-> 前端/接口可见 rejectReason
```

### P2：计划进路自动申请

目标：

- 根据默认主线 route 或简单 route plan 生成 `RouteIntent`。
- 列车进入 approach window 后自动申请 route。
- 冷却防抖，避免重复请求。

验收：

```text
TR-001 接近某 route entry
-> 自动生成 REQUEST_ROUTE
-> 联锁建立 route
-> switch locked
-> MA 开放
```

### P3：冲突组与优先级仲裁

目标：

- 多个 route request 冲突时构建 conflict group。
- 按 manual/mainline/delay/load/ETA/aging 选择 winner。
- loser 生成 HOLD 或 SPEED_FACTOR。

验收：

```text
TR-001/TR-002 同时接近 SW01
-> 只给 winner 发 REQUEST_ROUTE
-> loser 被 HOLD 或限速
-> winner route release 后 loser 进入下一轮
```

### P4：异常拒绝与改路

目标：

- 标准化处理联锁拒绝。
- route fault/unavailable 时尝试 fallback route。
- 无 fallback 时 HOLD，并展示原因。

验收：

```text
目标 route 被拒绝
-> reservation=REJECTED
-> decision.rejectReason 可见
-> 可等待类拒绝进入 WAITING
-> 可改路类拒绝进入 REROUTING
```

### P5：图定位增强后的精确调度

目标：

- 消费 `currentSegmentId + offsetInSegment + direction`。
- 精确判断分支前后车、同向追踪、反向冲突。
- 支持折返线、出入库线、越行线演示。

该阶段需要车辆/轨道/信号共同配合，不建议由 dispatch 单独完成。

---

## 13. 测试方案

### 13.1 单元测试

| 测试 | 重点 |
|---|---|
| RouteIntentResolverTest | manual route 优先、默认主线 fallback、无 route 时不生成请求。 |
| RouteCatalogTest | 从 `OperationalLineData.routes/switches` 推导 switch requirement。 |
| RouteConflictDetectorTest | 区段重叠、道岔位置冲突、已建立 route 冲突。 |
| RoutePriorityScorerTest | 人工、主线、晚点、ETA、等待 aging 分值正确。 |
| RouteDecisionEngineTest | winner 生成 REQUEST_ROUTE，loser 生成 HOLD/SPEED_FACTOR。 |
| RouteReservationStoreTest | feedback accepted/rejected/released 状态流转。 |
| RouteCooldownTest | 同一 train/route 不重复发命令。 |

### 13.2 集成测试

| 场景 | 验收标准 |
|---|---|
| 人工 route 请求 | `REQUEST_ROUTE` 被联锁消费，dispatch decision 更新。 |
| 自动 route 请求 | 列车接近窗口自动申请 route。 |
| 两车道岔冲突 | 只有 winner 建路，loser 被调度约束压住。 |
| 进路释放再申请 | winner 通过释放后，waiting train 自动申请。 |
| 联锁拒绝 | dispatch 记录 rejectReason，进入 WAITING/REROUTING/REJECTED。 |

### 13.3 演示脚本

推荐演示闭环：

```text
1. 初始化两列车 TR-001、TR-002。
2. 两车接近同一 SW01。
3. TR-001 目标 R-main-down，TR-002 目标 R-branch-down。
4. route dispatch 构建 conflict group。
5. TR-001 因主线/晚点成为 winner。
6. TR-001 发 REQUEST_ROUTE。
7. TR-002 发 HOLD，reason=ROUTE_CONFLICT_WAIT。
8. 联锁建立 TR-001 route，SW01=NORMAL locked=true。
9. TR-001 通过后 route released，SW01 unlocked。
10. TR-002 下一轮发 REQUEST_ROUTE，SW01=REVERSE locked=true。
11. 前端同时展示：
    - dispatch.routeDecisions
    - activeCommands
    - routeStates
    - switchStates
    - authorities
```

---

## 14. 风险与应对

| 风险 | 影响 | 应对 |
|---|---|---|
| 当前 `positionMeters` 无法区分分支同里程 | 多车排序/ETA 不精确 | 第一阶段只做局部演示；后续推动图定位字段。 |
| route 配置不足 | 自动 route intent 无法稳定生成 | 第一阶段支持人工 route 和默认主线；后续补 route plan 配置。 |
| route 重复申请 | 命令队列抖动、联锁日志刷屏 | reservation store + cooldown。 |
| winner 每 tick 改变 | 道岔调度抖动 | 稳定阈值、已建立优先、tie-break 固定。 |
| loser HOLD 无法释放 | 列车永久扣停 | HOLD payload 绑定 decisionId，decision released 后取消/过期。 |
| 联锁拒绝原因自由文本 | 调度难以分类处理 | 第一阶段字符串 contains 兜底；后续信号模块标准化 reject code。 |
| dispatch 依赖 track/signal 太强 | 模块边界变模糊 | 只读依赖允许，禁止调用设备级操作。 |
| 同向多车同 route 规则不清 | 可能过度保守或过度放行 | 第一阶段咽喉区保守一次一车；后续交给信号定义同向追踪策略。 |

---

## 15. 2.0 版最终建议

道岔调度在本系统中不应被实现为“调度直接扳道岔”，而应实现为“调度在运营层办理目标进路”。当前代码已经有联锁办理、道岔转换、道岔锁闭、MA 重算和车辆运行链路，2.0 版最应该补的是 dispatch 内部的计划进路解析、资源预约、冲突仲裁和可解释状态。

第一期最小闭环建议：

```text
人工/默认 route intent
  -> RouteReservation
  -> REQUEST_ROUTE
  -> 联锁反馈
  -> DispatchSnapshot 展示 decision/rejectReason
```

第二期扩展为：

```text
自动计划进路
  -> approach window
  -> conflict group
  -> winner REQUEST_ROUTE
  -> loser HOLD/SPEED_FACTOR
  -> route released 后下一轮
```

第三期再接：

```text
故障拒绝
  -> fallback route
  -> REROUTE
  -> 无替代则 HOLD
```

这样设计的好处是：

- 与现有 `SimulationRuntime` 顺序天然兼容。
- 不破坏信号/联锁/轨道的安全边界。
- dispatch 模块职责清楚，答辩时容易解释。
- 能尽快做出“多车争道岔，调度决定先后”的演示闭环。
- 后续接外部车辆服务或图定位增强时，不需要推翻调度架构。

---

**文档结束**
