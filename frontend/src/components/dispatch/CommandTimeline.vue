<script setup lang="ts">
import type { DispatchCommandView } from '../../types/dispatch'

defineProps<{
  commands: DispatchCommandView[]
}>()

const commandLabel = (type: string) => {
  const labels: Record<string, string> = {
    SHORTEN_DWELL: '本车追赶：缩短停站',
    EXTEND_DWELL: '本车放慢：延长停站',
    HEADWAY_ADJUST: '本车间隔调节',
    SPEED_BIAS: '速度偏置',
    SPEED_LIMIT: '临时限速',
    TEMP_SPEED_LIMIT: '临时限速',
    SPEED_FACTOR: '速度系数',
    LIMIT_FACTOR: '限速系数',
    HOLD: '扣车',
    HOLD_TRAIN: '扣车',
    DEPART: '发车',
    REQUEST_ROUTE: '申请进路',
    REROUTE: '重排进路',
  }
  return labels[type] ?? type
}

const regulationLabel = (action?: string | null) => {
  const labels: Record<string, string> = {
    CATCH_UP: '本车追赶',
    SLOW_DOWN: '本车放慢',
    NORMAL: '本车正常',
    OBSERVE: '继续观测',
  }
  return action ? labels[action] ?? action : null
}

const statusLabel = (status: string) => {
  const labels: Record<string, string> = {
    PENDING: '待下发',
    SENT: '已下发',
    APPLIED: '约束已出现',
    EFFECT_CONFIRMED: '效果已闭环',
    TIMEOUT: '执行超时',
    SKIPPED: '已跳过',
    CANCELLED: '已取消',
    EXPIRED: '已结束',
    RELEASED: '已释放',
  }
  return labels[status] ?? status
}

const statusDescription = (status: string) => {
  const descriptions: Record<string, string> = {
    PENDING: '调度已生成指令，等待进入信号/车辆执行链路。',
    SENT: '指令已经下发，尚未看到可核对的 MA、速度、停站或进路状态变化。',
    APPLIED: '已经看到中间约束或车辆状态变化，但还不等于最终运行效果恢复。',
    EFFECT_CONFIRMED: '调度已经确认目标效果满足，指令可以退出持续约束。',
    TIMEOUT: '超过观察窗口仍未看到效果，需要检查信号、车辆或线路约束。',
    SKIPPED: '指令被校验规则跳过，没有进入执行链路。',
    CANCELLED: '指令已取消。',
    EXPIRED: '指令已结束。',
    RELEASED: '进路或预约已经释放。',
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
        <div class="meta">
          作用本车 {{ item.regulatedTrainId || item.trainId }} · 原因 {{ item.reason }}
          <span v-if="regulationLabel(item.regulationAction)"> · {{ regulationLabel(item.regulationAction) }}</span>
        </div>
        <div class="description">{{ statusDescription(item.status) }}</div>
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
  border-left: 3px solid #3b82f6;
  padding: 8px 10px;
  background: var(--bg-panel-raised);
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
  color: var(--text-secondary);
}

.description {
  margin-top: 4px;
  font-size: 12px;
  color: var(--text-secondary);
  line-height: 1.45;
}

.status {
  font-size: 11px;
  padding: 2px 8px;
  border-radius: 999px;
  background: var(--border);
}

.status[data-status='APPLIED'] {
  background: var(--status-ok-bg);
  color: var(--status-ok);
}

.status[data-status='SENT'] {
  background: var(--status-info-bg);
  color: var(--status-info);
}

.status[data-status='EFFECT_CONFIRMED'] {
  background: var(--status-ok-bg);
  color: var(--status-ok);
}

.status[data-status='PENDING'] {
  background: var(--status-warn-bg);
  color: var(--status-warn);
}

.status[data-status='TIMEOUT'] {
  background: var(--status-danger-bg);
  color: var(--status-danger);
}

.status[data-status='SKIPPED'] {
  background: var(--status-danger-bg);
  color: var(--status-danger);
}

.status[data-status='CANCELLED'],
.status[data-status='EXPIRED'],
.status[data-status='RELEASED'] {
  background: var(--border);
  color: var(--text-secondary);
}
</style>
