<script setup lang="ts">
import type { SimulationStatus } from '../../types/simulation'

defineProps<{
  status: SimulationStatus
  runMode: string
  targetHeadwaySeconds: number
  interventionActive: boolean
  tick: number
}>()

const emit = defineEmits<{
  start: []
  pause: []
  reset: []
  tick: []
}>()

function label(type: string) {
  const map: Record<string, string> = {
    PEAK: '高峰',
    FLAT: '平峰',
    OFF_PEAK: '低谷',
    STOPPED: '已停止',
    RUNNING: '运行中',
    PAUSED: '已暂停'
  }
  return map[type] ?? type
}
</script>

<template>
  <header class="status-bar">
    <div class="info">
      <h1>运营调度</h1>
      <span class="chip">{{ label(status) }}</span>
      <span class="chip mode">{{ label(runMode) }}</span>
      <span class="meta">目标间隔 {{ targetHeadwaySeconds }}s · Tick {{ tick }}</span>
      <span v-if="interventionActive" class="chip warn">调度干预中</span>
    </div>
    <div class="actions">
      <button type="button" class="action-start" @click="emit('start')">启动</button>
      <button type="button" @click="emit('pause')">暂停</button>
      <button type="button" class="primary" @click="emit('tick')">步进</button>
      <button type="button" @click="emit('reset')">重置</button>
      <slot name="extra-actions" />
    </div>
  </header>
</template>

<style scoped>
.status-bar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 16px 20px;
  background: #fff;
  border: 1px solid #e2e8f0;
  border-radius: 12px;
  box-shadow: 0 1px 2px rgb(15 23 42 / 6%);
}

.info {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

h1 {
  margin: 0;
  font-size: 20px;
}

.chip {
  background: #f1f5f9;
  color: #475569;
  padding: 4px 10px;
  border-radius: 999px;
  font-size: 12px;
}

.chip.mode {
  background: #dbeafe;
  color: #1d4ed8;
}

.chip.warn {
  background: #fef3c7;
  color: #92400e;
}

.meta {
  color: #64748b;
  font-size: 13px;
}

.actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

button {
  border: 1px solid #cbd5e1;
  background: #fff;
  color: #334155;
  border-radius: 8px;
  padding: 10px 16px;
  cursor: pointer;
  font-size: 14px;
  font-weight: 500;
}

button.primary,
button.action-start {
  background: #2563eb;
  border-color: #2563eb;
  color: #fff;
}

button:hover {
  filter: brightness(0.97);
}
</style>
