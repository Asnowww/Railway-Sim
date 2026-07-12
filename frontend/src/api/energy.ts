import { apiBaseUrl } from './config'

async function request<T>(path: string): Promise<T> {
  const response = await fetch(`${apiBaseUrl}${path}`)
  if (!response.ok) {
    throw new Error(`Request failed: ${response.status} ${response.statusText}`)
  }
  return response.json() as Promise<T>
}

export const energyApi = {
  trains: () => request<unknown[]>('/energy/trains'),
  powerSections: () => request<unknown>('/energy/power-sections')
}
