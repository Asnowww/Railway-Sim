# 架构说明

## 总体方案

项目采用：

```text
中心总控 + 模块化单体 + 事件驱动 + 多车实体仿真
```

核心原因是列车数量会从少量演示扩展到几十、上百甚至 500 辆。如果给每辆车分配一个微服务，主要压力会出现在服务间通信、序列化、网络连接、线程调度和部署管理上，而不是列车运动计算本身。

本项目的列车状态权威持有者为 **`vehicle-runtime-service:9300`**（`TrainStateHolder`），中央 `backend:8080` 持有镜像状态。

- **9300** 持有 `TrainStateHolder`（等效 `TrainEntity` 的 40+ 可变字段），是列车位置、速度、牵引力、能耗、TCMS 诊断等状态的权威源。
- **线路基础设施仍由 8080 静态配置域持有**：车站/站台 ID 与里程只在 bootstrap 时作为只读拓扑下发。9300 用它执行停车、到站识别和停站计时，但不得自行生成车站。
- **中央** 的 `TrainEntity` 固定为 9300 权威状态的镜像，不再承担本地车辆物理计算。
- `SimulationRuntime` 提供中央统一仿真时钟；9300 在自主模式下有独立 `@Scheduled` 时钟。
- 调度、信号、轨道在 `backend:8080` 内协作；拆分部署时，单车控制/状态/物理由 `vehicle-runtime-service:9300` 权威执行，权威供电计算由 `power-network-service:9200` 执行。
- `step-fleet` 接口不再传输列车状态（9300 从 `TrainStateHolder` 读取），只传输约束（轨道/信号/调度/供电）。
- REST 和 WebSocket 只作为外部接口，不进入仿真热循环。
- 每列车实例可通过 `POST /vehicle-runtime/trains/{id}/manual-control` 独立手动控制。

## 中央后端模块（8080）

```text
backend/src/main/java/com/railwaysim
├─ infrastructure 线路工作簿/YAML 适配、标准静态对象、线性投影
├─ simulation      仿真总控、统一时钟、快照
├─ simulation/event 内部事件总线
├─ train           多车实体镜像、车辆管理器（权威在 9300）
├─ vehicle         车辆运行时集成层、FMU 调用端口
├─ track           轨道区段、占用状态、拓扑服务
├─ signal          移动授权 MA、限速计算
├─ power           供电分区状态
├─ dispatch        运行计划、调度指令、扰动处理
├─ monitor         告警、指标、状态聚合
├─ api             REST 和 WebSocket 接口
└─ config          WebSocket、仿真参数配置
```

## vehicle-runtime-service 模块（9300）

```text
vehicle-runtime-service/src/main/java/com/railwaysim/vehicleruntime
├─ runtime/
│  ├─ VehicleRuntimeManager.java    总入口，管理所有 VehicleRuntimeInstance
│  ├─ VehicleRuntimeInstance.java    单车运行时实例（控制+仿真队列）
│  ├─ TrainStateHolder.java          每列车权威状态持有者（NEW）
│  ├─ VehicleControlQueue.java       控制队列（约束→牵引/制动指令转换）
│  ├─ VehicleRuntimeTickClock.java   自主 tick 时钟（NEW）
│  ├─ DriverCommandHolder.java       司控台命令缓存
│  └─ FmuHttpVehiclePhysicsExecutor.java / JavaFallbackVehiclePhysicsExecutor.java
├─ api/
│  ├─ VehicleRuntimeController.java       REST 端点
│  ├─ DriverCabInputController.java       PLC 控制输入执行
│  └─ PeripheralAggregationController.java 9300 外设聚合入口（仅 PLC_INPUT）
├─ model/                            请求/响应 DTO
├─ protocol/                         RSIM 聚合帧编解码和通道方向校验
└─ drivercab/                        PLC 原生协议编解码
```

## 仿真主循环（中央驱动模式）

中央驱动模式下，一个仿真步长内的顺序：

```text
SimulationRuntime.tick()
→ TrackService.updateOccupancy()
→ TrackService.constraintsForTrains()
→ SignalService.calculateAuthorities()
→ DispatchService.constraintsForTrains()

→ EXTERNAL_HTTP 模式 —— 9300 权威：
   → 打包约束（无列车状态）→ POST /vehicle-runtime/step-fleet
   → 9300 从本地 TrainStateHolder 读取状态 → 控制决策 → FMU 物理计算
   → 9300 返回 { VehiclePhysicsOutput[], TrainStateReport[], TrainStateSnapshot[], events[] }
   → 中央通过 TrainManager.updateMirrorState() 更新镜像状态
   → 事件桥接到中央 SimpleEventBus

→ 9300 不可用 —— 当前 tick 失败并进入可观测降级状态：
   → VehicleRuntimeIntegrationService 更新健康状态并抛出稳定领域异常
   → SimulationRuntime 中止本 tick，不更新中央车辆镜像

→ TrackService.updateOccupancy()
→ PowerService.update()
→ SimpleEventBus.drain()
→ 持久化 runId/tick 下的控制决策、车辆、能耗、供电、信号、轨道和 FMU 记录
→ MonitorService.buildSnapshot()
→ WebSocket push snapshot
```

## 仿真主循环（9300 自主模式）

9300 可脱离中央独立运行，用于单列车调试或教学演示：

```text
9300 VehicleRuntimeTickClock @Scheduled
→ 生成默认约束（全速、无限制）
→ 调用 VehicleRuntimeManager.stepFleet()
   → 从本地 TrainStateHolder 读取状态
   → VehicleControlQueue.control() 控制决策
   → FMU / JavaFallback 物理计算
   → 更新 TrainStateHolder → 检测事件
→ 等待下一个定时 tick
```

切换方式：
- `POST /vehicle-runtime/autonomous/enable` — 开启自主模式
- `POST /vehicle-runtime/autonomous/disable` — 关闭自主模式（恢复中央驱动）
- `POST /vehicle-runtime/tick` — 单步手动推进

后续如果加入客流、调度、故障注入，可以插入到主循环中，但要保持一个原则：模块之间优先使用内存对象和内部事件，不要在每个 tick 中走 REST、MySQL 或 WebSocket。

车辆运行时主链路通过 `vehicle-runtime-service` 暴露，每车内部包含控制队列、仿真队列和 `TrainStateHolder`（权威状态持有者）。9300保持100 ms TCMS控制与中央回传周期；每个控制周期内保持控制指令不变，连续调用9000执行5个20 ms物理子步，并仅在第5步完成后提交权威车辆状态。9300异常会中止当前tick，中央不计算替代物理状态。后续接入真实车辆模型时，只替换9300内部仿真层或其内部fallback实现，不让信号、轨道、供电和调度模块直接操作FMU。

## 数据分层

实时状态放内存：

- 列车位置、速度、满载率
- 车辆物理输出、能耗状态、最后一次 FMU 输出
- 区段占用
- 信号机状态、MA、限速
- 供电分区电压、电流、带电状态
- 当前告警和通信状态

MySQL 存历史和配置：

- `simulation_run` 运行生命周期，状态为 `CREATED/RUNNING/PAUSED/COMPLETED/FAILED/CANCELLED_BY_RESET`
- `vehicle_control_command_log` 每 tick 最终控制决策及 `commandId/traceId`
- 运行计划
- 调度指令记录
- 告警记录
- 历史运行轨迹
- 车辆物理快照、牵引能耗、再生制动记录
- FMU 调用日志和故障日志
- 接触轨分区电压电流记录
- 用户权限
- 系统配置变更

YAML 存仿真静态配置：

- 线路拓扑
- 站点位置
- 区段长度、限速、坡度
- 道岔配置
- 供电分区
- 车辆牵引/制动参数、FMU 映射、接触轨参数、模块步长

如果外部线路数据来自电子地图工作簿，则先进入 `infrastructure` 适配层，完成：

- sheet 级结构解析
- 单位转换与哨兵值清洗
- 标准对象归一化
- 面向当前 `TrainState.positionMeters` 1D 运行环路的线性投影

也就是说，工作簿不是直接喂给 `TrackService`、`SignalService` 和 `PowerService`，而是先转成系统内部统一静态对象，再由各模块消费。

## 事件与降级

车辆和供电链路通过内部事件总线发布运行状态变化，包括 `VehiclePhysicsUpdated`、`TractionPowerChanged`、`BrakeForceChanged`、`RegenerativePowerGenerated`、`ThirdRailVoltageChanged`、`PowerLimitTriggered`、`FmuStepFailed` 和 `FmuFallbackActivated`。监控层只消费事件和状态快照生成告警，不直接参与车辆物理计算。

监控层将每个活动告警映射为结构化 `FaultImpact`，并维护稳定 occurrence 生命周期。9300/9200 的健康状态由 `ServiceHealthService` 归一化为 `UP/DEGRADED/STALE/FALLBACK/RECOVERING`。从异常恢复时不允许直接回 UP；`RecoveryGate` 必须同时验证 runId、tick 水位、topology/config hash 及 model/parameter version。告警活动索引、最后健康态和 last-good baseline 都持久化，8080 重启后回填。

9200 重启会先回到未 bootstrap 状态，拒绝权威查询和 step；8080 探测到该状态后重下发拓扑。9300 可跨 8080 重启保留车辆实例，但仅在新 run 的 tick 0/1 执行 run rollover，同时清除旧 run 的命令、约束和物理缓存，避免旧 tick 或旧输入污染新运行。

新增 `vehicle-runtime-service` 作为外部车辆运行时，端口 `9300`。列车上线推荐先由车辆仿真系统调用 `POST /vehicle-runtime/trains/launch`，创建车辆仿真实例、唤醒本车控制队列，再向中央 `/api/trains/runtime-registrations` 注册状态镜像。现场四端口中只有 PLC 产生业务输入：8080 把 PLC 46 字节原始帧封装为 RSIM Version 1 聚合帧，通过 `POST /vehicle-runtime/peripherals/frame` 送入 9300；网络屏、信号屏和视景系统只接收 8080 的显示输出。8080 还提供信号系统需要的 `driverConsoleState/cabDisplay` 和 26 字节 PLC 显示输出。详细方向和帧格式见 `docs/司机台与显示系统端口聚合协议.md`。`split` 模式下中央每 tick 只同步列车状态、MA、轨道和调度约束；不再计算或下发供电约束。9300 在控制前向 9200 请求电压/可用功率约束，完成全车步进后每 tick 最多一次提交完整分区负荷；请求必须携带同一 `simulationRunId`。中央只镜像 9200 快照用于前端和告警，绝不补写车辆负荷。生产 `EXTERNAL_HTTP` 模式下 9300 失败会明确中止当前 tick，不由 8080 静默切换成另一套控制/物理结果；Java fallback 仅在 9300 内部按已定义的 FMU 降级策略执行。

```text
vehicle-runtime-service:9300（列车状态权威持有者）
  -> POST /vehicle-runtime/trains/launch（车辆启动）
  -> POST backend /api/trains/runtime-registrations（中央镜像注册）
  <- POST /vehicle-runtime/peripherals/frame（RSIM 聚合帧，仅 PLC_INPUT）
  <- POST /vehicle-runtime/trains/{id}/manual-control（手动控制）
  <- POST /vehicle-runtime/tick（自主单步推进）
  -> 返回 TrainStateSnapshot（权威状态快照）

中央系统（调度/信号/控制编排）
  -> TrackService.updateOccupancy()
  -> SignalService.calculateAuthorities()
  -> DispatchService.evaluate()
  -> PowerService.constraintsForTrains()
  -> POST /vehicle-runtime/step-fleet（仅发约束，无列车状态）
       <- 返回 { trainOutputs, trainReports, trainStates, events }
  -> TrainManager.updateMirrorState(trainStates) ← 镜像更新
  -> SimpleEventBus 发布事件 ← 事件桥接
  -> PowerService.update()
  -> WebSocket push snapshot
```

外部 Python FMU 服务默认关闭，`FmuVehiclePhysicsAdapter` 当前使用 `SimpleVehicleDynamicsModel` 作为可运行降级模型。后续启用外部 FMU 时，如果远程步进失败，系统会发布 FMU 失败和降级事件，并继续用简化模型推进列车，避免仿真主控被模型服务拖垮。

## 外部车辆运行时（9300）与 FMU 服务

`vehicle-runtime-service` 是首期推荐的外部车辆侧运行时，内部以 `VehicleRuntimeInstance -> TrainStateHolder + VehicleControlQueue + VehicleSimulationQueue` 管理每辆车。**9300 持有每列车的权威状态**（`TrainStateHolder`），中央系统仅保留镜像副本。

### 9300 核心特性

- **权威状态持有**：`TrainStateHolder` 包含等效 `TrainEntity` 的 40+ 可变字段（位置、速度、力、能耗、TCMS 诊断、司控台状态、车站追踪），通过 `snapshot()` 生成 `TrainStateSnapshot` 供中央消费。
- **只读停车拓扑**：`TrainStateHolder` 的车站表来自 8080 bootstrap 的当前线路配置；已删除按线路长度构造 6 个等距站的兼容逻辑。静态拓扑来源不改变 9300 对列车控制和动态状态的权威性。
- **自主时钟**：`VehicleRuntimeTickClock` 提供 `@Scheduled` 定时推进，不依赖中央驱动。通过 `POST /autonomous/enable|disable` 切换。
- **手动控制**：每列车可通过 `POST /trains/{id}/manual-control` 独立设置牵引/制动/紧急制动/方向命令，下个 tick 通过 `DriverCommandHolder` 生效。`VehicleControlQueue.decideDynamicsState()` 已有 manualCommand 分支。
- **司控台 PLC**：`DriverCabInputController` 接收 46 字节 PLC 二进制输入，解码后存入 `DriverCommandHolder` 并应用状态变更到 `TrainStateHolder`。
- **事件检测**：`stepFleet()` 自动检测位置变化、牵引功率变化、制动变化、回馈功率变化和故障码变化，返回 `events[]` 列表，中央桥接到 `SimpleEventBus`。
- **双模兼容**：`stepFleet()` 同时支持旧格式（请求含 `trains` 列表）和新格式（仅约束）。新旧模式都填充 `trainStates` 响应字段。

### 启动流程

启动边界是外部车辆仿真先 `launch` 注册到 9300，中央通过 `step-fleet` 响应中的 `trainStates` 接收状态镜像，不再反向注册。首期物理模型移植 Java fallback，不接真实 FMU；已完成单元测试、中央 HTTP 集成测试和供电负荷转发测试。

`fmu-service` 采用 HTTP JSON 批量调用作为第一版接入方式，接口为 `POST /step-fleet`。服务内部按 `FmuManager → FleetStepper → TrainFMUInstance` 分层，当前 `TrainFMUInstance` 使用 Python fallback 模型执行，后续导出 `TrainTractionBrake.fmu` 后在这一层替换为 FMPy/PyFMI 加载 FMU。

Modelica 草案位于 `fmu-service/modelica/TrainTractionBrake.mo`，只覆盖车辆牵引、制动、阻力、纵向动力学、牵引功率、取流电流和再生制动功率，不把信号、轨道、调度和接触轨供电整体放进 Modelica。

## 前端模块

```text
frontend/src
├─ views           页面级视图
├─ components      可复用监控组件
├─ api             REST/WebSocket 封装
├─ types           前后端共享概念的 TS 类型
└─ styles          全局样式
```

前端通过 REST 做控制和查询，通过 WebSocket 接收实时快照。不要让每个组件自己建立 WebSocket，统一由 `api/ws.ts` 维护连接，再分发数据。

初始化阶段前端只保留工程骨架和目录边界，不预置业务界面。各成员后续在自己的功能分支中补充视图和组件。
