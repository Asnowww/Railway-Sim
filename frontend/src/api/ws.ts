import { getSimulationWebSocketUrl } from './config'
import type { SimulationSnapshot, SocketMessage } from '../types/simulation'

type SnapshotListener = (snapshot: SimulationSnapshot) => void

export class SimulationSocket {
  private socket: WebSocket | null = null
  private listeners = new Set<SnapshotListener>()
  private intentionalClose = false
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null

  connect() {
    if (this.socket && this.socket.readyState === WebSocket.OPEN) return
    if (!window.location.host) return

    this.intentionalClose = false
    try {
      this.socket = new WebSocket(getSimulationWebSocketUrl())
    } catch {
      this.socket = null
      this.scheduleReconnect()
      return
    }

    this.socket.addEventListener('message', (event) => {
      try {
        const message = JSON.parse(event.data) as SocketMessage
        if (message.type === 'snapshot') {
          this.listeners.forEach((listener) => listener(message.payload))
        }
      } catch { /* ignore parse errors */ }
    })

    this.socket.addEventListener('close', () => {
      this.socket = null
      if (!this.intentionalClose) {
        this.scheduleReconnect()
      }
    })

    this.socket.addEventListener('error', () => {
      // close event will fire after error, triggering reconnect
    })
  }

  private scheduleReconnect() {
    if (this.reconnectTimer) return
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null
      this.connect()
    }, 3000)
  }

  subscribe(listener: SnapshotListener) {
    this.listeners.add(listener)
    return () => this.listeners.delete(listener)
  }

  disconnect() {
    this.intentionalClose = true
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer)
      this.reconnectTimer = null
    }
    this.socket?.close()
    this.socket = null
  }
}

export const simulationSocket = new SimulationSocket()
