import { request } from '../shared/api/client'
import type { LocalNetHealth } from '../types/ops'

export const localNetApi = {
  adapters: () => request<LocalNetHealth[]>('/localnet/adapters'),
  adapter: (adapterId: string) => request<LocalNetHealth>(`/localnet/adapters/${encodeURIComponent(adapterId)}`),
  replay: (adapterId: string, payload: ArrayBuffer) =>
    request<Record<string, unknown>>(`/localnet/adapters/${encodeURIComponent(adapterId)}/replay`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/octet-stream' },
      body: payload
    })
}
