/**
 * 客流模拟数据生成器 —— 仅供 /forecast 演示模块使用。
 *
 * ⚠ 这里的全部数据都是前端本地合成的模拟数据，不来自任何后端接口。
 * 生成规则是确定性的（站点 ID 播种），同一天内刷新页面曲线不变，
 * 避免"每次刷新都换一套数字"的虚假实时感。
 */

export interface FlowPoint {
  /** 当日分钟数（0-1439） */
  minute: number
  inbound: number
  outbound: number
}

export interface ForecastPoint {
  minute: number
  expected: number
  lower: number
  upper: number
}

export interface StationFlowProfile {
  stationId: string
  stationName: string
  /** 车站规模系数（0.6~1.6） */
  scale: number
  history: FlowPoint[]
  forecast: ForecastPoint[]
  /** 当前 15 分钟进站人数 */
  currentInbound: number
  currentOutbound: number
  /** 相对该站容量的负荷百分比 */
  loadPercent: number
  /** 未来 60 分钟负荷趋势：-1 下降 / 0 平稳 / 1 上升 */
  trend: -1 | 0 | 1
  peakMinute: number
}

/** mulberry32 —— 确定性伪随机（同一 seed 序列固定） */
function mulberry32(seed: number): () => number {
  let a = seed >>> 0
  return () => {
    a |= 0
    a = (a + 0x6d2b79f5) | 0
    let t = Math.imul(a ^ (a >>> 15), 1 | a)
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}

function hashString(value: string): number {
  let hash = 2166136261
  for (let i = 0; i < value.length; i++) {
    hash ^= value.charCodeAt(i)
    hash = Math.imul(hash, 16777619)
  }
  return hash >>> 0
}

function gaussian(x: number, mu: number, sigma: number): number {
  const d = (x - mu) / sigma
  return Math.exp(-0.5 * d * d)
}

export const BUCKET_MINUTES = 15
const DAY_START_MINUTE = 5 * 60 + 30 // 05:30 首班车前
const DAY_END_MINUTE = 24 * 60

/** 双峰通勤模型：早高峰 8:00、晚高峰 18:00，午间小峰 */
function baseDemand(minute: number, scale: number, noise: () => number): number {
  const morning = gaussian(minute, 8 * 60, 55) * 1.0
  const evening = gaussian(minute, 18 * 60, 65) * 0.92
  const noon = gaussian(minute, 12.5 * 60, 90) * 0.28
  const floor = 0.08
  const raw = (morning + evening + noon + floor) * 1400 * scale
  const jitter = 1 + (noise() - 0.5) * 0.16
  return Math.max(0, Math.round(raw * jitter))
}

export function buildStationProfile(
  stationId: string,
  stationName: string,
  nowMinute: number,
  daySeed: number
): StationFlowProfile {
  const seed = hashString(stationId) ^ daySeed
  const noise = mulberry32(seed)
  const scale = 0.6 + mulberry32(seed ^ 0x9e3779b9)() * 1.0
  // 进出站不对称：商务区早进多晚出多，居住区相反
  const residential = mulberry32(seed ^ 0x51ed270b)() > 0.5

  const history: FlowPoint[] = []
  for (let minute = DAY_START_MINUTE; minute <= Math.min(nowMinute, DAY_END_MINUTE); minute += BUCKET_MINUTES) {
    const demand = baseDemand(minute, scale, noise)
    const morningShare = gaussian(minute, 8 * 60, 90)
    const inboundShare = residential ? 0.38 + morningShare * 0.34 : 0.62 - morningShare * 0.28
    history.push({
      minute,
      inbound: Math.round(demand * inboundShare),
      outbound: Math.round(demand * (1 - inboundShare))
    })
  }

  // 预测：未来 2 小时，期望值沿用同一模型，置信区间随时间展宽
  const forecast: ForecastPoint[] = []
  const forecastNoise = mulberry32(seed ^ 0x2545f491)
  for (let step = 1; step <= 8; step++) {
    const minute = nowMinute + step * BUCKET_MINUTES
    if (minute > DAY_END_MINUTE) break
    const expected = baseDemand(minute, scale, forecastNoise)
    const spread = 0.06 + step * 0.035
    forecast.push({
      minute,
      expected,
      lower: Math.round(expected * (1 - spread)),
      upper: Math.round(expected * (1 + spread))
    })
  }

  const last = history[history.length - 1] ?? { minute: nowMinute, inbound: 0, outbound: 0 }
  const capacityPerBucket = 2200 * scale
  const loadPercent = Math.min(140, Math.round(((last.inbound + last.outbound) / capacityPerBucket) * 100))

  const futureAvg =
    forecast.length > 0 ? forecast.slice(0, 4).reduce((sum, point) => sum + point.expected, 0) / Math.min(4, forecast.length) : 0
  const currentTotal = last.inbound + last.outbound
  const trend: -1 | 0 | 1 = futureAvg > currentTotal * 1.12 ? 1 : futureAvg < currentTotal * 0.88 ? -1 : 0

  let peakMinute = 8 * 60
  let peakValue = -1
  for (let minute = DAY_START_MINUTE; minute <= DAY_END_MINUTE; minute += BUCKET_MINUTES) {
    const value = gaussian(minute, 8 * 60, 55) + gaussian(minute, 18 * 60, 65) * 0.92
    if (value > peakValue) {
      peakValue = value
      peakMinute = minute
    }
  }

  return {
    stationId,
    stationName,
    scale,
    history,
    forecast,
    currentInbound: last.inbound,
    currentOutbound: last.outbound,
    loadPercent,
    trend,
    peakMinute
  }
}

export function minuteToLabel(minute: number): string {
  const h = Math.floor(minute / 60) % 24
  const m = minute % 60
  return `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}`
}

/** 当日种子：日期变化时曲线整体换一天的"剧本"，一天内保持稳定 */
export function todaySeed(date: Date): number {
  return hashString(`${date.getFullYear()}-${date.getMonth() + 1}-${date.getDate()}`)
}
