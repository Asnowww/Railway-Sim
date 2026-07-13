import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { trackApi } from '../api/track'
import type { LineTopology } from '../types/topology'

/**
 * 静态线路拓扑（车站里程 / 区段几何 / 道岔 / 进路定义）。
 * 启动时加载一次；仿真 reset 不改变线路配置，无需刷新。
 */
export const useTopologyStore = defineStore('topology', () => {
  const topology = ref<LineTopology | null>(null)
  const loading = ref(false)
  const error = ref('')

  async function load(force = false): Promise<void> {
    if (loading.value) return
    if (topology.value && !force) return
    loading.value = true
    try {
      topology.value = await trackApi.topology()
      error.value = ''
    } catch (err) {
      error.value = err instanceof Error ? err.message : String(err)
    } finally {
      loading.value = false
    }
  }

  const stations = computed(() => topology.value?.stations ?? [])
  const stationNameById = computed(() => {
    const map = new Map<string, string>()
    stations.value.forEach((station) => map.set(station.id, station.name))
    return map
  })

  /** 车站显示名：优先拓扑名称，缺失时回退 ID（保持可诊断性）。 */
  function stationName(stationId?: string | null): string {
    if (!stationId) return '—'
    return stationNameById.value.get(stationId) ?? stationId
  }

  const lineLengthMeters = computed(() => {
    if (!topology.value) return 0
    if (topology.value.lengthMeters > 0) return topology.value.lengthMeters
    return Math.max(0, ...(topology.value.segments ?? []).map((segment) => segment.endMeters))
  })

  /** 区段 id → 视景边号（UDP segNo），来自 topology.segments[].rawSegmentId */
  const rawSegmentIds = computed<Record<string, number>>(() => {
    const map: Record<string, number> = {}
    for (const segment of topology.value?.segments ?? []) {
      if (segment.rawSegmentId !== undefined && segment.rawSegmentId > 0) {
        map[segment.id] = segment.rawSegmentId
      }
    }
    return map
  })

  return { topology, loading, error, load, stations, stationName, stationNameById, lineLengthMeters, rawSegmentIds }
})
