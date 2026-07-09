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
| 信号到车辆 | `GET /api/signal/vehicles/commands` | 将当前 MA/限速投影为车辆侧可消费的信号命令。 |
| 信号到中央纳管 | `POST /api/trains/lifecycle` | ADD/DELETE/CLEAR 管理外部车辆控制会话上线/下线。 |
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

该入口会调用 `TrainManager.applyOperationalTelemetry(...)`，同步更新车辆向信号暴露的速度、累计里程、载重、紧急制动状态、可用牵引单元和可用制动单元。

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

## 信号到车辆：命令投影

```http
GET /api/signal/vehicles/commands
```

返回模型为 `SignalVehicleCommand`，由当前 `SignalService.authorities()` 与 `TrainState` 投影生成。

| 场景 | 输出行为 |
|---|---|
| 正常 MA | `authorityEndMeters`、`speedLimitMetersPerSecond` 按 `MovementAuthority` 输出。 |
| 未并入车辆控制会话 | `tractionCutoff=true`、`serviceBrakeCommand=true`、`emergencyBrakeCommand=false`，原因 `CONTROL_SESSION_*`。 |
| 无 MA | `tractionCutoff=true`、`serviceBrakeCommand=true`、`emergencyBrakeCommand=true`，原因 `NO_MOVEMENT_AUTHORITY`。 |
| MA 耗尽 | `tractionCutoff=true`、`serviceBrakeCommand=true`、`emergencyBrakeCommand=true`，原因 `MOVEMENT_AUTHORITY_EXHAUSTED`。 |
| 信号限速为 0 | `tractionCutoff=true`、`serviceBrakeCommand=true`，不越级成为调度命令。 |
| 车辆故障限速 | `speedLimitMetersPerSecond=min(MA限速, vehicleFaultSpeedLimitMetersPerSecond)`，原因 `VEHICLE_FAULT_SPEED_LIMIT`。 |

主循环内车辆控制并不从调度系统直接拿命令，而是消费 `MovementAuthority` 和信号命令语义。牵引/制动百分比仍由车辆侧 `OnboardTrainSubsystem` 根据 MA、限速、供电、车辆状态和轨道约束综合决策。

## 上线/下线场景

列车上线/退出由信号侧生命周期语义进入中央系统：

| action | 协议指令 | 行为 |
|---|---|---|
| `ADD` | `0x01` | 创建中央侧 `TrainEntity`、`ExternalTrainControlSession` 和 onboard 节点注册信息。 |
| `DELETE` | `0x02` | 请求指定列车退出信号网和电网，完成摘除后清理本地实体。 |
| `CLEAR` | `0x04` | 请求所有列车退出。 |

上线时序：

1. 车辆控制系统作为外部车辆节点已在车辆侧启动。
2. 信号侧发 `ADD`，携带列车号、link ID、偏移和方向。
3. 中央创建外部控制会话，状态从 `CONNECTING` 进入 `SIGNAL_ATTACHING`。
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
| 正常上线 | `SignalTrainLifecycleCommand` + `ExternalTrainControlSession`。 |
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
- 不由中央系统启动车辆控制系统进程；中央只纳管外部车辆节点会话。
- 不把车辆内部牵引/制动百分比暴露为信号系统直接控制量。
