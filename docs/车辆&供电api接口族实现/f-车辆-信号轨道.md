# 车辆-信号轨道接口族具体实现

## 实现范围

本接口族在后端内部通过 `TrainState`、`TrackConstraint`、`MovementAuthority` 和 `TcmsAtoAdapterService` 实现。车辆侧只提供信号/轨道安全计算需要的车辆能力状态，不实现 MA、联锁、进路或轨道占用算法。

## 车辆侧输出

车辆向信号/轨道可消费字段：

| 字段 | 来源 | 用途 |
|---|---|---|
| `trainId` | `TrainState.id` | 匹配 MA、轨道约束和占用。 |
| `headMileage` / `tailMileage` | `TrainState` | 区段占用和尾部清扫判断。 |
| `lengthMeters` | `TrainState` | 占用范围计算。 |
| `speedMetersPerSecond` | 车辆物理输出 | ATP/MA 安全判断。 |
| `zeroSpeed` | `TrainState` | 站台门、发车、门控条件。 |
| `doorState` | TCMS 状态 | 发车联锁。 |
| `brakeAvailable` | TCMS 状态 | 安全制动能力。 |
| `tractionAvailable` | TCMS 状态 | 发车/运行能力。 |
| `currentCollectionStatus` | 供电约束/车辆状态 | 降级或禁止发车判断。 |
| `loadMassKg` / `overloadStatus` | 协议载重/车辆载重策略 | 供信号侧进行发车联锁、限速或运营处置判断。 |
| `availableTractionCount` / `availableBrakeCount` | 协议可用牵引/制动数量 | 供信号侧评估车辆能力。 |
| `vehicleProtectionReason` | 车辆本地保护策略 | 仅为状态/建议，不等价于调度命令。 |
| `operationMode` | TCMS/ATO | ATO、ATP_BRAKE、DEGRADED 等状态展示。 |
| `faultCode` | TCMS/FMU/注入故障 | 安全降级原因。 |

## 信号/轨道侧输入

车辆侧消费：

- `MovementAuthority.authorityEndMeters`
- `MovementAuthority.speedLimitMetersPerSecond`
- `TrackConstraint.speedLimitMetersPerSecond`
- `TrackConstraint.gradient`
- `TrackConstraint.curveRadiusMeters`
- `TrackConstraint.stationDistanceMeters`

这些字段统一进入 `TcmsAtoAdapterService.decideDynamicsState()`，产生车辆动力学状态。

代码中预留了接口模型：

| 方向 | 模型 | 说明 |
|---|---|---|
| 信号到车辆 | `SignalVehicleCommand` | 表达 MA、限速、牵引切除、常用/紧急制动等信号侧下发量。 |
| 车辆到信号 | `VehicleSignalStatus` | 表达车门、零速、受流、牵引/制动可用、载重和车辆保护原因。 |

调度指令不直接进入车辆模块；调度侧的扣车、临时限速、跳停等策略应先进入信号/ATS，再由信号侧转化为 `MovementAuthority`、速度限制或 `SignalVehicleCommand`。

## 已实现 API 暴露

车辆-信号/轨道接口族当前不新增专用 REST 控制接口；联调通过以下只读状态完成：

| 接口 | 说明 |
|---|---|
| `GET /api/trains` | 查看车辆侧安全接口字段。 |
| `GET /api/simulation/snapshot` | 同时查看 `trains[]`、`trackSegments[]`、`authorities[]`。 |
| `WS /ws/simulation` | 周期推送同一快照。 |

## 增修机制记录

| 类型 | 机制/细节 | 原因 |
|---|---|---|
| 具体逻辑 | 将 `dynamicsState`、`movementAuthorityDistanceMeters`、`stationDistanceMeters`、`stoppingDistanceMeters` 暴露在 `TrainState`。 | 便于联调时解释车辆为何牵引、惰行、制动或停车。 |
| 业务考量 | 不把车辆内部牵引/制动命令作为信号系统输入。 | 信号系统需要安全能力和状态，不应越级控制车辆物理模型。 |
| 边界修正 | 新增 `SignalVehicleCommand` / `VehicleSignalStatus`。 | 明确“调度 -> 信号 -> 车辆”的控制链路。 |
| 现实考量 | 复杂线路拓扑先通过公里标线性投影消费。 | 当前车辆动力学和供电模型仍是 1D 位置模型，复杂图拓扑由信号/轨道侧维护。 |

## 边界

- 不实现 MA 生成。
- 不实现道岔和进路联锁。
- 不实现信号机显示逻辑。
- 不由信号/轨道直接调用 FMU。
