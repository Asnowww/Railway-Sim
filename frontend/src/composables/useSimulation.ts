import { onBeforeUnmount, onMounted, ref } from 'vue'
import { dispatchApi } from '../api/dispatch'
import { simulationApi } from '../api/rest'
import { simulationSocket } from '../api/ws'
import type { DispatchSnapshot, RunPlanResponse } from '../types/dispatch'
import type { SimulationSnapshot, SimulationStatus } from '../types/simulation'

const emptyDispatch: DispatchSnapshot = {
  runMode: 'FLAT',
  planId: '',
  targetHeadwaySeconds: 300,
  defaultDwellSeconds: 25,
  services: [],
  stationHeadways: [],
  interventionActive: false,
  trainProfiles: [],
  openDisturbances: [],
  activeCommands: [],
  routeDispatchActive: false,
  routeDecisions: [],
  routeReservations: []
}

export function useSimulation() {
  const plan = ref<RunPlanResponse | null>(null)
  const snapshot = ref<SimulationSnapshot | null>(null)
  const dispatch = ref<DispatchSnapshot>(emptyDispatch)
  const status = ref<SimulationStatus>('STOPPED')
  const tick = ref(0)
  const errorMessage = ref('')
  const backendReady = ref(false)
  const autoRunning = ref(false)

  let autoTimer: ReturnType<typeof setInterval> | null = null
  let unsubscribeSnapshot: (() => void) | null = null

  function applySnapshot(payload: SimulationSnapshot) {
    snapshot.value = payload
    status.value = payload.status
    tick.value = payload.tick
    dispatch.value = payload.dispatch
      ? {
          ...emptyDispatch,
      ...payload.dispatch,
      services: payload.dispatch.services ?? [],
      stationHeadways: payload.dispatch.stationHeadways ?? [],
      routeDecisions: payload.dispatch.routeDecisions ?? [],
          routeReservations: payload.dispatch.routeReservations ?? []
        }
      : emptyDispatch
    backendReady.value = true
  }

  async function loadPlan() {
    plan.value = await dispatchApi.plan()
  }

  async function runSimulation(action: 'start' | 'pause' | 'reset' | 'tick') {
    errorMessage.value = ''
    try {
      const result =
        action === 'start'
          ? await simulationApi.start()
          : action === 'pause'
            ? await simulationApi.pause()
            : action === 'reset'
              ? await simulationApi.reset()
              : await simulationApi.tick()
      applySnapshot(result)
    } catch (error) {
      backendReady.value = false
      errorMessage.value =
        error instanceof Error
          ? `${error.message}（后端接口返回异常，请查看后端控制台对应堆栈；若无法连接，再检查后端地址配置）`
          : '操作失败'
    }
  }

  async function refreshSimulationSnapshot() {
    errorMessage.value = ''
    try {
      applySnapshot(await simulationApi.snapshot())
    } catch (error) {
      backendReady.value = false
      errorMessage.value =
        error instanceof Error
          ? `${error.message}（后端接口返回异常，请查看后端控制台对应堆栈；若无法连接，再检查后端地址配置）`
          : '刷新失败'
    }
  }

  function toggleAutoRun() {
    autoRunning.value = !autoRunning.value
    if (autoTimer) {
      clearInterval(autoTimer)
      autoTimer = null
    }
    if (autoRunning.value) {
      autoTimer = setInterval(() => {
        if (status.value === 'RUNNING') {
          void runSimulation('tick')
        }
      }, 500)
    }
  }

  onMounted(async () => {
    try {
      await loadPlan()
    } catch (error) {
      errorMessage.value =
        error instanceof Error
          ? `${error.message}（运行计划加载失败，快照仍会继续尝试加载）`
          : '运行计划加载失败'
    }

    try {
      applySnapshot(await simulationApi.snapshot())
    } catch (error) {
      backendReady.value = false
      errorMessage.value =
        error instanceof Error
          ? `${error.message}（后端快照接口异常，请查看后端控制台对应堆栈；若无法连接，再检查 Vite 代理或 VITE_API_BASE_URL）`
          : '后端快照加载失败'
    }

    simulationSocket.connect()
    unsubscribeSnapshot = simulationSocket.subscribe(applySnapshot)
  })

  onBeforeUnmount(() => {
    if (autoTimer) {
      clearInterval(autoTimer)
    }
    unsubscribeSnapshot?.()
    unsubscribeSnapshot = null
    simulationSocket.disconnect()
  })

  return {
    plan,
    snapshot,
    dispatch,
    status,
    tick,
    errorMessage,
    backendReady,
    autoRunning,
    runSimulation,
    refreshSimulationSnapshot,
    toggleAutoRun
  }
}
