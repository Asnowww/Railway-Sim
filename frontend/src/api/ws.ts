import type { SimulationSnapshot, SocketMessage } from '../types/simulation'

type SnapshotListener = (snapshot: SimulationSnapshot) => void

export class SimulationSocket {
  private socket: WebSocket | null = null
  private listeners = new Set<SnapshotListener>()

  connect() {
    const protocol = window.location.protocol === 'https:' ? 'wss' : 'ws'
    this.socket = new WebSocket(`${protocol}://${window.location.host}/ws/simulation`)
    this.socket.addEventListener('message', (event) => {
      const message = JSON.parse(event.data) as SocketMessage
      if (message.type === 'snapshot') {
        this.listeners.forEach((listener) => listener(message.payload))
      }
    })
  }

  subscribe(listener: SnapshotListener) {
    this.listeners.add(listener)
    return () => this.listeners.delete(listener)
  }

  disconnect() {
    this.socket?.close()
    this.socket = null
  }
}

export const simulationSocket = new SimulationSocket()

