import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { simulationSocket, type SocketState } from '../api/ws'
import { simulationApi } from '../api/rest'
import { serviceHealthApi } from '../api/serviceHealth'
import { useSimulationStore } from './simulation'
import type { ServiceHealthRecord } from '../types/ops'

/** 快照推送间隔 1s；超过 3s 未收到即判 STALE。 */
const STALE_THRESHOLD_MILLIS = 3000
const HEALTH_POLL_MILLIS = 15_000

export type LinkQuality = 'up' | 'stale' | 'down' | 'connecting'

/**
 * 连接与数据新鲜度状态机。
 * - wsState：WebSocket 物理连接状态
 * - linkQuality：综合判定（up / stale / down），全站的"数据可信度"开关
 * - serviceHealth：/api/service-health 定时轮询（9300/9200 门禁状态）
 */
export const useConnectionStore = defineStore('connection', () => {
  const simulation = useSimulationStore()

  const wsState = ref<SocketState>('idle')
  const wsDetail = ref('')
  const now = ref(Date.now())
  const restReachable = ref<boolean | null>(null)
  const serviceHealth = ref<ServiceHealthRecord[]>([])
  const serviceHealthError = ref('')

  let started = false
  let clockTimer = 0
  let healthTimer = 0

  const snapshotAgeMillis = computed(() => {
    if (simulation.lastSnapshotAt === null) return null
    return Math.max(0, now.value - simulation.lastSnapshotAt)
  })

  const linkQuality = computed<LinkQuality>(() => {
    if (wsState.value === 'connecting' && simulation.lastSnapshotAt === null) return 'connecting'
    if (wsState.value !== 'up') {
      // WS 断开但 REST 曾拉到过快照——数据只是陈旧
      return simulation.lastSnapshotAt !== null ? 'stale' : 'down'
    }
    if (snapshotAgeMillis.value === null) return 'connecting'
    if (snapshotAgeMillis.value > STALE_THRESHOLD_MILLIS) {
      // 后端的快照推送挂在 tick 循环上：PAUSED/STOPPED 时不推送，
      // 此时静默是正常稳态（最后一帧即当前真实状态），不能判为数据过期。
      return simulation.status === 'RUNNING' ? 'stale' : 'up'
    }
    return 'up'
  })

  /** 仿真未运行且推送静默（用于顶栏提示"已连接·仿真未运行"） */
  const idleQuiet = computed(
    () =>
      wsState.value === 'up' &&
      simulation.status !== 'RUNNING' &&
      snapshotAgeMillis.value !== null &&
      snapshotAgeMillis.value > STALE_THRESHOLD_MILLIS
  )

  const lastSnapshotClock = computed(() => {
    if (simulation.lastSnapshotAt === null) return ''
    return new Date(simulation.lastSnapshotAt).toLocaleTimeString('zh-CN', { hour12: false })
  })

  /** 数据是否可信（供各面板决定是否叠加冻结遮罩） */
  const dataTrusted = computed(() => linkQuality.value === 'up')

  const vehicleRuntimeHealth = computed(() => simulation.vehicleRuntime)

  async function refreshServiceHealth(): Promise<void> {
    try {
      serviceHealth.value = await serviceHealthApi.list()
      serviceHealthError.value = ''
    } catch (error) {
      serviceHealthError.value = error instanceof Error ? error.message : String(error)
    }
  }

  /** 应用启动时调用一次：建立 WS、拉初始快照、启动健康轮询。 */
  function start(): void {
    if (started) return
    started = true

    simulationSocket.onStateChange((state, detail) => {
      const reconnected = state === 'up' && wsState.value !== 'up'
      wsState.value = state
      wsDetail.value = detail ?? ''
      // WS 重连成功后主动拉一次快照校准：后端 PAUSED/STOPPED 时不推送，
      // 若断线期间仿真状态变了，仅靠 WS 会一直停留在断线前的旧状态（误判"数据过期"）。
      if (reconnected) {
        void simulationApi
          .snapshot()
          .then((snapshot) => {
            restReachable.value = true
            simulation.applySnapshot(snapshot)
          })
          .catch(() => undefined)
      }
    })
    simulationSocket.subscribe((snapshot) => simulation.applySnapshot(snapshot))
    simulationSocket.connect()

    void simulationApi
      .snapshot()
      .then((snapshot) => {
        restReachable.value = true
        simulation.applySnapshot(snapshot)
      })
      .catch(() => {
        restReachable.value = false
      })

    void refreshServiceHealth()
    clockTimer = window.setInterval(() => {
      now.value = Date.now()
    }, 1000)
    healthTimer = window.setInterval(() => void refreshServiceHealth(), HEALTH_POLL_MILLIS)
  }

  function stop(): void {
    if (!started) return
    started = false
    simulationSocket.disconnect()
    window.clearInterval(clockTimer)
    window.clearInterval(healthTimer)
  }

  return {
    wsState,
    wsDetail,
    restReachable,
    serviceHealth,
    serviceHealthError,
    snapshotAgeMillis,
    linkQuality,
    idleQuiet,
    lastSnapshotClock,
    dataTrusted,
    vehicleRuntimeHealth,
    refreshServiceHealth,
    start,
    stop
  }
})
