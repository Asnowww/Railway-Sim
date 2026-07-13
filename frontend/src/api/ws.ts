import { getSimulationWebSocketUrl } from './config'
import type { SimulationSnapshot, SocketMessage } from '../types/simulation'

export type SocketState = 'idle' | 'connecting' | 'up' | 'down'

type SnapshotListener = (snapshot: SimulationSnapshot) => void
type StateListener = (state: SocketState, detail?: string) => void

const BASE_RECONNECT_MILLIS = 1000
const MAX_RECONNECT_MILLIS = 30_000

/**
 * 全局唯一 WebSocket 连接（后端 1s 推一次快照）。
 * 显式状态机 + 指数退避重连。数据新鲜度（STALE）判定在 connection store 中
 * 基于最后快照时间完成——比 onclose 更早发现僵死连接。
 */
export class SimulationSocket {
  private socket: WebSocket | null = null
  private snapshotListeners = new Set<SnapshotListener>()
  private stateListeners = new Set<StateListener>()
  private intentionalClose = false
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null
  private reconnectAttempts = 0
  private currentState: SocketState = 'idle'

  get state(): SocketState {
    return this.currentState
  }

  connect(): void {
    if (this.socket && (this.socket.readyState === WebSocket.OPEN || this.socket.readyState === WebSocket.CONNECTING)) {
      return
    }

    this.intentionalClose = false
    this.setState('connecting')
    let socket: WebSocket
    try {
      socket = new WebSocket(getSimulationWebSocketUrl())
    } catch (error) {
      this.setState('down', error instanceof Error ? error.message : 'WebSocket 创建失败')
      this.scheduleReconnect()
      return
    }
    this.socket = socket

    socket.addEventListener('open', () => {
      this.reconnectAttempts = 0
      this.setState('up')
    })

    socket.addEventListener('message', (event) => {
      let message: SocketMessage
      try {
        message = JSON.parse(event.data as string) as SocketMessage
      } catch {
        this.setState('up', '收到无法解析的 WebSocket 消息')
        return
      }
      if (message.type === 'snapshot' && message.payload) {
        this.snapshotListeners.forEach((listener) => listener(message.payload))
      }
    })

    socket.addEventListener('close', () => {
      this.socket = null
      if (!this.intentionalClose) {
        this.setState('down', '连接已断开，准备重连')
        this.scheduleReconnect()
      } else {
        this.setState('idle')
      }
    })

    socket.addEventListener('error', () => {
      // close 事件会随后触发并进入重连
    })
  }

  private scheduleReconnect(): void {
    if (this.reconnectTimer) return
    const delay = Math.min(BASE_RECONNECT_MILLIS * 2 ** this.reconnectAttempts, MAX_RECONNECT_MILLIS)
    this.reconnectAttempts += 1
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null
      this.connect()
    }, delay)
  }

  private setState(state: SocketState, detail?: string): void {
    this.currentState = state
    this.stateListeners.forEach((listener) => listener(state, detail))
  }

  subscribe(listener: SnapshotListener): () => void {
    this.snapshotListeners.add(listener)
    return () => this.snapshotListeners.delete(listener)
  }

  onStateChange(listener: StateListener): () => void {
    this.stateListeners.add(listener)
    return () => this.stateListeners.delete(listener)
  }

  disconnect(): void {
    this.intentionalClose = true
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer)
      this.reconnectTimer = null
    }
    this.reconnectAttempts = 0
    this.socket?.close()
    this.socket = null
  }
}

export const simulationSocket = new SimulationSocket()
