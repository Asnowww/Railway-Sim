import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { storeToRefs } from 'pinia'
import { dispatchApi } from '../api/dispatch'
import { simulationApi } from '../api/rest'
import { useSimulationStore } from '../stores/simulation'
import type { RunPlanResponse } from '../types/dispatch'

/**
 * 调试页专用的仿真控制组合函数。
 * 快照与 WebSocket 连接统一由全局 connection/simulation store 持有，
 * 本组合函数只做：运行计划加载、控制操作转发、调试用自动步进定时器。
 * （旧版在组件卸载时会断开全局 WebSocket——已修复。）
 */
export function useSimulation() {
  const store = useSimulationStore()
  const { snapshot, status, tick, dispatch } = storeToRefs(store)

  const plan = ref<RunPlanResponse | null>(null)
  const errorMessage = ref('')
  const autoRunning = ref(false)
  const backendReady = computed(() => store.lastSnapshotAt !== null)

  let autoTimer: ReturnType<typeof setInterval> | null = null

  async function loadPlan(): Promise<void> {
    plan.value = await dispatchApi.plan()
  }

  async function runSimulation(action: 'start' | 'pause' | 'reset' | 'tick'): Promise<void> {
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
      store.applySnapshot(result)
    } catch (error) {
      errorMessage.value =
        error instanceof Error ? `${error.message}（请确认后端地址配置正确且服务已启动）` : '操作失败'
    }
  }

  function toggleAutoRun(): void {
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
        error instanceof Error ? `${error.message}（请确认 Vite 代理已指向可用后端）` : '初始化失败'
    }
  })

  onBeforeUnmount(() => {
    if (autoTimer) clearInterval(autoTimer)
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
    toggleAutoRun
  }
}
