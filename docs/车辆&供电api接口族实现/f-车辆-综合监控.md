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
| `/api/vehicle/runtime-health` | GET | 外部车辆运行时健康状态、实例队列和 fallback 原因。 |
| `/api/trains/runtime-registrations` | POST | 服务间注册入口；车辆仿真实例启动后由 9300 调用，综合监控不直接调用。 |

## 控制/演示接口

| 接口 | 方法 | 说明 |
|---|---|---|
| `/api/trains/{trainId}/faults` | POST | 注入车辆仿真故障。 |
| `/api/trains/{trainId}/faults/clear` | POST | 清除车辆仿真故障。 |
| `/api/trains/lifecycle` | POST | 外部车辆控制会话 ADD/DELETE/CLEAR 兼容入口；推荐上线流程已迁移到 9300 的 `launch`。 |
| `/api/vehicle/runtime-health` | GET | 查看9300运行时健康、实例数量、数据质量和最近错误。 |

写接口请求必须包含 `confirmToken=SIMULATION_CONFIRM`。

综合监控侧应把 `/api/trains/lifecycle` 理解为联调/信号协议入口，而不是数据库维护入口。推荐启动链路是 `vehicle-runtime-service /vehicle-runtime/trains/launch -> /api/trains/runtime-registrations`，前端通过中央 `/api/vehicle/runtime-health`、`controlSessionState`、`signalNetworkStatus` 和 `powerNetworkStatus` 判断是否真正上线；只有 `IN_SERVICE + ATTACHED + ATTACHED` 才表示已进入中央主循环。

## WebSocket 快照字段

`trains[]` 复用 `TrainState`，关键字段包括：

- 位置速度：`positionMeters`、`headMileage`、`tailMileage`、`speedMetersPerSecond`
- 外部控制会话：`controlSessionState`、`signalNetworkStatus`、`powerNetworkStatus`、`controlSessionReason`
- 控制状态：`operationMode`、`dynamicsState`、`dynamicsConstraintReason`
- 车辆设备：`doorState`、`tractionState`、`brakeState`、`currentCollectionStatus`
- 可用性：`tractionAvailable`、`brakeAvailable`、`selfCheckStatus`
- 能耗：`tractionPowerWatts`、`regenPowerWatts`、`energyConsumedKwh`、`energyRegeneratedKwh`
- 故障：`faultCode`、`faultLevel`、`availableOperationMode`、`dataQuality`

快照顶层 `vehicleRuntime` 字段用于展示 `EXTERNAL_HTTP`、`FALLBACK`、实例数量和最近错误。综合监控只读该字段，不直接写入外部 `vehicle-runtime-service:9300`。

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
