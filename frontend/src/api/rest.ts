import { apiBaseUrl } from './config'
import type { SimulationSnapshot } from '../types/simulation'

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${apiBaseUrl}${path}`, init)
  if (!response.ok) {
    throw new Error(`Request failed: ${response.status} ${response.statusText}`)
  }
  return response.json() as Promise<T>
}

export const simulationApi = {
  snapshot: () => request<SimulationSnapshot>('/simulation/snapshot'),
  start: () => request<SimulationSnapshot>('/simulation/start', { method: 'POST' }),
  pause: () => request<SimulationSnapshot>('/simulation/pause', { method: 'POST' }),
  stop: () => request<SimulationSnapshot>('/simulation/stop', { method: 'POST' }),
  reset: () => request<SimulationSnapshot>('/simulation/reset', { method: 'POST' }),
  tick: () => request<SimulationSnapshot>('/simulation/tick', { method: 'POST' }),
  alarms: (runId?: string) => request<unknown[]>(runId ? `/alarms?runId=${encodeURIComponent(runId)}` : '/alarms'),
  acknowledgeAlarm: (alarmId: string) => request<unknown>(`/alarms/${encodeURIComponent(alarmId)}/acknowledge`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ operator: 'monitor-console' })
  })
}

