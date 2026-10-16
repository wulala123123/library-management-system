import { expect, it } from 'vitest'
import { adminAction, copyStateText, rawText, validateActionBasics, validateLocation } from './copyActionPresentation'
it('distinguishes controlled states from identical unknown legacy strings without trimming them', () => {
  expect(copyStateText('DISABLED', true)).toBe('临时停用')
  expect(copyStateText('WRITTEN_OFF', true)).toBe('已报损')
  for (const value of ['DISABLED', 'WRITTEN_OFF', 'AVAILABLE ', 'borrowed', '<img onerror=x>']) expect(copyStateText(value, false)).toContain(JSON.stringify(value))
  expect(rawText(null)).toBe('未分配（NULL）'); expect(rawText(' ')).toBe('" "'); expect(rawText('')).toBe('""')
})
it('requires explicit distinct copy identity, one to one hundred copies, a reason and admin for write-off or restore', () => {
  expect(validateActionBasics('MOVE', [1, 2], '现场核实', false)).toBeNull()
  expect(validateActionBasics('DISABLE', Array.from({ length: 100 }, (_, i) => i + 1), '实物停用', false)).toBeNull()
  for (const ids of [[], [1, 1], [0], [1.5], Array.from({ length: 101 }, (_, i) => i + 1)]) expect(validateActionBasics('MOVE', ids, '核实', true)).not.toBeNull()
  for (const action of ['WRITE_OFF', 'RESTORE'] as const) { expect(adminAction(action)).toBe(true); expect(validateActionBasics(action, [1], '核实', false)).not.toBeNull() }
  expect(validateActionBasics('ENABLE', [1], '  ', true)).not.toBeNull()
  expect(validateActionBasics('MOVE', [1], '😀'.repeat(500), true)).toBeNull()
  expect(validateActionBasics('MOVE', [1], '😀'.repeat(501), true)).not.toBeNull()
  expect(validateActionBasics('MOVE', [1], '理由\t', true)).not.toBeNull()
})
it('requires an explicit destination and preserves text while distinguishing null from an empty draft', () => {
  expect(validateLocation('', '')).not.toBeNull()
  for (const value of ['', ' ', '\tA', '\ud800', 'a'.repeat(201)]) expect(validateLocation('TEXT', value)).not.toBeNull()
  expect(validateLocation('TEXT', '  A区 <img onerror=x>  ')).toBeNull()
  expect(validateLocation('TEXT', '😀'.repeat(200))).toBeNull()
  expect(validateLocation('UNASSIGNED', '不发送旧输入')).toBeNull()
})
