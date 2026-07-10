# 运营调度模块道岔与进路调度详细设计

> 文档版本：v1.0  
> 所属项目：Railway-Sim 上京地铁仿真系统  
> 模块负责人：运营调度  
> 关联文档：[DISPATCH_OUTLINE_DESIGN.md](./DISPATCH_OUTLINE_DESIGN.md)、[DISPATCH_DETAILED_DESIGN.md](./DISPATCH_DETAILED_DESIGN.md)、[../docs/API_CONTRACT.md](../docs/API_CONTRACT.md)  
> 编写说明：本文设计的是运营调度模块在分岔、合流、共用区段场景下的进路级调度能力。调度模块只生成运营意图和调度约束，不直接扳动道岔，不直接锁闭进路，也不绕过信号/联锁向车辆下发牵引或制动控制量。

---

## 1. 设计目标与范围

### 1.1 背景

现有运营调度模块已经覆盖运行计划、停站偏差、行车间隔、拥挤扰动和人工限速/扣车等时间维度调度能力。但在线路存在分岔、合流、折返线、出入段线、越行线或共用咽喉区时，仅依赖时间戳和 headway 调度不足以表达“哪列车走哪条路径、冲突时谁先通过、谁在道岔保护区外等待”的运营决策。

因此需要在调度模块中补充“道岔与进路调度”能力。本文中“道岔调度”统一落地为“进路级调度”：调度模块根据运行计划、车次目的地、运营策略和异常状态确定列车的目标进路，并向信号/联锁模块提交进路请求；信号/联锁模块负责判断安全可行性并办理进路，道岔实际转换和锁闭由轨道/联锁能力完成，车辆模块只按信号授权运行。

### 1.2 目标

| 目标 | 说明 |
|---|---|
| 支持分岔场景 | 同一共用区段后的列车可按运营计划进入正线、支线、折返线或出入段线。 |
| 支持计划进路解析 | 正常情况下列车路线由运行计划确定，调度负责解析目标 route 并在合适窗口申请办理。 |
| 支持合流冲突仲裁 | 多列车从不同方向汇入同一共用区段时，调度决定优先通过顺序。 |
| 支持共用咽喉区调度 | 多进路共享道岔、渡线或区段时，调度根据晚点、主线优先、客流和安全距离做运营级排序。 |
| 支持异常改路 | 当信号/联锁拒绝、轨道故障、车辆异常或供电约束导致计划 route 不可用时，调度可选择替代 route 或生成等待约束。 |
| 保持安全边界 | 调度只请求进路或生成等待/限速约束，进路能否建立由信号/联锁最终判定。 |
| 可解释可展示 | 每个进路调度决策都有原因、优先级依据、结果和拒绝原因，供调度页和综合监控展示。 |
| 可演示闭环 | 能演示“两列车接近同一道岔区，优先列车建立进路，等待列车扣停或限速，进路释放后再办理下一进路”。 |

### 1.3 非目标

| 非目标 | 说明 |
|---|---|
| 不直接扳动道岔 | 调度不调用 `throwSwitch`、`lockSwitch`、`unlockSwitch` 等设备级操作。 |
| 不替代联锁 | 调度不判断所有安全联锁条件，只做运营选择；冲突检测和锁闭由信号/联锁负责。 |
| 不生成牵引/制动控制量 | 调度不输出 `tractionCommand`、`brakeCommand`。 |
| 不重构完整运行图 | 第一期只做计划进路解析、异常改路和局部冲突仲裁，不做全日运行图自动重排。 |
| 不直接访问外部车辆服务 | 外部车辆控制系统由中央车辆/车载控制适配层调用，调度只消费统一 `TrainState` 和执行反馈。 |

### 1.4 设计定位

道岔与进路调度包含三个层次：

```text
1. 计划进路解析
   正常情况下，列车运行路线由运行计划、车次、目的地或 service pattern 给定。
   调度模块负责解析该车下一条目标 route，并在列车进入办理窗口时申请进路。

2. 异常改路决策
   当计划 route 被信号/联锁拒绝，或轨道/车辆/供电状态显示 route 不可用时，
   调度模块决定等待、扣车、限速，或选择替代 route。

3. 多车冲突仲裁
   当多列车目标 route 共享道岔、区段或咽喉资源时，
   调度模块决定谁先申请进路，谁在保护区外等待或限速。
```

其中，计划进路解析是基础能力，异常改路和多车冲突仲裁是调度模块体现运营决策能力的重点。

---

## 2. 总体架构

### 2.1 模块边界

```text
运营调度模块
  - 读取 TrainState、RouteState、SwitchState、TrackSegmentState、MovementAuthority
  - 判断列车接近分岔/合流/共用咽喉区
  - 解析列车计划目标进路，必要时选择替代进路
  - 在多车共享进路资源时计算冲突优先级
  - 生成 REQUEST_ROUTE/REROUTE/HOLD/SPEED_LIMIT 等调度命令
  - 周期性评估车辆、信号、轨道、进路和道岔状态
  - 记录 RouteDispatchDecision 并输出 dispatch 快照

信号/联锁模块
  - 接收进路请求
  - 判断进路是否存在、区段是否冲突、道岔是否可转换、道岔是否锁闭
  - 接受或拒绝调度进路请求，并返回拒绝原因
  - 建立/释放进路
  - 锁闭/解锁道岔
  - 计算 MovementAuthority 和速度授权

轨道模块
  - 提供区段占用、故障、拓扑、道岔位置和锁闭状态
  - 执行道岔转换与锁闭的底层状态变更

车辆/基层车辆控制模块
  - 提供统一 TrainState
  - 按信号 MA、轨道约束、供电约束和车辆控制逻辑运行
  - 对停站调整等车辆侧调度命令提供执行反馈
```

### 2.2 主循环位置

进路调度纳入现有仿真主循环，不单独开线程。建议执行顺序如下：

```text
SimulationRuntime.tick()
  1. TrainManager.states()                         获取当前列车状态
  2. TrackService.updateOccupancy()                 更新区段占用
  3. SignalService.calculateAuthorities(..., none)  初算 MA，供调度读取安全态势
  4. DispatchService.evaluate()                     时间调度 + 计划进路解析/异常改路/冲突仲裁
  5. RouteInterlockingService.applyDispatchCommand() 校验并办理 REQUEST_ROUTE/REROUTE
  6. DispatchService.constraintsForTrains()          生成扣车/限速等调度约束
  7. SignalService.calculateAuthorities(..., dispatchConstraints)
                                                    结合调度约束重算 MA
  8. TrainManager.tickAll()                         车辆按最终 MA/约束运行
  9. MonitorService.buildSnapshot()                 快照包含 dispatch/route/switch 状态
  10. WebSocket broadcast                           按 pushIntervalMillis 限流推送
```

关键原则：

- 调度在本周期生成进路请求和等待约束，但不直接确认 route 是否安全可通行。
- 信号/联锁在同周期校验并尝试办理进路，是最终安全可行性的判定方。
- 车辆最终只看到信号授权、轨道约束、供电约束和车载控制输入。
- 若联锁拒绝进路，调度将该拒绝原因记录到决策与命令状态中，并可对列车生成 `HOLD` 或 `SPEED_LIMIT`。

### 2.3 进路请求与状态评估闭环

调度、信号、轨道、车辆之间采用“目标进路请求 + 状态评估”的闭环，而不是调度直接指挥车辆或道岔。

```text
1. 调度根据计划/目的地/人工命令/异常策略确定目标 routeId。
2. 调度在列车进入进路办理窗口时提交 REQUEST_ROUTE 或 REROUTE。
3. 信号/联锁检查进路、道岔、区段、冲突和安全距离。
4. 联锁接受后建立进路、锁闭道岔，SignalService 生成 MA/限速。
5. 车辆按 MA、轨道约束、供电约束和车载控制逻辑运行。
6. 车辆、信号、轨道状态进入中央状态缓存或快照。
7. 调度按 evaluateIntervalMs 周期性评估这些状态。
8. 若发现异常或收到事件，调度重新决策：等待、扣车、限速或选择替代 route。
```

因此，调度模块的判断是运营层判断：

| 问题 | 责任模块 |
|---|---|
| 这辆车运营上应该申请哪条 route？ | 调度 |
| 什么时候申请 route？ | 调度 |
| route 被拒绝后等待、限速还是改路？ | 调度 |
| route 是否满足安全联锁条件？ | 信号/联锁 |
| 道岔是否能转换并锁闭？ | 信号/联锁 + 轨道 |
| MA 是否可以开放给车辆？ | 信号 |
| 车辆如何牵引、制动、开关门？ | 车辆/基层车辆控制 |

---

## 3. 场景设计

### 3.0 计划进路执行场景

单列车正常运行时，目标线路通常已经由运行计划、车次、目的地或 service pattern 固定。调度模块不需要在每个道岔前重新临时选择“左/右”，而是负责将计划转换为下一条目标进路，并在列车接近办理窗口时向信号/联锁提交申请。

```text
TR-001 计划：S01 -> S02 -> S03 -> S04 -> S05
计划进路序列：R-S01-S02 -> R-S02-S03 -> R-S03-S04 -> R-S04-S05
```

处理流程：

```text
1. 调度读取 TR-001 的运行计划或 route plan。
2. 调度解析下一目标进路为 R-S02-S03。
3. 当 TR-001 进入进路办理窗口时，调度提交 REQUEST_ROUTE(R-S02-S03)。
4. 信号/联锁校验并建立进路，生成 MA。
5. 车辆按 MA 运行。
6. 调度周期性观察 TrainState、RouteState、SwitchState、MovementAuthority。
7. 若进路办理成功且列车通过，调度记录完成并准备下一条计划进路。
```

该场景中，调度的主要职责是“计划进路解析 + 请求办理 + 状态跟踪”，不是安全通行确认。

### 3.1 分岔场景

分岔场景指多列车先共用同一区段，随后通过道岔进入不同方向。

```text
T01 共用区段
  |
  +-- SW01 NORMAL  -> T02 正线方向 -> S03
  |
  +-- SW01 REVERSE -> T03 支线方向 -> S06
```

处理流程：

```text
1. 调度读取列车运行计划、目的地或 service pattern。
2. 判断 TR-001 应走正线，目标进路为 R-main-down。
3. 判断 TR-002 应走支线，目标进路为 R-branch-down。
4. 当列车进入进路办理窗口时，调度生成 REQUEST_ROUTE。
5. 联锁检查道岔 SW01 是否可转换、目标进路区段是否冲突。
6. 联锁接受后，道岔转换并锁闭，SignalService 开放对应 MA。
7. 列车通过后，联锁释放进路并解锁道岔。
```

调度输出示例：

```json
{
  "trainId": "TR-001",
  "commandType": "REQUEST_ROUTE",
  "payload": {
    "routeId": "R-main-down",
    "junctionId": "J-SW01",
    "reason": "SERVICE_PATTERN_MAINLINE"
  },
  "reason": "ROUTE_DISPATCH"
}
```

### 3.2 合流场景

合流场景指多列车从不同方向汇入同一共用区段。

```text
T02 正线方向 ----+
                +-- SW02 -> T04 共用区段
T03 支线方向 ----+
```

核心问题是“谁先通过合流点”。调度采用运营优先级排序：

```text
1. 已进入保护区或制动距离不足的列车优先。
2. 已建立进路的列车优先。
3. 晚点更严重的列车优先。
4. 主线列车优先。
5. 满载率更高的列车优先。
6. ETA 更早的列车优先。
```

冲突处理流程：

```text
1. 调度计算接近合流点的列车 ETA。
2. 若两列车 ETA 差值小于 conflictWindowSec，则形成冲突组。
3. 调度选择优先列车，生成 REQUEST_ROUTE。
4. 对等待列车生成 HOLD 或 SPEED_LIMIT，使其停在道岔保护区外。
5. 优先列车通过并释放进路后，调度为等待列车请求下一进路。
```

### 3.3 共用咽喉区场景

共用咽喉区包括折返线、停车线、出入段线、渡线等多条进路共享的区域。其特点是多个 route 可能共享一个或多个轴计区段和道岔。

调度策略：

- 对进入咽喉区的列车建立冲突组。
- 每次只允许一个互斥进路进入 `REQUESTED` 或 `ESTABLISHED`。
- 对其他列车生成等待约束，避免压入咽喉区造成死锁。
- 对被拒绝的进路记录 `rejectReason`，供前端展示。
- 避免频繁改判：已建立进路原则上不取消，除非存在安全拒绝或人工取消。

### 3.4 临时改路场景

当前方区段故障、道岔故障、支线拥堵或调度员人工干预时，调度可尝试替代进路。

```text
1. 调度计划进路为 R-main-down。
2. 信号/联锁拒绝该进路，或轨道/车辆/供电状态显示该进路不可用。
3. 调度记录 rejectReason 或异常原因。
4. 调度查询替代进路 R-branch-down。
5. 调度判断替代进路从运营角度可接受。
6. 调度生成 REROUTE 或 REQUEST_ROUTE(R-branch-down)。
7. 联锁接受：执行改路，并在 dispatch 快照记录新 routeId。
8. 联锁拒绝：对列车生成 HOLD，等待人工或后续恢复。
```

异常来源不只来自信号，也可能来自轨道、车辆、供电或调度自身的周期评估：

```text
Signal/Interlocking: ROUTE_REJECTED, MA_UNAVAILABLE
Track: SWITCH_FAULT, SEGMENT_FAULT, SEGMENT_OCCUPIED
Vehicle: TRAIN_FAULT, EMERGENCY_BRAKE, DATA_QUALITY_FALLBACK
Power: POWER_SECTION_OUTAGE, TRACTION_UNAVAILABLE
Dispatch: DELAY_TOO_HIGH, HEADWAY_CONFLICT, ROUTE_WAIT_TIMEOUT
```

---

## 4. 核心数据模型

### 4.1 RouteIntent

表示列车的运营运行意图。正常情况下由运行计划、车次、目的地或 service pattern 生成；异常情况下可由人工命令、故障改路策略或调度重决策覆盖。

```json
{
  "trainId": "TR-001",
  "serviceNo": "A001",
  "direction": "DOWN",
  "originStationId": "S01",
  "destinationStationId": "S05",
  "preferredRouteId": "R-main-down",
  "nextRouteId": "R-main-down",
  "priority": 80,
  "reason": "TIMETABLE"
}
```

| 字段 | 说明 |
|---|---|
| `trainId` | 列车 ID。 |
| `serviceNo` | 车次或服务号。 |
| `direction` | 运行方向。 |
| `originStationId` | 起点站。 |
| `destinationStationId` | 终到站或当前任务目的地。 |
| `preferredRouteId` | 计划优先进路。 |
| `nextRouteId` | 当前要申请的下一条进路。 |
| `priority` | 运营优先级分值。 |
| `reason` | 意图来源，如 `TIMETABLE`、`DELAY_RECOVERY`、`FAULT_REROUTE`、`MANUAL`。 |

### 4.2 RouteCandidate

表示某个道岔或咽喉区可选的一条进路。

```json
{
  "routeId": "R-main-down",
  "name": "下行正线进路",
  "junctionId": "J-SW01",
  "fromSegmentId": "T01",
  "toSegmentId": "T02",
  "requiredSwitches": [
    {
      "switchId": "SW01",
      "position": "NORMAL"
    }
  ],
  "conflictSegmentIds": ["T01", "T02"],
  "estimatedTravelSeconds": 45
}
```

建议来源：

- `OperationalLineData.routes()`：进路定义。
- `OperationalLineData.switches()`：道岔 normal/reverse 对应区段。
- `TrackService.states()`：区段和拓扑状态。
- 后续可扩展 `config/dispatch-route-plan.yaml` 配置车次与 route 的对应关系。

### 4.3 RouteDispatchDecision

表示一次进路调度决策。

```json
{
  "decisionId": "RD-20260709-0001",
  "simulationRunId": "run-001",
  "junctionId": "J-SW01",
  "selectedTrainId": "TR-001",
  "selectedRouteId": "R-main-down",
  "waitingTrainIds": ["TR-002"],
  "priorityScore": 95.0,
  "reason": "TR-001 delayed by 90s; mainline priority",
  "status": "REQUESTED",
  "commandId": "CMD-a1b2c3d4",
  "rejectReason": null,
  "createdAt": "2026-07-09T10:00:00Z",
  "updatedAt": "2026-07-09T10:00:01Z"
}
```

状态定义：

| status | 说明 |
|---|---|
| `PLANNED` | 已计算出意图，尚未申请进路。 |
| `REQUESTED` | 已向联锁提交进路请求。 |
| `ACCEPTED` | 联锁接受，进路已建立或准备建立。 |
| `REJECTED` | 联锁拒绝，记录拒绝原因。 |
| `WAITING` | 目标列车或冲突列车等待条件满足。 |
| `RELEASED` | 进路已释放，决策结束。 |
| `CANCELLED` | 人工或策略撤销。 |

### 4.4 DispatchCommand 扩展

当前调度命令模型可以承载进路调度。建议在命令类型上扩展：

| commandType | 消费方 | 说明 |
|---|---|---|
| `REQUEST_ROUTE` | 信号/联锁 | 请求为列车建立指定进路。 |
| `REROUTE` | 信号/联锁 | 改路到指定进路或方向。 |
| `HOLD_BEFORE_JUNCTION` | 信号 | 在道岔保护区外扣停。 |
| `JUNCTION_SPEED_LIMIT` | 信号 | 接近道岔或冲突区限速。 |
| `HOLD` / `HOLD_TRAIN` | 信号 | 通用扣车。 |
| `SPEED_LIMIT` / `TEMP_SPEED_LIMIT` | 信号 | 临时限速。 |

进路请求命令示例：

```json
{
  "id": "CMD-a1b2c3d4",
  "trainId": "TR-001",
  "commandType": "REQUEST_ROUTE",
  "payload": {
    "routeId": "R-main-down",
    "junctionId": "J-SW01",
    "decisionId": "RD-20260709-0001"
  },
  "reason": "ROUTE_DISPATCH",
  "status": "PENDING",
  "createdAt": "2026-07-09T10:00:00Z",
  "appliedAt": null
}
```

等待命令示例：

```json
{
  "id": "CMD-wait-0001",
  "trainId": "TR-002",
  "commandType": "HOLD_BEFORE_JUNCTION",
  "payload": {
    "junctionId": "J-SW01",
    "stopBeforeMeters": 1180.0,
    "decisionId": "RD-20260709-0001",
    "selectedTrainId": "TR-001"
  },
  "reason": "ROUTE_CONFLICT_WAIT",
  "status": "PENDING",
  "createdAt": "2026-07-09T10:00:00Z",
  "appliedAt": null
}
```

---

## 5. 调度决策算法

### 5.1 调度窗口

只有进入道岔调度窗口的列车才参与进路调度，避免过早锁闭道岔。

建议判定条件：

```text
distanceToJunction <= routeDispatch.approachWindowMeters
或
etaToJunction <= routeDispatch.approachWindowSeconds
```

默认值：

| 配置项 | 默认值 | 说明 |
|---|---:|---|
| `routeDispatch.approachWindowMeters` | 500 | 进入道岔调度窗口的距离。 |
| `routeDispatch.approachWindowSeconds` | 60 | 进入道岔调度窗口的 ETA。 |
| `routeDispatch.conflictWindowSeconds` | 45 | 判定合流冲突的 ETA 时间窗。 |
| `routeDispatch.holdBeforeJunctionMeters` | 120 | 等待列车应停在道岔前的安全距离。 |

### 5.2 计划进路解析与异常改路

进路意图不是每 tick 临时选择道岔方向，而是先解析“当前列车下一条应办理的 routeId”。来源按优先级排序：

```text
1. 人工调度命令指定 routeId。
2. 故障/扰动策略指定替代 routeId。
3. 运行计划、车次、目的地或 service pattern 指定计划 routeId。
4. 默认 route，即当前方向的主线进路。
```

正常情况下，列车路线固定，调度只做计划进路解析和办理时机判断：

```text
TrainState + serviceNo/destination + route plan -> plannedRouteId
```

异常情况下，调度才会进入替代 route 选择：

```text
plannedRouteId rejected/unavailable
  -> lookup fallback routes
  -> choose operationally acceptable route
  -> submit REROUTE or REQUEST_ROUTE
```

若无法解析进路：

- 不生成 `REQUEST_ROUTE`。
- 记录 `RouteDispatchDecision.status=REJECTED`，`rejectReason=NO_ROUTE_INTENT`。
- 对列车不做进路干预，继续由信号自动进路或默认拓扑处理。

### 5.3 冲突组构建

两个 route 发生以下任一情况时认为冲突：

- `conflictSegmentIds` 有交集。
- 需要同一道岔处于不同位置。
- 目标区段已有其他列车占用或预留。
- 联锁状态中已有互斥 `ESTABLISHED` route。
- 该 route 与已建立 route 的 `axleSegmentIds` 有交集。

冲突组示例：

```json
{
  "junctionId": "J-SW01",
  "candidateTrainIds": ["TR-001", "TR-002"],
  "candidateRouteIds": ["R-main-down", "R-branch-down"],
  "conflictType": "SWITCH_POSITION_CONFLICT"
}
```

### 5.4 优先级排序

建议第一期采用规则优先，后续再引入分值。

规则顺序：

| 顺序 | 规则 | 说明 |
|---:|---|---|
| 1 | 安全临界优先 | 已进入保护区或制动距离不足的列车优先，避免急停。 |
| 2 | 已建立进路优先 | 避免取消已锁闭进路导致抖动。 |
| 3 | 晚点严重优先 | `departureDelaySec` 或运行偏差更大的列车优先。 |
| 4 | 主线优先 | 主线列车优先于支线、出入段线。 |
| 5 | 客流高优先 | `loadRate` 更高或拥挤扰动列车优先。 |
| 6 | ETA 早优先 | 预计更早到达冲突点的列车优先。 |
| 7 | 稳定 tie-break | 仍相同时按 `trainId` 或进入窗口时间固定排序，避免每 tick 改判。 |

后续可扩展分值：

```text
priorityScore =
  delayWeight * max(0, delaySec)
+ headwayWeight * abs(headwayDeviationSec)
+ loadWeight * loadRate
+ mainlineBonus
+ establishedRouteBonus
- switchThrowPenalty
- waitingTooLongPenalty
```

### 5.5 决策输出

对于冲突组：

```text
winner = selectPriorityTrain(conflictGroup)
issue REQUEST_ROUTE/REROUTE for winner

for loser in conflictGroup - winner:
  if loser is close to junction:
      issue HOLD_BEFORE_JUNCTION
  else:
      issue JUNCTION_SPEED_LIMIT
```

等待列车约束释放条件：

- 优先列车对应进路已 `RELEASED`。
- 目标 route 变为 `AVAILABLE`。
- 道岔不再被冲突 route 锁闭。
- 等待列车重新进入优先级排序，成为下一轮 winner。

### 5.6 防抖与稳定性

为避免进路反复切换：

- 已建立进路默认不取消。
- 同一 `trainId + junctionId` 在 `routeDispatch.cooldownSec` 内不重复生成相同请求。
- 若联锁拒绝，短时间内不重复申请同一 route，除非状态发生变化。
- 等待列车的 HOLD/SPEED_LIMIT 应随进路释放自动过期。
- 人工命令优先级高于自动策略，但仍必须通过联锁校验。

### 5.7 异常触发与重新分配

调度模块按 `evaluateIntervalMs` 周期性评估车辆、信号、轨道和进路状态，同时可消费事件总线中的异常事件。异常不只来自信号模块，轨道、车辆、供电和调度自身判断都可以触发重决策。

触发条件：

| 来源 | 条件 | 调度处理 |
|---|---|---|
| 信号/联锁 | `REQUEST_ROUTE` 被拒绝，或 MA 长时间不可用 | 记录拒绝原因，等待或尝试替代 route。 |
| 轨道 | 道岔故障、区段故障、目标区段占用超时 | 标记原 route 不可用，尝试替代 route 或 HOLD。 |
| 车辆 | 车辆故障、紧急制动、外部车辆服务 fallback | 采用保守策略，暂停激进改路或提速。 |
| 供电 | 牵引供电不可用、分区失电 | 生成 HOLD/SPEED_LIMIT，后续可扩展避开受影响区段。 |
| 调度 | route 等待超时、晚点扩大、headway 冲突 | 重新计算优先级，必要时改路或调整等待顺序。 |

重决策流程：

```text
1. 标记当前 RouteDispatchDecision 为 REJECTED/WAITING 或 NEEDS_REROUTE。
2. 记录 rejectReason 或 abnormalReason。
3. 查询替代 route candidates。
4. 若存在运营上可接受的替代 route，生成 REROUTE/REQUEST_ROUTE。
5. 若无替代 route，生成 HOLD 或 SPEED_LIMIT，使列车在保护区外等待。
6. 状态恢复后重新进入计划进路解析和冲突仲裁流程。
```

---

## 6. 与各模块交互

### 6.1 与信号/联锁模块

#### 调度读取

| 数据 | 来源 | 用途 |
|---|---|---|
| `MovementAuthority` | `SignalService.authorities()` | 判断列车当前授权距离和速度，辅助 ETA 与安全边界。 |
| `RouteState` | `RouteInterlockingService.states()` | 判断进路是否可用、已建立、冲突或被哪列车占用。 |
| `RouteDispatchResult` | `RouteInterlockingService.applyDispatchCommand(...)` | 获取联锁接受或拒绝结果。 |
| 信号/联锁异常事件 | 事件总线或状态快照 | 触发等待、扣车、限速或替代 route 选择。 |

#### 调度输出

| 输出 | 消费方 | 说明 |
|---|---|---|
| `REQUEST_ROUTE` | `RouteInterlockingService` | 请求建立指定 route。 |
| `REROUTE` | `RouteInterlockingService` | 请求改路或替代 route。 |
| `HOLD` / `SPEED_LIMIT` | `SignalService` | 由信号折算进 MA/速度授权。 |

#### 联锁职责

联锁必须负责：

- route 是否存在。
- route 是否已经被其他列车建立。
- route 与其他 `ESTABLISHED` route 是否冲突。
- 道岔是否锁闭。
- 道岔是否可转换到要求位置。
- 区段是否占用、预留或故障。
- 建立 route 后锁闭道岔。
- 列车通过后释放 route 并解锁道岔。

调度必须接受联锁拒绝，不得绕过。

#### 交互约束

- 调度发给信号/联锁的是 `routeId` 或进路请求，不是道岔 `NORMAL/REVERSE` 设备动作。
- 信号/联锁接受请求后，才可转换和锁闭道岔，并生成对应 MA。
- 信号/联锁拒绝请求时，必须返回可展示的 `rejectReason`，供调度记录和前端展示。
- 调度可根据 `rejectReason` 选择等待、限速、扣车或替代 route，但不能强制建立被拒绝进路。

### 6.2 与轨道模块

#### 调度读取

| 数据 | 来源 | 用途 |
|---|---|---|
| `TrackSegmentState` | `TrackService.states()` | 判断区段占用、故障、位置范围。 |
| `SwitchState` | `TrackService.switchStates()` | 展示道岔位置和锁闭状态，辅助 route 可用性判断。 |
| `TrackConstraint` | `TrackService.constraintsForTrains(...)` | 辅助限速和等待策略。 |

#### 轨道职责

轨道模块负责底层状态：

- 区段占用和故障。
- 道岔当前位置。
- 道岔是否锁闭。
- 道岔能否转换。
- 执行道岔转换和锁闭。

调度不得直接调用设备级道岔操作。若调度需要改变路径，只能向联锁申请 route。

### 6.3 与车辆/基层车辆控制模块

#### 车辆输入给调度

调度依赖统一 `TrainState`，不关心车辆控制逻辑在中央进程内还是外部服务。

| 字段 | 用途 |
|---|---|
| `id` / `serviceNo` | 识别列车和运行任务。 |
| `routeId` / `direction` / `linkId` | 解析线路、方向和拓扑位置。 |
| `positionMeters` | 判断接近道岔、构建前后车顺序。 |
| `speedMetersPerSecond` | 计算 ETA、判断是否可安全等待。 |
| `status` | 判断运行、停站、故障、紧急制动等状态。 |
| `loadRate` | 客流高优先和拥挤扰动依据。 |
| `currentStationId` / `dwellElapsedSeconds` | 与时间调度联动。 |
| `lastDepartureAt` | headway 和晚点判断。 |
| `dataQuality` / `faultCode` | 外部车辆服务降级或故障时采用保守策略。 |

建议车辆后续扩展字段：

```json
{
  "destinationStationId": "S05",
  "servicePattern": "MAINLINE",
  "nextPlannedRouteId": "R-main-down"
}
```

#### 调度对车辆的影响

调度不直接给车辆下发“往左/往右开”。路径选择通过信号 MA 体现。

| 调度意图 | 车辆感知方式 |
|---|---|
| 建立正线进路 | 信号开放正线方向 MA。 |
| 建立支线进路 | 信号开放支线方向 MA。 |
| 等待其他列车通过 | MA 速度被信号折算为 0 或较低限速。 |
| 临时限速 | `MovementAuthority.speedLimitMetersPerSecond` 降低。 |
| 停站调整 | 若车辆控制系统支持，接收 `DWELL_ADJUST` 类命令并返回执行反馈。 |

#### 与外部车辆控制服务的关系

组长后续将车辆控制拆成外部服务时，调度模块仍只依赖中央统一接口：

```text
外部车辆控制服务 -> 中央车辆适配层 -> TrainState -> DispatchService
DispatchService -> DispatchConstraint/DispatchCommand -> Signal/Interlocking/Vehicle Adapter
```

必须对齐：

- 外部服务输出的状态能还原完整 `TrainState`。
- 外部服务失败时有 `dataQuality=FALLBACK` 或类似字段。
- 停站调整类命令若由车辆执行，需要返回执行结果。
- 外部服务按中央 tick 的 `simTime` 和 `deltaSeconds` 运行，不自建真实时间节奏。

### 6.4 与供电模块

第一期道岔/进路调度不直接联动供电模块。

可选后续扩展：

- 供电分区失电或限功率时，调度避免将列车调入受影响区段。
- 供电故障产生 `POWER_CONSTRAINT` 扰动，调度生成 `HOLD` 或改路建议。
- 供电恢复后重新评估 route 可用性。

### 6.5 与仿真总控

仿真总控负责：

- 每 tick 调用调度评估。
- 保证进路调度、联锁办理、MA 重算和车辆推进顺序稳定。
- 将车辆、信号、轨道、供电的状态变化汇入统一快照或事件流，供调度周期评估。
- reset 时清空调度内存状态、route 决策缓存和命令队列。
- pause 时保持 route 和 switch 状态不自动变化。
- WebSocket 推送按 `simulation.pushIntervalMillis` 限流。

### 6.6 与前端/综合监控

前端展示建议分两层：

1. 真实状态：复用已有 `routeStates` 和 `switchStates`。
2. 调度决策：在 `dispatch` 段新增 `routeDecisions`。

示例：

```json
{
  "dispatch": {
    "routeDecisions": [
      {
        "decisionId": "RD-20260709-0001",
        "junctionId": "J-SW01",
        "selectedTrainId": "TR-001",
        "selectedRouteId": "R-main-down",
        "waitingTrainIds": ["TR-002"],
        "reason": "TR-001 delayed by 90s; mainline priority",
        "status": "ACCEPTED",
        "rejectReason": null
      }
    ]
  },
  "routeStates": [
    {
      "routeId": "R-main-down",
      "status": "ESTABLISHED",
      "establishedByTrainId": "TR-001",
      "lockedSwitchIds": ["SW01"],
      "axleSegmentIds": ["T01", "T02"]
    }
  ],
  "switchStates": [
    {
      "id": "SW01",
      "position": "NORMAL",
      "locked": true
    }
  ]
}
```

---

## 7. API 与快照设计

### 7.1 REST 接口建议

第一期可复用 `POST /api/dispatch/commands` 人工提交 route 命令：

```http
POST /api/dispatch/commands
Content-Type: application/json
```

请求：

```json
{
  "trainId": "TR-001",
  "commandType": "REROUTE",
  "detail": "R-main-down"
}
```

后续建议结构化：

```json
{
  "trainId": "TR-001",
  "commandType": "REQUEST_ROUTE",
  "payload": {
    "routeId": "R-main-down",
    "junctionId": "J-SW01"
  }
}
```

新增查询接口建议：

```http
GET /api/dispatch/route-decisions
GET /api/dispatch/route-decisions/current
```

响应：

```json
[
  {
    "decisionId": "RD-20260709-0001",
    "simulationRunId": "run-001",
    "junctionId": "J-SW01",
    "selectedTrainId": "TR-001",
    "selectedRouteId": "R-main-down",
    "waitingTrainIds": ["TR-002"],
    "priorityScore": 95.0,
    "reason": "TR-001 delayed by 90s; mainline priority",
    "status": "ACCEPTED",
    "commandId": "CMD-a1b2c3d4",
    "rejectReason": null,
    "createdAt": "2026-07-09T10:00:00Z",
    "updatedAt": "2026-07-09T10:00:01Z"
  }
]
```

### 7.2 WebSocket 扩展

现有地址保持：

```text
ws://localhost:8080/ws/simulation
```

建议在 `payload.dispatch` 中增加：

```json
{
  "routeDispatchActive": true,
  "routeDecisions": [
    {
      "decisionId": "RD-20260709-0001",
      "junctionId": "J-SW01",
      "selectedTrainId": "TR-001",
      "selectedRouteId": "R-main-down",
      "waitingTrainIds": ["TR-002"],
      "status": "ACCEPTED",
      "reason": "TR-001 delayed by 90s; mainline priority",
      "rejectReason": null
    }
  ]
}
```

外层继续保留：

- `routeStates`：信号联锁真实进路状态。
- `switchStates`：轨道道岔真实状态。
- `authorities`：信号 MA 结果。

---

## 8. 数据库设计建议

第一期可继续使用内存仓储。若需要历史追溯，建议新增表：

```sql
CREATE TABLE IF NOT EXISTS dispatch_route_decision_record (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  simulation_run_id VARCHAR(64) NOT NULL,
  decision_id VARCHAR(64) NOT NULL,
  junction_id VARCHAR(64) NOT NULL,
  selected_train_id VARCHAR(64) NOT NULL,
  selected_route_id VARCHAR(64) NOT NULL,
  waiting_train_ids_json TEXT NULL,
  priority_score DOUBLE DEFAULT 0,
  decision_reason VARCHAR(255) NULL,
  status VARCHAR(32) NOT NULL,
  command_id VARCHAR(64) NULL,
  reject_reason VARCHAR(255) NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_drd_run_time (simulation_run_id, created_at),
  INDEX idx_drd_train (selected_train_id, created_at),
  INDEX idx_drd_route (selected_route_id, created_at),
  UNIQUE KEY uk_drd_decision (decision_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

与现有表关系：

| 表 | 关系 |
|---|---|
| `dispatch_command_record` | `command_id` 关联进路请求、扣车或限速命令。 |
| `disturbance_record` | 进路调度可能由故障、拥挤、晚点等扰动触发。 |
| `train_station_record` | 用于分析进路决策对到发偏差的影响。 |
| `operation_log` | 人工进路调整可记录审计日志。 |

---

## 9. 异常与降级处理

### 9.1 联锁拒绝

联锁拒绝原因示例：

| rejectReason | 说明 | 调度处理 |
|---|---|---|
| `ROUTE_NOT_FOUND` | 目标进路不存在。 | 记录失败，回退默认 route 或人工处理。 |
| `ROUTE_CONFLICT` | 与已建立进路冲突。 | 对本车或冲突车生成等待约束。 |
| `SWITCH_LOCKED` | 目标道岔已锁闭。 | 等待当前进路释放。 |
| `SWITCH_CANNOT_THROW` | 道岔因占用/故障不可转换。 | HOLD 并上报异常。 |
| `SEGMENT_OCCUPIED` | 目标区段占用。 | HOLD 或 SPEED_LIMIT。 |
| `SEGMENT_FAULT` | 目标区段故障。 | 尝试替代进路或保持扣车。 |

### 9.2 车辆状态不可用

当 `TrainState.dataQuality=FALLBACK/INVALID` 或车辆外部服务失联：

- 不生成激进改路和提速策略。
- 允许保守的 `HOLD` 和 `SPEED_LIMIT`。
- 在 route decision 中记录 `reason=TRAIN_STATE_FALLBACK`。
- 前端展示“车辆状态降级，进路调度保守运行”。

### 9.3 道岔状态不可用

当 `SwitchState` 缺失或轨道拓扑不完整：

- 不主动申请需要该道岔的 route。
- 记录 `REJECTED` 或 `WAITING`。
- 保持列车在保护区外等待。

### 9.4 Reset / Pause

Reset：

- 清空 route decision 内存缓存。
- 清空待执行 route 命令。
- 调用联锁 reset，释放 route 和道岔锁闭状态。
- 新建 simulationRunId。

Pause：

- 不推进调度评估。
- 保持已建立 route 和 switch 状态。
- 前端继续展示当前状态。

---

## 10. 测试与验收

### 10.1 单元测试

| 测试 | 说明 |
|---|---|
| 进路意图解析 | 根据 service pattern/destination/manual command 解析 routeId。 |
| 冲突组构建 | 两 route 共用区段或同一道岔不同位置时识别冲突。 |
| 优先级选择 | 晚点、主线、ETA、载客率规则排序正确。 |
| 冷却防抖 | 同一 train/junction 不重复生成相同命令。 |
| 联锁拒绝处理 | 拒绝后 route decision 状态和等待命令正确。 |

### 10.2 集成测试

| 场景 | 验收 |
|---|---|
| 分岔 | TR-001 走正线，TR-002 走支线，联锁建立对应 route。 |
| 合流 | 两车接近合流点，只允许优先车进路建立，另一车等待。 |
| 进路释放 | 优先车通过后 route 释放，等待车可继续申请。 |
| 道岔锁闭 | route 建立后 switch locked=true，释放后 unlocked。 |
| 联锁拒绝 | 冲突 route 被拒绝，调度记录 rejectReason 并生成 HOLD。 |

### 10.3 演示场景

推荐演示：

```text
1. TR-001 与 TR-002 同时接近 SW01。
2. TR-001 目标正线 R-main-down，TR-002 目标支线 R-branch-down。
3. 两 route 共享 SW01，形成冲突组。
4. 调度根据晚点或主线优先选择 TR-001。
5. TR-001 route 建立，SW01 转到 NORMAL 并锁闭。
6. TR-002 收到 HOLD_BEFORE_JUNCTION 或 SPEED_LIMIT。
7. TR-001 通过，route 释放，SW01 解锁。
8. 调度为 TR-002 申请 R-branch-down，SW01 转到 REVERSE 并锁闭。
9. 前端显示 route decision、routeStates、switchStates 和 activeCommands。
```

---

## 11. 实施计划

### P0：设计和文档

- 明确调度、信号联锁、轨道、车辆边界。
- 更新 API 文档，补充 route decision 和 route command。
- 确认现有 `RouteInterlockingService` 能支持 routeId 结构化申请。

### P1：人工进路请求入口与联锁链路验证

- 支持人工提交 `REQUEST_ROUTE` 或结构化 `REROUTE`，用于联调、演示、应急和覆盖自动策略。
- 联锁返回接受/拒绝结果。
- 调度记录 `RouteDispatchDecision`。
- 前端能展示 route decision 和 rejectReason。
- 说明：该阶段不代表正式运营需要人工逐车确认；常规进路最终由调度按计划自动申请。

### P2：计划进路自动解析与申请

- 根据 `serviceNo`、`destinationStationId`、运行计划或配置映射计划 route。
- 列车接近进路办理窗口时自动申请目标 route。
- 同一列车同一路口增加冷却，避免重复申请。
- 调度周期性跟踪 route 是否建立、MA 是否开放、列车是否按计划通过。

### P3：合流/咽喉冲突仲裁

- 构建冲突组。
- 按优先级选择 winner。
- 对 loser 生成 `HOLD_BEFORE_JUNCTION` 或 `JUNCTION_SPEED_LIMIT`。
- route 释放后自动进入下一轮申请。

### P4：扰动联动改路

- 信号/联锁拒绝、轨道故障、车辆异常、供电约束或晚点严重时尝试替代 route。
- 若联锁拒绝，回退 HOLD。
- 与调度页面、综合监控、历史记录联调。

---

## 12. 风险与注意事项

| 风险 | 影响 | 应对 |
|---|---|---|
| 调度越权扳道岔 | 破坏联锁安全边界 | 调度只发 route command，不调用轨道设备操作。 |
| routeId 不稳定 | 人工命令和配置难以维护 | routeId 统一由线路配置定义，并在 API 文档中固定。 |
| 车辆外部服务状态不完整 | 调度无法计算 ETA、停站和优先级 | 要求中央统一补齐 TrainState。 |
| 频繁改判 | 前端抖动、进路反复申请 | 引入冷却、已建立进路优先和稳定 tie-break。 |
| 联锁拒绝原因不可见 | 调试困难 | RouteDispatchDecision 必须记录 rejectReason。 |
| 道岔保护区定义缺失 | 等待位置不准确 | 第一版用固定 `holdBeforeJunctionMeters`，后续由线路配置补充。 |

---

## 13. 结论

道岔调度不应设计为调度模块直接控制道岔，而应设计为运营层的目标进路决策、状态评估、异常改路和冲突仲裁能力。正常情况下，列车路线由运行计划、车次、目的地或 service pattern 固定，调度负责解析计划 route 并在合适窗口向信号/联锁申请办理；信号/联锁负责检查安全条件、建立进路、锁闭道岔并生成 MA；轨道模块负责实际区段和道岔状态；车辆模块只按最终信号授权和车载控制逻辑运行。

第一期建议以“人工进路请求入口 + 联锁链路验证 + 调度状态跟踪”为最小闭环，随后扩展到计划进路自动申请、合流/咽喉优先级仲裁和异常改路。该设计能与现有时间维度调度自然结合，并为后续外部车辆控制服务拆分保持清晰边界。

---

**文档结束**
