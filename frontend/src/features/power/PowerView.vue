<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { storeToRefs } from 'pinia'
import * as echarts from 'echarts'
import { useSimulationStore } from '../../stores/simulation'
import { useConnectionStore } from '../../stores/connection'
import { powerApi } from '../../api/power'
import { newTraceId } from '../../shared/api/client'
import Panel from '../../shared/components/Panel.vue'
import StatusBadge from '../../shared/components/StatusBadge.vue'
import { requestConfirm } from '../../shared/confirm'
import { toastError, toastOk } from '../../shared/toast'
import {
  breakerStatusLabel,
  dataQualityLabel,
  dataQualityTone,
  healthTone,
  lockoutStateLabels,
  maintenancePowerLabels,
  powerSectionStatusLabel,
  powerStatusTone,
  protectionStateLabels,
  serviceHealthLabel,
  strayCurrentRiskLabel,
  strayRiskTone,
  supplyModeLabel,
  voltageComparisonLabel
} from '../../shared/labels'
import type {
  IsolatorState,
  PowerEnergyResponse,
  PowerExternalHealth,
  PowerMaintenanceLock,
  StrayCurrentRisk,
  SubstationState
} from '../../types/power'

const simulation = useSimulationStore()
const connection = useConnectionStore()
const { powerSections } = storeToRefs(simulation)

const selectedSectionId = ref('')
watch(
  powerSections,
  (sections) => {
    if (!selectedSectionId.value && sections.length > 0) selectedSectionId.value = sections[0]!.id
  },
  { immediate: true }
)

const selectedSection = computed(() => powerSections.value.find((section) => section.id === selectedSectionId.value) ?? null)

/* ---------- REST 明细 ---------- */

const substations = ref<SubstationState[]>([])
const isolators = ref<IsolatorState[]>([])
const strayCurrent = ref<StrayCurrentRisk[]>([])
const maintenanceLocks = ref<PowerMaintenanceLock[]>([])
const externalHealth = ref<PowerExternalHealth | null>(null)
const energy = ref<PowerEnergyResponse | null>(null)
let pollTimer = 0

async function loadDetails(): Promise<void> {
  const results = await Promise.allSettled([
    powerApi.substations(),
    powerApi.isolators(),
    powerApi.strayCurrent(),
    powerApi.maintenanceLocks(),
    powerApi.externalHealth(),
    powerApi.energy()
  ])
  if (results[0].status === 'fulfilled') substations.value = results[0].value
  if (results[1].status === 'fulfilled') isolators.value = results[1].value
  if (results[2].status === 'fulfilled') strayCurrent.value = results[2].value
  if (results[3].status === 'fulfilled') maintenanceLocks.value = results[3].value
  if (results[4].status === 'fulfilled') externalHealth.value = results[4].value
  if (results[5].status === 'fulfilled') energy.value = results[5].value
}

onMounted(() => {
  void loadDetails()
  pollTimer = window.setInterval(() => void loadDetails(), 10_000)
})

onBeforeUnmount(() => {
  window.clearInterval(pollTimer)
  chart?.dispose()
  chart = null
})

/* ---------- 能耗图表 ---------- */

const chartRef = ref<HTMLDivElement | null>(null)
let chart: echarts.ECharts | null = null

function renderChart(): void {
  if (!chartRef.value) return
  if (!chart) chart = echarts.init(chartRef.value)
  const sections = powerSections.value
  chart.setOption({
    tooltip: { trigger: 'axis' },
    legend: { data: ['牵引负荷', '再生功率', '未吸收再生'], textStyle: { color: '#9aa7ba' } },
    grid: { left: 56, right: 20, top: 36, bottom: 28 },
    xAxis: {
      type: 'category',
      data: sections.map((section) => section.id),
      axisLabel: { color: '#9aa7ba' },
      axisLine: { lineStyle: { color: '#3a475f' } }
    },
    yAxis: {
      type: 'value',
      name: 'kW',
      nameTextStyle: { color: '#9aa7ba' },
      axisLabel: { color: '#9aa7ba' },
      splitLine: { lineStyle: { color: '#283246' } }
    },
    series: [
      { name: '牵引负荷', type: 'bar', data: sections.map((s) => Math.round(s.loadWatts / 1000)), itemStyle: { color: '#4c8dff' } },
      { name: '再生功率', type: 'bar', data: sections.map((s) => Math.round(s.regenPowerWatts / 1000)), itemStyle: { color: '#34d17b' } },
      {
        name: '未吸收再生',
        type: 'bar',
        data: sections.map((s) => Math.round(s.unabsorbedRegenPowerWatts / 1000)),
        itemStyle: { color: '#f5b83d' }
      }
    ]
  })
}

watch(powerSections, () => renderChart(), { deep: false })
onMounted(() => setTimeout(renderChart, 0))

/* ---------- 危险操作 ---------- */

const POWER_FAULT_TYPES = [
  { value: 'UNDERVOLTAGE', label: '欠压' },
  { value: 'OVERCURRENT', label: '过流' },
  { value: 'DEENERGIZED', label: '失电' },
  { value: 'BREAKER_TRIP', label: '断路器跳闸' },
  { value: 'MAINTENANCE_LOCK', label: '检修闭锁' },
  { value: 'ISOLATED', label: '隔离' }
]

const faultForm = ref({ sectionId: '', faultType: POWER_FAULT_TYPES[0]!.value })
const operationForm = ref({ targetType: 'ISOLATOR', targetId: '', desiredState: 'OPEN' })
const busy = ref(false)

watch(
  powerSections,
  (sections) => {
    if (!faultForm.value.sectionId && sections.length > 0) faultForm.value.sectionId = sections[0]!.id
  },
  { immediate: true }
)

async function injectPowerFault(clear: boolean): Promise<void> {
  if (!faultForm.value.sectionId || busy.value) return
  const meta = POWER_FAULT_TYPES.find((item) => item.value === faultForm.value.faultType)
  const confirm = await requestConfirm({
    title: clear ? '清除供电故障' : '注入供电故障',
    lines: [
      `目标分区：${faultForm.value.sectionId}`,
      ...(clear ? [] : [`故障类型：${meta?.label ?? faultForm.value.faultType}`, '注入将立即刷新分区状态并可能触发列车降级。'])
    ],
    danger: !clear,
    requireReason: true
  })
  if (!confirm.ok) return
  busy.value = true
  const traceId = newTraceId(clear ? 'pwrclear' : 'pwrfault')
  try {
    const body = { faultType: faultForm.value.faultType, operator: confirm.operator, reason: confirm.reason, traceId }
    if (clear) await powerApi.clearFault(faultForm.value.sectionId, body)
    else await powerApi.injectFault(faultForm.value.sectionId, body)
    toastOk(clear ? '供电故障已清除' : '供电故障已注入', faultForm.value.sectionId, traceId)
    await loadDetails()
  } catch (error) {
    toastError(clear ? '清除失败' : '注入失败', error)
  } finally {
    busy.value = false
  }
}

const OPERATION_TARGETS = [
  { value: 'ISOLATOR', label: '隔离开关' },
  { value: 'BREAKER', label: '断路器' },
  { value: 'SUBSTATION_DEVICE', label: '变电所设备' },
  { value: 'DRAINAGE_CABINET', label: '排流柜' }
]
const OPERATION_STATES = ['OPEN', 'CLOSED', 'ON', 'OFF']

async function submitOperation(): Promise<void> {
  if (!operationForm.value.targetId || busy.value) return
  const targetMeta = OPERATION_TARGETS.find((item) => item.value === operationForm.value.targetType)
  const confirm = await requestConfirm({
    title: '供电设备操作',
    lines: [
      `目标：${targetMeta?.label ?? operationForm.value.targetType} ${operationForm.value.targetId}`,
      `目标状态：${operationForm.value.desiredState}`,
      '该操作等价于现场倒闸操作，将立即作用于供电仿真。'
    ],
    danger: true,
    requireReason: true,
    confirmText: '执行操作'
  })
  if (!confirm.ok) return
  busy.value = true
  const traceId = newTraceId('pwrop')
  try {
    await powerApi.operate({
      targetType: operationForm.value.targetType,
      targetId: operationForm.value.targetId,
      desiredState: operationForm.value.desiredState,
      operator: confirm.operator,
      reason: confirm.reason,
      traceId
    })
    toastOk('供电操作已执行', `${operationForm.value.targetId} → ${operationForm.value.desiredState}`, traceId)
    await loadDetails()
  } catch (error) {
    toastError('供电操作失败', error)
  } finally {
    busy.value = false
  }
}

function kw(watts?: number): string {
  if (watts === undefined || watts === null || Number.isNaN(watts)) return '—'
  return `${Math.round(watts / 1000)} kW`
}

const stale = computed(() => !connection.dataTrusted)
</script>

<template>
  <div class="power-view">
    <div v-if="externalHealth" class="external-banner" :class="healthTone(externalHealth.heartbeatStatus)">
      <StatusBadge
        :tone="healthTone(externalHealth.heartbeatStatus)"
        :label="`外部供电仿真（9200）：${serviceHealthLabel(externalHealth.heartbeatStatus)}`"
      />
      <span class="small muted">
        模式 {{ externalHealth.mode ?? '—' }} · 时延 {{ externalHealth.latencyMillis ?? '—' }}ms · 数据质量
        {{ dataQualityLabel(externalHealth.dataQuality) }}
      </span>
      <span v-if="externalHealth.heartbeatStatus === 'FALLBACK'" class="small fallback-note">
        外部供电失联——以下分区电压为中央镜像回退值，不作为权威数据
      </span>
    </div>

    <Panel title="供电分区" :subtitle="`${powerSections.length} 个分区`" flush :stale="stale" :stale-since="connection.lastSnapshotClock">
      <div class="section-cards scroll-y">
        <button
          v-for="section in powerSections"
          :key="section.id"
          type="button"
          :class="['section-card', { active: section.id === selectedSectionId }]"
          @click="selectedSectionId = section.id"
        >
          <header>
            <span class="mono id">{{ section.id }}</span>
            <StatusBadge :tone="powerStatusTone(section.status)" :label="powerSectionStatusLabel(section.status)" :blink="section.status === 'LOST' || section.status === 'FAULT'" />
          </header>
          <div class="metrics">
            <span class="num volt">{{ Math.round(section.voltage) }} V</span>
            <span class="num">{{ Math.round(section.current) }} A</span>
            <span class="num">{{ kw(section.loadWatts) }}</span>
          </div>
          <footer>
            <span class="small muted">{{ supplyModeLabel(section.supplyMode) }}</span>
            <StatusBadge
              v-if="section.strayCurrentRiskLevel && section.strayCurrentRiskLevel !== 'NORMAL'"
              :tone="strayRiskTone(section.strayCurrentRiskLevel)"
              :label="`杂散 ${strayCurrentRiskLabel(section.strayCurrentRiskLevel)}`"
            />
          </footer>
        </button>
        <p v-if="powerSections.length === 0" class="empty">暂无供电分区数据</p>
      </div>
    </Panel>

    <div class="mid-grid">
      <Panel v-if="selectedSection" :title="`分区详情 · ${selectedSection.name || selectedSection.id}`" :subtitle="`更新 ${selectedSection.updatedAt ?? '—'}`">
        <div class="kv-grid">
          <div><dt>状态</dt><dd><StatusBadge :tone="powerStatusTone(selectedSection.status)" :label="powerSectionStatusLabel(selectedSection.status)" /></dd></div>
          <div><dt>供电方式</dt><dd>{{ supplyModeLabel(selectedSection.supplyMode) }}</dd></div>
          <div><dt>范围</dt><dd class="num">{{ Math.round(selectedSection.startMeters) }}–{{ Math.round(selectedSection.endMeters) }} m</dd></div>
          <div><dt>变电所 / 馈线</dt><dd class="mono">{{ selectedSection.substationId }} / {{ selectedSection.feederId }}</dd></div>
          <div><dt>中央电压</dt><dd class="num">{{ Math.round(selectedSection.voltage) }} V</dd></div>
          <div><dt>外部电压（权威）</dt><dd class="num">{{ Math.round(selectedSection.externalVoltage) }} V</dd></div>
          <div>
            <dt>电压比对</dt>
            <dd>
              <StatusBadge
                :tone="selectedSection.voltageComparisonStatus === 'MATCHED' ? 'ok' : selectedSection.voltageComparisonStatus === 'DIVERGED' ? 'danger' : selectedSection.voltageComparisonStatus === 'DEVIATED' ? 'warn' : 'neutral'"
                :label="`${voltageComparisonLabel(selectedSection.voltageComparisonStatus)}（${selectedSection.voltageDeviation?.toFixed(1) ?? '—'}V / ${selectedSection.voltageDeviationPercent?.toFixed(2) ?? '—'}%）`"
              />
            </dd>
          </div>
          <div><dt>电流</dt><dd class="num">{{ Math.round(selectedSection.current) }} A（外部 {{ Math.round(selectedSection.externalCurrent) }} A）</dd></div>
          <div><dt>牵引负荷</dt><dd class="num">{{ kw(selectedSection.loadWatts) }}（外部 {{ kw(selectedSection.externalLoadWatts) }}）</dd></div>
          <div><dt>可用功率</dt><dd class="num">{{ kw(selectedSection.availablePowerWatts) }}</dd></div>
          <div><dt>再生 / 已吸收 / 未吸收</dt><dd class="num">{{ kw(selectedSection.regenPowerWatts) }} / {{ kw(selectedSection.absorbedRegenPowerWatts) }} / {{ kw(selectedSection.unabsorbedRegenPowerWatts) }}</dd></div>
          <div><dt>隔离开关</dt><dd>{{ selectedSection.isolatorStatus }}</dd></div>
          <div><dt>断路器</dt><dd>{{ breakerStatusLabel(selectedSection.breakerStatus) }}</dd></div>
          <div><dt>保护</dt><dd>{{ protectionStateLabels[selectedSection.protectionState] ?? selectedSection.protectionState }}</dd></div>
          <div><dt>检修</dt><dd>{{ maintenancePowerLabels[selectedSection.maintenanceState] ?? selectedSection.maintenanceState }} · {{ lockoutStateLabels[selectedSection.lockoutState] ?? selectedSection.lockoutState }}</dd></div>
          <div><dt>变电所可用性</dt><dd>{{ selectedSection.substationAvailability }}</dd></div>
          <div>
            <dt>杂散电流</dt>
            <dd>
              <StatusBadge :tone="strayRiskTone(selectedSection.strayCurrentRiskLevel)" :label="strayCurrentRiskLabel(selectedSection.strayCurrentRiskLevel)" />
              <span class="small muted">{{ selectedSection.strayCurrentRiskReason }}</span>
            </dd>
          </div>
          <div><dt>外部数据质量</dt><dd><StatusBadge :tone="dataQualityTone(selectedSection.externalDataQuality)" :label="dataQualityLabel(selectedSection.externalDataQuality)" /></dd></div>
          <div><dt>影响列车</dt><dd class="mono">{{ selectedSection.affectedTrainIds?.join(', ') || '—' }}</dd></div>
          <div v-if="selectedSection.externalSupportReason"><dt>外部说明</dt><dd class="small">{{ selectedSection.externalSupportReason }}</dd></div>
        </div>
      </Panel>

      <Panel title="分区负荷与再生（实时）">
        <div ref="chartRef" class="energy-chart" aria-label="分区负荷与再生功率柱状图"></div>
        <div v-if="energy" class="energy-summary">
          <span>总负荷 <strong class="num">{{ kw(energy.totalLoadWatts) }}</strong></span>
          <span>总再生 <strong class="num">{{ kw(energy.totalRegenPowerWatts) }}</strong></span>
          <span>已吸收 <strong class="num">{{ kw(energy.totalAbsorbedRegenPowerWatts) }}</strong></span>
          <span>未吸收 <strong class="num">{{ kw(energy.totalUnabsorbedRegenPowerWatts) }}</strong></span>
        </div>
      </Panel>
    </div>

    <div class="lists-grid">
      <Panel title="变电所与设备" flush>
        <div class="scroll-y table-h">
          <table class="data">
            <thead>
              <tr><th>变电所</th><th>供电方式</th><th>可用性</th><th>设备</th></tr>
            </thead>
            <tbody>
              <tr v-if="substations.length === 0"><td colspan="4" class="empty">暂无变电所数据</td></tr>
              <tr v-for="substation in substations" :key="substation.id">
                <td class="mono">{{ substation.id }} <span class="small muted">{{ substation.name }}</span></td>
                <td>{{ supplyModeLabel(substation.supplyMode) }}</td>
                <td>{{ substation.availability ?? '—' }}</td>
                <td>
                  <div class="device-chips">
                    <span
                      v-for="device in substation.devices ?? []"
                      :key="device.id"
                      :class="['device-chip', { off: device.available === false }]"
                      :title="`${device.deviceType ?? ''} · 状态 ${device.state ?? '—'} · 影响 ${(device.affectsSectionIds ?? []).join(',') || '—'}`"
                    >
                      {{ device.id }}·{{ device.state ?? '—' }}
                    </span>
                  </div>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </Panel>

      <Panel title="隔离开关" flush>
        <div class="scroll-y table-h">
          <table class="data">
            <thead><tr><th>开关</th><th>接触轨分区</th><th>状态</th><th>数据质量</th></tr></thead>
            <tbody>
              <tr v-if="isolators.length === 0"><td colspan="4" class="empty">暂无隔离开关数据</td></tr>
              <tr v-for="isolator in isolators" :key="isolator.id">
                <td class="mono">{{ isolator.id }}</td>
                <td class="mono">{{ isolator.thirdRailSectionId ?? '—' }}</td>
                <td>
                  <StatusBadge :tone="isolator.state === 'CLOSED' ? 'ok' : 'warn'" :label="isolator.state === 'CLOSED' ? '合闸' : isolator.state === 'OPEN' ? '分闸' : String(isolator.state ?? '—')" />
                </td>
                <td><StatusBadge :tone="dataQualityTone(isolator.dataQuality)" :label="dataQualityLabel(isolator.dataQuality)" /></td>
              </tr>
            </tbody>
          </table>
        </div>
      </Panel>

      <Panel title="杂散电流监测" flush>
        <div class="scroll-y table-h">
          <table class="data">
            <thead><tr><th>监测点</th><th>分区</th><th>极化电位</th><th>风险</th><th>建议</th></tr></thead>
            <tbody>
              <tr v-if="strayCurrent.length === 0"><td colspan="5" class="empty">暂无杂散电流数据</td></tr>
              <tr v-for="monitor in strayCurrent" :key="monitor.id ?? monitor.sectionId">
                <td class="mono">{{ monitor.id ?? '—' }}</td>
                <td class="mono">{{ monitor.sectionId ?? '—' }}</td>
                <td class="num">{{ monitor.polarizedPotentialVolts?.toFixed(2) ?? '—' }} V</td>
                <td>
                  <StatusBadge
                    :tone="strayRiskTone(monitor.riskLevel)"
                    :label="strayCurrentRiskLabel(monitor.riskLevel)"
                    :blink="monitor.riskLevel === 'CRITICAL'"
                  />
                </td>
                <td class="small">{{ monitor.suggestedAction ?? monitor.riskReason ?? '—' }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </Panel>

      <Panel title="检修锁定" flush>
        <div class="scroll-y table-h">
          <table class="data">
            <thead><tr><th>对象</th><th>检修状态</th><th>锁定</th><th>断路器</th></tr></thead>
            <tbody>
              <tr v-if="maintenanceLocks.length === 0"><td colspan="4" class="empty">无检修锁定</td></tr>
              <tr v-for="lock in maintenanceLocks" :key="lock.sectionId">
                <td class="mono">{{ lock.sectionId }} <span class="small muted">{{ lock.sectionName }}</span></td>
                <td>{{ maintenancePowerLabels[lock.maintenanceState ?? ''] ?? lock.maintenanceState ?? '—' }}</td>
                <td>{{ lockoutStateLabels[lock.lockoutState ?? ''] ?? lock.lockoutState ?? '—' }}</td>
                <td>{{ breakerStatusLabel(lock.breakerStatus) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </Panel>
    </div>

    <div class="ops-grid">
      <Panel title="供电故障注入 / 清除" subtitle="危险操作：二次确认 + 审计。清除不会绕过检修/锁定状态">
        <div class="op-form">
          <label class="field">
            <span>目标分区</span>
            <select v-model="faultForm.sectionId">
              <option v-for="section in powerSections" :key="section.id" :value="section.id">{{ section.id }} {{ section.name }}</option>
            </select>
          </label>
          <label class="field">
            <span>故障类型</span>
            <select v-model="faultForm.faultType">
              <option v-for="type in POWER_FAULT_TYPES" :key="type.value" :value="type.value">{{ type.label }}（{{ type.value }}）</option>
            </select>
          </label>
          <div class="op-actions">
            <button type="button" class="btn danger" :disabled="busy || !faultForm.sectionId" @click="injectPowerFault(false)">注入</button>
            <button type="button" class="btn" :disabled="busy || !faultForm.sectionId" @click="injectPowerFault(true)">清除</button>
          </div>
        </div>
      </Panel>

      <Panel title="设备级操作" subtitle="隔离开关 / 断路器 / 变电所设备 / 排流柜（等价倒闸操作）">
        <div class="op-form">
          <label class="field">
            <span>设备类型</span>
            <select v-model="operationForm.targetType">
              <option v-for="target in OPERATION_TARGETS" :key="target.value" :value="target.value">{{ target.label }}</option>
            </select>
          </label>
          <label class="field">
            <span>设备 ID</span>
            <input v-model="operationForm.targetId" type="text" placeholder="例如 ISO-P01-A" />
          </label>
          <label class="field">
            <span>目标状态</span>
            <select v-model="operationForm.desiredState">
              <option v-for="state in OPERATION_STATES" :key="state" :value="state">{{ state }}</option>
            </select>
          </label>
          <button type="button" class="btn danger" :disabled="busy || !operationForm.targetId" @click="submitOperation">执行操作</button>
        </div>
      </Panel>
    </div>
  </div>
</template>

<style scoped>
.power-view {
  display: flex;
  flex-direction: column;
  gap: var(--gap-lg);
}

.external-banner {
  display: flex;
  align-items: center;
  gap: var(--gap-md);
  flex-wrap: wrap;
  background: var(--bg-panel);
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  padding: 8px 14px;
}

.fallback-note { color: var(--status-fault); font-weight: 700; }

.section-cards {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: var(--gap-md);
  padding: 12px 14px;
  max-height: 240px;
}

.section-card {
  background: var(--bg-panel-raised);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  padding: 10px 12px;
  color: var(--text-primary);
  cursor: pointer;
  display: flex;
  flex-direction: column;
  gap: 6px;
  text-align: left;
}

.section-card:hover { border-color: var(--accent); }
.section-card.active { border-color: var(--accent); background: var(--accent-muted); }

.section-card header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 6px;
}

.section-card .id { font-weight: 700; }

.section-card .metrics {
  display: flex;
  gap: 12px;
  font-size: var(--fs-sm);
}

.section-card .volt { font-weight: 700; color: var(--accent); }

.section-card footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 6px;
}

.mid-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.2fr) minmax(0, 1fr);
  gap: var(--gap-lg);
  align-items: start;
}

@media (max-width: 1100px) {
  .mid-grid { grid-template-columns: 1fr; }
}

.kv-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
  gap: var(--gap-md);
}

.kv-grid div { display: flex; flex-direction: column; gap: 2px; }
.kv-grid dt { font-size: var(--fs-xs); color: var(--text-muted); }
.kv-grid dd { margin: 0; font-weight: 600; display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }

.energy-chart { width: 100%; height: 260px; }

.energy-summary {
  display: flex;
  gap: var(--gap-lg);
  flex-wrap: wrap;
  font-size: var(--fs-sm);
  color: var(--text-secondary);
}

.lists-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(340px, 1fr));
  gap: var(--gap-lg);
  align-items: start;
}

.table-h { max-height: 220px; }

.device-chips { display: flex; flex-wrap: wrap; gap: 4px; }

.device-chip {
  font-size: 10px;
  font-family: var(--font-mono);
  padding: 1px 6px;
  border-radius: 999px;
  background: var(--status-ok-bg);
  border: 1px solid var(--status-ok);
  color: var(--status-ok);
}

.device-chip.off {
  background: var(--status-danger-bg);
  border-color: var(--status-danger);
  color: var(--status-danger);
}

.ops-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--gap-lg);
}

@media (max-width: 1000px) {
  .ops-grid { grid-template-columns: 1fr; }
}

.op-form {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
  gap: var(--gap-md);
  align-items: end;
}

.op-actions { display: flex; gap: var(--gap-sm); }

.small { font-size: var(--fs-xs); }
.muted { color: var(--text-muted); }
.empty { text-align: center; color: var(--text-muted); padding: 12px; }
</style>
