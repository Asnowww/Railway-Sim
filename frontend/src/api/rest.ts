import type { SimulationSnapshot } from '../types/simulation'

async function request<T>(url: string, init?: RequestInit): Promise<T> {
  const response = await fetch(url, init)
  if (!response.ok) {
    throw new Error(`Request failed: ${response.status} ${response.statusText}`)
  }
  return response.json() as Promise<T>
}

export const simulationApi = {
  snapshot: () => request<SimulationSnapshot>('/api/simulation/snapshot'),
  start: () => request<SimulationSnapshot>('/api/simulation/start', { method: 'POST' }),
  pause: () => request<SimulationSnapshot>('/api/simulation/pause', { method: 'POST' }),
  reset: () => request<SimulationSnapshot>('/api/simulation/reset', { method: 'POST' })
}

