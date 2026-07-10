<script setup lang="ts">
import type { DispatchDisturbance } from '../../types/dispatch'

defineProps<{
  disturbances: DispatchDisturbance[]
}>()

const typeLabel = (type: string) => {
  const labels: Record<string, string> = {
    DWELL_EXTENDED: '停站时间过长',
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

const deviationText = (item: DispatchDisturbance) => {
  if (item.disturbanceType === 'CROWDING') {
    return `满载率 ${(item.deviationValue * 100).toFixed(0)}%`
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
          列车 {{ item.trainId }}
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
  background: #fff;
  border: 1px solid #e2e8f0;
  border-radius: 12px;
  padding: 16px;
}

h2 {
  margin: 0 0 12px;
  font-size: 16px;
}

.empty {
  margin: 0;
  color: #94a3b8;
}

ul {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  gap: 10px;
}

li {
  border: 1px solid #f1f5f9;
  border-radius: 8px;
  padding: 10px;
}

.title {
  font-weight: 600;
  font-size: 14px;
}

.meta {
  color: #64748b;
  font-size: 12px;
  margin-top: 4px;
}

.description {
  margin-top: 6px;
  color: #475569;
  font-size: 12px;
  line-height: 1.45;
}

.status {
  display: inline-block;
  margin-top: 6px;
  font-size: 11px;
  color: #b45309;
  background: #fef3c7;
  padding: 2px 8px;
  border-radius: 999px;
}

.status[data-status='OPEN'] {
  color: #b45309;
  background: #fef3c7;
}

.status[data-status='HANDLED'] {
  color: #1d4ed8;
  background: #dbeafe;
}

.status[data-status='RECOVERED'] {
  color: #0f766e;
  background: #ccfbf1;
}
</style>
