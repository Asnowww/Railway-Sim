import { reactive } from 'vue'

export interface ConfirmRequest {
  title: string
  /** 操作对象与后果说明，多行 */
  lines: string[]
  /** danger 操作按钮红色并要求填写 reason */
  danger?: boolean
  /** 是否要求填写操作原因（危险写接口审计需要） */
  requireReason?: boolean
  confirmText?: string
}

export interface ConfirmResult {
  ok: boolean
  reason: string
  operator: string
}

interface ConfirmState {
  visible: boolean
  request: ConfirmRequest | null
  resolve: ((result: ConfirmResult) => void) | null
}

export const confirmState = reactive<ConfirmState>({
  visible: false,
  request: null,
  resolve: null
})

/** 全局二次确认。危险写操作（供电操作/故障注入/生命周期）必须经此入口。 */
export function requestConfirm(request: ConfirmRequest): Promise<ConfirmResult> {
  // 若已有弹窗，直接拒绝新请求，避免叠加
  if (confirmState.visible && confirmState.resolve) {
    confirmState.resolve({ ok: false, reason: '', operator: '' })
  }
  confirmState.request = request
  confirmState.visible = true
  return new Promise((resolve) => {
    confirmState.resolve = resolve
  })
}

export function settleConfirm(result: ConfirmResult): void {
  confirmState.visible = false
  confirmState.resolve?.(result)
  confirmState.resolve = null
  confirmState.request = null
}
