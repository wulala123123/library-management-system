export const MAX_ACTION_COPIES = 100
export const actionNames = { MOVE: '移动位置', DISABLE: '临时停用', ENABLE: '启用可借', WRITE_OFF: '正式报损', RESTORE: '恢复为停用' } as const
export type CopyActionKind = keyof typeof actionNames
export const actionKinds = Object.keys(actionNames) as CopyActionKind[]
export const adminAction = (action: CopyActionKind) => action === 'WRITE_OFF' || action === 'RESTORE'
export const needsLocation = (action: CopyActionKind) => action === 'MOVE' || action === 'RESTORE'
export const rawText = (value: string | null) => value === null ? '未分配（NULL）' : JSON.stringify(value)
export const utf8Bytes = (value: string) => new TextEncoder().encode(value).length
export function copyStateText(status: string, controlled: boolean) {
  if (status === 'AVAILABLE') return '可借'
  if (status === 'BORROWED') return '在借'
  if (controlled && status === 'DISABLED') return '临时停用'
  if (controlled && status === 'WRITTEN_OFF') return '已报损'
  return `原状态待核实：${JSON.stringify(status)}`
}
export function validateActionBasics(action: CopyActionKind, ids: number[], reason: string, isAdmin: boolean): string | null {
  if (!actionKinds.includes(action)) return '请选择办理动作。'
  if (adminAction(action) && !isAdmin) return '正式报损和恢复仅管理员可办理。'
  if (!ids.length || ids.length > MAX_ACTION_COPIES || ids.some(id => !Number.isSafeInteger(id) || id <= 0) || new Set(ids).size !== ids.length) return '请明确选择 1 至 100 本不同的实体册。'
  if (!reason.trim() || [...reason].length > 500 || /[\p{Cc}\p{Cs}]/u.test(reason)) return '请填写办理依据（1 至 500 字，不含控制字符）。'
  return null
}
export function validateLocation(kind: 'TEXT' | 'UNASSIGNED' | '', text: string): string | null {
  if (kind === 'UNASSIGNED') return null
  if (kind !== 'TEXT') return '请为每册明确选择文本位置或未分配，不沿用书目位置。'
  if (!text.trim() || [...text].length > 200 || /[\p{Cc}\p{Cs}]/u.test(text)) return '文本位置需为 1 至 200 字，不含控制字符；清空请明确选择未分配。'
  return null
}
