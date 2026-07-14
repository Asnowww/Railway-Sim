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
POST /api/simulation/stop
POST /api/simulation/reset
POST /api/simulation/tick
```

`start` 将当前 `DispatchService.simulationRunId` 标记为 `RUNNING`；`pause` 只记 `PAUSED` 而不结束运行；`stop` 记 `COMPLETED`；未捕获的 tick 异常记 `FAILED`。`reset` 先在同一数据库事务中把旧 run 记为 `CANCELLED_BY_RESET`并创建新 run，再清理临时控制态；历史表不删除。

### 运行历史与证据查询

```http
GET /api/simulation-runs?limit=50
GET /api/simulation-runs/{runId}
GET /api/simulation-runs/{runId}/summary
GET /api/simulation-runs/{runId}/train-snapshots?limit=100&offset=0
GET /api/simulation-runs/{runId}/control-decisions?limit=100&offset=0
GET /api/simulation-runs/{runId}/power-snapshots?limit=100&offset=0
GET /api/simulation-runs/{runId}/stop-results?limit=100&offset=0
GET /api/simulation-runs/{runId}/stop-statistics?trainId={trainId}&stationId={stationId}&requiredSampleCount=10
GET /api/simulation-runs/{runId}/faults?limit=100&offset=0
```

`SimulationSnapshot` 顶层必须携带 `simulationRunId`。车辆物理、能耗、供电分区、轨道占用、信号状态、FMU 故障、最终车辆决策和危险操作审计使用同一 `(simulationRunId,tick)`。

`stop-results` 中每条 `TrainStopResult` 对应一次稳定进站，不是每 tick 快照。它包含显式 `StoppingTarget`、`targetValidFromTick`、MA 覆盖标志、目标/实际位置、签名/绝对误差、越站、成功与原因、最大减速度、最大 jerk、制动切换次数、紧急制动、`controlStageHistory`、控制模式和参数版本。MA 终点比站台目标更近时发布 `TARGET_OVERRIDDEN_BY_MA`事件。

`stop-statistics` 按 runId 必选、trainId/stationId 可选的场景口径返回样本数、平均绝对误差、P95、总体方差、成功数/成功率、越站数、紧急制动数和原因分布。`sampleRequirementMet` 只在样本数达到 `requiredSampleCount`（默认 10）时为 true。

### 告警生命周期

```http
GET /api/alarms?runId={runId}
POST /api/alarms/{alarmId}/acknowledge
```

```json
{
  "operator": "dispatcher-01"
}
```

告警状态为 `RAISED/ACKNOWLEDGED/CLEARED`。每条告警携带 `FaultImpact{severity,affectedTrainIds,affectedSectionIds,safetyAction,clearCondition,recoveryCondition}`。同一 run 内相同活动故障使用稳定 occurrence ID 并只更新 `lastSeenAt`；条件消失时自动 `CLEARED`，再次发生创建新 occurrence。告警及活动索引在 8080 重启后从 `alarm_record` 回填。

### 外部服务健康与恢复门槛

```http
GET /api/service-health
GET /api/service-health/{serviceId}
POST /api/service-health/{serviceId}/recovery/check
```

```json
{
  "expectedRunId": "run-001",
  "expectedTick": 120
}
```

9300 和 9200 状态统一为 `UP/DEGRADED/STALE/FALLBACK/RECOVERING`。异常后首次恢复健康不直接进入 UP；必须同时通过 runId、lastAcceptedTick、topology/config hash、model/parameter version 门槛。不匹配时保持 `RECOVERING` 并返回具体 `rejectionReasons`。最后健康记录和 last-good baseline 分别写入 `service_health_record/service_health_baseline`，重启后恢复。

9200 进程重启后默认从 `config/power_third_rail.yaml` 加载 1500 V、5 分区基线并置为 `bootstrapped=true`，`/power-network/constraints/query` 可立即使用；`POWER_NETWORK_CONFIG_PATH` 可显式覆盖该默认文件，8080 后续仍可重新下发权威拓扑。9300 保留车辆实例跨中央重启，但 runId 改变只允许发生在新 run 的 tick 0/1；接管时清空旧司机命令、供电约束和物理缓存并触发重同步，运行中途换 run 返回 `VEHICLE_RUN_ID_MISMATCH`。

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

该接口属于车辆控制系统内部外设适配：`司机驾驶模拟台 PLC <-> 单车车辆控制子系统`。不是调度、供电或中央主循环直接控制司机台的接口；信号侧的 MA/模式/门使能只作为 PLC 输出报文的输入之一。

**架构分界（方案 A）：**

| 端点 | 所属服务 | 端口 | 说明 |
|---|---|---|---|
| `POST .../plc-input` JSON | backend协议网关 | 8080 | 前端结构化输入校验、46字节编码和结果透传，不执行控制 |
| `POST /vehicle-runtime/peripherals/frame` octet-stream | vehicle-runtime-service | 9300 | 聚合帧解码；仅接受 PLC 输入并执行命令存储与后续仲裁 |
| `GET .../state` | backend | 8080 | 司机台显示状态（来自信号系统） |
| `GET .../plc-output` | backend | 8080 | PLC 输出报文编码 |

协议模型：

- `DriverCabPlcCodec`：实现司机台 PLC 第 7 章报文内容定义的小端编解码。8080 接收原始输入并封装聚合帧，9300 解聚合后执行输入；输出侧在 `backend:8080`。
- `PLC -> 上位机`：46 字节，24 字节报文头 + 22 字节数据区，周期 100ms；固定标识 `55 AA 55 AA`，长度字段小端 `2E 00/16 00`。位偏移依现场表解码，其中紧急制动是 byte 28 bit0，开关门是 byte 29 bit0..3。
- `上位机 -> PLC`：26 字节，24 字节报文头 + 2 字节数据区；由 backend 8080 编码，来源为 `TrainState` 与当前 `SignalVehicleCommand.cabDisplay`。

无真实 TCP PLC 时使用 8080 结构化入口，或直接构造 RSIM 聚合帧联调：

```http
POST http://localhost:9300/vehicle-runtime/peripherals/frame
GET  http://localhost:9300/vehicle-runtime/peripherals/status
GET  http://localhost:8080/api/vehicle/driver-cabs/{trainId}/state
GET  http://localhost:8080/api/vehicle/driver-cabs/{trainId}/plc-output
```

- 浏览器前端连接8080并提交结构化JSON；8080编码46字节后封装为聚合帧转发9300，并透传 `DriverCommandAcceptance`。聚合帧使用 `RSIM` 魔数、Version 1、通道、序号、车号、原始现场载荷和 CRC32；所有服务间外设数据复用9300端口。
- 非法枚举码（门模式/方向手柄/主手柄）和越界百分比（0–100 范围外）将返回 `400` 和 `DriverCommandAcceptance{accepted:false, reasonCode: "DECODE_FAILED"}`，不再静默截断。
- 合法报文返回 `DriverCommandAcceptance{accepted:true, commandId, trainId, reasonCode: "ACCEPTED", receivedAt, expiresAt}`。
- 9300 未找到已启动的 `VehicleRuntimeInstance` 时返回 `404` 和 `reasonCode: "UNKNOWN_TRAIN"`，命令不进入 Holder。
- `GET state/plc-output` 仍由 `backend:8080` 提供。
- 真实 TCP 由 8080 `DriverCabTcpAdapter` 管理连接；在 `EXTERNAL_HTTP` 中它只把 PLC 46 字节原始输入封装为聚合帧转发到 9300，不执行车辆控制。网络屏和信号屏均为纯输出显示端，不读取或转发任何屏幕输入。端点为 PLC `192.168.100.123:8001`、网络屏 `192.168.100.121:8888`、信号屏 `192.168.100.122:9999`；视景系统由 8080 向 `18.32.115.28:8302` 单向发送 UDP Version 1.3 报文。完整方向矩阵和 RSIM 帧布局见 `docs/司机台与显示系统端口聚合协议.md`。

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
- `driver-cab-tcp` 只接受46字节 PLC 输入；网络屏570字节、信号屏68字节和视景Version 1.3 UDP都是中央到显示端的单向输出。
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

这些接口是中央对前端提供的只读供电镜像。`split` 模式下，外部 FastAPI 供电仿真是分区电压、可用功率和保护约束的唯一权威；中央不再计算或下发 `PowerConstraint` 给 9300，只读取并转发快照给前端/告警。

`PowerSectionState` 在分区电压、电流、负荷、保护和检修状态之外，已扩展：

| 字段 | 含义 |
|---|---|
| `supplyMode` | `DOUBLE_END`、`SINGLE_END`、`CROSS_FEED`、`OUTAGE` 等供电方式。 |
| `isolatorStatus` | 供电分区边界隔离开关汇总状态。 |
| `substationAvailability` | 关联牵引变电所可用性。 |
| `externalDataQuality` | 供电设备级状态来源质量，外部供电仿真失联时为 `FALLBACK`。 |
| `externalVoltage` | 外部供电仿真返回的权威接触轨电压；`voltage` 是中央镜像模型值，仅用于兼容展示。 |
| `externalCurrent` | 外部供电仿真返回的区段牵引电流。 |
| `externalLoadWatts` | 外部供电仿真返回的区段牵引负荷。 |
| `voltageDeviation` | 外部电压减中央电压，单位 V。 |
| `voltageDeviationPercent` | 电压偏差百分比，按中央电压归一化。 |
| `voltageComparisonStatus` | `NO_EXTERNAL_DATA`、`MATCHED`、`DEVIATED`、`DIVERGED`。 |
| `externalSupportReason` | 外部仿真对当前供电支撑方式、电压结果或降级原因的说明。 |
| `strayCurrentRiskLevel` | 杂散电流风险等级：`NORMAL`、`ATTENTION`、`WARNING`、`CRITICAL`。 |
| `strayCurrentRiskReason` | 杂散电流风险原因。 |

`split` 模式下，9300 将全车负荷按 tick 提交给 9200，并直接消费 9200 返回的分区电压、可用功率和受流约束。8080 只保存供电快照镜像用于监控、告警和持久化，不再生成或转发中央 `PowerConstraint`。LOCAL 兼容模式的本地计算不得被当作 SPLIT 主链真值。

### 能耗和维修预留

```http
GET /api/energy/trains
GET /api/energy/power-sections
GET /api/vehicle/maintenance-states
```

`GET /api/vehicle/onboard-subsystems` 已移除。中央前端统一查询 `GET /api/vehicle/runtime-health`；9300内部实例明细由 `GET /vehicle-runtime/instances` 提供。

### 仿真故障注入

仿真写接口只用于演示和联调。供电和车辆故障接口必须带二次确认字段；信号轨道故障接口
使用严格请求 DTO，并返回可审计的 `operator`、`reason` 和 `traceId`。

```http
POST /api/power/sections/{sectionId}/faults
POST /api/power/sections/{sectionId}/faults/clear
POST /api/power/operations
POST /api/trains/{trainId}/faults
POST /api/trains/{trainId}/faults/clear
POST /api/signal-track/faults
POST /api/signal-track/faults/{segmentId}/clear
GET  /api/signal-track/faults
```

信号轨道区段故障注入请求：

```json
{
  "sourceId": "T02",
  "faultType": "TRACK_CIRCUIT_UNKNOWN",
  "reason": "demo",
  "operator": "simulation",
  "traceId": "trace-signal-001"
}
```

`sourceId` 和 `faultType` 必填。`faultType` 必须是 `SignalTrackFaultType` 中的合法枚举值，非法值不会被替换成默认故障。

注入或清除响应：

```json
{
  "accepted": true,
  "changed": true,
  "idempotent": false,
  "operation": "INJECT",
  "sourceId": "T02",
  "faultType": "TRACK_CIRCUIT_UNKNOWN",
  "operator": "simulation",
  "reason": "demo",
  "traceId": "trace-signal-001"
}
```

| 状态码 | 场景 |
|---|---|
| `200` | 首次操作成功；重复注入或重复清除也返回 `200`，此时 `changed=false`、`idempotent=true`。 |
| `400` | `sourceId`/`faultType` 缺失或为空，或 `faultType` 不是合法枚举。 |
| `404` | `sourceId`/路径中的 `segmentId` 不对应任何轨道区段。 |

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

- 危险写接口在修改内存仿真状态之前，同步写入带 `run_id/tick/trace_id` 的 `operation_log`；供电写接口同时维护 `power_operation_log`。
- 同步审计写入失败时返回 `503 ApiError{code:"AUDIT_FAILED",...}`，不执行故障注入、故障清除或供电操作，也不降级到异步队列伪报成功。
- `protocol_packet_log` 仍是旁路报文诊断，可以在写库失败时继续现场通信；该规则不适用于上述危险业务写操作。
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
POST /api/dispatch/commands/{commandId}/cancel
GET /api/dispatch/route/list
POST /api/dispatch/route/establish?routeId={routeId}&trainId={trainId}
GET /api/dispatch/station-records
```

调度模块位于综合层，读取车辆运行状态和信号授权状态，生成运营调度意图。调度不直接生成牵引/制动命令；扣车、限速和速度比例类调度约束由信号模块折算为 MA/限速后再进入车辆控制链路。
进路/道岔调度遵循同一边界：调度模块只生成 `REQUEST_ROUTE` / `REROUTE` 意图和预约记录，实际道岔扳动、锁闭和冲突安全检查由信号联锁执行。

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
      "dwellDeviationSeconds": 0,
      "departureDelaySeconds": 0
    }
  ],
  "openDisturbances": [],
  "activeCommands": [],
  "routeDispatchActive": false,
  "routeDecisions": [
    {
      "decisionId": "RD-1234abcd",
      "selectedTrainId": "TR-001",
      "selectedRouteId": "R_MAIN",
      "waitingTrainIds": [],
      "status": "REQUESTED",
      "routeCommandId": "DC-route-TR-001-R_MAIN-1783555200000",
      "reason": "AUTO_ROUTE_APPROACH(route=R_MAIN,distance=300m,eta=38s)",
      "rejectReason": null
    }
  ],
  "routeReservations": [
    {
      "reservationId": "RR-1234abcd",
      "trainId": "TR-001",
      "routeId": "R_MAIN",
      "decisionId": "RD-1234abcd",
      "state": "REQUESTED",
      "commandId": "DC-route-TR-001-R_MAIN-1783555200000",
      "rejectReason": null
    }
  ]
}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `runMode` | string | 当前运行模式。 |
| `targetHeadwaySeconds` | number | 当前目标行车间隔。 |
| `defaultDwellSeconds` | number | 当前默认停站时间。 |
| `interventionActive` | boolean | 是否存在开放扰动或活跃调度命令。 |
| `trainProfiles` | array | 列车运行偏差摘要。 |
| `trainProfiles[].departureDelaySeconds` | number | 最近一次实际发车相对计划发车的偏差，正值表示延误。 |
| `openDisturbances` | array | 当前开放或已处理但未恢复的扰动。 |
| `activeCommands` | array | 当前 `PENDING` 或 `APPLIED` 的命令摘要。 |
| `routeDispatchActive` | boolean | 是否存在活跃进路调度决策。 |
| `routeDecisions` | array | 调度侧进路决策摘要，P1/P2 用于展示人工或自动申请进路的原因与联锁反馈。 |
| `routeDecisions[].status` | string | `REQUESTED`、`ACCEPTED`、`REJECTED`、`CANCELLED`。 |
| `routeReservations` | array | 调度侧进路预约摘要。 |
| `routeReservations[].state` | string | `REQUESTED`、`ACCEPTED`、`RELEASED`、`EXPIRED`、`REJECTED`、`CANCELLED`。 |
| `routeReservations[].rejectReason` | string/null | 联锁拒绝或调度取消原因。 |

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
| `SENT` | 已进入信号或车辆执行链路。 |
| `APPLIED` | 已应用。 |
| `EFFECT_CONFIRMED` | 执行结果已由信号、联锁或车辆状态确认。 |
| `TIMEOUT` | 在规定时间内未确认执行效果。 |
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

结构化进路请求：

```json
{
  "trainId": "TR-001",
  "commandType": "REQUEST_ROUTE",
  "routeId": "R-main-down"
}
```

也可将进路字段放入 `payload`；`routeId` 顶层字段优先覆盖同名 payload。`REQUEST_ROUTE` 和
`REROUTE` 是一次性联锁命令，联锁接受后状态转为 `EFFECT_CONFIRMED`，拒绝时转为
`SKIPPED`，拒绝原因写入 `payload.lastFeedbackReason`。

进路命令提交响应会在 `payload` 中同时返回 `decisionId` 和 `reservationId`。调用方使用响应
`id` 关联命令历史，使用 `decisionId` 关联 `dispatch.routeDecisions`，使用 `reservationId` 关联
`dispatch.routeReservations`；三者不得绕过调度闭环直接建立联锁进路。

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
| `REQUEST_ROUTE` | `routeId` 或 `payload.routeId` | 请求联锁建立指定进路，并记录接受或拒绝反馈 |
| `REROUTE` | `routeId`、JSON `detail` 或进路说明 | 请求联锁改路；保留旧 detail 格式兼容 |

P1/P2 进路调度闭环：

1. 前端或自动策略提交 `REQUEST_ROUTE` / `REROUTE`。
2. `DispatchService` 校验命令，创建 `RouteDispatchDecision` 与 `RouteReservation`，并将命令加入调度队列。
3. `SimulationRuntime` 在同一 tick 将进路命令交给 `RouteInterlockingService.applyDispatchCommand(...)`。
4. 信号联锁根据 route 区段推导道岔位置，执行道岔检查、扳动、锁闭和进路冲突检查。
5. runtime 将联锁接受/拒绝通过 `DispatchCommandFeedback` 回传调度。
6. 调度更新 `routeReservations[].state` 与 `routeDecisions[].status`，并通过 `/api/dispatch/status` 和 WebSocket 快照同步给前端。
7. 后续联锁释放进路后，调度将已接受的预约标记为 `RELEASED`。

P2 自动申请由 dispatch 内部触发：当列车进入 route 入口接近窗口时，调度按 MAIN 优先、入口距离和进路长度选择候选进路，自动生成 `REQUEST_ROUTE`。自动命令 payload 会包含：

| payload 字段 | 类型 | 说明 |
|---|---|---|
| `autoGenerated` | boolean | `true` 表示 P2 自动进路意图生成。 |
| `source` | string | 当前为 `AUTO_ROUTE_INTENT`。 |
| `routeId` | string | 选中的进路 ID。 |
| `distanceToEntryMeters` | number | 列车到进路入口的估算距离。 |
| `etaToEntrySeconds` | number | 预计到达入口时间。 |
| `priorityScore` | number | P2 解释性评分，P3 多车仲裁会继续扩展。 |
| `validUntil` | string | 自动意图有效期。 |

#### 撤销调度命令

```http
POST /api/dispatch/commands/{commandId}/cancel
```

响应：

```json
{
  "accepted": true,
  "commandId": "DC-550e8400-e29b-41d4-a716-446655440000"
}
```

对 route 命令撤销时，对应 `RouteReservation` 会进入 `CANCELLED`。

#### 查询进路列表

```http
GET /api/dispatch/route/list
```

该接口当前返回信号联锁视角的进路列表，供调试页展示线路与联锁状态。调度侧 P1/P2 预约状态请从 `/api/dispatch/status.routeReservations` 读取。

响应：

```json
[
  {
    "routeId": "R_MAIN",
    "name": "进站->正线",
    "type": "MAIN",
    "fromStation": "S01",
    "toStation": "S05",
    "segmentIds": ["T01", "T02"],
    "status": "AVAILABLE"
  }
]
```

#### 联锁直接建路调试接口

```http
POST /api/dispatch/route/establish?routeId=R_MAIN&trainId=TR-001
```

该接口直接调用信号联锁 `establishRoute`，用于联锁调试，不会创建 `DispatchCommand`、`RouteDispatchDecision` 或 `RouteReservation`。前端需要验证调度闭环时，应调用 `POST /api/dispatch/commands` 并提交 `REQUEST_ROUTE`。

响应：

```json
{
  "accepted": true,
  "routeId": "R_MAIN",
  "trainId": "TR-001",
  "rejectReason": null
}
```

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

`POST /vehicle-runtime/step-fleet` 请求包含 `tick`、`deltaSeconds`、`requestedAt`、`movementAuthorities[]`、`trackConstraints[]`、`dispatchConstraints[]`、`powerConstraints[]`、`simulationRunId`、`driverCommands[]`。

**重要变更**：请求不再包含 `trains[]`。9300 从本地 `TrainStateHolder` 读取每列车的权威状态（位置、速度、TCMS 诊断等）。中央只发送约束（轨道、信号、调度、供电），不再发送列车状态。

| 字段 | 类型 | 说明 |
|---|---|---|
| `simulationRunId` | string | 当前仿真运行 ID，贯穿 8080→9300→9200 用于时间线对齐 |
| `driverCommands[]` | array of `DriverControlCommandSnapshot` | 已废弃。PLC 和手动控制现已直接发往 9300，不再经 step request 转发。字段保留仅为向后兼容。 |

响应新增字段：

| 字段 | 类型 | 说明 |
|---|---|---|
| `trainStates` | array of `TrainStateSnapshot` | 9300 的权威列车状态快照，中央用于镜像更新。包含全部 58+ 字段（位置、速度、力、能耗、TCMS 诊断、车站追踪、故障码）。 |
| `events` | array of `VehicleRuntimeEvent` | 本 tick 内发生的事件列表（VehiclePhysicsUpdated、TractionPowerChanged、BrakeForceChanged、RegenerativePowerGenerated、TrainFaultStateChanged）。中央桥接到 `SimpleEventBus`。 |

`TrainStateSnapshot` 新增字段：

| 字段 | 类型 | 说明 |
|---|---|---|
| `currentStationId` | string/null | 列车当前所在车站 ID（不在站时为 null） |
| `dwellElapsedSeconds` | int | 已停站秒数 |
| `lastDepartureAt` | string/null | 上次发车时间戳 |

9300 先为全车准备控制输入，每个 tick 只调用一次 9000 `/step-fleet`，再统一写回 `trainOutputs[]`、`trainReports[]`、`instanceStates[]`、`trainStates[]` 和 `events[]`。当 PLC 命令或手动控制被选中时，`trainReports[]` 的 `decisionSource=DRIVER`，并回传 `inputCommandId/inputTraceId`；8080 以该报告建立中央决策镜像，不再查询自己的旧 PLC Holder 推断来源。

### 9300 新增端点

| 方法 | 路径 | 说明 |
|---|---|---|
| `POST` | `/vehicle-runtime/peripherals/frame` | RSIM Version 1 外设聚合入口；仅接受 `PLC_INPUT`，其他通道返回 `CHANNEL_DIRECTION_INVALID`。 |
| `GET` | `/vehicle-runtime/peripherals/status` | 查询聚合协议版本、允许通道、收拒帧计数、最近序号和车号。 |
| `POST` | `/vehicle-runtime/trains/{trainId}/manual-control` | 手动控制单列车：设置牵引/制动/紧急制动/方向。命令存入 `DriverCommandHolder`，下个 tick 通过 `VehicleControlQueue.decideDynamicsState()` 生效。 |
| `GET` | `/vehicle-runtime/trains/{trainId}/state` | 获取单列车权威状态快照 |
| `GET` | `/vehicle-runtime/trains/state` | 获取所有列车权威状态快照 |
| `POST` | `/vehicle-runtime/autonomous/enable` | 开启 9300 自主 tick 模式 |
| `POST` | `/vehicle-runtime/autonomous/disable` | 关闭 9300 自主 tick 模式 |
| `POST` | `/vehicle-runtime/tick` | 手动触发一次自主 tick |

手动控制请求体：

```json
{
  "tractionCommand": 0.75,
  "brakeCommand": 0.0,
  "emergencyBrake": false,
  "direction": 1.0,
  "doorOpenRequest": null,
  "timeoutMs": 5000
}
```

`POST /vehicle-runtime/bootstrap` 还会携带供电仿真联动配置：

| 字段 | 含义 |
|---|---|
| `powerNetworkBaseUrl` | 中央配置的外部供电仿真地址，默认 `http://localhost:9200`。 |
| `forwardPowerLoads` | 是否启用 9300 -> 9200 的权威供电闭环。启用时使用 `/constraints/query` 与 `/step`。 |
| `stations[]` | 8080 从当前线路 YAML/工作簿解析出的只读车站目标，包含 `id/name/positionMeters/platformIds`。9300 只消费该静态拓扑做停车和到站判断，不创建或修改车站。 |

`stations[]` 只在 bootstrap/恢复时传输，不属于逐 tick 车辆状态同步。9300 的 `configHash` 包含车站 ID、里程和站台 ID；车站拓扑不一致时恢复门禁不得进入 `UP`。

当 `vehicle-runtime-service` 处于 `EXTERNAL_HTTP` 模式且 `forwardPowerLoads=true` 时，闭环为：`9300 -> POST 9200/constraints/query -> 车辆控制/单次批量FMU动力学 -> POST 9200/step -> 下一周期供电约束`。中央 `PowerIntegrationService` 只调用 `GET 9200/power-network/state` 拉取镜像，绝不补写负荷；该写入权按部署配置固定，不因某车FMU降级而切回中央，从而避免双写。只有非拆分部署的`LOCAL`或`DUAL_SHADOW`模式保留兼容路径`PowerIntegrationService.refreshSnapshot(sectionLoads) -> POST /power-network/state/query`。

`POST 9200/power-network/step` 请求额外必须携带 `simulationRunId`。9200 在同一 run 内对重复 tick 幂等、对倒退 tick 返回 409；只允许在新 run 的首 tick 切换 runId，运行中途改变 runId 返回 `POWER_RUN_ID_MISMATCH`。

9300物理实例恢复接口：

```http
POST /vehicle-runtime/physics/instances/{trainId}/reset
POST /vehicle-runtime/physics/instances/{trainId}/resync
POST /vehicle-runtime/physics/instances/resync-all
```

fallback列车不会自动切回FMU；上述RESET/RESYNC命令在下一个100 ms权威车辆状态到达时执行。`GET /vehicle-runtime/health`额外返回`physicsMode`、`fmuModelVersion`、`parameterSetId`、`fmuBatchLatencyMillis`和`fallbackTrainCount`。

中央新增监控入口：

```http
GET /api/vehicle/runtime-health
```

该接口返回外部车辆运行时健康状态和实例队列状态。`/api/simulation/snapshot` 与 WebSocket 快照同步携带 `vehicleRuntime` 字段。

实现状态：WP5～WP6已完成9300到9000的单次批量调用、逐车/整批Java降级、9200再生预算及同段多车耦合；尚未执行WP7统一编排和WP8十分钟长稳、跨进程恢复及最终性能验收。

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

9000接口固定采用20 ms通信步长。生产链路由9300在一个100 ms TCMS周期内连续调用5次；牵引、制动和安全约束在这5个子步内保持不变，第5次输出才更新9300权威状态并回传8080。该20 ms周期不改变PLC输入、信号/轨道、供电提交、中央快照和前端推送周期。

请求：

```json
{
  "simTime": "2026-07-07T10:00:00Z",
  "deltaSeconds": 0.02,
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
      "deltaSeconds": 0.02,
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

> ✅ **已移除**：`onboard-subsystem-mode` 对应的 `OnboardTrainSubsystemManager` 及 `HttpOnboardTrainSubsystemClient` 已删除。车辆控制使用 `railway.simulation.vehicle-runtime.mode=EXTERNAL_HTTP` 搭配 9300。

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
      "activeCommands": [],
      "routeDispatchActive": false,
      "routeDecisions": [],
      "routeReservations": []
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
