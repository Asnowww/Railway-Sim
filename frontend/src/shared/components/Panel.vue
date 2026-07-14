<script setup lang="ts">
const props = withDefaults(
  defineProps<{
    title?: string
    subtitle?: string
    /** 数据过期时叠加冻结遮罩并显示最后更新时间 */
    stale?: boolean
    staleSince?: string
    /** 面板主体是否去内边距（放表格/图时） */
    flush?: boolean
  }>(),
  { stale: false, flush: false }
)
</script>

<template>
  <section class="panel">
    <header v-if="props.title || $slots.actions" class="panel-header">
      <div class="titles">
        <h3 v-if="props.title">{{ props.title }}</h3>
        <p v-if="props.subtitle" class="subtitle">{{ props.subtitle }}</p>
      </div>
      <div v-if="$slots.actions" class="actions">
        <slot name="actions" />
      </div>
    </header>
    <div :class="['panel-body', { flush: props.flush }]">
      <slot />
      <div v-if="props.stale" class="stale-overlay" role="status">
        <span class="stale-tag">数据已冻结</span>
        <span v-if="props.staleSince" class="stale-time num">最后更新 {{ props.staleSince }}</span>
      </div>
    </div>
  </section>
</template>

<style scoped>
.panel {
  display: flex;
  flex-direction: column;
  min-height: 0;
  background: var(--bg-panel);
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-panel);
  overflow: hidden;
}

.panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--gap-md);
  padding: 10px 14px;
  border-bottom: 1px solid var(--border);
  flex: none;
}

.titles h3 {
  font-size: var(--fs-md);
}

.subtitle {
  margin: 2px 0 0;
  font-size: var(--fs-xs);
  color: var(--text-muted);
}

.actions {
  display: flex;
  align-items: center;
  gap: var(--gap-sm);
}

.panel-body {
  position: relative;
  flex: 1;
  min-height: 0;
  padding: 12px 14px;
  display: flex;
  flex-direction: column;
  gap: var(--gap-md);
}

.panel-body.flush {
  padding: 0;
}

.stale-overlay {
  position: absolute;
  inset: 0;
  background: rgba(13, 17, 23, 0.55);
  backdrop-filter: grayscale(0.8);
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 6px;
  z-index: 5;
}

.stale-tag {
  padding: 3px 12px;
  border-radius: 999px;
  background: var(--status-stale-bg);
  border: 1px solid var(--status-stale);
  color: var(--status-stale);
  font-size: var(--fs-sm);
  font-weight: 700;
}

.stale-time {
  font-size: var(--fs-xs);
  color: var(--text-secondary);
}
</style>
