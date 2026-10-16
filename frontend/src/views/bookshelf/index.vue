<template>
  <div v-if="canManage" class="bookshelf-management">
    <el-alert title="库位是可关联多册的登记地址，当前不按容量禁选。旧书目绑定保留待核实，关联实体册数独立统计（含在借、报损），解除旧绑定不会清除册的位置。" type="info" :closable="false" />
    <div class="card-container">
      <h3>书架地址维护</h3><div class="toolbar"><el-button type="primary" @click="maintain('BOOKSHELF', 'CREATE')">新增书架</el-button><el-input v-model="keyword" placeholder="搜索书架名称/编号" @keyup.enter="search" /><el-button @click="search">查询书架</el-button></div>
      <p v-if="error">{{ error }}</p>
      <el-table :data="shelves" v-loading="loading" border>
        <el-table-column prop="id" label="书架 ID" width="90" /><el-table-column prop="code" label="编号" /><el-table-column prop="name" label="名称" /><el-table-column prop="deptName" label="部门" /><el-table-column label="原描述"><template #default="{ row }"><code>{{ JSON.stringify(row.location) }}</code></template></el-table-column><el-table-column prop="capacity" label="历史容量资料" width="125" /><el-table-column prop="status" label="原启停值" width="100" /><el-table-column prop="layoutVersion" label="版本" width="90" />
        <el-table-column label="维护" min-width="290"><template #default="{ row }"><el-button link @click="showStoreys(row)">层管理</el-button><el-button link @click="maintain('BOOKSHELF', 'UPDATE', row)">编辑书架</el-button><el-button v-if="user.isAdmin" link type="danger" @click="maintain('BOOKSHELF', 'DELETE', row)">删除书架</el-button><el-button link @click="showHistory('BOOKSHELF', row.id)">维护历史</el-button></template></el-table-column>
      </el-table><el-pagination v-model:current-page="page" :page-size="10" :total="total" layout="total, prev, pager, next" @current-change="loadShelves" />
    </div>
    <div class="card-container">
      <h3>登记地址 / 旧书目绑定 / 关联实体册</h3><div class="toolbar"><el-input v-model="addressKeyword" placeholder="搜索地址或书架 / 层名称" @keyup.enter="searchAddresses" /><el-input-number v-model="addressShelfId" :min="1" placeholder="书架 ID" /><el-input-number v-model="addressStoreyId" :min="1" placeholder="层 ID" /><el-button @click="searchAddresses">查询登记地址</el-button><el-button @click="maintain('LOCATION', 'CREATE', null, addressStoreyId)">新增地址</el-button></div>
      <p v-if="addressError">{{ addressError }}</p><el-table :data="addresses" v-loading="addressLoading" border>
        <el-table-column label="登记地址" min-width="360"><template #default="{ row }"><CopyPlacement :placement="{kind:'REGISTERED',text:null,registeredLocation:row.address}" :show-raw="false" /></template></el-table-column>
        <el-table-column label="旧书目绑定（待核实）" min-width="230"><template #default="{ row }"><p>旧 book_id：{{ row.legacyBinding.bookId ?? 'NULL' }}</p><p>原 status：<code>{{ JSON.stringify(row.legacyBinding.rawStatus) }}</code></p><p>{{ row.legacyBinding.needsReview ? '待核实旧资料' : '无旧绑定待核实项' }}</p></template></el-table-column>
        <el-table-column label="关联实体册数" min-width="145"><template #default="{ row }">{{ row.linkedCopyCount }} 册（含在借及报损）</template></el-table-column>
        <el-table-column label="地址维护与旧绑定" min-width="300"><template #default="{ row }"><el-button link @click="maintain('LOCATION', 'UPDATE', addressOriginal(row))">编辑地址</el-button><el-button v-if="user.isAdmin" link type="danger" @click="maintain('LOCATION', 'DELETE', addressOriginal(row))">删除地址</el-button><el-button v-if="row.address.selectable && row.legacyBinding.bookId === null && row.legacyBinding.rawStatus === 'EMPTY'" link @click="maintain('LOCATION', 'ASSIGN', addressOriginal(row))">绑定旧书目</el-button><el-button v-if="canClear(row)" link type="warning" @click="maintain('LOCATION', 'CLEAR', addressOriginal(row))">解除旧书目绑定</el-button><el-button link @click="showHistory('LOCATION', row.address.locationId)">维护历史</el-button></template></el-table-column>
      </el-table><el-pagination v-model:current-page="addressPage" :page-size="10" :total="addressTotal" layout="total, prev, pager, next" @current-change="loadAddresses" />
    </div>
    <el-dialog v-model="storeyVisible" :title="`层管理 · ${currentShelf?.name || ''} #${currentShelf?.id || ''}`" width="min(1100px,96vw)"><el-button type="primary" @click="maintain('STOREY', 'CREATE', null, currentShelf?.id)">新增书架层</el-button><p v-if="storeyError">{{ storeyError }}</p><el-table :data="storeys" border><el-table-column prop="id" label="层 ID" /><el-table-column prop="levelNum" label="层号" /><el-table-column prop="name" label="层名称" /><el-table-column prop="capacity" label="历史容量资料" /><el-table-column prop="status" label="原启停值" /><el-table-column prop="layoutVersion" label="版本" /><el-table-column label="维护" min-width="290"><template #default="{row}"><el-button link @click="viewStoreyAddresses(row)">查看地址</el-button><el-button link @click="maintain('STOREY','UPDATE',row)">编辑层</el-button><el-button v-if="user.isAdmin" link type="danger" @click="maintain('STOREY','DELETE',row)">删除层</el-button><el-button link @click="showHistory('STOREY',row.id)">维护历史</el-button></template></el-table-column></el-table></el-dialog>
    <StructureMaintenanceDialog v-model:visible="maintenanceVisible" :entity-type="targetType" :operation="targetOperation" :original="targetOriginal" :parent-id="targetParent" @success="refreshed" @refresh="refreshed" />
    <el-dialog v-model="historyVisible" title="地址维护历史" width="min(1000px,96vw)"><StructureEventList :entity-type="historyType" :entity-id="historyId" :visible="historyVisible" :revision="revision" /></el-dialog>
  </div><el-empty v-else description="仅管理员和馆员可查看及维护登记地址。" />
</template>
<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useUserStore } from '@/store/modules/user'
import { getBookshelves, getStoreys } from '@/api/bookshelf'
import { getLocationAddresses, type AddressRow, type StructureEntityType } from '@/api/locationAddress'
import type { Bookshelf, BookshelfStorey } from '@/types'
import CopyPlacement from '@/components/locations/CopyPlacement.vue'
import StructureEventList from '@/components/locations/StructureEventList.vue'
import StructureMaintenanceDialog from './StructureMaintenanceDialog.vue'
const user = useUserStore(), canManage = computed(() => user.isAdmin || user.isLibrarian)
const shelves = ref<Bookshelf[]>([]), keyword = ref(''), page = ref(1), total = ref(0), loading = ref(false), error = ref('')
const addresses = ref<AddressRow[]>([]), addressKeyword = ref(''), addressShelfId = ref<number>(), addressStoreyId = ref<number>(), addressPage = ref(1), addressTotal = ref(0), addressLoading = ref(false), addressError = ref('')
const storeyVisible = ref(false), currentShelf = ref<Bookshelf | null>(null), storeys = ref<BookshelfStorey[]>([]), storeyError = ref('')
const maintenanceVisible = ref(false), targetType = ref<StructureEntityType>('BOOKSHELF'), targetOperation = ref<'CREATE'|'UPDATE'|'DELETE'|'ASSIGN'|'CLEAR'>('CREATE'), targetOriginal = ref<Record<string, unknown> | null>(null), targetParent = ref<number>()
const historyVisible = ref(false), historyType = ref<StructureEntityType>('BOOKSHELF'), historyId = ref(1), revision = ref(0)
let shelfRequest = 0, addressRequest = 0, storeyRequest = 0
async function loadShelves() { if (!canManage.value) return; const current = ++shelfRequest; loading.value = true; error.value = ''; try { const result = await getBookshelves({ page: page.value, size: 10, keyword: keyword.value || undefined }); if (current === shelfRequest) { shelves.value = result.records; total.value = result.total } } catch { if (current === shelfRequest) { shelves.value = []; error.value = '书架读取失败，请重试。' } } finally { if (current === shelfRequest) loading.value = false } }
async function loadAddresses() { if (!canManage.value) return; const current = ++addressRequest; addressLoading.value = true; addressError.value = ''; try { const result = await getLocationAddresses({ page: addressPage.value, size: 10, keyword: addressKeyword.value || undefined, bookshelfId: addressShelfId.value, storeyId: addressStoreyId.value, selectableOnly: false }); if (current === addressRequest) { addresses.value = result.records; addressTotal.value = result.total } } catch { if (current === addressRequest) { addresses.value = []; addressError.value = '地址读取失败，请重试。' } } finally { if (current === addressRequest) addressLoading.value = false } }
function search() { page.value = 1; loadShelves() }
function searchAddresses() { addressPage.value = 1; loadAddresses() }
async function loadStoreys() { if (!canManage.value || !currentShelf.value) return; const current = ++storeyRequest; try { const result = await getStoreys(currentShelf.value.id); if (current === storeyRequest) { storeys.value = result; storeyError.value = '' } } catch { if (current === storeyRequest) storeyError.value = '书架层读取失败，请重试。' } }
function showStoreys(row: Bookshelf) { currentShelf.value = row; storeyVisible.value = true; loadStoreys() }
function viewStoreyAddresses(row: BookshelfStorey) { addressShelfId.value = row.bookshelfId; addressStoreyId.value = row.id; storeyVisible.value = false; searchAddresses() }
function maintain(type: StructureEntityType, operation: typeof targetOperation.value, row: object | null = null, parentId?: number) { if (!canManage.value || (operation === 'DELETE' && !user.isAdmin)) return; targetType.value = type; targetOperation.value = operation; targetOriginal.value = row ? { ...row } : null; targetParent.value = parentId; maintenanceVisible.value = true }
function addressOriginal(row: AddressRow) { return { id: row.address.locationId, layoutVersion: row.address.locationVersion, code: row.address.code, storeyId: row.address.storeyId, status: row.legacyBinding.rawStatus, bookId: row.legacyBinding.bookId, linkedCopyCount: row.linkedCopyCount } }
const canClear = (row: AddressRow) => (row.legacyBinding.bookId !== null || row.legacyBinding.rawStatus === 'OCCUPIED') && ['EMPTY', 'OCCUPIED', 'DISABLED'].includes(row.legacyBinding.rawStatus || '')
function showHistory(type: StructureEntityType, id: number) { historyType.value = type; historyId.value = id; historyVisible.value = true }
function refreshed() { ++revision.value; loadShelves(); loadAddresses(); if (storeyVisible.value) loadStoreys() }
onMounted(() => { loadShelves(); loadAddresses() })
</script>
<style scoped>.toolbar { display: flex; gap: 8px; flex-wrap: wrap; margin: 12px 0; }.toolbar .el-input { width: 250px; }p { font-size: 13px; }code { white-space: pre-wrap; overflow-wrap: anywhere; }.card-container { margin-top: 16px; }</style>
