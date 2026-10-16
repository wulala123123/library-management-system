<template>
  <section class="scope-picker">
    <el-radio-group v-model="kind" :disabled="disabled" @change="publish"><el-radio-button value="ADDRESS_SET">明确地址集合</el-radio-button><el-radio-button value="COPY_SET">明确实体册集合</el-radio-button></el-radio-group>
    <template v-if="kind === 'ADDRESS_SET'">
      <LocationAddressPicker v-model="address" :disabled="disabled" :require-selectable="false" />
      <el-button :disabled="disabled || !address || addresses.length >= 10" @click="addAddress">加入本次范围</el-button>
      <ul><li v-for="row in addresses" :key="row.address.locationId">地址 #{{ row.address.locationId }} · {{ row.address.bookshelfName ?? 'NULL' }} / {{ row.address.storeyName ?? 'NULL' }} / {{ row.address.code ?? 'NULL' }} <el-button link :disabled="disabled" @click="removeAddress(row.address.locationId)">移除</el-button></li></ul>
      <p>最多 10 个明确地址，相关册最多 100 本；这不是地址容量限制。含借出、停用、报损及未知状态册。</p>
    </template>
    <template v-else>
      <el-form inline @submit.prevent="search"><el-form-item label="精确馆内条码"><el-input v-model="barcode" :disabled="disabled" placeholder="完整原文，不去空白" /></el-form-item><el-form-item label="书目关键词"><el-input v-model="keyword" :disabled="disabled" /></el-form-item><el-button :disabled="disabled" @click="search">查找实体册</el-button></el-form>
      <el-table :data="copies" border v-loading="loading"><el-table-column label="实体册 / 登记书名" min-width="200"><template #default="{ row }">#{{ row.id }} · 登记书名：{{ row.book.title }}<br /><code>{{ JSON.stringify(row.barcode) }}</code></template></el-table-column><el-table-column label="登记位置" min-width="240"><template #default="{ row }"><CopyPlacement :placement="row.placement" :raw-text="row.location" /></template></el-table-column><el-table-column label="选择" width="100"><template #default="{ row }"><el-button link :disabled="disabled || selectedCopies.some(copy => copy.id === row.id) || selectedCopies.length >= 100" @click="addCopy(row)">加入范围</el-button></template></el-table-column></el-table>
      <el-pagination :current-page="page" :page-size="10" :total="total" layout="total, prev, pager, next" @current-change="changePage" />
      <ul><li v-for="row in selectedCopies" :key="row.id">册 #{{ row.id }} · {{ row.title }} · <code>{{ JSON.stringify(row.barcode) }}</code> <el-button link :disabled="disabled" @click="removeCopy(row.id)">移除</el-button></li></ul>
      <details><summary>按已核对的稳定册 ID 明确输入</summary><el-input v-model="explicitIds" :disabled="disabled" placeholder="正整数册 ID，逗号分隔" /><el-button :disabled="disabled" @click="addIds">加入 ID</el-button><p>数据库册 ID 与条码、ISBN、列表序号不同；不能借此声称整个地址或书目已盘完。</p></details>
    </template>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <p class="scope-summary">提交范围：{{ kind }} [{{ ids.join(', ') }}]。创建后范围和基线固定，选错须取消后新建。</p>
  </section>
</template>
<script setup lang="ts">
import { computed, ref } from 'vue'
import { getBookCopies, type CopyRow } from '@/api/bookCopyQuery'
import type { AddressRow } from '@/api/locationAddress'
import type { StocktakeScope } from '@/api/stocktake'
import LocationAddressPicker from '@/components/locations/LocationAddressPicker.vue'
import CopyPlacement from '@/components/locations/CopyPlacement.vue'
import { parseExplicitIds } from './stocktakeInput'
defineProps<{ disabled?: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [scope: StocktakeScope] }>()
const kind = ref<'ADDRESS_SET' | 'COPY_SET'>('ADDRESS_SET'), address = ref<AddressRow | null>(null), addresses = ref<AddressRow[]>([])
const selectedCopies = ref<{ id: number; title: string; barcode: string | null }[]>([]), copies = ref<CopyRow[]>([])
const barcode = ref(''), keyword = ref(''), explicitIds = ref(''), page = ref(1), total = ref(0), loading = ref(false), error = ref('')
const ids = computed(() => kind.value === 'ADDRESS_SET' ? addresses.value.map(row => row.address.locationId) : selectedCopies.value.map(row => row.id))
function publish() { emit('update:modelValue', kind.value === 'ADDRESS_SET' ? { kind: 'ADDRESS_SET', locationIds: [...ids.value] } : { kind: 'COPY_SET', copyIds: [...ids.value] }) }
function addAddress() { if (address.value && !addresses.value.some(row => row.address.locationId === address.value!.address.locationId) && addresses.value.length < 10) addresses.value.push(address.value); publish() }
function removeAddress(id: number) { addresses.value = addresses.value.filter(row => row.address.locationId !== id); publish() }
function addCopy(row: CopyRow) { selectedCopies.value.push({ id: row.id, title: row.book.title, barcode: row.barcode }); publish() }
function removeCopy(id: number) { selectedCopies.value = selectedCopies.value.filter(row => row.id !== id); publish() }
function addIds() { try { const additions = parseExplicitIds(explicitIds.value, 100).filter(id => !selectedCopies.value.some(row => row.id === id)); if (selectedCopies.value.length + additions.length > 100) throw new Error('单元最多 100 册，请拆分明确范围。'); selectedCopies.value.push(...additions.map(id => ({ id, title: '明确指定的册 ID，创建时由服务端核对', barcode: null }))); error.value = ''; publish() } catch (cause) { error.value = (cause as Error).message } }
let request = 0
async function load() { const current = ++request; loading.value = true; error.value = ''; try { const result = await getBookCopies({ page: page.value, size: 10, barcode: barcode.value || undefined, keyword: keyword.value || undefined }); if (current === request) { copies.value = result.records; total.value = result.total } } catch { if (current === request) { copies.value = []; total.value = 0; error.value = '实体册查询失败，请保留条件后重试。' } } finally { if (current === request) loading.value = false } }
function search() { page.value = 1; load() }
function changePage(value: number) { page.value = value; load() }
</script>
<style scoped>
.scope-picker { display: grid; gap: 12px; }.scope-picker p { font-size: 13px; color: #65758b; margin: 0; line-height: 1.7; }.scope-summary { background: #f1f6fd; padding: 12px; border-radius: 6px; }ul { padding-left: 20px; margin: 0; }code { white-space: pre-wrap; overflow-wrap: anywhere; }
</style>
