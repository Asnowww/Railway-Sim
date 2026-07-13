import { apiBaseUrl } from './config'

async function request<T>(path: string): Promise<T> {
  const response = await fetch(`${apiBaseUrl}${path}`)
  if (!response.ok) {
    throw new Error(`Request failed: ${response.status} ${response.statusText}`)
  }
  return response.json() as Promise<T>
}

export const operationLogApi = {
  list: (targetRef?: string) =>
    request<unknown[]>(targetRef ? `/operation-logs?targetRef=${encodeURIComponent(targetRef)}` : '/operation-logs'),
  health: () => request<unknown>('/operation-logs/health')
}
