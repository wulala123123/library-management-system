import { afterEach, expect, it, vi } from 'vitest'
import { downloadBlob } from './download'

afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals() })

it.each(['csv', 'json'])('downloads the unchanged %s blob and filename, then releases its object URL', format => {
  const blob = new Blob(['\ufeff000005,未记录\r\n']), create = vi.fn(() => 'blob:original'), revoke = vi.fn()
  vi.stubGlobal('URL', { createObjectURL: create, revokeObjectURL: revoke })
  let anchor: HTMLAnchorElement | undefined
  vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) { anchor = this; expect(revoke).not.toHaveBeenCalled() })
  downloadBlob(blob, `snapshot-000005.${format}`)
  expect(create).toHaveBeenCalledExactlyOnceWith(blob)
  expect(anchor?.href).toBe('blob:original')
  expect(anchor?.download).toBe(`snapshot-000005.${format}`)
  expect(revoke).toHaveBeenCalledExactlyOnceWith('blob:original')
})

it('releases the object URL when the browser refuses the download and preserves the failure', () => {
  const failure = new Error('synthetic click failure'), revoke = vi.fn()
  vi.stubGlobal('URL', { createObjectURL: () => 'blob:failed', revokeObjectURL: revoke })
  vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => { throw failure })
  expect(() => downloadBlob(new Blob(['original']), 'original.csv')).toThrow(failure)
  expect(revoke).toHaveBeenCalledExactlyOnceWith('blob:failed')
})
