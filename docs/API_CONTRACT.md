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

推荐启动流程由外部车辆运行时发起：`vehicle-runtime-service:9300` 先启动车辆仿真实例并唤醒车辆控制队列，再调用中央注册接口建立状态镜像。中央不替信号系统生成具体行车命令。

```http
POST /api/trains/runtime-registrations
POST /api/trains/lifecycle
```

`POST /api/trains/runtime-registrations` 是服务间入口，由 `vehicle-runtime-service` 调用：

```json
{
  "trainId": "TR-003",
  "linkId": 12,
  "offsetMeters": 640.0,
  "direction": "DOWN",
  "reason": "vehicle runtime launch",
  "traceId": "runtime-train-003"
}
```

`POST /api/trains/lifecycle` 作为中央侧兼容/联调入口保留：

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

推荐处理顺序：`vehicle-runtime-service /vehicle-runtime/trains/launch` -> 创建 `VehicleRuntimeInstance` -> 唤醒 `VehicleControlQueue` -> `TrainController.registerFromVehicleRuntime()` -> `TrainManager.registerRuntimeStartedTrain()` -> 后续 tick 按信号和供电约束推进会话状态。

兼容处理顺序：`POST /api/trains/lifecycle` -> `TrainManager.applyLifecycleCommand()` -> `TrainManager.addTrain()` -> 中央反向注册外部运行时。该路径用于旧演示和生命周期语义联调，不作为推荐列车启动流程。

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

`GET /api/signal/vehicles/statuses` 返回的 `VehicleSignalStatus` 包含 `driverConsoleState`，用于暴露司机台 PLC 侧开关/手柄状态投影：门模式开关、ATO 启动标志、模式升/降级确认、自动折返、方向手柄和主手柄状态。

`GET /api/signal/vehicles/commands` 将当前 `MovementAuthority` 和车辆状态投影为 `SignalVehicleCommand`，同时通过 `cabDisplay` 下发司机台/DMI 显示字段：

| 场景 | 输出 |
|---|---|
| 正常 MA | 输出 MA 终点和授权速度。 |
| 无 MA 或 MA 耗尽 | 牵引切除、常用制动、紧急制动。 |
| 未并入车辆控制会话 | 牵引切除、常用制动，不进入紧急制动。 |
| 车辆故障限速 | 授权速度取 `min(MA限速, vehicleFaultSpeedLimitMetersPerSecond)`。 |

`cabDisplay` 对齐 ATP/ATO -> DMI 和网络屏/信号屏语义，包含 `currentDrivingMode`、`maximumAvailableDrivingMode`、`doorEnable`、`doorControlMode`、`tractionBrakeInfo`、`departureInfo`、`turnbackInfo`、`speedLimitMetersPerSecond`、`emergencyBrake`、`distanceToNextStationMeters`。模式枚举使用 `DTO`、`ATO`、`AR`、`SM`、`RM`；门控模式使用 `AUTOMATIC`、`SEMI_AUTOMATIC`、`MANUAL`。

### 车辆-司机台 PLC 适配接口

该接口属于车辆控制系统内部外设适配：`司机驾驶模拟台 PLC <-> DriverCabAdapter <-> 单车车辆控制子系统`。它不是调度、供电或中央主循环直接控制司机台的接口；信号侧的 MA/模式/门使能只作为车辆控制系统生成 PLC 输出报文的输入之一。

协议模型：

- `DriverCabPlcCodec`：实现司机台 PLC 第 7 章报文内容定义的小端编解码。
- `PLC -> 上位机`：46 字节，24 字节报文头 + 22 字节数据区，周期 100ms；当前解码钥匙、门模式、ATO 启动、模式升/降级确认、自动折返、方向手柄、主手柄、牵引/制动级位和紧急制动按钮。
- `上位机 -> PLC`：26 字节，24 字节报文头 + 2 字节数据区；当前编码高断合、制动故障、开门灯、门关好、网络故障、ATO/自动折返可用与激活等指示量。

前期无真实 TCP PLC 时使用 REST 二进制入口联调：

```http
POST /api/vehicle/driver-cabs/{trainId}/plc-input
GET /api/vehicle/driver-cabs/{trainId}/state
GET /api/vehicle/driver-cabs/{trainId}/plc-output
```

- `plc-input` 使用 `application/octet-stream`，提交 46 字节 PLC 输入报文；后端写入该单车 `DriverCabStateSnapshot`，并按钥匙、门按钮、快制/紧急制动更新本车控制状态。
- `plc-output` 返回 `application/octet-stream`，生成 26 字节上位机到 PLC 报文；来源为 `TrainState` 与当前 `SignalVehicleCommand.cabDisplay`。
- 后续接真实设备时，应在车辆控制系统侧新增 TCP 客户端连接 PLC 的 `192.168.100.123:8001/8002/8003`，REST 入口只保留为测试入口。

视景适配接口用于把车辆侧运行态补齐到信号/ATS 视图，再由信号模块按 UDP 包发送给外部视景系统：

```http
PUT /api/signal/vehicles/{trainId}/vision-state
GET /api/signal/vehicles/vision-states
POST /api/signal/vision/udp/send?trainId=TR-001&host=18.32.115.28&port=8302
```

`PUT /vision-state` 是车辆系统到中央信号模块的打包上报入口，可上报 `speedMetersPerSecond`、`accelerationMetersPerSecondSquared`、`accelerationPercent`、`headPositionMeters`、`headSegmentId`、`directionCode`、`runCondition`、`headlightState`、`operationCode`、`departureCountdownSeconds`。UDP 发送时，本车字段优先使用该缓存，缺失时回退到 `TrainState`；信号机和道岔分别来自 `SignalState` 与 `SwitchState`。

### 机房局域系统协议适配

现场 TCP、UDP 和供电点表链路统一由 `com.railwaysim.localnet` 管理。默认配置全部关闭，只有本地 `application-local.yml` 显式设置 `railway.simulation.localnet.enabled=true` 且具体适配器 `enabled=true` 后才会绑定端口或连接机房设备。

```http
GET /api/localnet/adapters
GET /api/localnet/adapters/{adapterId}
POST /api/localnet/adapters/{adapterId}/replay
```

适配器：

| adapterId | 协议族 | 说明 |
|---|---|---|
| `signal-udp` | `SIGNAL` | 信号系统与中央数据库节点 UDP 报文适配。 |
| `driver-cab-tcp` | `DRIVER_CAB` | 司机台 PLC、网络屏、信号屏 TCP 适配。 |
| `power-points` | `POWER_POINTS` | 供电现场点表适配。 |

`GET /api/localnet/adapters` 返回每条链路的 `configured`、`enabled`、`running`、收发计数、错误计数、最近错误和最近报文摘要。该接口只用于联调诊断，不替代业务 REST。

`POST /api/localnet/adapters/{adapterId}/replay` 使用 `application/octet-stream`，不打开真实 socket，只把报文送入同一解析和领域映射路径：

- `signal-udp` 支持完整 `0xff 0xf0/0xf1` 帧，以及实时 ADD/DELETE/CLEAR 生命周期包。
- `driver-cab-tcp` 支持 46 字节 PLC 输入包回放。
- `power-points` 点表支持 YAML/CSV，字段包括 `pointId`、`name`、`direction`、`dataType`、`scale`、`address`、`domainTarget`、`defaultValue`、`quality`；replay 使用文本 `pointId=value`，例如 `ISO_P01_A_STATE=OPEN`。

协议报文审计写入 `protocol_packet_log`，但审计是旁路能力；数据库写入失败不会阻塞现场链路或仿真 tick。

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

这些接口是其他模块访问中央供电控制模块的统一入口。外部供电仿真系统由中央 `PowerIntegrationService` 管控；在 `EXTERNAL_HTTP` 权威车辆运行时健康且 `forwardPowerLoads=true` 时，`vehicle-runtime-service` 可作为仿真层把分区负荷推送到供电仿真，但综合监控、车辆、调度、维修/能耗等中央业务模块不得直接访问外部供电仿真服务。

`PowerSectionState` 在分区电压、电流、负荷、保护和检修状态之外，已扩展：

| 字段 | 含义 |
|---|---|
| `supplyMode` | `DOUBLE_END`、`SINGLE_END`、`CROSS_FEED`、`OUTAGE` 等供电方式。 |
| `isolatorStatus` | 供电分区边界隔离开关汇总状态。 |
| `substationAvailability` | 关联牵引变电所可用性。 |
| `externalDataQuality` | 供电设备级状态来源质量，外部供电仿真失联时为 `FALLBACK`。 |
| `externalVoltage` | 外部供电仿真返回的接触轨电压；`voltage` 仍表示中央本地计算电压。 |
| `externalCurrent` | 外部供电仿真返回的区段牵引电流。 |
| `externalLoadWatts` | 外部供电仿真返回的区段牵引负荷。 |
| `voltageDeviation` | 外部电压减中央电压，单位 V。 |
| `voltageDeviationPercent` | 电压偏差百分比，按中央电压归一化。 |
| `voltageComparisonStatus` | `NO_EXTERNAL_DATA`、`MATCHED`、`DEVIATED`、`DIVERGED`。 |
| `externalSupportReason` | 外部仿真对当前供电支撑方式、电压结果或降级原因的说明。 |
| `strayCurrentRiskLevel` | 杂散电流风险等级：`NORMAL`、`ATTENTION`、`WARNING`、`CRITICAL`。 |
| `strayCurrentRiskReason` | 杂散电流风险原因。 |

中央仍以本地 `voltage/current/availablePowerWatts` 生成车辆 `PowerConstraint`；外部电压 v1 只进入监控、偏差告警和联调可视化。

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

## 外部车辆运行时服务接口

`vehicle-runtime-service` 是新的外部车辆侧运行时，默认端口 `9300`。中央系统仍是唯一对前端和其他中央模块开放的入口；列车启动推荐由 `vehicle-runtime-service` 主动发起，中央通过 `railway.simulation.vehicle-runtime.mode=EXTERNAL_HTTP` 或 `DUAL_SHADOW` 在后续 tick 中接入外部服务。

车辆接口迁移边界见 `docs/车辆运行时接口迁移评估.md`：中央 REST / WebSocket 保留为业务入口，`vehicle-runtime-service` 只承接仿真 tick 内的车辆控制和动力学执行。

```http
GET  /vehicle-runtime/health
POST /vehicle-runtime/bootstrap
POST /vehicle-runtime/trains/launch
PUT  /vehicle-runtime/trains/{trainId}
DELETE /vehicle-runtime/trains/{trainId}
DELETE /vehicle-runtime/trains
GET  /vehicle-runtime/instances
POST /vehicle-runtime/step-fleet
GET  /vehicle-runtime/events
```

`POST /vehicle-runtime/trains/launch` 启动顺序固定为：创建车辆仿真实例、唤醒该车 `VehicleControlQueue`、调用中央 `/api/trains/runtime-registrations` 建立中央状态镜像。请求示例：

```json
{
  "trainId": "TR-003",
  "linkId": 12,
  "offsetMeters": 640.0,
  "direction": "DOWN",
  "registerWithCentral": true,
  "reason": "demo launch",
  "traceId": "runtime-train-003"
}
```

`POST /vehicle-runtime/step-fleet` 请求包含 `tick`、`deltaSeconds`、`requestedAt`、`trains[]`、`movementAuthorities[]`、`trackConstraints[]`、`powerConstraints[]`。外部服务按每车实例的控制队列和仿真队列同步返回 `trainOutputs[]`、`trainReports[]`、`instanceStates[]`。

`POST /vehicle-runtime/bootstrap` 还会携带供电仿真联动配置：

| 字段 | 含义 |
|---|---|
| `powerNetworkBaseUrl` | 中央配置的外部供电仿真地址，默认 `http://localhost:9200`。 |
| `forwardPowerLoads` | 是否由外部车辆运行时把列车牵引/再生负荷推送到 `/power-network/state/query`。 |

当 `vehicle-runtime-service` 处于 `EXTERNAL_HTTP` 权威模式、健康且 `forwardPowerLoads=true` 时，供电负荷写入链路为 `vehicle-runtime-service:9300 -> power-network-service:9200`；中央 `PowerIntegrationService` 只调用 `/power-network/state` 拉取供电状态。`LOCAL`、`DUAL_SHADOW`、外部车辆运行时不可用或降级时，中央恢复原路径：`PowerIntegrationService.refreshSnapshot(sectionLoads) -> POST /power-network/state/query`。

中央新增监控入口：

```http
GET /api/vehicle/runtime-health
```

该接口返回外部车辆运行时健康状态和实例队列状态。`/api/simulation/snapshot` 与 WebSocket 快照同步携带 `vehicleRuntime` 字段。

实现状态：首期已完成 HTTP 同步批量步进、实例队列、中央 fallback、供电负荷转发单元测试和中央供电写入权切换测试；尚未进行 `9300 + 9200 + backend` 长时间联动、真实多车压力、真实 FMU/RT-LAB 逻辑验收。

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

该协议不是面向前端或调度系统的 REST 接口，而是后端车辆运行时背后的实现。新 `vehicle-runtime-service` 健康时可同时接管车辆控制决策和车辆物理仿真；旧 `external-simulator` / FMU / UDP / RT-LAB 适配仍作为物理端口历史兼容路径保留。

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
  "supplyMode": "DOUBLE_END",
  "isolatorStatus": "CLOSED",
  "substationAvailability": "AVAILABLE",
  "breakerStatus": "CLOSED",
  "protectionState": "NORMAL",
  "maintenanceState": "NONE",
  "lockoutState": "UNLOCKED",
  "externalDataQuality": "GOOD",
  "externalVoltage": 1492.0,
  "externalCurrent": 418.0,
  "externalLoadWatts": 626000.0,
  "voltageDeviation": -8.0,
  "voltageDeviationPercent": 0.53,
  "voltageComparisonStatus": "MATCHED",
  "externalSupportReason": "normal double-end supply",
  "strayCurrentRiskLevel": "NORMAL",
  "strayCurrentRiskReason": "状态正常",
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
