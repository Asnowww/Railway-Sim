import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import type { SimulationSnapshot } from '../types/simulation'
import type { DispatchSnapshot } from '../types/dispatch'

const emptyDispatch: DispatchSnapshot = {
  runMode: '',
  planId: '',
  targetHeadwaySeconds: 0,
  defaultDwellSeconds: 0,
  services: [],
  stationHeadways: [],
  interventionActive: false,
  trainProfiles: [],
  openDisturbances: [],
  activeCommands: [],
  routeDispatchActive: false,
  routeDecisions: [],
  routeReservations: [],
  operationPlans: []
}

/**
 * 快照唯一持有者。WS 与 REST 控制操作的返回值都汇入 applySnapshot，
 * 所有工作台从这里取数据切片，不各自请求快照。
 */
export const useSimulationStore = defineStore('simulation', () => {
  const snapshot = ref<SimulationSnapshot | null>(null)
  const lastSnapshotAt = ref<number | null>(null)

  function applySnapshot(payload: SimulationSnapshot): void {
    snapshot.value = payload
    lastSnapshotAt.value = Date.now()
  }

  const status = computed(() => snapshot.value?.status ?? 'STOPPED')
  const tick = computed(() => snapshot.value?.tick ?? 0)
  const simulatedTime = computed(() => snapshot.value?.simulatedTime ?? null)
  const simulationRunId = computed(() => {
    const raw = snapshot.value as (SimulationSnapshot & { simulationRunId?: string }) | null
    return raw?.simulationRunId ?? null
  })
  const trains = computed(() => snapshot.value?.trains ?? [])
  const trackSegments = computed(() => snapshot.value?.trackSegments ?? [])
  const authorities = computed(() => snapshot.value?.authorities ?? [])
  const signalStates = computed(() => snapshot.value?.signalStates ?? [])
  const switchStates = computed(() => snapshot.value?.switchStates ?? [])
  const routeStates = computed(() => snapshot.value?.routeStates ?? [])
  const powerSections = computed(() => snapshot.value?.powerSections ?? [])
  const alarms = computed(() => snapshot.value?.alarms ?? [])
  const vehicleRuntime = computed(() => snapshot.value?.vehicleRuntime ?? null)
  const dispatch = computed<DispatchSnapshot>(() => {
    const raw = snapshot.value?.dispatch
    if (!raw) return emptyDispatch
    return {
      ...emptyDispatch,
      ...raw,
      services: raw.services ?? [],
      stationHeadways: raw.stationHeadways ?? [],
      trainProfiles: raw.trainProfiles ?? [],
      openDisturbances: raw.openDisturbances ?? [],
      activeCommands: raw.activeCommands ?? [],
      routeDecisions: raw.routeDecisions ?? [],
      routeReservations: raw.routeReservations ?? [],
      operationPlans: raw.operationPlans ?? []
    }
  })

  const unconfirmedAlarmCount = computed(() => alarms.value.filter((alarm) => !alarm.confirmed).length)

  const simulatedClock = computed(() => {
    if (!simulatedTime.value) return '--:--:--'
    const date = new Date(simulatedTime.value)
    if (Number.isNaN(date.getTime())) return '--:--:--'
    return date.toLocaleTimeString('zh-CN', { hour12: false })
  })

  return {
    snapshot,
    lastSnapshotAt,
    applySnapshot,
    status,
    tick,
    simulatedTime,
    simulatedClock,
    simulationRunId,
    trains,
    trackSegments,
    authorities,
    signalStates,
    switchStates,
    routeStates,
    powerSections,
    alarms,
    vehicleRuntime,
    dispatch,
    unconfirmedAlarmCount
  }
})
