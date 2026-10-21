/** Shared attachment protocol: preserve bytes; callers decide whether the request still owns the download. */
export interface CsvDownload { blob: Blob; filename: string; total: number; asOf: string }
export class CsvDownloadError extends Error {}
const whole = (expression: RegExp, value: string) => expression.exec(value)?.[0] === value
export const csvInstant = (value: unknown): value is string => typeof value === 'string' && whole(/^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\.[0-9]{1,9})?Z$/, value) && Number.isFinite(Date.parse(value))
export async function readCsvDownload(response: Response, filenamePattern: RegExp, maxTotal: number | null, signal?: AbortSignal): Promise<CsvDownload> {
  const contentType = response.headers.get('Content-Type') ?? '', disposition = response.headers.get('Content-Disposition') ?? ''
  const filename = /(?:^|;)\s*filename="?([^";]+)"?(?:;|$)/i.exec(disposition)?.[1]
  const totalText = response.headers.get('X-Total-Count') ?? '', asOf = response.headers.get('X-As-Of')
  if (!/^text\/csv(?:;|$)/i.test(contentType) || !/charset\s*=\s*"?utf-8"?/i.test(contentType) || !/^attachment(?:;|$)/i.test(disposition) || !filename || !whole(filenamePattern, filename)
    || !whole(/^(0|[1-9][0-9]*)$/, totalText) || !Number.isSafeInteger(Number(totalText)) || maxTotal !== null && Number(totalText) > maxTotal || !csvInstant(asOf)) throw new CsvDownloadError('导出文件返回内容不完整，请重试核对。')
  let bytes: ArrayBuffer
  try { bytes = await response.arrayBuffer() } catch (cause) { if (signal?.aborted) throw cause; throw new CsvDownloadError('导出文件读取失败，请重试。') }
  const prefix = new Uint8Array(bytes, 0, Math.min(3, bytes.byteLength))
  if (prefix[0] !== 0xef || prefix[1] !== 0xbb || prefix[2] !== 0xbf) throw new CsvDownloadError('导出文件返回内容不完整，请重试核对。')
  return { blob: new Blob([bytes], { type: 'text/csv;charset=utf-8' }), filename, total: Number(totalText), asOf }
}
