# 小组成员开发入口

这份文档的目标是让每个人知道“从哪里开始写、先写什么、怎么不影响别人”。

## 共同约定

先跑通最小闭环：

```text
后端模拟多辆列车运行
→ WebSocket 推送快照
→ 前端线路图展示列车位置
→ 信号/轨道/供电状态跟随变化
→ 调度或故障触发告警
```

所有人开发时遵守三条规则：

1. 先看 `docs/API_CONTRACT.md`，不要随意改接口字段名。
2. 优先改自己负责的模块目录。
3. 公共文件改动先在群里说一声，尤其是 `SimulationSnapshot`、前端 `types`、接口文档和数据库结构。

## 组长：架构、集成、接口

主要目录：

```text
docs/
backend/src/main/java/com/railwaysim/simulation
backend/src/main/java/com/railwaysim/api
frontend/src/api
```

先做任务：

1. 维护 `docs/API_CONTRACT.md`，保证前后端字段一致。
2. 维护 `SimulationRuntime` 的主循环顺序。
3. 每天检查各模块是否能合并运行。
4. 整理演示场景：正常运行、客流拥挤、信号故障、供电异常、调度干预。

不要频繁改每个人的业务模块。需要调整公共对象时，先发通知，再统一改。

## 综合监控负责人

主要目录：

```text
frontend/src/views/monitor/
frontend/src/components/monitor/
backend/src/main/java/com/railwaysim/monitor
```

先做任务：

1. 先设计综合监控需要展示的数据结构，确认是否复用 `SimulationSnapshot`。
2. 建立综合监控页面目录和组件目录，但不要修改其他模块的页面。
3. 从最小内容开始：列车数量、告警数量、拥挤列车数。
4. 后续再加线路图、客流热力图、趋势图和指标卡片。

开发建议：

- 前端先使用 WebSocket 快照，不要自己轮询多个接口。
- 告警展示只依赖 `Alarm` 类型，不直接耦合车辆、供电、信号内部对象。

## 运营调度负责人

主要目录：

```text
backend/src/main/java/com/railwaysim/dispatch
frontend/src/views/dispatch/
frontend/src/components/dispatch/
```

先做任务：

1. 定义运行计划：高峰、平峰、低谷的发车间隔。
2. 根据列车间隔识别轻微扰动。
3. 生成调度建议，例如延长停站、缩短停站、限速、跳停的模拟指令。
4. 后续在调度视图中展示当前运行模式、发车间隔和调度建议。

开发建议：

- 调度模块不要直接修改前端。
- 调度指令先写成 `DispatchCommand`，由 `SimulationRuntime` 或 `TrainManager` 执行。
- 策略先简单可解释，答辩时比复杂但不可控更好。

## 信号与轨道负责人

主要目录：

```text
backend/src/main/java/com/railwaysim/signal
backend/src/main/java/com/railwaysim/track
backend/src/main/java/com/railwaysim/infrastructure
frontend/src/views/signal-track/
frontend/src/components/signal-track/
config/line-demo.yaml
```

先做任务：

1. 维护 `infrastructure` 里的静态线路适配对象，确认 YAML 和线路工作簿导入后的标准字段一致。
2. 在 `TrackService` 中根据列车位置计算区段占用。
3. 在 `SignalService` 中根据前车位置计算 MA 和限速。
4. 后续在信号轨道视图中展示区段占用、MA、限速。

开发建议：

- 不要让信号模块直接调用前端接口。
- 不要在信号计算里读写 MySQL。
- 线路工作簿先经 `infrastructure` 适配层，再进入 `track` / `signal`，不要把 sheet 解析逻辑散落到业务服务里。
- 如果需要新增轨道字段，先改适配层标准对象和 `docs/API_CONTRACT.md`，再改业务服务。

## 供电与车辆负责人

主要目录：

```text
backend/src/main/java/com/railwaysim/train          # 中央镜像
backend/src/main/java/com/railwaysim/vehicle         # 集成层
backend/src/main/java/com/railwaysim/power           # 供电
vehicle-runtime-service/src/main/java/...            # 9300 权威（新增）
fmu-service/                                          # FMU 物理计算
power-network-service/                                # 供电网络计算
frontend/src/views/power-train/
frontend/src/components/power-train/
```

先做任务：

1. **9300 `TrainStateHolder`**（已创建）：维护每列车权威状态（位置/速度/力/能耗/TCMS诊断/车站追踪）。`snapshot()` 生成 `TrainStateSnapshot` 供中央镜像消费。
2. **9300 `VehicleControlQueue`**（已存在）：把信号、轨道和供电约束转换为车辆物理输入；调度约束由信号模块先折算。读取 `TrainStateHolder` 获取当前状态。
3. **9300 手动控制 API**（已创建）：`POST /vehicle-runtime/trains/{id}/manual-control` 独立控制单列车牵引/制动/紧急制动。
4. **9300 自主时钟**（已创建）：`VehicleRuntimeTickClock`，`@Scheduled` 定时推进，通过 `POST /autonomous/enable|disable` 切换。
5. 维护中央 `VehicleRuntimeIntegrationService`：`EXTERNAL_HTTP` 模式向 9300 发约束（无列车状态），接收 `trainStates[]` 更新镜像。`LOCAL` 模式保留 `OnboardTrainSubsystemManager + VehiclePhysicsClient` 降级路径。
6. 维护 `fmu-service` 的 `FmuManager`、`FleetStepper`、`TrainFMUInstance` 和 Modelica 模型草案。
7. 完善车辆状态：牵引、制动、取流、电功率、再生制动、故障码（9300 `TrainStateHolder` + 中央镜像同步）。
8. 在 `PowerService` 中根据列车取流估算负荷、电压、电流。
9. 维护车辆/供电事件：牵引功率变化、制动力变化、再生功率、接触轨电压变化、FMU 失败和降级。9300 事件通过 step-fleet 响应 `events[]` 桥接到中央 `SimpleEventBus`。
10. 后续在供电车辆视图中展示车辆列表和供电分区状态。

开发建议：

- **列车状态权威在 9300**（`TrainStateHolder`），中央 `TrainEntity` 是镜像。`EXTERNAL_HTTP` 模式下中央不持有权威状态。
- 车辆是对象，不是服务，不要给每辆车开线程。9300 内部 `VehicleRuntimeInstance` 管理每车控制/仿真队列，但使用同步批处理（`stepFleet()`）而非每车线程。
- 多车更新在 `EXTERNAL_HTTP` 模式下由 9300 `VehicleRuntimeManager.stepFleet()` 批量处理；`LOCAL` 模式下由中央 `TrainManager.tickAll()` 处理。
- 供电模块输出状态即可，不要直接操控列车，必要时通过事件或调度指令联动。
- 调度、信号、供电、轨道不要直接调用 FMU 或 9300，统一通过 `step-fleet` 约束链路进入车辆物理边界。
- 实时状态优先放 `RealtimeStateCache`（中央）和 `TrainStateHolder`（9300），MySQL 表只用于后续快照、日志和回放。
- 调度命令只写入 `DispatchService`，再由信号模块折算为 MA、限速或后续 `SignalVehicleCommand`；车辆侧不直接消费 `DispatchConstraint`。

## 第一周推荐目标

第 1 天：

- 所有人拉仓库，跑通后端和前端。
- 确认自己能看到仿真快照和前端页面。

第 2-3 天：

- 综合监控完成线路图和告警列表。
- 调度完成运行计划和简单调度建议。
- 信号轨道完成区段占用和 MA 展示。
- 供电车辆完成多车运动和供电状态展示。

第 4-5 天：

- 联调一个完整演示场景。
- 补充异常事件：拥挤、轨道占用异常、供电失压、列车延误。
- 准备中期检查展示数据。

## 遇到冲突怎么办

先判断冲突文件：

- 如果是自己负责的文件，自己解决。
- 如果是 `docs/API_CONTRACT.md`、`SimulationSnapshot`、`frontend/src/types/simulation.ts`，找组长统一处理。
- 如果是别人负责的模块，不要直接覆盖，先沟通。

解决后必须运行对应检查：

```bash
cd backend && mvn test
cd frontend && pnpm build
```
