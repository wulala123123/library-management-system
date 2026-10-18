import { expect, it } from 'vitest'
import { publicationDraft, publicationEntry, publicationValue, validPublicationDate, validPublicationText } from './publicationDraft'
import { publicationBook } from './publicationFixtures'
it('distinguishes KEEP, CLEAR NULL, explicit empty description and known zero without filling unknowns', () => {
  const row = publicationDraft(publicationBook()); row.reference = '合成版权页'; row.actions.description = 'SET'; row.actions.pages = 'SET'
  expect(publicationEntry(row).changes).toEqual({ publishDate: null, pages: { action: 'SET', value: 0 }, description: { action: 'SET', value: '' } })
  row.actions.description = 'CLEAR'; expect(publicationEntry(row).changes.description).toEqual({ action: 'CLEAR' })
  expect([null, '', 0].map(publicationValue)).toEqual(['NULL（未知 / 未设置）', '""（明确空串）', '0'])
})
it('rejects guessed partial dates and invalid calendar dates while accepting a leap day', () => {
  for (const date of ['2024', '2024-02', '2023-02-29', '2024-04-31', '0000-01-01']) expect(validPublicationDate(date)).toBe(false)
  expect(validPublicationDate('2024-02-29')).toBe(true)
})
it('counts Unicode code points, retains line/tab text, rejects controls and isolated surrogates', () => {
  expect(validPublicationText('😀'.repeat(2000), 2000)).toBe(true); expect(validPublicationText('😀'.repeat(2001), 2000)).toBe(false)
  expect(validPublicationText('原文\n\r\t简介', 2000, false, true)).toBe(true)
  expect(validPublicationText('\u0001', 2000)).toBe(false); expect(validPublicationText('\ud800', 2000)).toBe(false)
  const row = publicationDraft(publicationBook()); row.actions.pages = 'SET'; row.reference = '合成依据'; row.pages = '1.2'; expect(() => publicationEntry(row)).toThrow('整数'); row.pages = '100000'; expect(() => publicationEntry(row)).toThrow('整数')
  row.pages = '0'; row.reference = ' '; expect(() => publicationEntry(row)).toThrow('引用说明')
})
it('uses single-line reason/reference rules while keeping descriptions multiline', () => {
  const row = publicationDraft(publicationBook()); row.actions.description = 'SET'; row.description = '简介第一行\n第二行\t😀'; row.reference = '版权页：第 2 页，人工查阅'
  expect(validPublicationText('人工核对补录', 500, true)).toBe(true)
  expect(publicationEntry(row)).toMatchObject({ changes: { description: { action: 'SET', value: row.description } }, evidence: { reference: row.reference } })
  for (const control of ['\n', '\r', '\t', '\u0000', '\u001f', '\u007f', '\u009f']) {
    expect(validPublicationText(`原因${control}原文`, 500, true)).toBe(false)
    row.reference = `出处${control}原文`; expect(() => publicationEntry(row)).toThrow('单行依据引用说明')
  }
  expect(validPublicationText('\u00a0\u2003\u3000', 500, true)).toBe(false)
  expect(validPublicationText('\ufeff', 500, true)).toBe(true) // Java does not classify BOM as whitespace.
})
