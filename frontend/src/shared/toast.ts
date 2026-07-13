import { reactive } from 'vue'
import { ApiError } from './api/client'

export interface ToastItem {
  id: number
  tone: 'ok' | 'danger' | 'warn' | 'info'
  title: string
  detail?: string
  traceId?: string
}

let nextId = 1

export const toasts = reactive<ToastItem[]>([])

export function pushToast(item: Omit<ToastItem, 'id'>, ttlMillis = 6000): void {
  const toast: ToastItem = { id: nextId++, ...item }
  toasts.push(toast)
  window.setTimeout(() => dismissToast(toast.id), ttlMillis)
}

export function dismissToast(id: number): void {
  const index = toasts.findIndex((t) => t.id === id)
  if (index >= 0) toasts.splice(index, 1)
}

/** 统一的操作失败提示：解析 ApiError 语义。 */
export function toastError(title: string, error: unknown): void {
  const detail = error instanceof ApiError ? error.displayMessage : error instanceof Error ? error.message : String(error)
  pushToast({ tone: 'danger', title, detail }, 9000)
}

export function toastOk(title: string, detail?: string, traceId?: string): void {
  pushToast({ tone: 'ok', title, detail, traceId })
}
