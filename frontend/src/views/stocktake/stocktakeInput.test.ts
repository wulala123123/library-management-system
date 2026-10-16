import { describe, expect, it } from 'vitest'
import { barcodeEvidence, completenessLabels, differenceExplanation, differenceLabel, parseExplicitIds, validateObservedBarcode } from './stocktakeInput'

describe('stocktake evidence input', () => {
  it('only parses explicit positive stable IDs and bounds the distinct finite set', () => {
    expect(parseExplicitIds('9, 3，9\n1', 3)).toEqual([9, 3, 1])
    for (const value of ['', '0', '-1', '01', '1.2', '9007199254740992', 'LMS-C-test']) expect(() => parseExplicitIds(value, 10)).toThrow()
    expect(() => parseExplicitIds('1,2,3', 2)).toThrow('最多选择 2')
  })
  it('retains leading zeros, edge whitespace, pure whitespace, case and literal formula characters', () => {
    for (const value of ['000021', ' AbC ', '   ', '=1+1', '𠮷']) {
      expect(validateObservedBarcode(value)).toBeNull()
      expect(JSON.parse(barcodeEvidence(value).literal)).toBe(value)
    }
    expect(barcodeEvidence('𠮷')).toEqual({ literal: '"𠮷"', codepoints: 1, bytes: 4 })
  })
  it('rejects controls and malformed unicode without normalizing input, with a separate observation limit', () => {
    for (const value of ['', '\n', '\t', '\u0000', '\ud800', 'x'.repeat(257)]) expect(validateObservedBarcode(value)).toBeTruthy()
    expect(validateObservedBarcode('x'.repeat(256))).toBeNull()
    expect(validateObservedBarcode('𠮷'.repeat(256))).toBeNull()
  })
  it('explains uncertainty without claiming missing, lost, intake or a full-library result', () => {
    expect(differenceLabel('UNKNOWN_BARCODE')).toBe('未知条码')
    expect(differenceExplanation('MISSING_CANDIDATE')).toContain('不等于遗失或报损')
    expect(differenceExplanation('UNKNOWN_BARCODE')).toContain('不能据此新增库存')
    expect(differenceExplanation('NOT_OBSERVED')).toContain('不能判断少册')
    expect(completenessLabels.COMPLETE_SCOPE).toContain('所选有限范围')
    expect(differenceLabel('NEW_SERVER_TAG')).toBe('NEW_SERVER_TAG')
  })
})
