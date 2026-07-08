# 车辆-综合监控接口族具体实现

## 实现范围

车辆模块向综合监控提供列车运行状态、TCMS 状态、牵引/制动/受流/车门状态、载客率、能耗和故障等级。综合监控负责展示，不参与车辆自身控制。

## REST 接口

| 接口 | 方法 | 返回 |
|---|---|---|
| `/api/trains` | GET | 全部列车状态。 |
| `/api/trains/{trainId}` | GET | 单车状态。 |
| `/api/trains/{trainId}/energy` | GET | 单车牵引能耗、再生能量和净能耗。 |
| `/api/trains/{trainId}/faults` | GET | 单车故障注入/清除历史。 |
| `/api/energy/trains` | GET | 全部列车能耗汇总。 |

## 控制/演示接口

| 接口 | 方法 | 说明 |
|---|---|---|
| `/api/trains/{trainId}/faults` | POST | 注入车辆仿真故障。 |
| `/api/trains/{trainId}/faults/clear` | POST | 清除车辆仿真故障。 |

写接口请求必须包含 `confirmToken=SIMULATION_CONFIRM`。

## WebSocket 快照字段

`trains[]` 复用 `TrainState`，关键字段包括：

- 位置速度：`positionMeters`、`headMileage`、`tailMileage`、`speedMetersPerSecond`
- 控制状态：`operationMode`、`dynamicsState`、`dynamicsConstraintReason`
- 车辆设备：`doorState`、`tractionState`、`brakeState`、`currentCollectionStatus`
- 可用性：`tractionAvailable`、`brakeAvailable`、`selfCheckStatus`
- 能耗：`tractionPowerWatts`、`regenPowerWatts`、`energyConsumedKwh`、`energyRegeneratedKwh`
- 故障：`faultCode`、`faultLevel`、`availableOperationMode`、`dataQuality`

## 增修机制记录

| 类型 | 机制/细节 | 原因 |
|---|---|---|
| 具体逻辑 | 车辆故障注入直接覆盖 `TrainState` 的门、牵引、制动、受流、自检和故障等级字段。 | 若只记录故障日志而不影响车辆状态，TCMS/ATO 下一 tick 无法体现故障约束。 |
| 业务考量 | 车辆故障接口只暴露故障码和状态，不暴露牵引/制动内部控制参数作为调度输入。 | 车辆自身控制参数属于车辆层内部诊断，综合监控只需要展示摘要。 |
| 现实考量 | 维修状态先由 `faultLevel` 映射为 `NONE`、`INSPECTION_REQUIRED`、`REPAIR_REQUIRED`。 | 当前没有完整维修工单系统，先提供可展示的预留状态。 |

## 验收点

- 注入 `DOOR_NOT_LOCKED` 后，`doorState=OPEN`、`selfCheckStatus=FAIL`、`faultLevel=3`。
- 注入 `BRAKE_UNAVAILABLE` 后，`brakeAvailable=false`，车辆状态进入故障。
- 清除故障后，车辆返回当前物理模型输出状态。
