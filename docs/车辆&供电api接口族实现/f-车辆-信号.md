# 车辆-信号接口族具体实现

## 实现范围

本接口族只描述车辆控制系统与信号系统之间的接口。信号系统负责 MA、限速、安全制动命令语义和列车上线/下线接入语义；车辆系统负责输出可供信号系统消费的车辆状态，并消费信号系统下发的 MA/制动约束。

轨道系统是独立系统；区段占用、坡度、曲线半径、站距、道岔和进路联锁不写入本接口族。调度系统也不直接进入车辆控制系统；调度意图必须先由调度/ATS/信号侧折算为 MA、限速、扣车或制动语义。

## 已落实代码入口

| 方向 | 接口/模型 | 说明 |
|---|---|---|
| 车辆到信号 | `GET /api/signal/vehicles/statuses` | 信号系统查询车辆侧安全状态投影。 |
| 车辆到信号 | `POST /api/signal/vehicles/telemetry` | JSON 形式接收车辆控制节点上报的列车运行遥测。 |
| 车辆到信号 | `POST /api/signal/vehicles/telemetry/content-packet?trainCount=N` | 二进制小端 `SignalTrainContentCodec` 协议包入口。 |
| 车辆到信号/视景适配 | `PUT /api/signal/vehicles/{trainId}/vision-state` | 车辆系统打包上报视景包需要但信号模块不长期持有的本车动态字段。 |
| 信号到车辆/司机台显示 | `GET /api/signal/vehicles/commands` | 将当前 MA/限速投影为车辆侧可消费的信号命令，并附带 ATP/ATO 到 DMI/司机台显示字段。 |
| 信号到视景系统 | `POST /api/signal/vision/udp/send` | 按指定列车生成视景 UDP 包并发送到指定 IP/端口。 |
| 车辆运行时到中央纳管 | `POST /api/trains/runtime-registrations` | 车辆仿真实例启动并唤醒控制队列后，向中央注册状态镜像。 |
| 信号到中央纳管 | `POST /api/trains/lifecycle` | ADD/DELETE/CLEAR 兼容入口，主要用于下线、清空和旧联调语义。 |
| 仿真主循环内部 | `SimulationRuntime -> SignalService -> TrainManager.tickAll()` | 每 tick 将 `MovementAuthority` 送入 `OnboardTrainSubsystemManager`。 |

## 车辆到信号：状态与遥测

### 状态查询

```http
GET /api/signal/vehicles/statuses
```

返回模型为 `VehicleSignalStatus`，来源为 `TrainState`。

| 字段 | 来源 | 用途 |
|---|---|---|
| `trainId` | `TrainState.id` | 信号系统匹配 MA 和列车状态。 |
| `controlSessionState` | 外部控制会话 | 上线/下线过程中判断是否可纳入信号闭环。 |
| `signalNetworkStatus` | 外部控制会话 | 判断车辆是否已并入信号网。 |
| `powerNetworkStatus` | 外部控制会话 | 判断车辆是否已并入供电网。 |
| `linkId` / `direction` | 生命周期协议 | 对齐信号侧 link 与上下行方向。 |
| `headMileage` / `tailMileage` | `TrainState` | 供信号安全距离和追踪间隔判断。 |
| `speedMetersPerSecond` | 车辆物理/遥测 | ATP/MA 安全判断。 |
| `zeroSpeed` | `TrainState` | 发车、门控和站台作业条件。 |
| `doorState` | 车辆控制状态 | 发车联锁条件。 |
| `tractionAvailable` / `brakeAvailable` | 车辆控制状态 | 判断可否牵引、可否保证制动。 |
| `currentCollectionStatus` | 车辆/供电状态 | 失流、欠压时信号可禁止发车或触发安全制动。 |
| `loadMassKg` / `overloadStatus` | 协议载重/车辆载荷策略 | 超载时可用于限速、禁止发车或运营提示。 |
| `availableTractionCount` / `availableBrakeCount` | 车辆遥测 | 车辆能力降级判断。 |
| `operationMode` | 车辆控制状态 | ATO、ATP_BRAKE、DEGRADED 等。 |
| `selfCheckStatus` / `faultLevel` / `faultCode` | 车辆控制状态 | 信号侧安全降级原因。 |
| `availableOperationMode` | 车辆控制状态 | NORMAL、DEGRADED、NO_DEPARTURE。 |
| `vehicleFaultSpeedLimitMetersPerSecond` | 车辆遥测 | 车辆侧故障限速，信号命令投影时与 MA 限速取最小值。 |
| `dynamicsState` / `dynamicsConstraintReason` | 车辆控制决策 | 解释车辆为何牵引、惰行、制动或停车。 |
| `dataQuality` | 适配层 | GOOD、FALLBACK、INVALID。 |
| `driverConsoleState` | 司机台 PLC 状态投影 | 对齐门模式开关、ATO 启动标志、模式升/降级确认、自动折返、方向手柄和主手柄状态。 |

`driverConsoleState` 是信号侧展示投影：8080 有9300快照携带的 `DriverCabStateSnapshot` 时优先使用，否则由最终车辆状态派生。真实 PLC 原始命令只在9300执行；是否生效以9300 `TrainStateReport.decisionSource/inputCommandId/inputTraceId` 为准，不以本展示字段反推。

| 字段 | 取值 | 说明 |
|---|---|---|
| `doorModeSwitchState` | `SEMI_AUTOMATIC`、`MANUAL`、`AUTOMATIC` | 对齐半自动/手动/自动门模式。 |
| `atoStartFlag` | boolean | ATO 模式且车辆会话已 `IN_SERVICE` 时置位。 |
| `modeUpgradeConfirmFlag` / `modeDowngradeConfirmFlag` | boolean | 预留司机台确认输入；当前状态投影默认不主动触发。 |
| `automaticTurnbackFlag` | boolean | `operationMode=AR` 时置位。 |
| `directionHandleState` | `ZERO`、`FORWARD`、`BACKWARD` | 当前按列车上下行接入方向投影为前进位。 |
| `masterHandleState` | `ZERO`、`TRACTION`、`BRAKE`、`FAST_BRAKE` | 按牵引、常用制动、紧急制动状态投影。 |

司机台 PLC 原始报文不属于车辆-信号接口本身，而属于车辆控制系统内部外设适配。前端向8080 `POST plc-input` 提交结构化JSON，8080只负责编码46字节并转发；最终由9300 `DriverCabInputController` 接收和执行。信号系统只消费车辆控制系统整理后的 `driverConsoleState` 和 `cabDisplay`。

9300 必须对 `trainId` 存在性、46字节帧头/长度、门模式/方向/主手柄枚举和0–100级位进行严格校验。合法命令与 MA、车门/自检和供电约束在下一tick仲裁；过期人工命令切除牵引并进入安全制动。

### 车辆遥测 JSON 入口

```http
POST /api/signal/vehicles/telemetry
Content-Type: application/json
```

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

该中央写入口已废弃并返回 `410 Gone`。车辆状态只由9300 `step-fleet` 返回的 `trainStates[]` 更新中央镜像；信号模块继续通过8080只读状态与命令投影。

### 车辆遥测二进制入口

```http
POST /api/signal/vehicles/telemetry/content-packet?trainCount=1
Content-Type: application/octet-stream
```

协议模型为 `SignalTrainContentCodec`：

| 字段 | 长度 | 单位/枚举 |
|---|---:|---|
| `trainNo` | 1 byte | 1-255 |
| `speed` | 4 bytes | cm/s，小端无符号整数 |
| `cumulativeDistance` | 4 bytes | cm，小端无符号整数 |
| `direction` | 1 byte | `UP=0x55`、`DOWN=0xaa`、`UNKNOWN=0xff` |
| `loadMassKg` | 4 bytes | kg，小端无符号整数 |
| `faultSpeedLimit` | 1 byte | cm/s |
| `emergencyBrakeApplied` | 1 byte | `1=true` |
| `availableTractionCount` | 1 byte | 可用牵引单元数 |
| `availableBrakeCount` | 1 byte | 可用制动单元数 |

### 车辆视景状态 PUT 入口

```http
PUT /api/signal/vehicles/{trainId}/vision-state
Content-Type: application/json
```

```json
{
  "speedMetersPerSecond": 10.0,
  "accelerationMetersPerSecondSquared": 0.55,
  "accelerationPercent": 50,
  "headPositionMeters": 123.456,
  "headSegmentId": "SEG-1",
  "directionCode": 1,
  "runCondition": "TRACTION",
  "headlightState": "HIGH",
  "departureCountdownSeconds": 30
}
```

该入口只维护信号/ATS 视景适配缓存，不替代 `TrainManager` 的权威车辆状态。UDP 组包时，缓存字段优先级高于 `TrainState`；缓存缺失时按当前仿真状态回退。`operationCode` 可直接覆盖 1 byte 运行工况字段；未覆盖时按 `TRACTION=0x11`、`BRAKE=0x12`、`COAST=0x13` 派生。

## 信号到车辆：命令投影

```http
GET /api/signal/vehicles/commands
```

返回模型为 `SignalVehicleCommand`，由当前 `SignalService.authorities()` 与 `TrainState` 投影生成。除 MA、限速和制动命令外，`cabDisplay` 统一承载 ATP/ATO 到 DMI/司机台显示链路字段，对齐 T13、T24、T25 的显示/控制语义。

| 场景 | 输出行为 |
|---|---|
| 正常 MA | `authorityEndMeters`、`speedLimitMetersPerSecond` 按 `MovementAuthority` 输出。 |
| 未并入车辆控制会话 | `tractionCutoff=true`、`serviceBrakeCommand=true`、`emergencyBrakeCommand=false`，原因 `CONTROL_SESSION_*`。 |
| 无 MA | `tractionCutoff=true`、`serviceBrakeCommand=true`、`emergencyBrakeCommand=true`，原因 `NO_MOVEMENT_AUTHORITY`。 |
| MA 耗尽 | `tractionCutoff=true`、`serviceBrakeCommand=true`、`emergencyBrakeCommand=true`，原因 `MOVEMENT_AUTHORITY_EXHAUSTED`。 |
| 信号限速为 0 | `tractionCutoff=true`、`serviceBrakeCommand=true`，不越级成为调度命令。 |
| 车辆故障限速 | `speedLimitMetersPerSecond=min(MA限速, vehicleFaultSpeedLimitMetersPerSecond)`，原因 `VEHICLE_FAULT_SPEED_LIMIT`。 |

`cabDisplay` 字段：

| 字段 | 取值 | 对应语义 |
|---|---|---|
| `currentDrivingMode` | `DTO`、`ATO`、`AR`、`SM`、`RM` | 当前驾驶模式/信号屏模式。 |
| `maximumAvailableDrivingMode` | `DTO`、`ATO`、`AR`、`SM`、`RM` | 最大可用驾驶模式；故障或未并入时降为 `RM`。 |
| `doorEnable.side` | `NONE`、`LEFT`、`RIGHT`、`BOTH` 等 | 开门使能信息；到站零速窗口内给出门侧。 |
| `doorEnable.automaticOpenAllowed` / `manualOpenAllowed` | boolean | 自动/手动开门许可。 |
| `doorControlMode` | `AUTOMATIC`、`SEMI_AUTOMATIC`、`MANUAL` | 对齐 AA/AM/MM 或自动/半自动/手动门控模式。 |
| `tractionBrakeInfo` | `COASTING`、`TRACTION`、`BRAKING`、`EMERGENCY_BRAKING` | 牵引制动信息。 |
| `departureInfo` | `HOLD`、`DEPART` | 扣车/发车信息。 |
| `turnbackInfo` | `INACTIVE`、`AVAILABLE`、`ACTIVE` | 折返可用/激活状态。 |
| `speedLimitMetersPerSecond` | number | 信号限速或车辆故障限速后的有效限速。 |
| `emergencyBrake` | boolean | ATP/车辆紧急制动显示。 |
| `distanceToNextStationMeters` | number | 距下一站距离。 |

主循环内车辆控制并不从调度系统直接拿命令，而是消费 `MovementAuthority` 和信号命令语义。牵引/制动百分比仍由车辆侧 `OnboardTrainSubsystem` 根据 MA、限速、供电、车辆状态和轨道约束综合决策。

## 信号到视景系统：UDP 发送

```http
POST /api/signal/vision/udp/send?trainId=TR-001&host=18.32.115.28&port=8302
```

配置默认值位于 `railway.simulation.vision`：

| 配置 | 默认值 | 说明 |
|---|---|---|
| `target-host` | `18.32.115.28` | 视景控制机或视景驱动计算机 IP。 |
| `target-port` | `8302` | 默认目标端口；调用参数 `port` 可覆盖。 |
| `local-port` | `0` | 默认随机本地端口；调用参数 `localPort` 可覆盖。 |

UDP 包按小端编码，字段顺序为：数据报计数、信号机状态列表、道岔状态列表、本车车速、本车发车表示器、本车运行工况、本车加速度、本车车头位置、本车车头边号、本车方向、他车数量、他车距离列表、他车边号列表、他车方向列表、他车车速列表。信号机顺序优先使用线路数据 `signals` 顺序，道岔顺序优先使用线路数据 `switches` 顺序；他车信息由当前全车状态组成，但由信号/ATS 视景适配层统一发布。

## 上线/下线场景

推荐列车上线由车辆仿真系统先启动实例，再向中央注册；信号侧生命周期语义仍保留下线、清空和兼容联调能力：

| action | 协议指令 | 行为 |
|---|---|---|
| `ADD` | `0x01` | 兼容创建中央侧 `TrainEntity`、`ExternalTrainControlSession` 和 onboard 节点注册信息。 |
| `DELETE` | `0x02` | 请求指定列车退出信号网和电网，完成摘除后清理本地实体。 |
| `CLEAR` | `0x04` | 请求所有列车退出。 |

上线时序：

1. 车辆仿真系统调用 `POST /vehicle-runtime/trains/launch`，创建车辆仿真实例。
2. 车辆运行时唤醒该车 `VehicleControlQueue`，使随车控制实例可接收 MA、轨道约束和供电约束。
3. 车辆运行时调用中央 `POST /api/trains/runtime-registrations`，中央建立 `TrainEntity` 镜像和外部控制会话。
4. 信号侧输出 MA 后，会话进入 `POWER_ATTACHING`。
5. 供电约束允许受流后，会话进入 `ONLINE_STANDBY`，再进入 `IN_SERVICE`。
6. 车辆开始在主循环中消费 MA/限速并输出运行状态。

下线时序：

1. 信号侧发 `DELETE` 或 `CLEAR`。
2. 会话进入 `SIGNAL_DETACHING`。
3. 下一步进入 `POWER_DETACHING`。
4. 再下一步进入 `DISCONNECTED`。
5. `TrainManager` 清理列车实体、控制会话和 onboard 节点注册信息。

## 场景覆盖矩阵

| 场景 | 当前实现 |
|---|---|
| 正常上线 | `POST /vehicle-runtime/trains/launch` + `POST /api/trains/runtime-registrations` + `ExternalTrainControlSession`。 |
| 正常运行 | `MovementAuthority` 进入 `OnboardTrainSubsystemManager`。 |
| 正常牵引 | 信号给 MA/限速，车辆侧自行算牵引。 |
| 常用制动 | MA 接近、限速为 0 或站停逻辑触发服务制动。 |
| 紧急制动 | 无 MA、MA 耗尽、车辆遥测紧急制动均可进入安全状态。 |
| 门未锁闭 | `VehicleSignalStatus.doorState/selfCheckStatus/faultCode` 暴露给信号。 |
| 制动不可用 | `availableBrakeCount=0` 或故障注入导致禁止发车。 |
| 牵引不可用 | `availableTractionCount=0` 或故障注入导致禁止牵引。 |
| 受流异常 | `currentCollectionStatus` 暴露，车辆侧切牵引并制动。 |
| 超载 | 协议载重进入 `VehicleLoadPolicy`，输出 `OVERLOAD`/`CRITICAL_OVERLOAD`。 |
| 外部仿真失败 | `dataQuality=FALLBACK`，信号可识别数据质量降级。 |
| 正常下线 | `DELETE/CLEAR` 进入摘除状态机并清理实体。 |

## 边界

- 不实现调度到车辆的直接控制接口；调度意图由信号/ATS 折算。
- 不实现轨道区段占用、道岔、坡度、曲线、站距接口；这些属于车辆-轨道或信号-轨道接口族。
- 不由信号系统直接调用 FMU 或外部车辆动力学模型。
- 不由中央系统启动车辆控制系统进程；车辆仿真系统先拉起实例，中央只纳管外部车辆节点会话镜像。
- 不把车辆内部牵引/制动百分比暴露为信号系统直接控制量。
