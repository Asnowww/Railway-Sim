/**
 * 全站唯一的枚举 → 中文字典。
 * 任何组件不得自建 label 映射；缺项时回退显示原始枚举值（保持可诊断性）。
 */

export type LabelDict = Record<string, string>

function lookup(dict: LabelDict, value: string | null | undefined): string {
  if (value === null || value === undefined || value === '') return '—'
  return dict[value] ?? value
}

/* ---------------- 仿真 ---------------- */

export const simulationStatusLabels: LabelDict = {
  STOPPED: '已停止',
  RUNNING: '运行中',
  PAUSED: '已暂停'
}
export const simulationStatusLabel = (v?: string | null) => lookup(simulationStatusLabels, v)

/* ---------------- 轨道 / 信号 ---------------- */

export const occupancyLabels: LabelDict = {
  FREE: '空闲',
  RESERVED: '预留',
  OCCUPIED: '占用',
  FAULT: '故障',
  BLOCKED: '封锁'
}
export const occupancyLabel = (v?: string | null) => lookup(occupancyLabels, v)

export const signalAspectLabels: LabelDict = {
  GREEN: '绿灯',
  YELLOW: '黄灯',
  RED: '红灯'
}
export const signalAspectLabel = (v?: string | null) => lookup(signalAspectLabels, v)

export const switchPositionLabels: LabelDict = {
  NORMAL: '定位',
  REVERSE: '反位'
}
export const switchPositionLabel = (v?: string | null) => lookup(switchPositionLabels, v)

export const routeStatusLabels: LabelDict = {
  AVAILABLE: '可用',
  VALIDATING: '校验中',
  SETTING_SWITCHES: '排列道岔',
  LOCKED: '已锁闭',
  OCCUPIED: '占用中',
  RELEASING: '解锁中',
  RELEASED: '已释放',
  CONFLICTED: '冲突',
  REJECTED: '已拒绝',
  FAILED: '失败',
  CANCELLED: '已取消',
  EXPIRED_BY_RESET: '因复位失效',
  ESTABLISHED: '已建立'
}
export const routeStatusLabel = (v?: string | null) => lookup(routeStatusLabels, v)

/* ---------------- 调度 ---------------- */

export const dispatchCommandTypeLabels: LabelDict = {
  HOLD: '扣车',
  HOLD_TRAIN: '扣车',
  SPEED_LIMIT: '限速',
  TEMP_SPEED_LIMIT: '临时限速',
  SPEED_FACTOR: '速度比例',
  LIMIT_FACTOR: '限速比例',
  SPEED_BIAS: '速度偏置',
  HEADWAY_ADJUST: '间隔调整',
  EXTEND_DWELL: '延长停站',
  SHORTEN_DWELL: '缩短停站',
  REQUEST_ROUTE: '申请进路',
  REROUTE: '改路',
  DEPARTURE: '发车'
}
export const dispatchCommandTypeLabel = (v?: string | null) => lookup(dispatchCommandTypeLabels, v)

export const dispatchCommandStatusLabels: LabelDict = {
  PENDING: '待执行',
  SENT: '已下发',
  APPLIED: '已应用',
  EFFECT_CONFIRMED: '效果已确认',
  TIMEOUT: '确认超时',
  EXPIRED: '已过期',
  SKIPPED: '被安全校验跳过',
  CANCELLED: '已撤销'
}
export const dispatchCommandStatusLabel = (v?: string | null) => lookup(dispatchCommandStatusLabels, v)

export const disturbanceTypeLabels: LabelDict = {
  DWELL_EXTENDED: '停站超时',
  DEPARTURE_DELAY: '发车延误',
  HEADWAY_SHRINK: '间隔过小',
  HEADWAY_EXPAND: '间隔过大',
  CROWDING: '客流拥挤'
}
export const disturbanceTypeLabel = (v?: string | null) => lookup(disturbanceTypeLabels, v)

export const disturbanceStatusLabels: LabelDict = {
  OPEN: '开放',
  HANDLED: '已处置',
  RECOVERED: '已恢复'
}
export const disturbanceStatusLabel = (v?: string | null) => lookup(disturbanceStatusLabels, v)

export const regulationActionLabels: LabelDict = {
  CATCH_UP: '本车追赶',
  SLOW_DOWN: '本车放慢',
  NORMAL: '正常',
  OBSERVE: '观察',
  NONE: '无动作'
}
export const regulationActionLabel = (v?: string | null) => lookup(regulationActionLabels, v)

export const runModeLabels: LabelDict = {
  PEAK: '高峰',
  FLAT: '平峰',
  OFF_PEAK: '低峰'
}
export const runModeLabel = (v?: string | null) => lookup(runModeLabels, v)

export const reservationStateLabels: LabelDict = {
  REQUESTED: '已申请',
  ACCEPTED: '已接受',
  RELEASED: '已释放',
  EXPIRED: '已过期',
  REJECTED: '被拒绝',
  CANCELLED: '已取消'
}
export const reservationStateLabel = (v?: string | null) => lookup(reservationStateLabels, v)

export const stationEventTypeLabels: LabelDict = {
  ARRIVAL: '到达',
  DEPARTURE: '出发'
}
export const stationEventTypeLabel = (v?: string | null) => lookup(stationEventTypeLabels, v)

/* ---------------- 车辆 ---------------- */

export const operationModeLabels: LabelDict = {
  ATO: '自动驾驶 ATO',
  DTO: '无人驾驶 DTO',
  AR: '自动折返 AR',
  SM: '监控人工 SM',
  RM: '限制人工 RM'
}
export const operationModeLabel = (v?: string | null) => lookup(operationModeLabels, v)

export const doorStateLabels: LabelDict = {
  CLOSED_LOCKED: '关闭锁紧',
  CLOSED: '已关闭',
  OPENING: '开门中',
  OPEN: '已开门',
  CLOSING: '关门中',
  FAULT: '车门故障'
}
export const doorStateLabel = (v?: string | null) => lookup(doorStateLabels, v)

export const tractionStateLabels: LabelDict = {
  APPLYING: '施加牵引',
  COASTING: '惰行',
  CUT: '牵引切除',
  DERATED: '牵引降级',
  IDLE: '待机'
}
export const tractionStateLabel = (v?: string | null) => lookup(tractionStateLabels, v)

export const brakeStateLabels: LabelDict = {
  RELEASED: '已缓解',
  APPLYING: '施加制动',
  SERVICE: '常用制动',
  EMERGENCY: '紧急制动',
  HOLDING: '保持制动'
}
export const brakeStateLabel = (v?: string | null) => lookup(brakeStateLabels, v)

export const trainStatusLabels: LabelDict = {
  RUNNING: '运行',
  STOPPED: '停车',
  DWELLING: '停站',
  OUT_OF_SERVICE: '退出服务'
}
export const trainStatusLabel = (v?: string | null) => lookup(trainStatusLabels, v)

export const dynamicsStateLabels: LabelDict = {
  ACCELERATING: '加速',
  CRUISING: '巡航',
  COASTING: '惰行',
  BRAKING: '制动',
  STOPPED: '停止',
  EMERGENCY_BRAKING: '紧急制动'
}
export const dynamicsStateLabel = (v?: string | null) => lookup(dynamicsStateLabels, v)

export const maintenanceStateLabels: LabelDict = {
  NONE: '正常',
  INSPECTION_REQUIRED: '需检查',
  REPAIR_REQUIRED: '需检修'
}
export const maintenanceStateLabel = (v?: string | null) => lookup(maintenanceStateLabels, v)

export const currentCollectionLabels: LabelDict = {
  NORMAL: '受流正常',
  DEGRADED: '受流降级',
  LOST: '失电',
  FAULT: '受流故障'
}
export const currentCollectionLabel = (v?: string | null) => lookup(currentCollectionLabels, v)

/* ---------------- 司机台 / DMI ---------------- */

/** 驾驶模式（cabDisplay.currentDrivingMode / maximumAvailableDrivingMode） */
export const drivingModeLabels: LabelDict = {
  DTO: '无人驾驶 DTO',
  ATO: '自动驾驶 ATO',
  AR: '自动折返 AR',
  SM: '监控人工 SM',
  RM: '限制人工 RM'
}
export const drivingModeLabel = (v?: string | null) => lookup(drivingModeLabels, v)
/** 模式短标（速度表旁大字） */
export const drivingModeShort = (v?: string | null) => (v && drivingModeLabels[v] ? v : v ?? '—')

export const tractionBrakeInfoLabels: LabelDict = {
  COASTING: '惰行',
  TRACTION: '牵引',
  BRAKING: '制动',
  EMERGENCY_BRAKING: '紧急制动'
}
export const tractionBrakeInfoLabel = (v?: string | null) => lookup(tractionBrakeInfoLabels, v)

export const departureInfoLabels: LabelDict = {
  DEPART: '允许发车',
  HOLD: '禁止发车'
}
export const departureInfoLabel = (v?: string | null) => lookup(departureInfoLabels, v)

export const turnbackInfoLabels: LabelDict = {
  INACTIVE: '无折返',
  AVAILABLE: '可折返',
  ACTIVE: '折返中'
}
export const turnbackInfoLabel = (v?: string | null) => lookup(turnbackInfoLabels, v)

export const doorSideLabels: LabelDict = {
  NONE: '无',
  LEFT: '左侧',
  RIGHT: '右侧',
  BOTH: '双侧',
  BOTH_LEFT_FIRST: '双侧(左先)',
  BOTH_RIGHT_FIRST: '双侧(右先)'
}
export const doorSideLabel = (v?: string | null) => lookup(doorSideLabels, v)

export const doorControlModeLabels: LabelDict = {
  AUTOMATIC: '自动',
  SEMI_AUTOMATIC: '半自动',
  MANUAL: '人工'
}
export const doorControlModeLabel = (v?: string | null) => lookup(doorControlModeLabels, v)

/** 方向手柄 / 主手柄（司控台写入枚举） */
export const directionHandleLabels: LabelDict = {
  FORWARD: '前进',
  ZERO: '零位',
  BACKWARD: '后退'
}
export const directionHandleLabel = (v?: string | null) => lookup(directionHandleLabels, v)

export const masterHandleLabels: LabelDict = {
  TRACTION: '牵引',
  ZERO: '零位',
  BRAKE: '制动',
  FAST_BRAKE: '快速制动'
}
export const masterHandleLabel = (v?: string | null) => lookup(masterHandleLabels, v)

export const vehicleProtectionLabels: LabelDict = {
  NONE: '无',
  OVERSPEED: '超速防护',
  MA_EXCEEDED: '越过授权',
  EMERGENCY: '紧急制动',
  DOOR_OPEN: '车门未锁',
  ROLLBACK: '溜逸防护'
}
export const vehicleProtectionLabel = (v?: string | null) => lookup(vehicleProtectionLabels, v)

export const overloadStatusLabels: LabelDict = {
  NORMAL: '正常',
  WARNING: '接近满载',
  OVERLOAD: '超载'
}
export const overloadStatusLabel = (v?: string | null) => lookup(overloadStatusLabels, v)

/* ---------------- 供电 ---------------- */

export const powerSectionStatusLabels: LabelDict = {
  ENERGIZED: '带电',
  DE_ENERGIZED: '停电',
  LOST: '失电',
  OVERRANGE: '越限',
  FAULT: '故障'
}
export const powerSectionStatusLabel = (v?: string | null) => lookup(powerSectionStatusLabels, v)

export const supplyModeLabels: LabelDict = {
  DOUBLE_END: '双端供电',
  SINGLE_END: '单端供电',
  CROSS_FEED: '越区供电',
  OUTAGE: '停电'
}
export const supplyModeLabel = (v?: string | null) => lookup(supplyModeLabels, v)

export const breakerStatusLabels: LabelDict = {
  CLOSED: '合闸',
  OPEN: '分闸',
  TRIPPED: '跳闸'
}
export const breakerStatusLabel = (v?: string | null) => lookup(breakerStatusLabels, v)

export const protectionStateLabels: LabelDict = {
  NORMAL: '正常',
  ALARM: '保护告警',
  TRIPPED: '保护动作'
}
export const protectionStateLabel = (v?: string | null) => lookup(protectionStateLabels, v)

export const maintenancePowerLabels: LabelDict = {
  NONE: '无检修',
  PLANNED: '计划检修',
  IN_PROGRESS: '检修中'
}
export const maintenancePowerLabel = (v?: string | null) => lookup(maintenancePowerLabels, v)

export const lockoutStateLabels: LabelDict = {
  UNLOCKED: '未锁定',
  LOCKED: '已锁定',
  LOCKED_OUT: '检修锁定'
}
export const lockoutStateLabel = (v?: string | null) => lookup(lockoutStateLabels, v)

export const strayCurrentRiskLabels: LabelDict = {
  NORMAL: '正常',
  ATTENTION: '关注',
  WARNING: '预警',
  CRITICAL: '严重'
}
export const strayCurrentRiskLabel = (v?: string | null) => lookup(strayCurrentRiskLabels, v)

export const voltageComparisonLabels: LabelDict = {
  NO_EXTERNAL_DATA: '无外部数据',
  MATCHED: '内外一致',
  DEVIATED: '存在偏差',
  DIVERGED: '严重偏离'
}
export const voltageComparisonLabel = (v?: string | null) => lookup(voltageComparisonLabels, v)

/* ---------------- 健康 / 数据质量 ---------------- */

export const serviceHealthLabels: LabelDict = {
  UP: '正常',
  DEGRADED: '降级',
  STALE: '数据过期',
  FALLBACK: '回退',
  RECOVERING: '恢复中',
  DOWN: '离线'
}
export const serviceHealthLabel = (v?: string | null) => lookup(serviceHealthLabels, v)

export const dataQualityLabels: LabelDict = {
  GOOD: '正常',
  DEGRADED: '降级',
  FALLBACK: '降级数据',
  STALE: '过期数据',
  INVALID: '数据无效',
  BAD: '异常数据'
}
export const dataQualityLabel = (v?: string | null) => lookup(dataQualityLabels, v)

/* ---------------- 告警 ---------------- */

export function alarmLevelLabel(level: number): string {
  if (level >= 3) return '严重'
  if (level === 2) return '警告'
  return '提示'
}

export function alarmLevelTone(level: number): 'danger' | 'warn' | 'info' {
  if (level >= 3) return 'danger'
  if (level === 2) return 'warn'
  return 'info'
}

export const alarmStateLabels: LabelDict = {
  RAISED: '未确认',
  ACKNOWLEDGED: '已确认',
  CLEARED: '已清除'
}
export const alarmStateLabel = (v?: string | null) => lookup(alarmStateLabels, v)

/* ---------------- 通用语义 tone 判定 ---------------- */

export type Tone = 'ok' | 'warn' | 'danger' | 'fault' | 'info' | 'neutral' | 'stale'

export function occupancyTone(v?: string | null): Tone {
  switch (v) {
    case 'OCCUPIED': return 'danger'
    case 'RESERVED': return 'warn'
    case 'FAULT':
    case 'BLOCKED': return 'fault'
    case 'FREE': return 'neutral'
    default: return 'neutral'
  }
}

export function signalTone(v?: string | null): Tone {
  switch (v) {
    case 'GREEN': return 'ok'
    case 'YELLOW': return 'warn'
    case 'RED': return 'danger'
    default: return 'neutral'
  }
}

export function healthTone(v?: string | null): Tone {
  switch (v) {
    case 'UP': return 'ok'
    case 'DEGRADED': return 'warn'
    case 'STALE': return 'stale'
    case 'FALLBACK': return 'fault'
    case 'RECOVERING': return 'info'
    case 'DOWN': return 'danger'
    default: return 'neutral'
  }
}

export function dataQualityTone(v?: string | null): Tone {
  switch (v) {
    case 'GOOD': return 'ok'
    case 'DEGRADED': return 'warn'
    case 'FALLBACK': return 'fault'
    case 'STALE': return 'stale'
    case 'INVALID':
    case 'BAD': return 'danger'
    default: return 'neutral'
  }
}

export function powerStatusTone(v?: string | null): Tone {
  switch (v) {
    case 'ENERGIZED': return 'ok'
    case 'LOST':
    case 'FAULT': return 'danger'
    case 'OVERRANGE': return 'warn'
    case 'DE_ENERGIZED': return 'neutral'
    default: return 'neutral'
  }
}

export function commandStatusTone(v?: string | null): Tone {
  switch (v) {
    case 'APPLIED':
    case 'EFFECT_CONFIRMED': return 'ok'
    case 'PENDING':
    case 'SENT': return 'info'
    case 'TIMEOUT':
    case 'SKIPPED': return 'warn'
    case 'EXPIRED':
    case 'CANCELLED': return 'neutral'
    default: return 'neutral'
  }
}

export function routeStatusTone(v?: string | null): Tone {
  switch (v) {
    case 'AVAILABLE': return 'neutral'
    case 'LOCKED':
    case 'ESTABLISHED': return 'ok'
    case 'OCCUPIED': return 'info'
    case 'CONFLICTED':
    case 'REJECTED':
    case 'FAILED': return 'danger'
    case 'RELEASING':
    case 'VALIDATING':
    case 'SETTING_SWITCHES': return 'warn'
    default: return 'neutral'
  }
}

export function strayRiskTone(v?: string | null): Tone {
  switch (v) {
    case 'NORMAL': return 'ok'
    case 'ATTENTION': return 'info'
    case 'WARNING': return 'warn'
    case 'CRITICAL': return 'danger'
    default: return 'neutral'
  }
}
