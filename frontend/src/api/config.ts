const explicitBackendOrigin = import.meta.env.VITE_BACKEND_ORIGIN as string | undefined

function resolveBackendOrigin(): string {
  if (explicitBackendOrigin) return explicitBackendOrigin.replace(/\/$/, '')

  const hostName = window.location.hostname
  if (hostName === '98.142.241.155') return 'http://98.142.241.155:18080'

  return ''
}

export const apiBaseUrl = `${resolveBackendOrigin()}/api`

export function getSimulationWebSocketUrl(): string {
  const backendOrigin = resolveBackendOrigin()
  if (!backendOrigin) {
    const protocol = window.location.protocol === 'https:' ? 'wss' : 'ws'
    return `${protocol}://${window.location.host}/ws/simulation`
  }

  return `${backendOrigin.replace(/^http/, 'ws')}/ws/simulation`
}
