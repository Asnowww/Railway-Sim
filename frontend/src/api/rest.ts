import { request } from '../shared/api/client'
import type { SimulationSnapshot } from '../types/simulation'

/** 仿真控制。start 后由后端 100ms 时钟自走推进，前端不驱动循环 tick；tick 仅用于单步。 */
export const simulationApi = {
  snapshot: () => request<SimulationSnapshot>('/simulation/snapshot'),
  start: () => request<SimulationSnapshot>('/simulation/start', { method: 'POST' }),
  pause: () => request<SimulationSnapshot>('/simulation/pause', { method: 'POST' }),
  stop: () => request<SimulationSnapshot>('/simulation/stop', { method: 'POST' }),
  reset: () => request<SimulationSnapshot>('/simulation/reset', { method: 'POST' }),
  tick: () => request<SimulationSnapshot>('/simulation/tick', { method: 'POST' }),
  /** 后端要求仿真非 RUNNING 时才允许注入/清除区段故障 */
  injectTrackFault: (segmentId: string) =>
    request<SimulationSnapshot>(`/simulation/fault/inject?segmentId=${encodeURIComponent(segmentId)}`, { method: 'POST' }),
  clearTrackFault: (segmentId: string) =>
    request<SimulationSnapshot>(`/simulation/fault/clear?segmentId=${encodeURIComponent(segmentId)}`, { method: 'POST' })
}
