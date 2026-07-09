# 车辆-轨道接口族具体实现

## 实现范围

本接口族只描述车辆系统与轨道系统之间的接口。轨道系统负责线路区段、区段占用、轨道限速、坡度、曲线半径、站距和道岔相关拓扑状态；车辆系统负责提供列车位置、长度、速度和车头/车尾里程，并消费轨道约束完成本车动力学决策。

信号系统是独立系统。MA、信号机显示、ATP 安全命令和上线/下线信号接入语义不写入本接口族。

## 已落实代码入口

| 方向 | 模型/接口 | 说明 |
|---|---|---|
| 车辆到轨道 | `TrainState.headMileage`、`tailMileage`、`lengthMeters`、`speedMetersPerSecond` | 轨道系统更新区段占用。 |
| 轨道到车辆 | `TrackConstraint` | 轨道系统输出限速、坡度、曲线半径和站距。 |
| 主循环内部 | `TrackService.updateOccupancy(trainManager.states())` | 每 tick 前后根据车辆位置刷新轨道占用。 |
| 主循环内部 | `TrackService.constraintsForTrains(...)` | 向车辆侧提供轨道约束。 |
| 联调状态 | `GET /api/simulation/snapshot` | 同时查看 `trains[]`、`trackSegments[]`。 |

## 车辆到轨道：占用输入

车辆侧向轨道系统提供以下字段：

| 字段 | 来源 | 用途 |
|---|---|---|
| `trainId` | `TrainState.id` | 匹配轨道占用来源。 |
| `headMileage` | `TrainState` | 判断车头所在区段。 |
| `tailMileage` | `TrainState` | 判断列车尾部是否清扫区段。 |
| `lengthMeters` | `TrainState` | 计算占用范围。 |
| `speedMetersPerSecond` | `TrainState` | 辅助展示和轨道异常场景判断。 |
| `dataQuality` | `TrainState` | 外部仿真 fallback 或车辆状态异常时提示轨道侧谨慎消费。 |

轨道占用由 `TrackService` 按当前车辆状态计算，车辆系统不直接修改轨道系统内部的区段状态。

外部 `vehicle-runtime-service` 启用时也不直接访问轨道系统或线路数据库；中央将 `TrackConstraint` 作为 `step-fleet` 请求的一部分下发给车辆运行时。

## 轨道到车辆：轨道约束

车辆侧消费 `TrackConstraint`：

| 字段 | 用途 |
|---|---|
| `trainId` | 匹配列车。 |
| `segmentId` | 当前轨道区段。 |
| `speedLimitMetersPerSecond` | 与信号 MA 限速取最小值。 |
| `gradient` | 车辆制动距离和动力学阻力计算。 |
| `curveRadiusMeters` | 车辆动力学输入预留。 |
| `stationDistanceMeters` | 进站停车和站停制动判断。 |

`OnboardTrainSubsystem` 在每 tick 中将轨道限速与信号 MA 限速合并，并用坡度、站距计算制动距离和站停逻辑。

## 场景覆盖矩阵

| 场景 | 当前实现 |
|---|---|
| 列车移动导致占用变化 | `TrackService.updateOccupancy(...)` 每 tick 根据 `TrainState` 刷新。 |
| 车头/车尾跨区段 | 由 `headMileage/tailMileage/lengthMeters` 支撑占用范围判断。 |
| 轨道限速 | `TrackConstraint.speedLimitMetersPerSecond` 进入车辆控制。 |
| 坡度影响制动距离 | `TrackConstraint.gradient` 进入 `OnboardTrainSubsystem.stoppingDistanceMeters(...)`。 |
| 进站制动 | `TrackConstraint.stationDistanceMeters` 进入 `STATION_BRAKE/STATION_STOPPED`。 |
| 离站保护 | 调度/信号释放停站后，车辆端短距离屏蔽本站 `stationDistance`，输出 `DEPARTING_STATION`，避免重复捕获同一站。 |
| 曲线半径 | `TrackConstraint.curveRadiusMeters` 进入车辆物理输入，当前简单动力学模型暂未深算轮轨耦合。 |
| 轨道故障 | 轨道系统限制 MA/占用与约束；车辆侧只消费最终约束。 |

## 边界

- 不生成 MA；MA 属于车辆-信号接口族和信号系统职责。
- 不处理信号机显示、ATP 命令和外部控制会话上线/下线。
- 不让车辆系统直接决定道岔位置、进路锁闭或轨道故障清除。
- 不把调度命令直接送入车辆系统；调度对轨道/信号的影响由对应系统折算。
