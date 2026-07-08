# 供电车辆-维修检修能耗接口族具体实现

## 实现范围

本接口族首期提供只读或预留状态，满足第二阶段“能查询车辆、供电、能耗和故障状态”的要求。维修、检修和能耗模块只访问中央供电控制模块 REST，不直接访问外部供电仿真系统。完整维修工单、检修审批和经济成本核算不在本期实现。

## REST 接口

| 接口 | 方法 | 返回 |
|---|---|---|
| `/api/power/maintenance-locks` | GET | 供电分区检修闭锁、接地/锁闭预留状态。 |
| `/api/power/substations` | GET | 牵引变电所可用性、设备状态和关联分区。 |
| `/api/power/substations/{substationId}/devices` | GET | 牵引变电所设备明细。 |
| `/api/power/isolators` | GET | 接触轨隔离开关状态，用于检修隔离视图。 |
| `/api/power/stray-current` | GET | 杂散电流监测点、排流柜状态和风险等级。 |
| `/api/vehicle/maintenance-states` | GET | 车辆维修状态预留视图。 |
| `/api/energy/trains` | GET | 全部列车牵引能耗、再生能量、净能耗。 |
| `/api/energy/power-sections` | GET | 供电分区负荷、再生吸收和未吸收功率。 |
| `/api/power/energy` | GET | `/api/energy/power-sections` 的供电模块别名，便于供电页面直接消费。 |

## 状态映射

车辆维修状态：

| 条件 | maintenanceState |
|---|---|
| `faultLevel >= 3` | `REPAIR_REQUIRED` |
| `faultLevel > 0` | `INSPECTION_REQUIRED` |
| 其他 | `NONE` |

供电检修状态：

| 字段 | 来源 |
|---|---|
| `maintenanceState` | `PowerSectionState.maintenanceState` |
| `lockoutState` | `PowerSectionState.lockoutState` |
| `breakerStatus` | `PowerSectionState.breakerStatus` |
| `status` | `PowerSectionState.status` |
| `isolatorStatus` | `PowerSectionState.isolatorStatus` |
| `substationAvailability` | `PowerSectionState.substationAvailability` |

设备检修状态：

| 对象 | 字段 |
|---|---|
| 牵引变电所 | `SubstationStateResponse.availability`、`supplyMode`、`sectionIds` |
| 牵引变电所设备 | `SubstationDeviceResponse.deviceType`、`state`、`available`、`affectsSectionIds` |
| 隔离开关 | `IsolatorStateResponse.state`、`thirdRailSectionId` |
| 杂散电流监测 | `StrayCurrentRiskResponse.cabinetState`、`riskLevel`、`suggestedAction` |

## 增修机制记录

| 类型 | 机制/细节 | 原因 |
|---|---|---|
| 具体逻辑 | 能耗接口直接复用当前仿真状态，不读取历史表。 | 当前仿真展示关注实时窗口，历史聚合需要更完整的统计周期定义。 |
| 具体逻辑 | 维修/检修模块通过中央供电控制接口读取设备级状态。 | 保持维修视图和供电控制状态一致，避免绕过中央系统直接访问外部仿真节点。 |
| 业务考量 | 维修检修接口首期只读，不提供工单审批写接口。 | 工单流转涉及权限、审批和人员组织，不属于方首岳本期实现范围。 |
| 现实考量 | 数据库有 `train_energy_record`、`power_section_record` 等留档表，但本接口先以实时状态为准。 | 本地 MySQL 可能不可用；实时状态能保证演示和联调不被数据库阻塞。 |

## 后续扩展预留

- 维修工单：`workOrderId`、`approvalState`、`assignedTeam`、`closedAt`。
- 检修审批：二次确认、接地状态、停送电票据。
- 经济分析：电价、峰谷时段、再生回馈收益、单位车公里能耗。
