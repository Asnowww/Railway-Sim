# 调度与信号轨道闭环改造方案

## 背景

当前调度闭环调试页已经可以展示运行图、时间间隔调度、进路预约、联锁状态、道岔和信号状态，但页面中仍保留了一组前端硬编码的路线模板。这个实现会造成职责边界不清：调度端看起来可以自己定义或添加路线，而实际系统中路线、区段、道岔、信号机和进路必须来自信号轨道端的静态线路数据和联锁状态。

本次改造的目标是明确边界：调度端只表达运营意图和调度申请，不创建轨道拓扑、不新增信号进路、不越过信号轨道端直接控制车辆。

## 职责边界

### 信号轨道端负责

- 从线路 YAML 或线路工作簿导入区段、道岔、信号机、应答器、车站、站台和进路。
- 维护 `TrackService` 中的区段占用、故障、预留和道岔状态。
- 维护 `RouteInterlockingService` 中的既有进路列表、联锁校验、道岔扳动锁闭、进路锁闭、占用和释放。
- 维护 `SignalService` 中的信号机状态、MA 和限速输出。
- 通过 REST 和 WebSocket 快照向调度端提供只读状态。

### 调度端负责

- 根据运行图、实际间隔、列车状态和信号轨道端暴露的既有进路生成调度意图。
- 创建 `DispatchCommand`，例如 `HEADWAY_ADJUST`、`REQUEST_ROUTE`、`CANCEL_ROUTE`、`DEPART`。
- 创建运营路线计划 `OperationPlan`，但计划只能引用信号轨道端已有 route 和 segment。
- 展示命令从提交、联锁处理、信号轨道执行到反馈确认的闭环状态。
- 不在前端或调度服务中新增真实轨道、道岔、信号机或联锁进路。

## 数据来源

调度前端应以以下数据为准：

| 数据 | 来源 | 用途 |
|---|---|---|
| 既有进路清单 | `GET /api/dispatch/route/list` | 人工申请进路、进路状态表、线网边高亮 |
| 运营路线模板 | `GET /api/dispatch/operation-route/templates` | 路线编排候选，仅选择既有模板 |
| 编排候选预览 | `POST /api/dispatch/operation-route/preview` 或本地基于模板筛选 | 校验点选起终点是否落在既有路线中 |
| 调度闭环状态 | WebSocket `snapshot.dispatch` 或 `GET /api/dispatch/status` | 命令、决策、预约、计划、间隔和扰动 |
| 轨道区段状态 | WebSocket `snapshot.trackSegments` | 区段占用、预留、故障 |
| 信号与 MA | WebSocket `snapshot.signalStates`、`snapshot.authorities` | 信号执行结果与车辆授权 |
| 道岔与联锁状态 | WebSocket `snapshot.switchStates`、`snapshot.routeStates` | 锁闭、释放、冲突和拒绝理由辅助展示 |

前端允许保留坐标布局和聚合边，用于可视化；但每条边属于哪些 route、当前能否被调度选择、是否显示为已申请/已锁闭，应由后端 route 的 `segmentIds` 和实时 `routeStates` 反推。

## 闭环流程

### 人工进路申请

```text
调度员选择列车 + 选择信号端已有 route
→ POST /api/dispatch/commands, commandType=REQUEST_ROUTE
→ DispatchService 记录命令、决策和预约
→ SimulationRuntime 在 tick 中转交 RouteInterlockingService
→ 联锁检查区段、故障、冲突、道岔位置和锁闭条件
→ runtime 生成 DispatchCommandFeedback
→ DispatchService 更新 RouteReservation 和 RouteDecision
→ WebSocket 快照推给前端
→ 前端展示 command / decision / reservation / routeState / switch / signal / MA
```

### 运营路线编排

```text
调度员在示意图上点选起点、经由点、终点
→ 前端只在后端 operation route templates 中筛选匹配候选
→ 没有候选时不能创建计划
→ 创建 OperationPlan 时提交 routeId、candidateKey 和 pointIds
→ 到点后 DispatchService 自动生成 REQUEST_ROUTE
→ 后续进入人工进路申请同样的联锁闭环
```

## 前端改造原则

1. 删除调度页本地硬编码 `dispatchRouteTemplates` 的业务用途。
2. 页面初始化时加载 `routeList` 和 `operationRouteTemplates`。
3. 路线编排候选只来自 `operationRouteTemplates`，并按点选节点进行子路径匹配。
4. 人工申请进路下拉框只显示 `routeList` 中的已有 route。
5. 线网聚合边的 route 归属由后端 route 的 `segmentIds` 反推；当前端布局中没有对应 segment 时，仅在“未映射”区域提示，不创建假路线。
6. 点选节点只作为筛选既有路线的输入；如果点选路径无法匹配已有模板，创建按钮保持禁用。
7. 页面文案避免“新增路线”“添加路线”，统一使用“匹配既有进路”“生成运营计划”“申请进路”。

## 与信号轨道端的接口约定

信号轨道端需要保证：

- `GET /api/dispatch/route/list` 返回的 `routeId` 与 `snapshot.routeStates[].routeId` 一致。
- `routeList[].segmentIds` 与 `snapshot.trackSegments[].id` 能够匹配，至少允许前端高亮已知区段。
- `operation-route/templates` 中的 `routeId` 必须对应信号端已有 route。
- `operation-route/templates[].segmentIds` 必须是信号端已有区段，不能由调度端补造。
- 进路被拒绝时，反馈中应尽量保留 failure code、是否可重试和原始拒绝原因，供调度端解释。

## 本次代码改造范围

- `frontend/src/views/dispatch/DispatchLoopDebugView.vue`
  - 加载并使用后端 `operationRouteTemplates`。
  - 路线编排候选从后端模板生成，不再从前端硬编码模板生成。
  - 线网边/节点高亮使用后端 route 与 segment 的映射。
  - 增加路线数据来源提示，强调调度端只匹配既有信号进路。

## 后续建议

- 后端 `OperationPlanningService.defaultTemplates()` 仍是演示模板，后续应进一步从 `RouteCatalog` 或线路适配层完整生成，减少后端内部硬编码。
- `DispatchCommandView` 可增加最近一次 feedback details，让前端更清楚展示联锁拒绝原因、failure code 和 retryable。
- 增加专门的命令生命周期面板，把 `PENDING -> SENT -> EFFECT_CONFIRMED/APPLIED/REJECTED/RELEASED` 展示成一条链路。
