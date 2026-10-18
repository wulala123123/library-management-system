import type { FinanceAmount, FinanceEntry, FinanceSourceField } from '@/api/memberFinance'

export const financeFundLabels = { MEMBER_FEE: '会费', DEPOSIT: '押金' }
export const financeKindLabels = { OPENING: '已核实期初', RECEIPT: '实际收款登记', REFUND: '实际退款登记', REVERSAL: '错误登记冲正' }
const unknownLabels: Record<string,string> = { OPENING_UNVERIFIED:'期初未核实', SOURCE_LINK_UNVERIFIED:'来源关联未核实', UNEXPLAINED_HISTORY:'历史收退尚待核实', LEDGER_ANOMALY:'台账存在待核对事项', NOT_ASSESSED:'尚未核定' }
export const financeAmountText = (value: FinanceAmount) => value.state === 'KNOWN' ? `${value.amount} 元` : `未知（${unknownLabels[value.reasonCode] ?? '待核实'}）`
export const financeOptionalText = (value: string | null) => value === null ? '未记录' : value === '' ? '空字符串' : value
export function financeSourceText(field: FinanceSourceField) {
  if(field.presence==='EMPTY')return '空字符串（原文）'
  if(field.presence==='NULL')return '原值为空值'
  if(field.presence==='MISSING')return '原字段缺失'
  if(field.presence==='INVALID_TYPE')return '原值类型异常，原文不展示'
  if(field.presence==='TOO_LONG')return '原值过长，原文不展示'
  return field.rawText!
}
export const sourceStateLabels = { NO_PROFILE:'尚无会员业务档案',MANUAL:'人工登记档案，无可核实的原系统关联',VERIFIED_LINK:'来源关联已核对，金额仍未核实',UNVERIFIED_LINK:'来源关联待核实',INVALID_ARCHIVE:'原档案格式异常，需核对' }
export const sourceTypeLabels = { MISSING:'缺失',NULL:'空值',STRING:'字符串',NUMBER:'来源数值（数据库投影）',BOOLEAN:'布尔值',ARRAY:'数组',OBJECT:'对象' }
export const sourceReasonText = (reason: string) => ({SOURCE_UNAVAILABLE:'无可用来源档案',SOURCE_LINK_UNVERIFIED:'来源关联未核实',SOURCE_ARCHIVE_INVALID:'原档案格式异常',INVALID_JSON:'原档案格式异常',NON_OBJECT_ROOT:'原档案不是资料对象',SOURCE_TOO_LONG:'原字段过长',SOURCE_INVALID_TYPE:'原字段类型异常'}[reason] ?? '存在待核实的来源事项')
export const evidenceTypeLabels = { LEGACY_ARCHIVE:'原系统档案',COUNTER_VOUCHER:'柜台凭证',VERIFICATION_NOTE:'核实说明' }
export const businessTypeLabels = { COUNTER_REGISTRATION:'柜台实际收退',VERIFIED_OPENING:'核实期初',LEDGER_CORRECTION:'台账纠错' }
export const financeEntryState = (entry: FinanceEntry) => entry.kind === 'REVERSAL' ? '冲正记录，保留原事实；不产生新的收退款' : entry.effectiveAtAsOf ? '计入本次查询时点' : '本次查询时点已被冲正，不计入有效收退'
