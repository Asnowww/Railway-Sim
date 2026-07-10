const explicitApiBaseUrl = import.meta.env.VITE_API_BASE_URL as string | undefined
const explicitBackendOrigin = import.meta.env.VITE_BACKEND_ORIGIN as string | undefined

function trimTrailingSlash(url: string): string {
  return url.replace(/\/$/, '')
}

function resolveBackendOrigin(): string {
  if (explicitBackendOrigin) return trimTrailingSlash(explicitBackendOrigin)
  if (explicitApiBaseUrl) return trimTrailingSlash(explicitApiBaseUrl).replace(/\/api$/, '')

  return ''
}

export const apiBaseUrl = explicitApiBaseUrl ? trimTrailingSlash(explicitApiBaseUrl) : `${resolveBackendOrigin()}/api`

export function getSimulationWebSocketUrl(): string {
  const backendOrigin = resolveBackendOrigin()
  if (!backendOrigin) {
    const protocol = window.location.protocol === 'https:' ? 'wss' : 'ws'
    return `${protocol}://${window.location.host}/ws/simulation`
  }

  return `${backendOrigin.replace(/^http/, 'ws')}/ws/simulation`
}
