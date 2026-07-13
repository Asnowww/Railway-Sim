<script setup lang="ts">
import { ref, watch } from 'vue'
import { confirmState, settleConfirm } from '../confirm'

const reason = ref('')
const operator = ref('dispatcher-01')

watch(
  () => confirmState.visible,
  (visible) => {
    if (visible) reason.value = ''
  }
)

function cancel(): void {
  settleConfirm({ ok: false, reason: '', operator: operator.value })
}

function confirm(): void {
  if (confirmState.request?.requireReason && !reason.value.trim()) return
  settleConfirm({ ok: true, reason: reason.value.trim(), operator: operator.value.trim() || 'dispatcher-01' })
}
</script>

<template>
  <Teleport to="body">
    <div v-if="confirmState.visible && confirmState.request" class="confirm-mask" @click.self="cancel">
      <div class="confirm-dialog" role="alertdialog" aria-modal="true" :aria-label="confirmState.request.title">
        <header :class="{ danger: confirmState.request.danger }">
          <h3>{{ confirmState.request.title }}</h3>
        </header>
        <div class="body">
          <p v-for="(line, index) in confirmState.request.lines" :key="index">{{ line }}</p>
          <label class="field">
            <span>操作员</span>
            <input v-model="operator" type="text" />
          </label>
          <label v-if="confirmState.request.requireReason" class="field">
            <span>操作原因（必填，写入审计日志）</span>
            <input v-model="reason" type="text" placeholder="例如：联调演示 / 故障演练" />
          </label>
        </div>
        <footer>
          <button type="button" class="btn" @click="cancel">取消</button>
          <button
            type="button"
            :class="['btn', confirmState.request.danger ? 'danger' : 'primary']"
            :disabled="Boolean(confirmState.request.requireReason) && !reason.trim()"
            @click="confirm"
          >
            {{ confirmState.request.confirmText ?? '确认执行' }}
          </button>
        </footer>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.confirm-mask {
  position: fixed;
  inset: 0;
  background: rgba(5, 8, 12, 0.65);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
}

.confirm-dialog {
  width: min(440px, calc(100vw - 40px));
  background: var(--bg-panel-raised);
  border: 1px solid var(--border-strong);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-overlay);
  overflow: hidden;
}

header {
  padding: 12px 16px;
  border-bottom: 1px solid var(--border);
}

header.danger h3 {
  color: var(--status-danger);
}

.body {
  padding: 14px 16px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.body p {
  margin: 0;
  color: var(--text-secondary);
  font-size: var(--fs-sm);
}

footer {
  display: flex;
  justify-content: flex-end;
  gap: var(--gap-sm);
  padding: 12px 16px;
  border-top: 1px solid var(--border);
}
</style>
