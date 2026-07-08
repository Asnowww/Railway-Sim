# 供电-车辆接口族具体实现

## 实现范围

本接口族是后端仿真热循环内的内部接口，不直接暴露给调度或前端作为控制入口。供电模块只输出约束，车辆模块通过 `OnboardTrainSubsystemManager` 消费约束并生成车辆物理输入。即使供电设备状态来自外部供电仿真系统，车辆模块仍只对接中央供电控制模块，不直接访问外部服务。

列车上线由信号生命周期 `ADD` 进入中央系统；供电模块不通过 SQL 或供电接口创建列车。新车被 `TrainManager` 纳管后，供电模块在下一轮 `constraintsForTrains()` 中按位置输出 `PowerConstraint`，再由车辆控制节点决定牵引、惰行或制动。

## 代码落点

| 方向 | 实现位置 | 说明 |
|---|---|---|
| 供电到车辆 | `PowerService.constraintsForTrains()` -> `PowerConstraintService.constraintsForTrains()` | 根据列车位置匹配中央供电分区，输出 `PowerConstraint`。 |
| 车辆到供电 | `PowerService.updateFromVehicleOutputs()` -> `PowerConstraintService.calculateStates()` | 聚合车辆电流、牵引功率、再生功率和受影响列车，并结合设备级状态重算分区能力。 |
| 设备状态接入 | `PowerIntegrationService` | 只供中央供电控制模块内部使用；车辆模块不依赖外部供电仿真协议。 |
| 单车基层智能子系统 | `OnboardTrainSubsystemManager.control()` | 将供电约束、信号/轨道约束转换为本车控制输入；调度约束先由信号模块折算为 MA/限速。 |
| 快照输出 | `PowerSectionState`、`TrainState` | WebSocket 和 REST 共用同一状态对象。 |

## 数据流

```text
TrainState.positionMeters
  -> PowerService.constraintsForTrains()
  -> PowerConstraint(sectionId, railVoltage, powerAvailableWatts, powerDeratingFactor, regenAvailable)
  -> OnboardTrainSubsystemManager
  -> OnboardTrainSubsystem
  -> VehiclePhysicsInput
  -> VehiclePhysicsClient / LOCAL / EXTERNAL_UDP / EXTERNAL_RTLAB_API / DUAL_SHADOW
  -> VehiclePhysicsOutput
  -> PowerService.updateFromVehicleOutputs()
  -> PowerConstraintService.calculateStates()
```

`PowerIntegrationService` 在供电模块内部同步变电所、隔离开关、接触轨和杂散电流风险状态。车辆侧只消费 `PowerConstraint`，不需要知道这些设备状态来自本地配置、fallback 还是外部供电仿真系统。

## 外部车辆仿真适配

外部仿真系统只接在车辆物理端口，不越级读取或控制供电分区。供电状态仍由 `PowerService` 维护，车辆指令仍由 `OnboardTrainSubsystem` 根据供电、信号和轨道约束生成；调度约束经信号模块下发。

| 适配对象 | 本期实现 | 说明 |
|---|---|---|
| 本地回退 | `LocalFallbackVehicleAdapter` | 默认模式，保持自研仿真可跑。 |
| UDP 高频车辆控制 | `ExternalUdpVehicleAdapter` | 按 20ms、小端、1-20 车 `command/percent` 和 `acc/speed/distance` 对齐协议。 |
| RT-LAB API | `RtLabApiVehicleAdapter` | 封装 `OpalOpenProject`、`OpalLoad`、`OpalExecute`、`OpalReset`、`OpalCloseProject` 和变量写入；当前用 stub 保持接口可测。 |
| 影子比对 | `ShadowCompareAdapter` | 本地输出作为权威，外部输出只用于误差对比。 |

指令映射固定为：

| 内部输入 | 外部协议 |
|---|---|
| `tractionCommand > 0` | `command=1`，百分比为牵引比例四舍五入到 0-100。 |
| `brakeCommand > 0` | `command=2`，百分比为制动比例四舍五入到 0-100。 |
| `emergencyBrakeCommand=true` | `command=2`，`percent=100`。 |
| 无牵引且无制动 | `command=0`，`percent=0`。 |

`segNo + offset` 由 `ExternalSegmentMapper` 依据线路区段生成；外部段号通过 `railway.simulation.external-simulator.segment-mapping` 覆盖。该映射属于车辆-轨道耦合接口，最终段号需与信号/轨道共同确认。

当前 `PowerSystemAndTrainsV1/...` 仍按 RT-LAB 车辆/综合动力学模型命名空间处理，不作为车辆模块访问供电设备状态的入口。供电设备级状态统一由中央供电控制模块吸收后折算为 `PowerConstraint` 和 `PowerSectionState`。

## 已实现字段

供电到车辆：

| 字段 | 来源 | 车辆侧用途 |
|---|---|---|
| `sectionId` | 供电分区映射 | 诊断和影响范围。 |
| `railVoltage` | 分区电压 | 判断低压、失电和受流状态。 |
| `powerAvailableWatts` | 分区可用功率 | 限制牵引可用功率。 |
| `currentCollectionAvailable` | 分区状态 | 失电时切除牵引。 |
| `powerDeratingFactor` | 欠压/过流状态 | 触发 `POWER_DERATED`。 |
| `regenAvailable` | 分区状态 | 再生制动吸收能力标识。 |
| `constraintReason` | 分区状态 | 车辆状态机约束原因。 |

`constraintReason` 可出现 `NORMAL`、`UNDERVOLTAGE`、`OVERCURRENT`、`DEENERGIZED`、`TRIPPED`、`ISOLATED`、`MAINTENANCE_LOCKED` 等值。车辆侧只根据约束原因和可用功率调整牵引、制动和受流状态，不反向操作供电设备。

车辆到供电：

| 字段 | 来源 | 供电侧用途 |
|---|---|---|
| `newPositionMeters` | 车辆物理输出 | 映射供电分区。 |
| `railCurrentAmps` | 车辆物理输出 | 计算压降和过流。 |
| `tractionPowerWatts` | 车辆物理输出 | 计算分区负荷。 |
| `regenPowerWatts` | 车辆物理输出 | 计算再生吸收/未吸收功率。 |
| `energyRegeneratedKwh` | 车辆物理输出 | 能耗统计接口复用。 |

## 增修机制记录

| 类型 | 机制/细节 | 原因 |
|---|---|---|
| 具体逻辑 | 供电故障注入后立即刷新 `PowerSectionState`，不等待下一 tick。 | REST 调用需要立即可见，否则联调时会出现“接口成功但状态未变”的误判。 |
| 业务边界 | 供电接口只输出可用能力、受影响列车和约束原因，不直接下发扣车/折返。 | 调度策略属于调度模块，供电模块不能越级控制运营决策。 |
| 现实考量 | 供电拓扑由中央配置和设备级状态共同生成，车辆模块仍只消费中央约束。 | 避免车辆模块与外部供电仿真协议耦合，保证主循环边界稳定。 |

## 验收点

- `POST /api/power/sections/{sectionId}/faults` 注入 `DEENERGIZED` 后，同分区列车下一 tick 牵引切除。
- `POST /api/power/sections/{sectionId}/faults` 注入 `UNDERVOLTAGE` 后，车辆进入供电降额或受限状态。
- `GET /api/power/sections/{sectionId}` 能看到 `affectedTrainIds`、`availablePowerWatts`、`breakerStatus`、`protectionState`。
