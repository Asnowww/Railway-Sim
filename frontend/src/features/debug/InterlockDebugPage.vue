<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { storeToRefs } from 'pinia'
import { dispatchApi } from '../../api/dispatch'
import { trainApi } from '../../api/trains'
import { useSimulationStore } from '../../stores/simulation'
import Panel from '../../shared/components/Panel.vue'
import { requestConfirm } from '../../shared/confirm'
import { newTraceId } from '../../shared/api/client'
import { toastError, toastOk } from '../../shared/toast'
import type { DispatchRouteInfo } from '../../types/dispatch'

const simulation = useSimulationStore()
const { trains } = storeToRefs(simulation)

const routes = ref<DispatchRouteInfo[]>([])
const form = ref({ routeId: '', trainId: '' })
const busy = ref(false)
const lastResult = ref('')

onMounted(() => {
  void dispatchApi
    .routeList()
    .then((list) => (routes.value = list))
    .catch(() => undefined)
})

/** 联锁直连建路：绕过调度闭环，仅用于联锁调试。 */
async function establish(): Promise<void> {
  if (!form.value.routeId || !form.value.trainId || busy.value) return
  busy.value = true
  try {
    const result = await dispatchApi.establishRoute(form.value.routeId, form.value.trainId)
    lastResult.value = JSON.stringify(result)
    if (result.accepted) toastOk('联锁已接受建路', `${form.value.trainId} → ${form.value.routeId}`)
    else toastError('联锁拒绝', new Error(result.rejectReason ?? '未知原因'))
  } catch (error) {
    toastError('建路请求失败', error)
  } finally {
    busy.value = false
  }
}

/* ---- 列车生命周期（服务间兼容入口，联调用） ---- */

const lifecycleForm = ref({ action: 'ADD', trainNo: 3, linkId: 12, offsetMeters: 640, direction: 'DOWN' })

async function applyLifecycle(): Promise<void> {
  const confirm = await requestConfirm({
    title: '列车生命周期命令（联调）',
    lines: [
      `动作：${lifecycleForm.value.action}`,
      lifecycleForm.value.action === 'ADD'
        ? `车号 ${lifecycleForm.value.trainNo} · link ${lifecycleForm.value.linkId} · 偏移 ${lifecycleForm.value.offsetMeters}m · ${lifecycleForm.value.direction}`
        : '将影响现有列车实体。',
      '推荐生产链路是 9300 launch，本入口仅用于旧演示与语义联调。'
    ],
    danger: true,
    requireReason: true
  })
  if (!confirm.ok) return
  busy.value = true
  try {
    const body = {
      action: lifecycleForm.value.action,
      trains:
        lifecycleForm.value.action === 'CLEAR'
          ? undefined
          : [
              {
                trainNo: lifecycleForm.value.trainNo,
                linkId: lifecycleForm.value.linkId,
                offsetMeters: lifecycleForm.value.offsetMeters,
                direction: lifecycleForm.value.direction
              }
            ],
      operator: confirm.operator,
      reason: confirm.reason,
      traceId: newTraceId('lifecycle')
    }
    const result = await trainApi.applyLifecycle(body)
    toastOk('生命周期命令已执行', `当前列车 ${result.length} 列`)
  } catch (error) {
    toastError('生命周期命令失败', error)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="interlock-debug">
    <Panel title="联锁直连建路" subtitle="绕过调度闭环（不创建 DispatchCommand/预约），仅供联锁调试；生产请在调度台提交 REQUEST_ROUTE">
      <div class="form-row">
        <label class="field">
          <span>进路</span>
          <select v-model="form.routeId">
            <option value="" disabled>选择进路</option>
            <option v-for="route in routes" :key="route.routeId" :value="route.routeId">
              {{ route.routeId }} {{ route.name }}（{{ route.fromStation }}→{{ route.toStation }}）
            </option>
          </select>
        </label>
        <label class="field">
          <span>列车</span>
          <select v-model="form.trainId">
            <option value="" disabled>选择列车</option>
            <option v-for="train in trains" :key="train.id" :value="train.id">{{ train.id }}</option>
          </select>
        </label>
        <button type="button" class="btn primary" :disabled="busy || !form.routeId || !form.trainId" @click="establish">
          直接建路
        </button>
      </div>
      <p v-if="lastResult" class="mono result">{{ lastResult }}</p>
    </Panel>

    <Panel title="列车生命周期（0x01/0x02/0x04 兼容入口）" subtitle="POST /api/trains/lifecycle · 需 confirmToken 与审计">
      <div class="form-row">
        <label class="field">
          <span>动作</span>
          <select v-model="lifecycleForm.action">
            <option value="ADD">ADD 接入</option>
            <option value="DELETE">DELETE 退出</option>
            <option value="CLEAR">CLEAR 全部退出</option>
          </select>
        </label>
        <label class="field"><span>车号</span><input v-model.number="lifecycleForm.trainNo" type="number" min="1" /></label>
        <label class="field"><span>link ID</span><input v-model.number="lifecycleForm.linkId" type="number" min="0" /></label>
        <label class="field"><span>偏移 (m)</span><input v-model.number="lifecycleForm.offsetMeters" type="number" min="0" /></label>
        <label class="field">
          <span>方向</span>
          <select v-model="lifecycleForm.direction">
            <option value="UP">UP</option>
            <option value="DOWN">DOWN</option>
          </select>
        </label>
        <button type="button" class="btn danger" :disabled="busy" @click="applyLifecycle">执行</button>
      </div>
    </Panel>
  </div>
</template>

<style scoped>
.interlock-debug {
  display: flex;
  flex-direction: column;
  gap: var(--gap-lg);
  padding: var(--gap-lg);
}

.form-row {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
  gap: var(--gap-md);
  align-items: end;
}

.result {
  margin: 8px 0 0;
  font-size: var(--fs-xs);
  color: var(--text-secondary);
  word-break: break-all;
}
</style>
