<script setup lang="ts">
import type { DispatchTrainProfile } from '../../types/dispatch'

defineProps<{
  profiles: DispatchTrainProfile[]
}>()

const actionLabel = (action: string) => {
  const labels: Record<string, string> = {
    CATCH_UP: '本车追赶',
    SLOW_DOWN: '本车放慢',
    NORMAL: '本车正常运行',
    OBSERVE: '等待参考数据',
  }
  return labels[action] ?? action
}

const reasonLabel = (reason: string) => {
  const labels: Record<string, string> = {
    HEADWAY_TOO_LONG: '本车与前车间隔过长',
    HEADWAY_TOO_SHORT: '本车与前车间隔过短',
    HEADWAY_ON_TARGET: '本车间隔在目标范围内',
    SCHEDULE_DELAY_RECOVERY: '无前车参考，按运行图恢复本车晚点',
    WAITING_FOR_REFERENCE_DATA: '等待同站发车参考数据',
  }
  return labels[reason] ?? reason
}

const errorText = (value: number | null) => {
  if (value === null) return '-'
  const sign = value > 0 ? '+' : ''
  return `${sign}${value.toFixed(0)}s`
}
</script>

<template>
  <section class="panel">
    <div class="heading">
      <h2>本车间隔调节</h2>
      <span>偏差为正则追赶，偏差为负则放慢</span>
    </div>
    <p v-if="profiles.length === 0" class="empty">暂无列车间隔观测</p>
    <div v-else class="list">
      <article v-for="profile in profiles" :key="profile.trainId" :data-action="profile.regulationAction">
        <div class="train">
          <strong>{{ profile.regulatedTrainId || profile.trainId }}</strong>
          <span>{{ actionLabel(profile.regulationAction) }}</span>
        </div>
        <div class="metrics">
          <span>参考前车 {{ profile.frontTrainId || '-' }}</span>
          <span>实际间隔 {{ profile.headwayActualSeconds === null ? '-' : `${profile.headwayActualSeconds.toFixed(0)}s` }}</span>
          <span>间隔偏差 {{ errorText(profile.headwayErrorSeconds) }}</span>
        </div>
        <p>{{ reasonLabel(profile.regulationReason) }}</p>
      </article>
    </div>
  </section>
</template>

<style scoped>
.panel {
  background: #fff;
  border: 1px solid #e2e8f0;
  border-radius: 12px;
  padding: 16px;
}

.heading {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}

h2 {
  margin: 0;
  font-size: 16px;
}

.heading > span,
.empty {
  color: #64748b;
  font-size: 12px;
}

.list {
  display: grid;
  gap: 10px;
}

article {
  border: 1px solid #e2e8f0;
  border-left: 4px solid #94a3b8;
  border-radius: 8px;
  padding: 10px 12px;
  background: #f8fafc;
}

article[data-action='CATCH_UP'] {
  border-left-color: #f59e0b;
  background: #fffbeb;
}

article[data-action='SLOW_DOWN'] {
  border-left-color: #ef4444;
  background: #fef2f2;
}

article[data-action='NORMAL'] {
  border-left-color: #10b981;
  background: #ecfdf5;
}

.train {
  display: flex;
  justify-content: space-between;
  gap: 8px;
}

.train span {
  font-size: 12px;
  font-weight: 700;
}

.metrics {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 14px;
  margin-top: 6px;
  color: #475569;
  font-size: 12px;
}

p {
  margin: 6px 0 0;
  color: #64748b;
  font-size: 12px;
}
</style>
