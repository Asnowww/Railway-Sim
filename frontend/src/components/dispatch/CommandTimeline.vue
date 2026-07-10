<script setup lang="ts">
import type { DispatchCommandView } from '../../types/dispatch'

defineProps<{
  commands: DispatchCommandView[]
}>()

const commandLabel = (type: string) => {
  const labels: Record<string, string> = {
    SHORTEN_DWELL: '缩短停站/尽快发车',
    EXTEND_DWELL: '延长停站',
    HEADWAY_ADJUST: '调整行车间隔',
    SPEED_BIAS: '速度偏置',
    SPEED_LIMIT: '临时限速',
    TEMP_SPEED_LIMIT: '临时限速',
    SPEED_FACTOR: '速度系数',
    LIMIT_FACTOR: '限速系数',
    HOLD: '扣车',
    HOLD_TRAIN: '扣车',
    DEPART: '发车',
    REROUTE: '重排进路',
  }
  return labels[type] ?? type
}

const statusLabel = (status: string) => {
  const labels: Record<string, string> = {
    PENDING: '待下发',
    SENT: '已下发',
    APPLIED: '已观测到执行',
    EFFECT_CONFIRMED: '闭环已完成',
    TIMEOUT: '执行超时',
    SKIPPED: '已跳过',
    CANCELLED: '已取消',
    EXPIRED: '已结束',
  }
  return labels[status] ?? status
}

const statusDescription = (status: string) => {
  const descriptions: Record<string, string> = {
    PENDING: '调度已生成指令，等待进入信号/车辆执行链路。',
    SENT: '指令已经下发，调度正在观察列车状态、MA 和间隔变化。',
    APPLIED: '已经从车辆状态或信号授权中观察到指令产生作用。',
    EFFECT_CONFIRMED: '调度已经确认扰动恢复，或该指令不需要继续施加约束。',
    TIMEOUT: '超过观察窗口仍未看到效果，需要检查信号、车辆或线路约束。',
    SKIPPED: '指令被校验规则跳过，没有进入执行链路。',
    CANCELLED: '指令已取消。',
    EXPIRED: '指令已结束。',
  }
  return descriptions[status] ?? '调度命令状态持续记录。'
}
</script>

<template>
  <section class="panel">
    <h2>调度指令</h2>
    <p v-if="commands.length === 0" class="empty">暂无调度指令记录</p>
    <ul v-else>
      <li v-for="item in commands" :key="item.id">
        <div class="row">
          <strong>{{ commandLabel(item.commandType) }}</strong>
          <span class="status" :data-status="item.status">{{ statusLabel(item.status) }}</span>
        </div>
        <div class="meta">列车 {{ item.trainId }} · 原因 {{ item.reason }}</div>
        <div class="description">{{ statusDescription(item.status) }}</div>
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
  border-left: 3px solid #3b82f6;
  padding: 8px 10px;
  background: #f8fafc;
  border-radius: 0 8px 8px 0;
}

.row {
  display: flex;
  justify-content: space-between;
  gap: 8px;
}

.meta {
  margin-top: 4px;
  font-size: 12px;
  color: #64748b;
}

.description {
  margin-top: 4px;
  font-size: 12px;
  color: #475569;
  line-height: 1.45;
}

.status {
  font-size: 11px;
  padding: 2px 8px;
  border-radius: 999px;
  background: #e2e8f0;
}

.status[data-status='APPLIED'] {
  background: #dcfce7;
  color: #166534;
}

.status[data-status='SENT'] {
  background: #dbeafe;
  color: #1d4ed8;
}

.status[data-status='EFFECT_CONFIRMED'] {
  background: #ccfbf1;
  color: #0f766e;
}

.status[data-status='PENDING'] {
  background: #fef3c7;
  color: #92400e;
}

.status[data-status='TIMEOUT'] {
  background: #fee2e2;
  color: #b91c1c;
}
</style>
