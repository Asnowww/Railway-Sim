# 运营调度模块概要设计

> 文档版本：v1.1
> 所属项目：Railway-Sim 上京地铁仿真系统
> 模块负责人：运营调度
> 编写说明：本文以当前 Spring Boot 单体架构为实施基准；与 FMU/TCMS Adapter 相关的接入方式见详细设计附录 A，待组长总架构定稿后对齐。

---

## 1. 模块概述

### 1.1 模块定位

运营调度模块位于系统**综合层**，是业务决策中心。模块以分时段运行计划为基准，实时读取列车运行状态，识别轻微运行扰动，在安全约束下生成仿真级调度指令，维持合理行车间隔与运行秩序，并将调度过程与结果供调度页面及综合监控展示与策略评估使用。

### 1.2 架构关系

```text
综合层
├── 综合监控模块        ← 读取调度快照展示
└── 运营调度模块        ← 本文档

控制层
├── 信号模块            → 提供 MA/限速（调度只读）
├── 轨道模块            → 提供拓扑/站位置（调度只读）
└── 供电模块            → 第一期无直接联动

车辆层
└── 车辆模块            ← 调度指令执行方（TrainManager）
```

### 1.3 设计原则

| 原则 | 说明 |
|------|------|
| 计划驱动 | 以静态分时段发车间隔为基准，不重构运行图 |
| 偏差触发 | 正常运行仅监控，超阈值才干预 |
| 内存决策 | 仿真周期内内存计算，MySQL 存配置与历史 |
| 指令联动 | 输出标准 `DispatchCommand`，由车辆模块执行 |
| 接口可替换 | 通过 `DispatchCommandPublisher` 抽象，便于架构演进 |
| 可解释性 | 每条指令可关联扰动类型与策略依据 |

### 1.4 模块边界

**范围内：**

- 分时段运行计划配置与管理
- 列车运行状态监控与计划对比
- 轻微运行扰动识别与记录
- 仿真级调度策略与指令生成
- 调度结果联动与历史落库
- 调度专页与快照数据供给

**范围外：**

- 运行图实时重构
- 工程级 ATS 人工审批流程
- 信号联锁、MA 计算
- 轨道占用、供电物理计算
- 票务收入、运营成本分析

---

## 2. 子模块划分

```text
运营调度模块 (com.railwaysim.dispatch)
├── 3.2.2 运行计划管理子模块
├── 3.2.3 列车运行状态监控子模块
├── 3.2.4 运行扰动识别子模块
├── 3.2.5 调度策略执行子模块
├── 3.2.6 调度结果联动子模块
└── 3.2.7 调度信息展示子模块
```

### 2.1 运行计划管理子模块

**功能说明：** 管理线路级分时段运行计划，是调度模块的运行基准。

**主要功能：**

- 配置不同时段的列车发车间隔（高峰、平峰、低谷）
- 管理线路级别的基础运行参数（停站时间等）
- 按仿真时间自动匹配当前运行模式
- 向其他子模块提供标准运行计划数据

**数据库表：** `running_plan_config`

**需求追溯：** OPS-FR-01、OPS-FR-02

---

### 2.2 列车运行状态监控子模块

**功能说明：** 获取并汇总列车实时运行状态，计算计划与实际偏差，为调度决策提供数据基础。

**主要功能：**

- 接收车辆模块传来的列车位置、速度、状态、满载率
- 检测到站/离站事件，记录计划与实际到发时刻
- 对列车实际运行状态与基准运行计划进行对比
- 识别停站时间偏差、发车间隔偏差
- 区间运行时间采用启发式估算：`区间长度 / (限速 × dispatch.averageSpeedRatio)`，比例默认 0.8，可配置

**数据库表：** `train_station_record`

**需求追溯：** OPS-FR-03、OPS-FR-04

---

### 2.3 运行扰动识别子模块

**功能说明：** 判断列车运行过程中是否出现影响运行节奏的扰动，与设备级告警分离。

**主要功能：**

- 分析列车运行偏差是否超出预设阈值
- 判断扰动类型（停站超时、发车延误、间隔异常、客流拥挤等）
- 防抖与冷却，避免策略震荡
- 将识别结果传递给调度策略执行模块

**数据库表：** `disturbance_record`（主）；严重情况可选写入 `alarm_record`

**需求追溯：** OPS-FR-05

---

### 2.4 调度策略执行子模块

**功能说明：** 在出现轻微运行扰动时，对列车运行参数进行仿真调整。

**主要功能：**

- 根据扰动类型触发相应调度策略
- 对停站时间或区间运行速度进行适度调整
- 校验信号 MA 限速与停站边界等安全约束
- 实现列车运行节奏逐步恢复

**数据库表：** `dispatch_command_record`

**需求追溯：** OPS-FR-06、OPS-FR-07、OPS-FR-09

---

### 2.5 调度结果联动子模块

**功能说明：** 将调度策略执行结果传递至车辆模块，确保调度效果在系统中体现。

**主要功能：**

- 通过 `DispatchCommandPublisher` 向车辆模块发送运行参数调整指令
- 读取信号模块 MA/限速，校验速度调整合法性
- 管理指令生命周期（PENDING → APPLIED → EXPIRED）
- 预留 Publisher 接口，便于后续架构调整

**交互对象：** 车辆模块（执行）、信号模块（只读校验）

**需求追溯：** OPS-FR-08、OPS-FR-11

---

### 2.6 调度信息展示子模块

**功能说明：** 提供调度专页可视化，并向综合监控供给调度相关数据。

**主要功能：**

- 展示当前运行模式、目标发车间隔、干预状态
- 展示实际/目标间隔对比、扰动列表、指令时间线
- 通过 WebSocket 快照 `dispatch` 段推送数据（推送频率 ≤1s，由总控限流）
- 综合监控复用快照展示调度指令状态
- 前端不单独建 WebSocket，统一订阅 `api/ws.ts`

**推送策略：** 内存状态每个 tick 更新；WebSocket 按 `simulation.pushIntervalMillis`（默认 1000ms）广播，避免高频 tick 导致 Vue 渲染卡顿。

**实现位置：** `frontend/src/views/dispatch/`、`frontend/src/components/dispatch/`

**需求追溯：** OPS-FR-10、OPS-FR-11

---

## 3. 在仿真主循环中的位置

```text
SimulationRuntime.tick()
  ① SignalService.calculateAuthorities()      信号模块
  ② TrainManager.tickAll(pendingCommands)      车辆模块（执行上周期指令）
  ③ TrackService.updateOccupancy()              轨道模块
  ④ PowerService.update()                       供电模块
  ⑤ DispatchService.evaluate()                  【运营调度模块】
  ⑥ MonitorService.buildSnapshot()              聚合 dispatch 段
  ⑦ WebSocket 推送
```

调度在本周期生成指令，下一周期由车辆模块执行。

---

## 4. 数据库表归属

系统共 18 张表，运营调度模块直接使用 4 张：

| 表名 | 读写 | 对应子模块 |
|------|------|------------|
| `running_plan_config` | 读+写 | 运行计划管理 |
| `train_station_record` | 写 | 运行状态监控 |
| `disturbance_record` | 写 | 运行扰动识别 |
| `dispatch_command_record` | 写 | 调度策略执行 |

**只读引用：** `line_config`、`station_config`、`train_info`、`passenger_flow_record`、`system_config`

**与告警分工：** 轻微扰动写 `disturbance_record`；严重情况可选升级 `alarm_record`

---

## 5. 对外交互概要

| 模块 | 方向 | 内容 | 方式 |
|------|------|------|------|
| 仿真总控 | ↔ 调度 | tick、reset、run_id | `DispatchService.evaluate()` |
| 车辆模块 | → 调度 | TrainState | `TrainManager.states()` |
| 调度 | → 车辆 | DispatchCommand | `TrainManagerPublisher` |
| 信号模块 | → 调度 | MovementAuthority | 只读校验 |
| 轨道模块 | → 调度 | 站位置、区段状态 | 只读 |
| 综合监控 | ← 调度 | DispatchSnapshot | WebSocket |
| 客流 | → 调度 | loadRate、站滞留 | TrainState / DB 只读 |

---

## 6. 非功能需求

| 指标 | 要求 | 实现要点 |
|------|------|----------|
| 实时性 | 状态刷新 ≤1s | WebSocket 按 pushIntervalMillis 限流；内存每 tick 更新 |
| 可配置性 | 计划估算可调 | dispatch.averageSpeedRatio 默认 0.8，存 system_config |
| 稳定性 | 避免策略震荡 | 确认 tick、冷却期、恢复滞回 |
| 安全约束 | 不超 MA/停站边界 | CommandValidator |
| 可扩展性 | 架构可演进 | DispatchCommandPublisher 抽象 |
| 可维护性 | 过程可追溯 | 三张业务表 + 指令记录 |
| 仿真同步 | 支持 pause/reset | reset 清内存、保留历史 |

---

## 7. 实施阶段

| 阶段 | 内容 | 产出 |
|------|------|------|
| P0 | 三张表 + 计划加载 + 快照 dispatch 段 | 前端可见运行模式 |
| P1 | 运行监控 + 到发检测 | 偏差可算 |
| P2 | 扰动识别 + 落库 | 扰动可识别 |
| P3 | 策略 + 指令 + 车辆联调 | 指令可执行 |
| P4 | 调度前端 + 监控联调 | 完整演示 |

---

## 8. 模块边界说明

> 运营调度模块以 `running_plan_config` 分时段静态计划为基准，通过 `train_station_record` 记录到发偏差，通过 `disturbance_record` 识别轻微扰动，通过 `dispatch_command_record` 记录调度指令。模块在仿真周期内基于内存状态完成决策，MySQL 用于配置与历史追溯。模块不做运行图重构，不涉及工程级 ATS 审批；指令通过 `DispatchCommandPublisher` 下发至车辆模块，并受信号 MA 限速约束。调度结果经 WebSocket 快照供本模块页面与综合监控展示。

详细设计见 [DISPATCH_DETAILED_DESIGN.md](./DISPATCH_DETAILED_DESIGN.md)。
代码实现计划见 [DISPATCH_IMPLEMENTATION_PLAN.md](./DISPATCH_IMPLEMENTATION_PLAN.md)。
