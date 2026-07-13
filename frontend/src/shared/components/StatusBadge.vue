<script setup lang="ts">
import type { Tone } from '../labels'

const props = withDefaults(
  defineProps<{
    tone: Tone
    label: string
    /** 仅严重状态允许闪烁（CRITICAL 告警、FAULT 区段） */
    blink?: boolean
    /** 形状通道：dot 圆 / square 方 / triangle 三角，用于信号灯等双通道编码 */
    shape?: 'dot' | 'square' | 'triangle'
  }>(),
  { blink: false, shape: 'dot' }
)
</script>

<template>
  <span :class="['status-badge', `tone-${props.tone}`]">
    <span :class="['marker', props.shape, { blinking: props.blink }]" aria-hidden="true"></span>
    <span class="label">{{ props.label }}</span>
  </span>
</template>

<style scoped>
.status-badge {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 1px 8px 1px 6px;
  border-radius: 999px;
  font-size: var(--fs-xs);
  font-weight: 600;
  white-space: nowrap;
  border: 1px solid transparent;
}

.marker {
  width: 8px;
  height: 8px;
  flex: none;
  background: currentColor;
}
.marker.dot { border-radius: 50%; }
.marker.square { border-radius: 1px; }
.marker.triangle {
  background: transparent;
  width: 0;
  height: 0;
  border-left: 5px solid transparent;
  border-right: 5px solid transparent;
  border-bottom: 9px solid currentColor;
}

.label { color: var(--text-primary); font-weight: 500; }

.tone-ok      { color: var(--status-ok);      background: var(--status-ok-bg);      border-color: rgba(52, 209, 123, 0.3); }
.tone-warn    { color: var(--status-warn);    background: var(--status-warn-bg);    border-color: rgba(245, 184, 61, 0.3); }
.tone-danger  { color: var(--status-danger);  background: var(--status-danger-bg);  border-color: rgba(240, 86, 79, 0.35); }
.tone-fault   { color: var(--status-fault);   background: var(--status-fault-bg);   border-color: rgba(251, 139, 60, 0.35); }
.tone-info    { color: var(--status-info);    background: var(--status-info-bg);    border-color: rgba(88, 166, 255, 0.3); }
.tone-neutral { color: var(--status-neutral); background: var(--status-neutral-bg); border-color: rgba(102, 115, 138, 0.3); }
.tone-stale   { color: var(--status-stale);   background: var(--status-stale-bg);   border-color: rgba(139, 152, 201, 0.3); }
</style>
