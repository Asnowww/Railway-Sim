<script setup lang="ts">
import type { DispatchDisturbance } from '../../types/dispatch'

defineProps<{
  disturbances: DispatchDisturbance[]
}>()

const typeLabel = (type: string) => {
  const labels: Record<string, string> = {
    DWELL_EXTENDED: '停站时间过长',
    TRAIN_REGULATION: '本车运行调节',
    HEADWAY_VIOLATION: '运行间隔超限',
    HEADWAY_SHRINK: '行车间隔过小',
    HEADWAY_EXPAND: '行车间隔过大',
    DEPARTURE_DELAY: '发车延误',
    CROWDING: '客流拥挤',
  }
  return labels[type] ?? type
}

const statusLabel = (status: string) => {
  const labels: Record<string, string> = {
    OPEN: '待处理',
    HANDLED: '已生成调度指令',
    RECOVERED: '已恢复',
  }
  return labels[status] ?? status
}

const statusDescription = (item: DispatchDisturbance) => {
  if (item.status === 'OPEN') {
    return '系统已检测到扰动，等待策略生成或校验。'
  }
  if (item.status === 'HANDLED') {
    return '调度已针对该扰动生成指令，后续会继续观察列车状态和间隔是否恢复。'
  }
  if (item.status === 'RECOVERED') {
    return '运行指标已回到阈值范围，调度干预可以结束。'
  }
  return '状态由调度闭环持续更新。'
}

const actionLabel = (action?: string | null) => {
  const labels: Record<string, string> = {
    CATCH_UP: '本车追赶',
    SLOW_DOWN: '本车放慢',
    NORMAL: '本车正常运行',
    OBSERVE: '继续观测',
  }
  return labels[action ?? 'OBSERVE'] ?? action ?? '继续观测'
}

const deviationText = (item: DispatchDisturbance) => {
  if (item.disturbanceType === 'CROWDING') {
    return `满载率 ${(item.deviationValue * 100).toFixed(0)}%`
  }
  if (item.disturbanceType === 'TRAIN_REGULATION' || item.disturbanceType === 'HEADWAY_VIOLATION') {
    if (item.headwayDirection === 'SCHEDULE_LATE') {
      return `本车运行图晚点 · 超限 ${(item.violationSec ?? item.deviationValue).toFixed(0)} 秒 · ${actionLabel(item.regulationAction)}`
    }
    const direction = item.headwayDirection === 'TOO_SHORT' ? '过短' : '过长'
    const actual = item.actualHeadwaySec ?? 0
    const target = item.targetHeadwaySec ?? 0
    const tolerance = item.toleranceSec ?? 0
    const violation = item.violationSec ?? item.deviationValue
    return `${direction} · 实际 ${actual.toFixed(0)} 秒 / 目标 ${target.toFixed(0)} 秒 · 偏差 ${actual - target >= 0 ? '+' : ''}${(actual - target).toFixed(0)} 秒 · ${actionLabel(item.regulationAction)}`
  }
  if (item.disturbanceType === 'HEADWAY_SHRINK' || item.disturbanceType === 'HEADWAY_EXPAND') {
    return `实际间隔 ${item.deviationValue.toFixed(0)} 秒`
  }
  return `偏差 ${item.deviationValue.toFixed(1)} 秒`
}
</script>

<template>
  <section class="panel">
    <h2>运行扰动</h2>
    <p v-if="disturbances.length === 0" class="empty">暂无扰动记录</p>
    <ul v-else>
      <li v-for="item in disturbances" :key="item.id">
        <div class="title">{{ typeLabel(item.disturbanceType) }}</div>
        <div class="meta">
          调节本车 {{ item.regulatedTrainId || item.trainId }}
          <span v-if="item.trainId !== (item.regulatedTrainId || item.trainId)"> · 事件列车 {{ item.trainId }}</span>
          <span v-if="item.stationId"> · 站点 {{ item.stationId }}</span>
          · {{ deviationText(item) }}
        </div>
        <div class="description">{{ statusDescription(item) }}</div>
        <span class="status" :data-status="item.status">{{ statusLabel(item.status) }}</span>
      </li>
    </ul>
  </section>
</template>

<style scoped>
.panel {
  background: var(--bg-panel);
  border: 1px solid var(--border);
  border-radius: 12px;
  padding: 16px;
}

h2 {
  margin: 0 0 12px;
  font-size: 16px;
}

.empty {
  margin: 0;
  color: var(--text-muted);
}

ul {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  gap: 10px;
}

li {
  border: 1px solid var(--bg-hover);
  border-radius: 8px;
  padding: 10px;
}

.title {
  font-weight: 600;
  font-size: 14px;
}

.meta {
  color: var(--text-secondary);
  font-size: 12px;
  margin-top: 4px;
}

.description {
  margin-top: 6px;
  color: var(--text-secondary);
  font-size: 12px;
  line-height: 1.45;
}

.status {
  display: inline-block;
  margin-top: 6px;
  font-size: 11px;
  color: var(--status-warn);
  background: var(--status-warn-bg);
  padding: 2px 8px;
  border-radius: 999px;
}

.status[data-status='OPEN'] {
  color: var(--status-warn);
  background: var(--status-warn-bg);
}

.status[data-status='HANDLED'] {
  color: var(--status-info);
  background: var(--status-info-bg);
}

.status[data-status='RECOVERED'] {
  color: var(--status-ok);
  background: var(--status-ok-bg);
}
</style>
