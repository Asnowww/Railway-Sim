import { apiBaseUrl } from '../../api/config'

/** 后端标准错误体（危险写接口审计失败等场景返回 ApiError{code,...}）。 */
export interface ApiErrorBody {
  code?: string
  message?: string
  [key: string]: unknown
}

export class ApiError extends Error {
  readonly status: number
  readonly code: string | null
  readonly body: ApiErrorBody | null

  constructor(status: number, statusText: string, body: ApiErrorBody | null) {
    const code = body?.code ?? null
    super(body?.message ?? `${status} ${statusText}`)
    this.name = 'ApiError'
    this.status = status
    this.code = code
    this.body = body
  }

  /** 面向操作反馈条的人话描述。 */
  get displayMessage(): string {
    if (this.code === 'AUDIT_FAILED') return '审计日志写入失败，后端已拒绝执行本次操作（503 AUDIT_FAILED）'
    if (this.status === 409) return `操作与当前状态冲突（409）：${this.message}`
    if (this.status === 404) return `目标不存在（404）：${this.message}`
    if (this.status === 400) return `请求不合法（400）：${this.message}`
    if (this.status === 410) return '该接口已被后端下线（410）'
    return this.message
  }
}

export interface RequestOptions extends RequestInit {
  /** 超时毫秒，默认 10s；仿真控制类接口响应快，不需要更长。 */
  timeoutMillis?: number
}

const DEFAULT_TIMEOUT = 10_000

/**
 * 全站唯一 HTTP 入口。
 * - 统一 baseURL / 超时 / 错误解析（含后端 ApiError.code）
 * - 网络失败与超时抛出带中文说明的 Error，调用方决定展示方式
 */
export async function request<T>(path: string, init?: RequestOptions): Promise<T> {
  const response = await rawRequest(path, init)
  if (response.status === 204) return undefined as T
  return (await response.json()) as T
}

/** 二进制响应（如 PLC 输出报文）。 */
export async function requestBinary(path: string, init?: RequestOptions): Promise<ArrayBuffer> {
  const response = await rawRequest(path, init)
  return response.arrayBuffer()
}

async function rawRequest(path: string, init?: RequestOptions): Promise<Response> {
  const { timeoutMillis = DEFAULT_TIMEOUT, ...rest } = init ?? {}
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), timeoutMillis)
  let response: Response
  try {
    response = await fetch(`${apiBaseUrl}${path}`, { ...rest, signal: controller.signal })
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') {
      throw new Error(`请求超时（${timeoutMillis}ms）：${path}`)
    }
    throw new Error(`无法连接后端（${path}）：${error instanceof Error ? error.message : String(error)}`)
  } finally {
    clearTimeout(timer)
  }

  if (!response.ok) {
    let body: ApiErrorBody | null = null
    try {
      body = (await response.json()) as ApiErrorBody
    } catch {
      /* 非 JSON 错误体 */
    }
    throw new ApiError(response.status, response.statusText, body)
  }
  return response
}

export function postJson<T>(path: string, body: unknown, init?: RequestOptions): Promise<T> {
  return request<T>(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
    ...init
  })
}

/** 危险写操作固定确认令牌（后端 confirmToken 校验）。 */
export const SIMULATION_CONFIRM_TOKEN = 'SIMULATION_CONFIRM'

/** 生成前端操作 traceId，贯穿操作审计链路。 */
export function newTraceId(prefix: string): string {
  const rand = Math.random().toString(36).slice(2, 8)
  return `ui-${prefix}-${Date.now().toString(36)}-${rand}`
}
