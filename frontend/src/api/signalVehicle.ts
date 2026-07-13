import { request } from '../shared/api/client'
import type { SignalVehicleCommand, VehicleSignalStatus } from '../types/vehicles'

/**
 * 车辆-信号只读投影。
 * 注意：原 applyTelemetry / applyTelemetryContentPacket 已删除——后端对应接口
 * 恒返回 410 GONE（列车状态权威已收归 9300），前端不得再调用。
 */
export const signalVehicleApi = {
  statuses: () => request<VehicleSignalStatus[]>('/signal/vehicles/statuses'),
  commands: () => request<SignalVehicleCommand[]>('/signal/vehicles/commands')
}
