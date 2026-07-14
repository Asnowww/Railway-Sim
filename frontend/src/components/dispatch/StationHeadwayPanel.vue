<script setup lang="ts">
import { computed } from 'vue'
import type { DispatchStationHeadway } from '../../types/dispatch'

const props = defineProps<{ observations: DispatchStationHeadway[] }>()

const errorText = (error: number) => `${error > 0 ? '+' : ''}${error.toFixed(0)}s`
const actionText = (action: string) => ({
  CATCH_UP: '本车追赶',
  SLOW_DOWN: '本车放慢',
  NORMAL: '正常',
  OBSERVE: '观察',
}[action] ?? action)

const summary = computed(() => {
  const items = props.observations
  const count = items.length
  const onTarget = items.filter((item) => item.state === 'ON_TARGET').length
  const tooShort = items.filter((item) => item.state === 'TOO_SHORT').length
  const tooLong = items.filter((item) => item.state === 'TOO_LONG').length
  const avgAbsError = count
    ? items.reduce((sum, item) => sum + Math.abs(item.headwayErrorSeconds), 0) / count
    : 0
  const worst = count
    ? [...items].sort((a, b) => Math.abs(b.headwayErrorSeconds) - Math.abs(a.headwayErrorSeconds))[0]
    : null
  const observedStations = new Set(items.map((item) => `${item.stationId}-${item.direction}`)).size

  return {
    count,
    onTarget,
    tooShort,
    tooLong,
    avgAbsError,
    worst,
    observedStations,
  }
})
</script>

<template>
  <section class="panel">
    <header>
      <h2>车站级间隔观测</h2>
      <span>同站、同方向实际发车事件</span>
    </header>
    <div class="summary-grid">
      <article>
        <small>完整观测</small>
        <strong>{{ summary.count }}</strong>
        <span>连续发车对</span>
      </article>
      <article>
        <small>平均偏差</small>
        <strong>{{ summary.avgAbsError.toFixed(0) }}s</strong>
        <span>越小越接近计划</span>
      </article>
      <article>
        <small>最大偏差</small>
        <strong>{{ summary.worst ? errorText(summary.worst.headwayErrorSeconds) : '-' }}</strong>
        <span>{{ summary.worst ? `${summary.worst.stationId} ${summary.worst.direction}` : '等待观测' }}</span>
      </article>
      <article>
        <small>状态分布</small>
        <strong>{{ summary.onTarget }}/{{ summary.tooShort }}/{{ summary.tooLong }}</strong>
        <span>正常/过短/过长</span>
      </article>
    </div>
    <p v-if="observations.length === 0" class="empty">
      尚无两列车从同一车站同方向发车的完整观测；等后车也从同站发车后，这里会显示实际间隔、偏差和调节动作。
    </p>
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
.summary-grid { display:grid; grid-template-columns:repeat(4,minmax(0,1fr)); gap:8px; margin-top:12px; }
.summary-grid article { display:grid; gap:3px; padding:9px; background:var(--bg-panel-raised); border:1px solid var(--border); border-radius:8px; min-width:0; }
.summary-grid small { color:var(--text-muted); font-size:11px; }
.summary-grid strong { color:var(--text-primary); font-size:16px; }
.summary-grid span { color:var(--text-secondary); font-size:11px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.empty { margin:14px 0 0; line-height:1.5; }
.list { display:grid; gap:8px; margin-top:12px; }
.list article { display:grid; grid-template-columns:.8fr 1fr 1.4fr 1fr; gap:8px; padding:9px; background:var(--bg-panel-raised); border-radius:8px; font-size:12px; }
b[data-state='TOO_LONG'] { color:var(--status-warn); }
b[data-state='TOO_SHORT'] { color:var(--status-danger); }
b[data-state='ON_TARGET'] { color:var(--status-ok); }

@media (max-width: 1100px) {
  .summary-grid { grid-template-columns:repeat(2,minmax(0,1fr)); }
  .list article { grid-template-columns:1fr 1fr; }
}
</style>
