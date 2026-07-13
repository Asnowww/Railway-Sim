<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { storeToRefs } from 'pinia'
import { useSimulationStore } from '../../stores/simulation'
import { useConnectionStore } from '../../stores/connection'
import { useTopologyStore } from '../../stores/topology'
import { trainApi } from '../../api/trains'
import { vehicleApi } from '../../api/vehicle'
import { signalVehicleApi } from '../../api/signalVehicle'
import { newTraceId } from '../../shared/api/client'
import Panel from '../../shared/components/Panel.vue'
import StatusBadge from '../../shared/components/StatusBadge.vue'
import { requestConfirm } from '../../shared/confirm'
import { toastError, toastOk } from '../../shared/toast'
import {
  brakeStateLabel,
  currentCollectionLabel,
  dataQualityLabel,
  dataQualityTone,
  doorStateLabel,
  dynamicsStateLabel,
  maintenanceStateLabel,
  operationModeLabel,
  serviceHealthLabel,
  healthTone,
  tractionStateLabel,
  trainStatusLabel
} from '../../shared/labels'
import type { TrainEnergyResponse } from '../../types/power'
import type { SignalVehicleCommand, TrainFaultRecord, VehicleMaintenanceState, VehicleSignalStatus } from '../../types/vehicles'
import type { VehicleRuntimeStatusResponse } from '../../types/simulation'

const route = useRoute()
const simulation = useSimulationStore()
const connection = useConnectionStore()
const topology = useTopologyStore()
const { trains, authorities } = storeToRefs(simulation)

const selectedTrainId = ref<string>((route.query.train as string) ?? '')

watch(
  trains,
  (list) => {
    if (!selectedTrainId.value && list.length > 0) selectedTrainId.value = list[0]!.id
  },
  { immediate: true }
)

const selectedTrain = computed(() => trains.value.find((train) => train.id === selectedTrainId.value) ?? null)
const selectedAuthority = computed(() => authorities.value.find((item) => item.trainId === selectedTrainId.value) ?? null)

/* ---------- 选中列车的 REST 明细 ---------- */

const energy = ref<TrainEnergyResponse | null>(null)
const faults = ref<TrainFaultRecord[]>([])
const detailError = ref('')

async function loadTrainDetail(): Promise<void> {
  if (!selectedTrainId.value) return
  detailError.value = ''
  const [energyResult, faultsResult] = await Promise.allSettled([
    trainApi.energy(selectedTrainId.value),
    trainApi.faults(selectedTrainId.value)
  ])
  energy.value = energyResult.status === 'fulfilled' ? energyResult.value : null
  faults.value = faultsResult.status === 'fulfilled' ? faultsResult.value : []
  if (energyResult.status === 'rejected' && faultsResult.status === 'rejected') {
    detailError.value = '能耗/故障明细加载失败（后端未连接？）'
  }
}

watch(selectedTrainId, () => void loadTrainDetail(), { immediate: true })

/* ---------- 页面级列表：信号命令 / 车辆信号状态 / 维护 / 运行时 ---------- */

const signalCommands = ref<SignalVehicleCommand[]>([])
const signalStatuses = ref<VehicleSignalStatus[]>([])
const maintenance = ref<VehicleMaintenanceState[]>([])
const runtime = ref<VehicleRuntimeStatusResponse | null>(null)
let pollTimer = 0

async function loadFleetDetails(): Promise<void> {
  const [commandsResult, statusesResult, maintenanceResult, runtimeResult] = await Promise.allSettled([
    signalVehicleApi.commands(),
    signalVehicleApi.statuses(),
    vehicleApi.maintenanceStates(),
    vehicleApi.runtimeHealth()
  ])
  if (commandsResult.status === 'fulfilled') signalCommands.value = commandsResult.value
  if (statusesResult.status === 'fulfilled') signalStatuses.value = statusesResult.value
  if (maintenanceResult.status === 'fulfilled') maintenance.value = maintenanceResult.value
  if (runtimeResult.status === 'fulfilled') runtime.value = runtimeResult.value
}

onMounted(() => {
  void loadFleetDetails()
  pollTimer = window.setInterval(() => void loadFleetDetails(), 10_000)
})

onBeforeUnmount(() => window.clearInterval(pollTimer))

function matchesTrain(item: { trainId?: string; trainNo?: number }, trainId: string): boolean {
  if (item.trainId === trainId) return true
  const numeric = trainId.replace(/\D/g, '')
  return numeric !== '' && item.trainNo !== undefined && String(item.trainNo) === String(Number(numeric))
}

const selectedCommand = computed(
  () => signalCommands.value.find((item) => matchesTrain(item, selectedTrainId.value)) ?? null
)
const selectedSignalStatus = computed(
  () => signalStatuses.value.find((item) => matchesTrain(item, selectedTrainId.value)) ?? null
)
const selectedMaintenance = computed(
  () => maintenance.value.find((item) => item.trainId === selectedTrainId.value) ?? null
)

/* ---------- 故障注入 / 清除 ---------- */

const TRAIN_FAULT_TYPES = [
  { value: 'BRAKE_FAILURE', label: '制动失效' },
  { value: 'TRACTION_FAILURE', label: '牵引失效' },
  { value: 'DOOR_FAULT', label: '车门故障' },
  { value: 'CURRENT_COLLECTION_FAULT', label: '受流故障' }
]
const faultType = ref(TRAIN_FAULT_TYPES[0]!.value)
const customFaultType = ref('')
const faultBusy = ref(false)

async function injectTrainFault(): Promise<void> {
  const type = customFaultType.value.trim() || faultType.value
  if (!selectedTrainId.value || faultBusy.value) return
  const confirm = await requestConfirm({
    title: '注入车辆故障',
    lines: [`目标列车：${selectedTrainId.value}`, `故障类型：${type}`, '注入将立即影响门/牵引/制动/受流等车辆状态。'],
    danger: true,
    requireReason: true
  })
  if (!confirm.ok) return
  faultBusy.value = true
  const traceId = newTraceId('trainfault')
  try {
    await trainApi.injectFault(selectedTrainId.value, {
      faultType: type,
      operator: confirm.operator,
      reason: confirm.reason,
      traceId
    })
    toastOk('车辆故障已注入', `${selectedTrainId.value} · ${type}`, traceId)
    await loadTrainDetail()
  } catch (error) {
    toastError('故障注入失败', error)
  } finally {
    faultBusy.value = false
  }
}

async function clearTrainFault(): Promise<void> {
  if (!selectedTrainId.value || faultBusy.value) return
  const confirm = await requestConfirm({
    title: '清除车辆故障',
    lines: [`目标列车：${selectedTrainId.value}`],
    requireReason: true
  })
  if (!confirm.ok) return
  faultBusy.value = true
  const traceId = newTraceId('trainclear')
  try {
    await trainApi.clearFault(selectedTrainId.value, {
      operator: confirm.operator,
      reason: confirm.reason,
      traceId
    })
    toastOk('车辆故障已清除', selectedTrainId.value, traceId)
    await loadTrainDetail()
  } catch (error) {
    toastError('清除失败', error)
  } finally {
    faultBusy.value = false
  }
}

function kwh(value?: number): string {
  if (value === undefined || value === null || Number.isNaN(value)) return '—'
  return `${value.toFixed(2)} kWh`
}

const stale = computed(() => !connection.dataTrusted)
</script>

<template>
  <div class="vehicles">
    <div class="layout">
      <Panel title="列车列表" :subtitle="`${trains.length} 列在线`" flush :stale="stale" :stale-since="connection.lastSnapshotClock">
        <div class="scroll-y train-list">
          <p v-if="trains.length === 0" class="empty">暂无列车</p>
          <button
            v-for="train in trains"
            :key="train.id"
            type="button"
            :class="['train-row', { active: train.id === selectedTrainId }]"
            @click="selectedTrainId = train.id"
          >
            <span class="mono id">{{ train.id }}</span>
            <span class="num speed">{{ Math.round(train.speedMetersPerSecond * 3.6) }} km/h</span>
            <span class="mode">{{ operationModeLabel(train.operationMode) }}</span>
            <StatusBadge
              v-if="train.faultCode && train.faultCode !== 'OK'"
              tone="danger"
              :label="train.faultCode"
            />
            <StatusBadge v-else :tone="train.status === 'RUNNING' ? 'ok' : 'neutral'" :label="trainStatusLabel(train.status)" />
          </button>
        </div>
      </Panel>

      <div class="detail-col">
        <Panel v-if="!selectedTrain" title="列车详情">
          <p class="empty">选择左侧列车查看详情</p>
        </Panel>

        <template v-else>
          <Panel :title="`列车 ${selectedTrain.id}`" :subtitle="`车次 ${selectedTrain.serviceNo || '—'} · 数据质量 ${dataQualityLabel(selectedTrain.dataQuality)}`" :stale="stale" :stale-since="connection.lastSnapshotClock">
            <div class="kv-grid">
              <div><dt>运行状态</dt><dd>{{ trainStatusLabel(selectedTrain.status) }}</dd></div>
              <div><dt>驾驶模式</dt><dd>{{ operationModeLabel(selectedTrain.operationMode) }}</dd></div>
              <div><dt>动力学状态</dt><dd>{{ dynamicsStateLabel(selectedTrain.dynamicsState) }}</dd></div>
              <div><dt>速度</dt><dd class="num">{{ (selectedTrain.speedMetersPerSecond * 3.6).toFixed(1) }} km/h</dd></div>
              <div><dt>加速度</dt><dd class="num">{{ selectedTrain.accelerationMetersPerSecondSquared.toFixed(2) }} m/s²</dd></div>
              <div><dt>里程</dt><dd class="num">{{ selectedTrain.positionMeters.toFixed(1) }} m</dd></div>
              <div><dt>当前车站</dt><dd>{{ topology.stationName(selectedTrain.currentStationId) }}<template v-if="selectedTrain.dwellElapsedSeconds">（停站 {{ selectedTrain.dwellElapsedSeconds }}s）</template></dd></div>
              <div><dt>方向</dt><dd>{{ selectedTrain.direction }}</dd></div>
              <div><dt>满载率</dt><dd class="num">{{ Math.round(selectedTrain.loadRate * 100) }}%（{{ Math.round(selectedTrain.loadMassKg / 1000) }}t）</dd></div>
            </div>
          </Panel>

          <div class="sub-grid">
            <Panel title="牵引 · 制动 · 车门 · 受流">
              <div class="kv-grid">
                <div><dt>牵引</dt><dd>{{ tractionStateLabel(selectedTrain.tractionState) }}<StatusBadge v-if="!selectedTrain.tractionAvailable" tone="danger" label="不可用" /></dd></div>
                <div><dt>制动</dt><dd>{{ brakeStateLabel(selectedTrain.brakeState) }}<StatusBadge v-if="!selectedTrain.brakeAvailable" tone="danger" label="不可用" /></dd></div>
                <div><dt>车门</dt><dd>{{ doorStateLabel(selectedTrain.doorState) }}</dd></div>
                <div><dt>受流</dt><dd>{{ currentCollectionLabel(selectedTrain.currentCollectionStatus) }}</dd></div>
                <div><dt>牵引力</dt><dd class="num">{{ Math.round(selectedTrain.tractionForceNewtons / 1000) }} kN</dd></div>
                <div><dt>制动力</dt><dd class="num">{{ Math.round(selectedTrain.brakeForceNewtons / 1000) }} kN</dd></div>
                <div><dt>牵引功率</dt><dd class="num">{{ Math.round(selectedTrain.tractionPowerWatts / 1000) }} kW</dd></div>
                <div><dt>网侧电流</dt><dd class="num">{{ Math.round(selectedTrain.railCurrentAmps) }} A</dd></div>
                <div><dt>可用牵引/制动单元</dt><dd class="num">{{ selectedTrain.availableTractionCount }} / {{ selectedTrain.availableBrakeCount }}</dd></div>
              </div>
            </Panel>

            <Panel title="移动授权与约束">
              <div class="kv-grid">
                <div><dt>MA 余量</dt><dd class="num">{{ Math.round(selectedTrain.movementAuthorityDistanceMeters) }} m</dd></div>
                <div><dt>MA 终点</dt><dd class="num">{{ selectedAuthority ? Math.round(selectedAuthority.authorityEndMeters) + ' m' : '—' }}</dd></div>
                <div><dt>授权限速</dt><dd class="num">{{ Math.round(selectedTrain.speedLimitMetersPerSecond * 3.6) }} km/h</dd></div>
                <div><dt>MA 原因</dt><dd class="small">{{ selectedAuthority?.reason || '—' }}</dd></div>
                <div><dt>距前方车站</dt><dd class="num">{{ Math.round(selectedTrain.stationDistanceMeters) }} m</dd></div>
                <div><dt>制动停车距离</dt><dd class="num">{{ Math.round(selectedTrain.stoppingDistanceMeters) }} m</dd></div>
                <div><dt>约束原因</dt><dd class="small">{{ selectedTrain.dynamicsConstraintReason || '—' }}</dd></div>
                <div v-if="selectedCommand?.cabDisplay"><dt>DMI 模式</dt><dd>{{ selectedCommand.cabDisplay.currentDrivingMode ?? '—' }} / 最高 {{ selectedCommand.cabDisplay.maximumAvailableDrivingMode ?? '—' }}</dd></div>
                <div v-if="selectedCommand"><dt>信号命令</dt><dd>
                  <StatusBadge v-if="selectedCommand.emergencyBrakeCommand" tone="danger" label="紧急制动" blink />
                  <StatusBadge v-else-if="selectedCommand.serviceBrakeCommand" tone="warn" label="常用制动" />
                  <StatusBadge v-else-if="selectedCommand.tractionCutoff" tone="warn" label="牵引切除" />
                  <StatusBadge v-else tone="ok" label="正常授权" />
                </dd></div>
              </div>
            </Panel>

            <Panel title="能耗与再生">
              <div class="kv-grid">
                <div><dt>累计能耗</dt><dd class="num">{{ kwh(selectedTrain.energyConsumedKwh) }}</dd></div>
                <div><dt>再生回馈</dt><dd class="num">{{ kwh(selectedTrain.energyRegeneratedKwh) }}</dd></div>
                <div><dt>再生功率</dt><dd class="num">{{ Math.round(selectedTrain.regenPowerWatts / 1000) }} kW</dd></div>
                <div v-if="energy"><dt>净能耗（REST 口径）</dt><dd class="num">{{ kwh(energy.netEnergyKwh ?? energy.netKwh) }}</dd></div>
                <div v-if="energy"><dt>能耗数据质量</dt><dd><StatusBadge :tone="dataQualityTone(energy.dataQuality)" :label="dataQualityLabel(energy.dataQuality)" /></dd></div>
              </div>
            </Panel>

            <Panel title="维护与自检">
              <div class="kv-grid">
                <div><dt>维护状态</dt><dd>{{ maintenanceStateLabel(selectedMaintenance?.maintenanceState ?? 'NONE') }}</dd></div>
                <div><dt>自检</dt><dd>{{ selectedTrain.selfCheckStatus }}</dd></div>
                <div><dt>故障等级</dt><dd><StatusBadge :tone="selectedTrain.faultLevel >= 3 ? 'danger' : selectedTrain.faultLevel > 0 ? 'warn' : 'ok'" :label="`L${selectedTrain.faultLevel}`" /></dd></div>
                <div><dt>故障码</dt><dd class="mono">{{ selectedTrain.faultCode || 'OK' }}</dd></div>
                <div v-if="selectedSignalStatus?.driverConsoleState"><dt>司机台手柄</dt><dd class="small">{{ selectedSignalStatus.driverConsoleState.masterHandleState ?? '—' }} · 方向 {{ selectedSignalStatus.driverConsoleState.directionHandleState ?? '—' }}</dd></div>
                <div v-if="selectedSignalStatus?.driverConsoleState"><dt>门模式</dt><dd class="small">{{ selectedSignalStatus.driverConsoleState.doorModeSwitchState ?? '—' }}</dd></div>
              </div>
            </Panel>
          </div>

          <Panel title="故障注入 / 清除" subtitle="演示与联调用途；操作写入后端审计日志">
            <div class="fault-form">
              <label class="field">
                <span>故障类型</span>
                <select v-model="faultType">
                  <option v-for="type in TRAIN_FAULT_TYPES" :key="type.value" :value="type.value">{{ type.label }}（{{ type.value }}）</option>
                </select>
              </label>
              <label class="field">
                <span>自定义类型（可选，优先生效）</span>
                <input v-model="customFaultType" type="text" placeholder="自定义故障码" />
              </label>
              <div class="fault-actions">
                <button type="button" class="btn danger" :disabled="faultBusy" @click="injectTrainFault">注入</button>
                <button type="button" class="btn" :disabled="faultBusy" @click="clearTrainFault">清除</button>
              </div>
            </div>
            <p v-if="detailError" class="error-text">{{ detailError }}</p>
            <h4 class="sub-title">故障记录</h4>
            <div class="scroll-y fault-table">
              <table class="data">
                <thead>
                  <tr><th>类型</th><th>注入时间</th><th>清除时间</th><th>操作员</th><th>traceId</th></tr>
                </thead>
                <tbody>
                  <tr v-if="faults.length === 0"><td colspan="5" class="empty">无故障记录</td></tr>
                  <tr v-for="(record, index) in faults" :key="index">
                    <td class="mono">{{ record.faultType ?? record.faultCode ?? '—' }}</td>
                    <td class="num small">{{ record.injectedAt ?? '—' }}</td>
                    <td class="num small">{{ record.clearedAt ?? '未清除' }}</td>
                    <td>{{ record.operator ?? '—' }}</td>
                    <td class="mono small">{{ record.traceId ?? '—' }}</td>
                  </tr>
                </tbody>
              </table>
            </div>
          </Panel>
        </template>
      </div>
    </div>

    <Panel v-if="runtime" title="车辆运行时（9300）实例状态" flush>
      <div class="scroll-y runtime-table">
        <table class="data">
          <thead>
            <tr>
              <th>实例</th><th>生命周期</th><th>控制队列</th><th>仿真队列</th><th>最后 Tick</th><th>时延</th><th>数据质量</th><th>说明</th>
            </tr>
          </thead>
          <tbody>
            <tr>
              <td colspan="8" class="runtime-head">
                <StatusBadge :tone="healthTone(runtime.health.heartbeatStatus)" :label="`心跳 ${serviceHealthLabel(runtime.health.heartbeatStatus)}`" />
                <span class="small muted">模式 {{ runtime.health.mode ?? '—' }} · 实例 {{ runtime.health.instanceCount ?? runtime.instances.length }} · 物理 {{ runtime.health.physicsMode ?? '—' }}</span>
              </td>
            </tr>
            <tr v-if="runtime.instances.length === 0"><td colspan="8" class="empty">无实例</td></tr>
            <tr v-for="instance in runtime.instances" :key="instance.trainId">
              <td class="mono">{{ instance.trainId }}</td>
              <td>{{ instance.lifecycleState }}</td>
              <td>{{ instance.controlQueueStatus }}</td>
              <td>{{ instance.simulationQueueStatus }}</td>
              <td class="num">{{ instance.lastTick }}</td>
              <td class="num">{{ instance.latencyMillis }} ms</td>
              <td><StatusBadge :tone="dataQualityTone(instance.dataQuality)" :label="dataQualityLabel(instance.dataQuality)" /></td>
              <td class="small">{{ instance.reason || '—' }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </Panel>
  </div>
</template>

<style scoped>
.vehicles {
  display: flex;
  flex-direction: column;
  gap: var(--gap-lg);
}

.layout {
  display: grid;
  grid-template-columns: 280px minmax(0, 1fr);
  gap: var(--gap-lg);
  align-items: stretch; /* 左侧列表卡与右侧详情列底边对齐 */
}

@media (max-width: 1000px) {
  .layout { grid-template-columns: 1fr; }
}

.train-list {
  max-height: 70vh;
  display: flex;
  flex-direction: column;
}

.train-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 14px;
  background: none;
  border: none;
  border-bottom: 1px solid var(--border);
  color: var(--text-primary);
  cursor: pointer;
  text-align: left;
}

.train-row:hover { background: var(--bg-hover); }
.train-row.active { background: var(--accent-muted); }

.train-row .id { font-weight: 700; }
.train-row .speed { color: var(--text-secondary); font-size: var(--fs-xs); min-width: 60px; }
.train-row .mode { font-size: var(--fs-xs); color: var(--text-secondary); flex: 1; }

.detail-col {
  display: flex;
  flex-direction: column;
  gap: var(--gap-lg);
  min-width: 0;
}

/* 2×2 等高网格：同行卡片底边对齐，消除高低不齐 */
.sub-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--gap-lg);
  align-items: stretch;
}

@media (max-width: 900px) {
  .sub-grid {
    grid-template-columns: 1fr;
  }
}

.kv-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(150px, 1fr));
  gap: var(--gap-md);
}

.kv-grid div { display: flex; flex-direction: column; gap: 2px; }
.kv-grid dt { font-size: var(--fs-xs); color: var(--text-muted); }
.kv-grid dd { margin: 0; font-weight: 600; display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }

.fault-form {
  display: grid;
  grid-template-columns: 1fr 1fr auto;
  gap: var(--gap-md);
  align-items: end;
}

.fault-actions { display: flex; gap: var(--gap-sm); }
.fault-table { max-height: 180px; }
.runtime-table { max-height: 260px; }
.runtime-head { display: flex; align-items: center; gap: var(--gap-md); }

.sub-title { font-size: var(--fs-sm); color: var(--text-secondary); margin-top: var(--gap-md); }
.small { font-size: var(--fs-xs); }
.muted { color: var(--text-muted); }
.empty { text-align: center; color: var(--text-muted); padding: 12px; }
.error-text { color: var(--status-danger); font-size: var(--fs-xs); }
</style>
