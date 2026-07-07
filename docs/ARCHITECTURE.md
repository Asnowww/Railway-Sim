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
├─ simulation      仿真总控、统一时钟、快照
├─ simulation/event 内部事件总线
├─ train           多车实体、车辆状态、车辆管理器
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
→ SignalService.calculateAuthorities()
→ TrainManager.tickAll()
→ TrackService.updateOccupancy()
→ PowerService.update()
→ MonitorService.buildSnapshot()
→ WebSocket push snapshot
```

后续如果加入客流、调度、故障注入，可以插入到主循环中，但要保持一个原则：模块之间优先使用内存对象和内部事件，不要在每个 tick 中走 REST、MySQL 或 WebSocket。

## 数据分层

实时状态放内存：

- 列车位置、速度、满载率
- 区段占用
- 信号机状态、MA、限速
- 供电分区电压、电流、带电状态
- 当前告警和通信状态

MySQL 存历史和配置：

- 运行计划
- 调度指令记录
- 告警记录
- 历史运行轨迹
- 用户权限
- 系统配置变更

YAML 存仿真静态配置：

- 线路拓扑
- 站点位置
- 区段长度、限速、坡度
- 道岔配置
- 供电分区

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
