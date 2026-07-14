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
  background: var(--bg-panel);
  border: 1px solid var(--border);
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
  background: var(--bg-hover);
  color: var(--text-secondary);
  padding: 4px 10px;
  border-radius: 999px;
  font-size: 12px;
}

.chip.mode {
  background: var(--status-info-bg);
  color: var(--status-info);
}

.chip.warn {
  background: var(--status-warn-bg);
  color: var(--status-warn);
}

.meta {
  color: var(--text-secondary);
  font-size: 13px;
}

.actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

button {
  border: 1px solid var(--border-strong);
  background: var(--bg-panel);
  color: var(--text-primary);
  border-radius: 8px;
  padding: 10px 16px;
  cursor: pointer;
  font-size: 14px;
  font-weight: 500;
}

button.primary,
button.action-start {
  background: var(--accent);
  border-color: var(--accent);
  color: #ffffff;
}

button:hover {
  filter: brightness(0.97);
}
</style>
