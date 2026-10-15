import { describe, expect, it } from 'vitest'
import { dueDateText } from './borrowDates'

describe('imported borrowing deadlines', () => {
  it('distinguishes a source unlimited loan from an unknown historical deadline', () => {
    expect(dueDateText({ dueDate: null, dueDateKind: 'UNLIMITED' })).toBe('不限制')
    expect(dueDateText({ dueDate: null, dueDateKind: 'UNKNOWN' })).toBe('原记录未提供')
    expect(dueDateText({ dueDate: '2026-11-15', dueDateKind: 'DATED' })).toBe('2026-11-15')
  })
})
