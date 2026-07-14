<script setup lang="ts">
import { dismissToast, toasts } from '../toast'
</script>

<template>
  <Teleport to="body">
    <div class="toast-host" aria-live="polite">
      <div v-for="toast in toasts" :key="toast.id" :class="['toast', `tone-${toast.tone}`]">
        <div class="content">
          <strong>{{ toast.title }}</strong>
          <p v-if="toast.detail">{{ toast.detail }}</p>
          <small v-if="toast.traceId" class="mono">traceId: {{ toast.traceId }}</small>
        </div>
        <button type="button" class="close" aria-label="关闭" @click="dismissToast(toast.id)">×</button>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.toast-host {
  position: fixed;
  right: 16px;
  bottom: 16px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  z-index: 1100;
  max-width: min(420px, calc(100vw - 32px));
}

.toast {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 10px 12px;
  border-radius: var(--radius-md);
  background: var(--bg-panel-raised);
  border: 1px solid var(--border-strong);
  border-left-width: 4px;
  box-shadow: var(--shadow-overlay);
}

.tone-ok { border-left-color: var(--status-ok); }
.tone-danger { border-left-color: var(--status-danger); }
.tone-warn { border-left-color: var(--status-warn); }
.tone-info { border-left-color: var(--status-info); }

.content strong { font-size: var(--fs-sm); }
.content p { margin: 3px 0 0; font-size: var(--fs-xs); color: var(--text-secondary); }
.content small { display: block; margin-top: 3px; color: var(--text-muted); font-size: 10px; }

.close {
  margin-left: auto;
  background: none;
  border: none;
  color: var(--text-muted);
  font-size: 16px;
  cursor: pointer;
  line-height: 1;
}
.close:hover { color: var(--text-primary); }
</style>
