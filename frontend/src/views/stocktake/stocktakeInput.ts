/** Technical identifiers may use separators; observed barcode text must never use this parser. */
export function parseExplicitIds(value: string, limit: number): number[] {
  const tokens = value.split(/[\s,，]+/u).filter(Boolean)
  if (!tokens.length) throw new Error('请明确选择至少一个 ID。')
  if (tokens.some(token => !/^[1-9]\d*$/u.test(token) || !Number.isSafeInteger(Number(token)))) throw new Error('ID 必须是正整数，不能使用条码或列表序号。')
  const ids = [...new Set(tokens.map(Number))]
  if (ids.length > limit) throw new Error(`单个盘点单元最多选择 ${limit} 项，请拆分有限范围。`)
  return ids
}

export function barcodeEvidence(value: string) {
  const codepoints = [...value].length
  const bytes = new TextEncoder().encode(value).length
  return { literal: JSON.stringify(value), codepoints, bytes }
}

export function validateObservedBarcode(value: string): string | null {
  if (value.length === 0) return '请输入馆内条码原文。'
  if (/[\p{Cc}\p{Cs}]/u.test(value)) return '条码原文不能含控制字符或无效 Unicode；输入尚未保存。'
  const evidence = barcodeEvidence(value)
  if (evidence.codepoints > 256 || evidence.bytes > 1024) return '观察条码最多 256 个字符、1024 个 UTF-8 字节；输入尚未保存。'
  return null
}

export function newStocktakeKey() { return crypto.randomUUID() }

export const differenceLabels: Record<string, [string, string]> = {
  OBSERVED_MATCH: ['观察相符', '本次观察与盘点核对条件相符。'],
  NOT_OBSERVED: ['尚未观察到', '覆盖未完成，不能判断少册。'],
  MISSING_CANDIDATE: ['少册待核实', '在明确完成检查的范围内未观察到，须管理员核实，不等于遗失或报损。'],
  EXPECTED_OFFSITE: ['正常在借', '有有效在借依据，未在馆内观察到不计少册。'],
  OBSERVED_WHILE_ON_LOAN: ['在借时被观察到', '观察与在借记录同时存在，须核实借还和实体册。'],
  LOAN_LINK_ANOMALY: ['借阅关联异常', '借阅依据需要核实，不能自动认定已归还。'],
  WRITTEN_OFF_FOUND: ['报损册被找到', '先核实，需恢复时通过原恢复流程到停用，不重复入库。'],
  STATE_OR_SOURCE_UNVERIFIED: ['状态或来源待核实', '历史未知依据保留，不能按同名状态自动处理。'],
  MISPLACED_CANDIDATE: ['错位待核实', '实际观察处与登记处不同，盘点本身不移动实体册。'],
  KNOWN_COPY_OUTSIDE_SCOPE: ['范围外已知册', '已知身份位于本次明确范围之外，不自动扩充范围。'],
  ENTERED_SCOPE: ['期间进入范围', '基线后进入范围，需要核对期间业务。'],
  LEFT_SCOPE: ['期间离开范围', '基线后离开范围，需要核对期间业务。'],
  UNKNOWN_BARCODE: ['未知条码', '按原文未找到实体册，保留观察，不能据此新增库存。'],
  UNSUPPORTED_BARCODE_FORMAT: ['条码格式待核实', '保留扫描原文，但不能用它推断或创建实体册。'],
  MULTIPLE_OBSERVED_LOCATIONS: ['多处观察', '同一身份在不同处被观察到，保留全部事实并逐项核实。'],
  DUPLICATE_SCAN: ['重复扫描', '独立扫描事件均保留，同一身份只计一册。'],
}
export function differenceLabel(code: string) { return differenceLabels[code]?.[0] || code }
export function differenceExplanation(code: string) { return differenceLabels[code]?.[1] || '保留服务端原始分类，等待核实。' }
export const coverageLabels: Record<string, string> = { NOT_ATTEMPTED: '未检查', PARTIAL: '部分检查', COMPLETE: '明确检查完成' }
export const completenessLabels: Record<string, string> = { PARTIAL: '部分盘点报告', COMPLETE_SCOPE: '所选有限范围检查完成' }
