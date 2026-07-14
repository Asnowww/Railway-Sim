<script setup lang="ts">
import type { DispatchStationHeadway } from '../../types/dispatch'

defineProps<{ observations: DispatchStationHeadway[] }>()

const errorText = (error: number) => `${error > 0 ? '+' : ''}${error.toFixed(0)}s`
const actionText = (action: string) => ({
  CATCH_UP: '本车追赶',
  SLOW_DOWN: '本车放慢',
  NORMAL: '正常',
  OBSERVE: '观察',
}[action] ?? action)
</script>

<template>
  <section class="panel">
    <header>
      <h2>车站级间隔观测</h2>
      <span>同站、同方向实际发车事件</span>
    </header>
    <p v-if="observations.length === 0" class="empty">尚无两列车从同一车站同方向发车的完整观测</p>
    <div v-else class="list">
      <article v-for="item in observations" :key="`${item.stationId}-${item.trainId}-${item.departureAt}`">
        <strong>{{ item.stationId }} · {{ item.direction }}</strong>
        <span>{{ item.frontTrainId }} → {{ item.trainId }}</span>
        <span>实际 {{ item.actualHeadwaySeconds.toFixed(0) }}s / 目标 {{ item.targetHeadwaySeconds }}s</span>
        <b :data-state="item.state">{{ errorText(item.headwayErrorSeconds) }} · {{ actionText(item.regulationAction) }}</b>
      </article>
    </div>
  </section>
</template>

<style scoped>
.panel { background:var(--bg-panel); border:1px solid var(--border); border-radius:12px; padding:16px; }
header { display:flex; justify-content:space-between; gap:12px; align-items:baseline; }
h2 { margin:0; font-size:16px; }
header span,.empty { color:var(--text-secondary); font-size:12px; }
.list { display:grid; gap:8px; margin-top:12px; }
article { display:grid; grid-template-columns:.8fr 1fr 1.4fr 1fr; gap:8px; padding:9px; background:var(--bg-panel-raised); border-radius:8px; font-size:12px; }
b[data-state='TOO_LONG'] { color:var(--status-warn); }
b[data-state='TOO_SHORT'] { color:var(--status-danger); }
b[data-state='ON_TARGET'] { color:var(--status-ok); }
</style>
