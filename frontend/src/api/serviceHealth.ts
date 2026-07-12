import { apiBaseUrl } from './config'

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${apiBaseUrl}${path}`, init)
  if (!response.ok) {
    throw new Error(`Request failed: ${response.status} ${response.statusText}`)
  }
  return response.json() as Promise<T>
}

export const serviceHealthApi = {
  list: () => request<unknown[]>('/service-health'),
  detail: (serviceId: string) => request<unknown>(`/service-health/${encodeURIComponent(serviceId)}`),
  recoveryCheck: (serviceId: string, body: { expectedRunId: string; expectedTick: number }) =>
    request<unknown>(`/service-health/${encodeURIComponent(serviceId)}/recovery/check`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    })
}
