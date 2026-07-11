# 供电-综合监控接口族具体实现

## 实现范围

供电模块向综合监控提供分区状态、变电所/馈线标识、电压电流、负荷、保护状态、检修闭锁、故障事件、再生制动吸收、设备状态、隔离开关状态和杂散电流风险。综合监控只消费中央供电控制模块输出，不直接访问外部供电仿真系统，也不参与供电计算。

## REST 接口

| 接口 | 方法 | 返回 |
|---|---|---|
| `/api/power/sections` | GET | 全部 `PowerSectionState`。 |
| `/api/power/sections/{sectionId}` | GET | 单个供电分区状态。 |
| `/api/power/sections/{sectionId}/events` | GET | 分区故障、恢复、检修闭锁事件历史。 |
| `/api/power/energy` | GET | 当前仿真窗口的供电负荷和再生吸收汇总。 |
| `/api/power/maintenance-locks` | GET | 检修闭锁状态预留视图。 |
| `/api/power/substations` | GET | 牵引变电所、供电方式、可用性、关联设备和分区。 |
| `/api/power/substations/{substationId}/devices` | GET | 指定牵引变电所设备状态。 |
| `/api/power/isolators` | GET | 接触轨隔离开关状态。 |
| `/api/power/stray-current` | GET | 杂散电流监测点、排流柜状态、极化电位和风险等级。 |
| `/api/power/external-health` | GET | 供电设备级数据源健康状态；仅作为综合监控诊断信息。 |
| `/api/simulation-runs/{runId}/power-snapshots` | GET | 按 runId 分页查询历史供电分区快照，只读。 |
| `/api/simulation-runs/{runId}/faults` | GET | 按 runId 查询车电故障时间线，只读。 |

## 控制/演示接口

| 接口 | 方法 | 说明 |
|---|---|---|
| `/api/power/sections/{sectionId}/faults` | POST | 仿真故障注入。 |
| `/api/power/sections/{sectionId}/faults/clear` | POST | 清除仿真故障。 |
| `/api/power/operations` | POST | 中央供电控制模块的设备级仿真操作入口。 |

写接口请求必须包含：

```json
{
  "faultType": "UNDERVOLTAGE",
  "reason": "demo",
  "operator": "simulation",
  "confirmToken": "SIMULATION_CONFIRM",
  "traceId": "trace-001"
}
```

## WebSocket 快照字段

`powerSections[]` 复用 `PowerSectionState`，已包含：

- `id`、`name`、`substationId`、`feederId`
- `startMeters`、`endMeters`
- `voltage`、`current`、`loadWatts`、`availablePowerWatts`
- `regenPowerWatts`、`absorbedRegenPowerWatts`、`unabsorbedRegenPowerWatts`
- `status`、`breakerStatus`、`protectionState`
- `maintenanceState`、`lockoutState`
- `supplyMode`、`isolatorStatus`、`substationAvailability`
- `externalDataQuality`、`externalVoltage`、`externalCurrent`、`externalLoadWatts`
- `voltageDeviation`、`voltageDeviationPercent`、`voltageComparisonStatus`
- `externalSupportReason`
- `strayCurrentRiskLevel`、`strayCurrentRiskReason`
- `affectedTrainIds`、`dataQuality`、`updatedAt`

快照顶层同时携带 `simulationRunId`。持久化 `power_section_record` 使用 `(simulation_run_id,tick,section_id)` 对齐同周期车辆物理、控制决策和 FMU 记录。

其中 `voltage/current/loadWatts` 是中央供电控制模块本地计算值，`external*` 是外部供电仿真按列车区段负荷回算的遥测值，`voltageComparisonStatus` 用于综合监控显示偏差告警。

设备级 REST 只用于综合监控、维修检修和演示联调视图：

| 对象 | 关键字段 |
|---|---|
| `SubstationStateResponse` | `id`、`name`、`supplyMode`、`availability`、`devices[]`、`sectionIds`、`dataQuality`、`updatedAt`。 |
| `SubstationDeviceResponse` | `id`、`name`、`deviceType`、`state`、`available`、`affectsSectionIds`。 |
| `IsolatorStateResponse` | `id`、`thirdRailSectionId`、`state`、`dataQuality`、`updatedAt`。 |
| `StrayCurrentRiskResponse` | `id`、`sectionId`、`cabinetState`、`polarizedPotentialVolts`、`riskLevel`、`riskReason`、`suggestedAction`。 |

## 增修机制记录

| 类型 | 机制/细节 | 原因 |
|---|---|---|
| 具体逻辑 | `PowerSectionState` 增加 `substationId` 和 `feederId`。 | 第二阶段快照字段要求综合监控能定位牵引所和馈线来源。 |
| 具体逻辑 | `PowerSectionState` 增加供电方式、隔离开关、变电所可用性和杂散风险字段。 | 综合监控需要从中央供电控制模块看到设备状态对分区能力的影响。 |
| 业务考量 | 故障写接口强制 `SIMULATION_CONFIRM`。 | 断路器跳闸、失电、检修闭锁属于高风险遥控语义，即便是仿真也需要二次确认字段。 |
| 安全门禁 | 危险操作在变更内存供电状态前，同步写入带 `run_id/tick/trace_id` 的 `operation_log`；写库失败返回 503 且不执行操作。 | 不允许“故障/分合闸已生效，但证据只留在内存”。 |
| 旁路诊断 | `protocol_packet_log` 和普通运行采样可在写库失败时降级告警，不阻断现场报文收发。 | 旁路诊断与危险业务操作的 fail-closed 语义分离。 |

## 边界

- 不实现真实远方分合闸；`/api/power/operations` 只代表中央仿真控制操作。
- 不向综合监控暴露外部供电仿真系统的私有协议。
- 不由综合监控修改供电状态；综合监控只调用仿真演示写接口。
