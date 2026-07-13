import { request } from '../shared/api/client'
import type { SimulationRunRecord, TrainStopStatistics } from '../types/ops'

function paging(limit = 100, offset = 0): string {
  return `limit=${encodeURIComponent(String(limit))}&offset=${encodeURIComponent(String(offset))}`
}

export const simulationRunsApi = {
  list: (limit = 50) => request<SimulationRunRecord[]>(`/simulation-runs?limit=${encodeURIComponent(String(limit))}`),
  detail: (runId: string) => request<SimulationRunRecord>(`/simulation-runs/${encodeURIComponent(runId)}`),
  summary: (runId: string) => request<Record<string, unknown>>(`/simulation-runs/${encodeURIComponent(runId)}/summary`),
  trainSnapshots: (runId: string, limit = 100, offset = 0) =>
    request<Record<string, unknown>[]>(`/simulation-runs/${encodeURIComponent(runId)}/train-snapshots?${paging(limit, offset)}`),
  controlDecisions: (runId: string, limit = 100, offset = 0) =>
    request<Record<string, unknown>[]>(`/simulation-runs/${encodeURIComponent(runId)}/control-decisions?${paging(limit, offset)}`),
  powerSnapshots: (runId: string, limit = 100, offset = 0) =>
    request<Record<string, unknown>[]>(`/simulation-runs/${encodeURIComponent(runId)}/power-snapshots?${paging(limit, offset)}`),
  stopResults: (runId: string, limit = 100, offset = 0) =>
    request<Record<string, unknown>[]>(`/simulation-runs/${encodeURIComponent(runId)}/stop-results?${paging(limit, offset)}`),
  stopStatistics: (runId: string, params: { trainId?: string; stationId?: string; requiredSampleCount?: number } = {}) => {
    const query = new URLSearchParams()
    if (params.trainId) query.set('trainId', params.trainId)
    if (params.stationId) query.set('stationId', params.stationId)
    query.set('requiredSampleCount', String(params.requiredSampleCount ?? 10))
    return request<TrainStopStatistics>(`/simulation-runs/${encodeURIComponent(runId)}/stop-statistics?${query.toString()}`)
  },
  faults: (runId: string, limit = 100, offset = 0) =>
    request<Record<string, unknown>[]>(`/simulation-runs/${encodeURIComponent(runId)}/faults?${paging(limit, offset)}`)
}
