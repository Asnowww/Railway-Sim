# 架构说明

## 总体方案

项目采用：

```text
中心总控 + 模块化单体 + 事件驱动 + 多车实体仿真
```

核心原因是列车数量会从少量演示扩展到几十、上百甚至 500 辆。如果给每辆车分配一个微服务，主要压力会出现在服务间通信、序列化、网络连接、线程调度和部署管理上，而不是列车运动计算本身。

本项目采用 `TrainManager + TrainEntity`：

- `TrainEntity` 表示一辆车的状态和运动行为。
- `TrainManager` 管理所有列车实体，并在每个仿真步长内批量更新。
- `SimulationRuntime` 提供统一仿真时钟。
- 信号、轨道、供电、调度等模块在同一个后端进程内协作。
- REST 和 WebSocket 只作为外部接口，不进入仿真热循环。

## 后端模块

```text
backend/src/main/java/com/railwaysim
├─ infrastructure 线路工作簿/YAML 适配、标准静态对象、线性投影
├─ simulation      仿真总控、统一时钟、快照
├─ simulation/event 内部事件总线
├─ train           多车实体、车辆状态、车辆管理器
├─ vehicle         TCMS/ATO 适配、车辆物理输入输出、FMU 调用端口
├─ track           轨道区段、占用状态、拓扑服务
├─ signal          移动授权 MA、限速计算
├─ power           供电分区状态
├─ dispatch        运行计划、调度指令、扰动处理
├─ monitor         告警、指标、状态聚合
├─ api             REST 和 WebSocket 接口
└─ config          WebSocket、仿真参数配置
```

## 仿真主循环

一个仿真步长内的推荐顺序：

```text
SimulationRuntime.tick()
→ TrackService.updateOccupancy()
→ TrackService.constraintsForTrains()
→ SignalService.calculateAuthorities()
→ PowerService.constraintsForTrains()
→ DispatchService.constraintsForTrains()
→ EXTERNAL_HTTP: vehicle-runtime-service /vehicle-runtime/step-fleet
→ fallback: OnboardTrainSubsystemManager + VehiclePhysicsClient.stepFleet()
→ TrainManager.apply vehicle physics output
→ TrackService.updateOccupancy()
→ PowerService.update()
→ SimpleEventBus.drain()
→ MonitorService.buildSnapshot()
→ WebSocket push snapshot
```

后续如果加入客流、调度、故障注入，可以插入到主循环中，但要保持一个原则：模块之间优先使用内存对象和内部事件，不要在每个 tick 中走 REST、MySQL 或 WebSocket。

车辆运行时主链路先通过 `vehicle-runtime-service` 暴露，每车内部包含控制队列和仿真队列。中央本地 `VehiclePhysicsClient.stepFleet()` 仍保留为可运行降级端口；后续接入 Python FMU 服务或真实车辆模型时，只替换车辆运行时内部仿真层或本地 fallback 实现，不让信号、轨道、供电和调度模块直接操作 FMU。

## 数据分层

实时状态放内存：

- 列车位置、速度、满载率
- 车辆物理输出、能耗状态、最后一次 FMU 输出
- 区段占用
- 信号机状态、MA、限速
- 供电分区电压、电流、带电状态
- 当前告警和通信状态

MySQL 存历史和配置：

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

新增 `vehicle-runtime-service` 作为外部车辆运行时，端口 `9300`。列车上线推荐先由车辆仿真系统调用 `POST /vehicle-runtime/trains/launch`，创建车辆仿真实例、唤醒本车控制队列，再向中央 `/api/trains/runtime-registrations` 注册状态镜像。中央在每个 tick 将列车状态、MA、轨道约束和供电约束同步发送给外部服务，外部服务按每车一对控制/仿真队列返回 `VehiclePhysicsOutput` 和 `TrainStateReport`。仅 `EXTERNAL_HTTP` 权威外部服务健康且供电仿真启用时，车辆运行时把牵引/再生负荷推送到 `power-network-service:9200`，中央供电控制随后只拉取供电状态；`DUAL_SHADOW` 不写供电负荷，外部车辆服务失败时中央立即回退本地 `OnboardTrainSubsystemManager + VehiclePhysicsClient` 链路，并把车辆运行时健康状态标记为 `FALLBACK`。

```text
vehicle-runtime-service:9300
  -> POST /vehicle-runtime/trains/launch
  -> POST backend /api/trains/runtime-registrations
中央系统
  -> TrainManager.tickAll()
  -> vehicle-runtime-service:9300
       -> VehicleControlQueue + VehicleSimulationQueue
       -> POST power-network-service:9200/power-network/state/query
  -> PowerIntegrationService
       -> GET power-network-service:9200/power-network/state
  -> REST/WebSocket 快照
```

外部 Python FMU 服务默认关闭，`FmuVehiclePhysicsAdapter` 当前使用 `SimpleVehicleDynamicsModel` 作为可运行降级模型。后续启用外部 FMU 时，如果远程步进失败，系统会发布 FMU 失败和降级事件，并继续用简化模型推进列车，避免仿真主控被模型服务拖垮。

## 外部车辆运行时与 FMU 服务

`vehicle-runtime-service` 是首期推荐的外部车辆侧运行时，内部以 `VehicleRuntimeInstance -> VehicleControlQueue + VehicleSimulationQueue` 管理每辆车。启动边界是外部车辆仿真先 `launch`，中央只接收注册镜像和后续 tick 输出。首期物理模型移植 Java fallback，不接真实 FMU；已完成单元测试、中央 HTTP 集成测试和供电负荷转发测试，但未进行 `9300 + 9200 + backend` 长时间逻辑仿真验收。

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
