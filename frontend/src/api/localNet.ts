import { apiBaseUrl } from './config'

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${apiBaseUrl}${path}`, init)
  if (!response.ok) {
    throw new Error(`Request failed: ${response.status} ${response.statusText}`)
  }
  return response.json() as Promise<T>
}

export const localNetApi = {
  adapters: () => request<unknown[]>('/localnet/adapters'),
  adapter: (adapterId: string) => request<unknown>(`/localnet/adapters/${encodeURIComponent(adapterId)}`),
  replay: (adapterId: string, payload: ArrayBuffer) =>
    request<unknown>(`/localnet/adapters/${encodeURIComponent(adapterId)}/replay`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/octet-stream' },
      body: payload
    })
}
