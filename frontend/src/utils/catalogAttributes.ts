import type { AgeLabel, AttributeField, BindingCode, CatalogBook, CatalogQuery } from '@/api/catalogAttribute'
export const ageOptions: { code: AgeLabel; label: string }[] = [ { code: 'AGE_0_2', label: '0-2岁' }, { code: 'AGE_3_4', label: '3-4岁' }, { code: 'AGE_5_6', label: '5-6岁' }, { code: 'AGE_7_9', label: '7-9岁' }, { code: 'AGE_10_12', label: '10-12岁' } ]
export const bindingLabels: Record<BindingCode, string> = { BOARD: '纸板书', PAPERBACK: '平装', HARDCOVER: '精装' }
export const attributeLabels: Record<AttributeField, string> = { displayTitleOverride: '展示名称覆盖', bindingCode: '装帧', ageLabels: '适读年龄标签', adoptedSourceTitle: '已采用来源原书名' }
export const bookDisplayTitle = (book: CatalogBook): string => book.displayTitle
export function ageText(value: AgeLabel[] | null) { return value === null ? '未设置' : ageOptions.filter(item => value.includes(item.code)).map(item => item.label).join('、') }
export function bindingText(value: BindingCode | null) { return value === null ? '未设置' : bindingLabels[value] }
export interface AttributeFilters { ageState: '' | 'KNOWN' | 'UNKNOWN'; ageLabels: AgeLabel[]; bindingState: '' | 'KNOWN' | 'UNKNOWN'; bindingCode: '' | BindingCode }
export const emptyAttributeFilters = (): AttributeFilters => ({ ageState: '', ageLabels: [], bindingState: '', bindingCode: '' })
export function attributeFilterQuery(filters: AttributeFilters): Partial<CatalogQuery> {
  return { ageState: filters.ageState || undefined, ageLabels: filters.ageState === 'UNKNOWN' || !filters.ageLabels.length ? undefined : [...filters.ageLabels], bindingState: filters.bindingState || undefined, bindingCode: filters.bindingState === 'UNKNOWN' ? undefined : filters.bindingCode || undefined }
}
export function validAttributeText(value: string, max = 200) { return !!value.trim() && [...value].length <= max && !/[\p{Cc}\p{Cs}]/u.test(value) }
function base64(value: string | null) { if (value === null) return ''; return btoa(Array.from(new TextEncoder().encode(value), byte => String.fromCharCode(byte)).join('')) }
function cell(value: unknown) { const text = value === null || value === undefined ? '未设置' : String(value); return `"${(/^[=+\-@]/u.test(text) ? "'" + text : text).replace(/"/g, '""')}"` }
export function catalogPageCsv(books: CatalogBook[]) {
  const headers = ['书目ID','ISBN','展示名称','登记书名','已采用来源原书名','展示名称UTF8Base64','登记书名UTF8Base64','已采用来源原书名UTF8Base64','装帧','任一年龄标签','作者','出版社','出版日期','页数','库位','简介','实体册总数（册）','状态']
  const rows = books.map(book => [book.id,book.isbn,bookDisplayTitle(book),book.catalogTitle,book.adoptedSourceTitle,base64(book.displayTitle),base64(book.catalogTitle),base64(book.adoptedSourceTitle),bindingText(book.bindingCode),ageText(book.ageLabels),book.author,book.publisher,book.publishDate,book.pages,book.location,book.description,book.stockTotal,book.status === 1 ? '在馆' : book.status === 0 ? '下架' : null])
  return '\uFEFF' + [headers,...rows].map(row => row.map(cell).join(',')).join('\r\n')
}
