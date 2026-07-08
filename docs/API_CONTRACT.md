# 接口契约

接口先保持小而稳定。每个成员开发模块时，优先遵守这里的对象结构，减少互相等待。

## REST

### 获取仿真快照

```http
GET /api/simulation/snapshot
```

### 控制仿真

```http
POST /api/simulation/start
POST /api/simulation/pause
POST /api/simulation/reset
POST /api/simulation/tick
```

### 获取列车状态

```http
GET /api/trains
GET /api/trains/{trainId}
GET /api/trains/{trainId}/energy
GET /api/trains/{trainId}/faults
```

### 外部车辆控制会话接入/退出

中央系统只接收外部车辆控制子系统的接入/退出语义，不负责启动车辆控制系统本体，也不替信号系统生成具体行车命令。

```http
POST /api/trains/lifecycle
```

请求：

```json
{
  "action": "ADD",
  "trains": [
    {
      "trainNo": 3,
      "linkId": 12,
      "offsetMeters": 640.0,
      "direction": "DOWN"
    }
  ],
  "reason": "external train control attached",
  "operator": "signal",
  "confirmToken": "SIMULATION_CONFIRM",
  "traceId": "signal-train-003"
}
```

`action` 支持：

| action | 对应协议指令 | 作用 |
|---|---|---|
| `ADD` | `0x01` | 建立中央侧外部车辆控制会话，并进入信号网/电网并入流程。 |
| `DELETE` | `0x02` | 请求指定列车退出信号网和电网，完成退出后清理本地列车实体。 |
| `CLEAR` | `0x04` | 请求所有列车退出。 |

协议二进制模型在 `SignalTrainLifecycleCommandCodec` 中落地：包头 `0xff 0xf1`，小端，ADD 每车包含列车号、link ID、偏移、方向和保留字节，DELETE 每车只包含列车号。

中央处理顺序：`TrainController` 转换请求 -> `TrainManager.applyLifecycleCommand()` -> `TrainManager.addTrain()` -> `OnboardTrainSubsystemManager.register()` -> 后续 tick 按信号和供电约束推进会话状态。

### 车辆-信号接口

该接口族只服务车辆系统与信号系统：车辆侧上报安全状态和运行遥测，信号侧输出 MA/限速/制动命令投影。车辆-调度不是本接口族；调度命令必须先由信号/ATS 折算。

```http
GET /api/signal/vehicles/statuses
GET /api/signal/vehicles/commands
POST /api/signal/vehicles/telemetry
POST /api/signal/vehicles/telemetry/content-packet?trainCount=1
```

`POST /api/signal/vehicles/telemetry` 使用 JSON：

```json
[
  {
    "trainNo": 1,
    "speedMetersPerSecond": 12.34,
    "cumulativeDistanceMeters": 987.65,
    "direction": "DOWN",
    "loadMassKg": 86400,
    "faultSpeedLimitMetersPerSecond": 2.0,
    "emergencyBrakeApplied": true,
    "availableTractionCount": 4,
    "availableBrakeCount": 5
  }
]
```

`content-packet` 使用 `application/octet-stream` 和 `SignalTrainContentCodec` 小端包：每车 18 字节，包含列车号、速度、累计里程、方向、载重、车辆故障限速、紧急制动、可用牵引单元数和可用制动单元数。

`GET /api/signal/vehicles/commands` 将当前 `MovementAuthority` 和车辆状态投影为 `SignalVehicleCommand`：

| 场景 | 输出 |
|---|---|
| 正常 MA | 输出 MA 终点和授权速度。 |
| 无 MA 或 MA 耗尽 | 牵引切除、常用制动、紧急制动。 |
| 未并入车辆控制会话 | 牵引切除、常用制动，不进入紧急制动。 |
| 车辆故障限速 | 授权速度取 `min(MA限速, vehicleFaultSpeedLimitMetersPerSecond)`。 |

### 获取供电状态

```http
GET /api/power/sections
GET /api/power/sections/{sectionId}
GET /api/power/sections/{sectionId}/events
GET /api/power/energy
GET /api/power/maintenance-locks
GET /api/power/substations
GET /api/power/substations/{substationId}/devices
GET /api/power/isolators
GET /api/power/stray-current
GET /api/power/external-health
```

这些接口是其他模块访问中央供电控制模块的统一入口。外部供电仿真系统只由中央 `PowerIntegrationService` 调用，综合监控、车辆、调度、维修/能耗模块不得直接访问外部供电仿真服务。

`PowerSectionState` 在分区电压、电流、负荷、保护和检修状态之外，已扩展：

| 字段 | 含义 |
|---|---|
| `supplyMode` | `DOUBLE_END`、`SINGLE_END`、`CROSS_FEED`、`OUTAGE` 等供电方式。 |
| `isolatorStatus` | 供电分区边界隔离开关汇总状态。 |
| `substationAvailability` | 关联牵引变电所可用性。 |
| `externalDataQuality` | 供电设备级状态来源质量，外部供电仿真失联时为 `FALLBACK`。 |
| `strayCurrentRiskLevel` | 杂散电流风险等级：`NORMAL`、`ATTENTION`、`WARNING`、`CRITICAL`。 |
| `strayCurrentRiskReason` | 杂散电流风险原因。 |

### 能耗和维修预留

```http
GET /api/energy/trains
GET /api/energy/power-sections
GET /api/vehicle/maintenance-states
GET /api/vehicle/onboard-subsystems
```

`GET /api/vehicle/onboard-subsystems` 返回中央侧纳管的单车基层智能子系统节点状态，用于查看本地/外部车辆控制节点是否在线、是否 fallback、租约是否仍有效。该接口只读，不用于调度越级控车。

### 仿真故障注入

仿真写接口只用于演示和联调，必须带二次确认字段。

```http
POST /api/power/sections/{sectionId}/faults
POST /api/power/sections/{sectionId}/faults/clear
POST /api/power/operations
POST /api/trains/{trainId}/faults
POST /api/trains/{trainId}/faults/clear
```

请求：

```json
{
  "faultType": "UNDERVOLTAGE",
  "reason": "demo",
  "operator": "simulation",
  "confirmToken": "SIMULATION_CONFIRM",
  "traceId": "trace-001"
}
```

实现约束：

- 写接口会记录 `operation_log`；供电写接口同时记录 `power_operation_log`。
- 车辆故障注入会立即影响 `TrainState` 中的门、牵引、制动、受流、自检和故障等级字段。
- 供电故障注入会立即刷新 `PowerSectionState`，清除故障不会绕过 `maintenanceState` 或 `lockoutState`。
- `/api/power/operations` 是中央供电控制模块的设备级操作入口，用于隔离开关、变电所设备、排流柜等仿真操作；调用方仍是中央 REST，不直接访问外部供电仿真系统。
- 调度策略只消费状态和影响范围，不由供电/车辆故障接口直接下发扣车或折返。

### 调度模块

```http
GET /api/dispatch/plan
GET /api/dispatch/plan/current
GET /api/dispatch/status
GET /api/dispatch/disturbances
GET /api/dispatch/commands
POST /api/dispatch/commands
GET /api/dispatch/station-records
```

调度模块位于综合层，读取车辆运行状态和信号授权状态，生成运营调度意图。调度不直接生成牵引/制动命令；扣车、限速和速度比例类调度约束由信号模块折算为 MA/限速后再进入车辆控制链路。

#### 查询运行计划

```http
GET /api/dispatch/plan
```

响应：

```json
{
  "planId": "RP-demo-001",
  "lineId": "demo-line-1",
  "periods": [
    {
      "periodType": "PEAK",
      "start": "07:00",
      "end": "09:00",
      "departureIntervalSec": 180,
      "defaultDwellTimeSec": 30
    },
    {
      "periodType": "FLAT",
      "start": "09:00",
      "end": "17:00",
      "departureIntervalSec": 300,
      "defaultDwellTimeSec": 25
    }
  ]
}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `planId` | string | 运行计划 ID。 |
| `lineId` | string | 线路 ID。 |
| `periods` | array | 分时段运行计划。 |
| `periodType` | string | `PEAK`、`FLAT`、`OFF_PEAK`。 |
| `start` / `end` | string | 时段起止，格式 `HH:mm`。跨午夜时 `start > end`。 |
| `departureIntervalSec` | number | 目标发车间隔，单位秒。 |
| `defaultDwellTimeSec` | number | 默认停站时间，单位秒。 |

#### 查询当前生效计划

```http
GET /api/dispatch/plan/current
```

响应：

```json
{
  "planId": "RP-demo-001",
  "lineId": "demo-line-1",
  "periodType": "FLAT",
  "departureIntervalSec": 300,
  "defaultDwellTimeSec": 25,
  "resolvedAt": "2026-07-08T13:00:00Z"
}
```

#### 查询调度状态快照

```http
GET /api/dispatch/status
```

响应结构与 WebSocket `payload.dispatch` 一致：

```json
{
  "runMode": "FLAT",
  "planId": "RP-demo-001",
  "targetHeadwaySeconds": 300,
  "defaultDwellSeconds": 25,
  "interventionActive": false,
  "trainProfiles": [
    {
      "trainId": "TR-001",
      "headwayActualSeconds": null,
      "headwayDeviationSeconds": 0,
      "dwellDeviationSeconds": 0
    }
  ],
  "openDisturbances": [],
  "activeCommands": []
}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `runMode` | string | 当前运行模式。 |
| `targetHeadwaySeconds` | number | 当前目标行车间隔。 |
| `defaultDwellSeconds` | number | 当前默认停站时间。 |
| `interventionActive` | boolean | 是否存在开放扰动或活跃调度命令。 |
| `trainProfiles` | array | 列车运行偏差摘要。 |
| `openDisturbances` | array | 当前开放或已处理但未恢复的扰动。 |
| `activeCommands` | array | 当前 `PENDING` 或 `APPLIED` 的命令摘要。 |

#### 查询扰动列表

```http
GET /api/dispatch/disturbances
```

响应：

```json
[
  {
    "id": "DIST-a1b2c3d4",
    "simulationRunId": "run-001",
    "trainId": "TR-002",
    "stationId": "S02",
    "disturbanceType": "DWELL_EXTENDED",
    "deviationValue": 18.0,
    "status": "OPEN",
    "recordedAt": "2026-07-08T13:00:00Z",
    "resolvedAt": null,
    "commandId": null
  }
]
```

扰动类型：

| disturbanceType | 说明 |
|---|---|
| `DWELL_EXTENDED` | 停站超时。 |
| `DEPARTURE_DELAY` | 发车延误。 |
| `HEADWAY_SHRINK` | 行车间隔过小。 |
| `HEADWAY_EXPAND` | 行车间隔过大。 |
| `CROWDING` | 客流拥挤。 |

扰动状态：

| status | 说明 |
|---|---|
| `OPEN` | 已识别，待处理。 |
| `HANDLED` | 已关联调度命令。 |
| `RECOVERED` | 偏差已恢复。 |

#### 查询调度命令

```http
GET /api/dispatch/commands
```

响应：

```json
[
  {
    "id": "CMD-a1b2c3d4",
    "trainId": "TR-003",
    "commandType": "SHORTEN_DWELL",
    "payload": {
      "deltaDwellSec": -5,
      "simulationRunId": "run-001",
      "disturbanceId": "DIST-a1b2c3d4"
    },
    "reason": "DWELL_EXTENDED",
    "status": "PENDING",
    "createdAt": "2026-07-08T13:00:00Z",
    "appliedAt": null
  }
]
```

命令状态：

| status | 说明 |
|---|---|
| `PENDING` | 等待执行。 |
| `APPLIED` | 已应用。 |
| `EXPIRED` | 已过期。 |
| `SKIPPED` | 被安全校验跳过。 |
| `CANCELLED` | 被撤销。 |

自动策略命令：

| commandType | payload | 说明 |
|---|---|---|
| `EXTEND_DWELL` | `{ "deltaDwellSec": 5 }` | 延长停站。 |
| `SHORTEN_DWELL` | `{ "deltaDwellSec": -5 }` | 缩短停站。 |
| `SPEED_BIAS` | `{ "speedBiasRatio": 1.05 }` | 区间运行速度比例建议。 |
| `HEADWAY_ADJUST` | `{ "targetHeadwaySec": 153 }` | 目标间隔调整建议，不重构运行图。 |

#### 提交人工调度命令

```http
POST /api/dispatch/commands
Content-Type: application/json
```

请求：

```json
{
  "trainId": "TR-001",
  "commandType": "SPEED_LIMIT",
  "detail": "8.0"
}
```

响应：

```json
{
  "id": "DC-550e8400-e29b-41d4-a716-446655440000",
  "trainId": "TR-001",
  "commandType": "SPEED_LIMIT",
  "payload": {
    "detail": "8.0"
  },
  "reason": "MANUAL",
  "status": "PENDING",
  "createdAt": "2026-07-08T13:00:00Z",
  "appliedAt": null
}
```

人工命令：

| commandType | detail | 作用 |
|---|---|---|
| `HOLD` / `HOLD_TRAIN` | 任意说明文本 | 调度服务记录扣车意图，由信号模块折算为 MA/限速或后续 `SignalVehicleCommand` |
| `SPEED_LIMIT` / `TEMP_SPEED_LIMIT` | 速度上限，单位 m/s | 由信号模块与轨道限速、安全距离共同计算后下发给车辆 |
| `SPEED_FACTOR` / `LIMIT_FACTOR` | 0-1 比例 | 由信号模块折算速度授权，不由车辆直接消费 |

#### 查询到发记录

```http
GET /api/dispatch/station-records
```

响应：

```json
[
  {
    "simulationRunId": "run-001",
    "trainId": "TR-001",
    "lineId": "demo-line-1",
    "stationId": "S02",
    "eventType": "ARRIVAL",
    "simulatedAt": "2026-07-08T13:00:00Z",
    "plannedTime": "2026-07-08T12:59:45Z",
    "delaySec": 15
  }
]
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `eventType` | string | `ARRIVAL` 或 `DEPARTURE`。 |
| `plannedTime` | string/null | 计划到达或计划发车时间。 |
| `delaySec` | number | 实际事件时刻相对计划时刻的偏差，单位秒。 |

当前实现说明：

- 调度历史记录当前使用内存仓储，后端重启后会清空；后续可替换为 JDBC/MySQL 存储。
- 自动调度命令中的停站调整类命令已进入调度命令模型，但车辆侧完整执行闭环仍需与车辆模块继续联调。
- 调度约束不会绕过信号模块；最终速度授权仍由 `SignalService.calculateAuthorities(...)` 综合轨道、联锁、MA 和调度约束计算。

## 内部 FMU 服务接口

外部 Python FMU 服务默认关闭。启用 `railway.simulation.fmu-service-enabled=true` 后，Spring Boot 会在每个仿真步长批量调用：

```http
POST http://localhost:9000/step-fleet
```

请求：

```json
{
  "simTime": "2026-07-07T10:00:00Z",
  "deltaSeconds": 0.1,
  "trains": [
    {
      "trainId": "TR-001",
      "positionMeters": 100.0,
      "speedMetersPerSecond": 12.5,
      "trainMassKg": 230000.0,
      "tractionCommand": 0.6,
      "brakeCommand": 0.0,
      "emergencyBrakeCommand": false,
      "speedLimitMetersPerSecond": 20.0,
      "movementAuthorityDistanceMeters": 900.0,
      "gradient": 0.0,
      "curveRadiusMeters": 1000.0,
      "railVoltage": 1500.0,
      "powerAvailableWatts": 3000000.0,
      "doorClosed": true,
      "adhesionCoefficient": 0.9,
      "previousEnergyConsumedKwh": 0.0,
      "previousEnergyRegeneratedKwh": 0.0,
      "deltaSeconds": 0.1,
      "dynamicsState": "ACCELERATING",
      "dynamicsConstraintReason": "SPEED_MARGIN_AVAILABLE",
      "stationDistanceMeters": 420.0,
      "stoppingDistanceMeters": 86.8
    }
  ]
}
```

响应：

```json
{
  "trainOutputs": [
    {
      "trainId": "TR-001",
      "newPositionMeters": 101.3,
      "newSpeedMetersPerSecond": 12.8,
      "accelerationMetersPerSecondSquared": 0.5,
      "tractionForceNewtons": 120000.0,
      "brakeForceNewtons": 0.0,
      "regenBrakeForceNewtons": 0.0,
      "tractionPowerWatts": 1740000.0,
      "railCurrentAmps": 1160.0,
      "regenPowerWatts": 0.0,
      "energyConsumedKwh": 0.05,
      "energyRegeneratedKwh": 0.0,
      "faultCode": "OK"
    }
  ]
}
```

## 外部车辆仿真协议适配

该协议不是面向前端或调度系统的 REST 接口，而是后端 `VehiclePhysicsClient.stepFleet()` 背后的车辆物理端口实现。主系统通过 `OnboardTrainSubsystemManager` 汇总信号、轨道、供电约束后生成车辆控制输入；调度约束先由信号模块折算为 MA/限速或后续 `SignalVehicleCommand`。

中央到车辆控制节点的调用通过 `railway.simulation.onboard-subsystem-mode` 切换：

| mode | 行为 |
|---|---|
| `IN_PROCESS` | 使用进程内 `OnboardTrainSubsystem`，默认模式。 |
| `EXTERNAL_HTTP` | 通过 `HttpOnboardTrainSubsystemClient` 调用外部车辆控制节点，失败时本地 fallback。 |
| `DUAL_SHADOW` | 本地输出为权威，外部节点只做影子在线验证。 |

配置：

```yaml
railway.simulation.external-simulator.mode: LOCAL
```

可选值：

| mode | 行为 |
|---|---|
| `LOCAL` | 使用本地 `SimpleVehicleDynamicsModel`；兼容旧 HTTP FMU 开关。 |
| `EXTERNAL_UDP` | 按协议 UDP 小端报文发送 1-20 车 `command + percent`，接收加速度、速度、累计里程。 |
| `EXTERNAL_RTLAB_API` | 通过 RT-LAB API 变量路径写入列车编号、指令、`segNo`、偏移、方向、激活端；当前用 stub 保持可运行。 |
| `DUAL_SHADOW` | 本地模型输出作为权威，外部适配器结果仅做误差比对。 |

外部车辆控制映射：

| 内部字段 | 外部字段 |
|---|---|
| `tractionCommand > 0` | `command=1`，`percent=round(tractionCommand*100)` |
| `brakeCommand > 0` | `command=2`，`percent=round(brakeCommand*100)` |
| `emergencyBrakeCommand=true` | `command=2`，`percent=100` |
| 无牵引/制动 | `command=0`，`percent=0` |

失败处理：

- UDP 超时、API 写入失败或外部返回缺车时，后端降级到本地车辆模型。
- 降级输出 `faultCode=EXTERNAL_SIM_FALLBACK`，车辆状态 `dataQuality=FALLBACK`。
- 供电状态仍以 `PowerService` 为准；当前协议不作为 PSCADA 供电遥测接口使用。

## WebSocket

地址：

```text
ws://localhost:8080/ws/simulation
```

消息类型：

```json
{
  "type": "snapshot",
  "payload": {
    "tick": 1,
    "status": "RUNNING",
    "trains": [],
    "trackSegments": [],
    "authorities": [],
    "powerSections": [],
    "alarms": [],
    "dispatch": {
      "runMode": "PEAK",
      "planId": "RP-demo-001",
      "targetHeadwaySeconds": 180,
      "defaultDwellSeconds": 30,
      "interventionActive": false,
      "trainProfiles": [],
      "openDisturbances": [],
      "activeCommands": []
    }
  }
}
```

## 核心对象

### TrainState

```json
{
  "id": "TR-001",
  "routeId": "demo-line-1",
  "serviceNo": "T001",
  "controlSessionState": "IN_SERVICE",
  "signalNetworkStatus": "ATTACHED",
  "powerNetworkStatus": "ATTACHED",
  "controlSessionReason": "EXTERNAL_CONTROL_IN_SERVICE",
  "linkId": 12,
  "direction": "DOWN",
  "positionMeters": 120.0,
  "speedMetersPerSecond": 12.5,
  "lengthMeters": 120.0,
  "headMileage": 120.0,
  "tailMileage": 0.0,
  "loadRate": 0.62,
  "loadMassKg": 44640.0,
  "overloadStatus": "NORMAL",
  "availableTractionCount": 6,
  "availableBrakeCount": 6,
  "vehicleProtectionReason": "NONE",
  "status": "RUNNING",
  "operationMode": "ATO",
  "zeroSpeed": false,
  "doorState": "CLOSED_LOCKED",
  "tractionState": "APPLYING",
  "brakeState": "RELEASED",
  "currentCollectionStatus": "NORMAL",
  "tractionAvailable": true,
  "brakeAvailable": true,
  "selfCheckStatus": "PASS",
  "faultLevel": 0,
  "availableOperationMode": "NORMAL",
  "dataQuality": "GOOD",
  "dynamicsState": "ACCELERATING",
  "dynamicsConstraintReason": "SPEED_MARGIN_AVAILABLE",
  "speedLimitMetersPerSecond": 20.0,
  "movementAuthorityDistanceMeters": 900.0,
  "stationDistanceMeters": 420.0,
  "stoppingDistanceMeters": 86.8,
  "accelerationMetersPerSecondSquared": 0.6,
  "tractionForceNewtons": 120000.0,
  "brakeForceNewtons": 0.0,
  "regenBrakeForceNewtons": 0.0,
  "railCurrentAmps": 420.0,
  "tractionPowerWatts": 630000.0,
  "regenPowerWatts": 0.0,
  "energyConsumedKwh": 0.12,
  "energyRegeneratedKwh": 0.0,
  "faultCode": "OK",
  "currentStationId": "S02",
  "dwellElapsedSeconds": 12,
  "lastDepartureAt": "2026-07-07T10:00:00Z"
}
```

### TrackSegment

```json
{
  "id": "T01",
  "startMeters": 0,
  "endMeters": 1250,
  "speedLimitMetersPerSecond": 20,
  "occupancy": "OCCUPIED"
}
```

### MovementAuthority

```json
{
  "trainId": "T001",
  "authorityEndMeters": 1000.0,
  "speedLimitMetersPerSecond": 20.0,
  "reason": "前方区段空闲"
}
```

### Alarm

```json
{
  "id": "ALM-1",
  "sourceModule": "train",
  "locationRef": "T001",
  "level": 2,
  "title": "列车拥挤",
  "detail": "满载率超过 80%",
  "raisedAt": "2026-07-07T10:00:00Z",
  "confirmed": false
}
```

### PowerSectionState

```json
{
  "id": "P01",
  "name": "南段供电分区",
  "substationId": "SS01",
  "feederId": "F01",
  "startMeters": 0.0,
  "endMeters": 2500.0,
  "voltage": 1500.0,
  "current": 420.0,
  "status": "ENERGIZED",
  "loadWatts": 630000.0,
  "regenPowerWatts": 0.0,
  "absorbedRegenPowerWatts": 0.0,
  "unabsorbedRegenPowerWatts": 0.0,
  "availablePowerWatts": 3000000.0,
  "breakerStatus": "CLOSED",
  "protectionState": "NORMAL",
  "maintenanceState": "NONE",
  "lockoutState": "UNLOCKED",
  "affectedTrainIds": ["TR-001"],
  "dataQuality": "GOOD",
  "updatedAt": "2026-07-07T10:00:00Z"
}
```

### EnergyResponse

列车能耗：

```json
{
  "trainId": "TR-001",
  "energyConsumedKwh": 0.12,
  "energyRegeneratedKwh": 0.02,
  "netEnergyKwh": 0.10,
  "statisticsWindow": "CURRENT_SIMULATION",
  "dataQuality": "GOOD",
  "updatedAt": "2026-07-07T10:00:00Z"
}
```

供电能耗：

```json
{
  "totalLoadWatts": 630000.0,
  "totalRegenPowerWatts": 100000.0,
  "totalAbsorbedRegenPowerWatts": 80000.0,
  "totalUnabsorbedRegenPowerWatts": 20000.0,
  "statisticsWindow": "CURRENT_SIMULATION",
  "dataQuality": "GOOD",
  "updatedAt": "2026-07-07T10:00:00Z",
  "sections": []
}
```

## 变更规则

- 新增字段可以，但不要随意改名或删除已有字段。
- 如果必须改接口，先在群里说明影响范围，再改 `docs/API_CONTRACT.md`。
- 前端和后端共用的概念，先在文档里定名，再进入代码。
