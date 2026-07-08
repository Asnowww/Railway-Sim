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

### 获取供电状态

```http
GET /api/power/sections
GET /api/power/sections/{sectionId}
GET /api/power/sections/{sectionId}/events
GET /api/power/energy
GET /api/power/maintenance-locks
```

### 能耗和维修预留

```http
GET /api/energy/trains
GET /api/energy/power-sections
GET /api/vehicle/maintenance-states
```

### 仿真故障注入

仿真写接口只用于演示和联调，必须带二次确认字段。

```http
POST /api/power/sections/{sectionId}/faults
POST /api/power/sections/{sectionId}/faults/clear
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
- 调度策略只消费状态和影响范围，不由供电/车辆故障接口直接下发扣车或折返。

### 调度命令

```http
GET /api/dispatch/plan
GET /api/dispatch/plan/current
GET /api/dispatch/status
GET /api/dispatch/disturbances
GET /api/dispatch/commands
GET /api/dispatch/station-records

提交调度命令：

```json
{
  "trainId": "TR-001",
  "commandType": "SPEED_LIMIT",
  "detail": "8.0"
}
```

当前车辆链路支持的调度命令：

| commandType | detail | 作用 |
|---|---|---|
| `HOLD` / `HOLD_TRAIN` | 任意说明文本 | 调度服务记录扣车意图，由信号模块折算为 MA/限速或后续 `SignalVehicleCommand` |
| `SPEED_LIMIT` / `TEMP_SPEED_LIMIT` | 速度上限，单位 m/s | 由信号模块与轨道限速、安全距离共同计算后下发给车辆 |
| `SPEED_FACTOR` / `LIMIT_FACTOR` | 0-1 比例 | 由信号模块折算速度授权，不由车辆直接消费 |

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

### 调度模块

```http
GET /api/dispatch/plan
GET /api/dispatch/plan/current
GET /api/dispatch/status
GET /api/dispatch/disturbances
GET /api/dispatch/commands
GET /api/dispatch/station-records
```

## 外部车辆仿真协议适配

该协议不是面向前端或调度系统的 REST 接口，而是后端 `VehiclePhysicsClient.stepFleet()` 背后的车辆物理端口实现。主系统仍通过 `TcmsAtoAdapterService` 汇总信号、轨道、供电约束后生成车辆控制输入；调度约束先由信号模块折算为 MA/限速或后续 `SignalVehicleCommand`。

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
