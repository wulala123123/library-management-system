<template>
  <div v-if="allowed" class="set-page">
    <div class="page-header"><div><h2>套装目录</h2><p>管理有明确依据的成员书目关系；实物配套和成员完整性仍待核实。</p></div><div><el-button v-if="user.isAdmin" @click="router.push('/catalog-set-reviews')">来源待核实</el-button><el-button v-if="user.isAdmin" type="primary" @click="openEditor(null)">新建套装目录</el-button><el-button v-if="user.isAdmin" @click="openEditor(selectedId)">恢复原请求</el-button></div></div>
    <el-alert title="这里的成员按书目种数统计。仍按单册条码借还，不改变实体册、库存、费用、扣次或限额。" :closable="false" type="info" />
    <div class="toolbar"><el-input v-model="keyword" placeholder="查找套装名称" aria-label="查找套装名称" @keyup.enter="search" /><el-select v-model="status" aria-label="套装状态"><el-option label="有效目录" value="ACTIVE" /><el-option label="退役目录" value="RETIRED" /><el-option label="全部目录" value="ALL" /></el-select><el-button @click="search">查询</el-button></div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-table :data="sets" v-loading="loading" row-key="setId">
      <el-table-column label="套装目录" min-width="240"><template #default="{ row }"><el-button link type="primary" @click="selectSet(row.setId)">#{{ row.setId }} {{ row.name }}</el-button></template></el-table-column>
      <el-table-column label="状态"><template #default="{ row }">{{ row.status === 'ACTIVE' ? '有效' : '已退役' }}</template></el-table-column>
      <el-table-column label="已核实成员书目" min-width="230"><template #default="{ row }">当前有效{{ row.effectiveMemberCount }}种 / 关系有效状态{{ row.activeMemberCount }}种 / 退役{{ row.retiredMemberCount }}种<br />完整性未核实</template></el-table-column>
      <el-table-column label="实物配套"><template #default>未核实</template></el-table-column>
      <el-table-column label="操作" width="140"><template #default="{ row }"><el-button link @click="selectSet(row.setId)">成员详情</el-button><el-button v-if="user.isAdmin" link type="primary" @click="openEditor(row.setId)">维护</el-button></template></el-table-column>
    </el-table>
    <el-pagination v-model:current-page="page" :page-size="20" :total="total" layout="total, prev, pager, next" @current-change="load" />
    <section v-if="selectedSet" class="detail">
      <div class="page-header"><h3>#{{ selectedSet.setId }} {{ selectedSet.name }}</h3><el-button v-if="user.isAdmin" @click="openEditor(selectedId)">维护此目录</el-button></div>
      <p class="preserve">备注：{{ selectedSet.note === null ? '未设置' : selectedSet.note === '' ? '空字符串' : selectedSet.note }}</p>
      <p>版本{{ selectedSet.revision }} · {{ selectedSet.status === 'ACTIVE' ? '有效目录' : '已退役目录' }}；已核实成员书目{{ selectedSet.activeMemberCount }}种，完整性未核实；实物配套未核实。</p>
      <el-select v-model="memberStatus" aria-label="成员状态" @change="memberSearch"><el-option label="有效状态关系" value="ACTIVE" /><el-option label="退役关系" value="RETIRED" /><el-option label="全部历史关系" value="ALL" /></el-select>
      <el-table :data="members" row-key="membershipId">
        <el-table-column label="关系编号" prop="membershipId" width="110" /><el-table-column label="成员书目" min-width="260"><template #default="{ row }">#{{ row.bookId }} {{ row.book.displayTitle }}<br />登记名 {{ row.book.catalogTitle }} · ISBN {{ row.book.isbn }}<el-tag v-if="row.book.deleted" type="danger">书目异常</el-tag></template></el-table-column>
        <el-table-column label="关系状态"><template #default="{ row }">{{ row.status === 'ACTIVE' ? '有效' : '已退役' }}</template></el-table-column><el-table-column label="当前有效"><template #default="{ row }">{{ row.effective ? '是' : '否' }}</template></el-table-column>
        <el-table-column label="其他套装"><template #default="{ row }"><el-button link @click="membershipBook = row.bookId; membershipVisible = true">查看多套关系</el-button></template></el-table-column>
      </el-table>
      <el-pagination v-model:current-page="memberPage" :page-size="20" :total="memberTotal" layout="total, prev, pager, next" @current-change="loadMembers" />
      <template v-if="user.isAdmin"><h3>本人办理记录</h3><el-button @click="loadEvents">读取本人记录</el-button><p>仅显示当前管理员本人办理的记录。</p><article v-for="event in events" :key="event.eventId" class="event"><h4>#{{ event.eventId }} {{ operationLabels[event.operation] }} · {{ event.occurredAt }}</h4><p>版本{{ event.beforeRevision }} → {{ event.afterRevision }} · {{ event.reason }}</p><p v-if="event.evidence.set" class="preserve">套装依据：{{ evidenceLabels[event.evidence.set.kind] }} · {{ event.evidence.set.reference }}</p><p v-for="entry in event.evidence.members" :key="entry.bookId" class="preserve">书目 #{{ entry.bookId }}：{{ evidenceLabels[entry.evidence.kind] }} · {{ entry.evidence.reference }}</p></article><el-pagination v-model:current-page="eventPage" :page-size="20" :total="eventTotal" layout="total, prev, pager, next" @current-change="loadEvents" /></template>
    </section>
    <SetEditor v-if="user.isAdmin" :key="user.userInfo?.id" v-model:visible="editorVisible" :set-id="editorId" @success="refresh" />
    <BookSetMemberships v-model:visible="membershipVisible" :book-id="membershipBook" />
  </div>
  <el-empty v-else description="仅管理员和馆员可查看套装目录。" />
</template>
<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useUserStore } from '@/store/modules/user'
import { getCatalogSets, getCatalogSet, getSetMembers, getSetEvents, type SetSummary, type SetSnapshot, type SafeSetMember, type SetEvent, type SetStatus } from '@/api/catalogSet'
import { evidenceLabels, operationLabels, safeId, setProblem } from './setProtocol'
import SetEditor from './SetEditor.vue'
import BookSetMemberships from './BookSetMemberships.vue'
const user = useUserStore(), route = useRoute(), router = useRouter(), allowed = computed(() => user.isAdmin || user.isLibrarian)
const keyword = ref(''), status = ref<SetStatus|'ALL'>('ACTIVE'), page = ref(1), total = ref(0), sets = ref<SetSummary[]>([]), loading = ref(false), error = ref('')
const selectedId = ref<number|null>(null), selectedSet = ref<SetSnapshot|null>(null), members = ref<SafeSetMember[]>([]), memberPage = ref(1), memberTotal = ref(0), memberStatus = ref<SetStatus|'ALL'>('ACTIVE')
const editorVisible = ref(false), editorId = ref<number|null>(null), membershipVisible = ref(false), membershipBook = ref<number|null>(null), events = ref<SetEvent[]>([]), eventPage = ref(1), eventTotal = ref(0)
let listSequence = 0, detailSequence = 0, memberSequence = 0, eventSequence = 0
watch(() => [allowed.value, user.userInfo?.id, user.userInfo?.role], () => { ++listSequence; ++detailSequence; ++memberSequence; ++eventSequence; sets.value = []; members.value = []; selectedSet.value = null; events.value = []; eventTotal.value = 0; editorVisible.value = false; if (allowed.value) { load(); if (selectedId.value) selectSet(selectedId.value) } }, { immediate: true })
watch(() => route.query.setId, value => { const id = typeof value === 'string' && /^[1-9]\d*$/.test(value) ? Number(value) : null; if (id && safeId(id) && allowed.value) selectSet(id) }, { immediate: true })
function search() { page.value = 1; load() }
async function load() { if (!allowed.value) return; const request = ++listSequence; loading.value = true; error.value = ''; try { const result = await getCatalogSets({ page: page.value, size: 20, keyword: keyword.value, status: status.value }); if (request === listSequence && allowed.value) { sets.value = result.records; total.value = result.total } } catch (cause) { if (request === listSequence) error.value = setProblem(cause).message } finally { if (request === listSequence) loading.value = false } }
async function selectSet(id: number) { if (!allowed.value) return; const request = ++detailSequence; ++memberSequence; ++eventSequence; selectedId.value = id; selectedSet.value = null; members.value = []; memberTotal.value = 0; memberPage.value = 1; events.value = []; eventPage.value = 1; eventTotal.value = 0; try { const result = await getCatalogSet(id); if (request === detailSequence && allowed.value) { selectedSet.value = result.set; loadMembers() } } catch (cause) { if (request === detailSequence) error.value = setProblem(cause).message } }
function memberSearch() { memberPage.value = 1; loadMembers() }
async function loadMembers() { if (!selectedId.value || !allowed.value) return; const id = selectedId.value, request = ++memberSequence; try { const result = await getSetMembers(id, memberPage.value, 20, memberStatus.value); if (request === memberSequence && selectedId.value === id && allowed.value) { members.value = result.records; memberTotal.value = result.total } } catch (cause) { if (request === memberSequence) error.value = setProblem(cause).message } }
async function loadEvents() { if (!user.isAdmin || !selectedId.value) return; const id = selectedId.value, owner = user.userInfo?.id, request = ++eventSequence; try { const result = await getSetEvents(id, eventPage.value); if (request === eventSequence && user.isAdmin && user.userInfo?.id === owner && id === selectedId.value) { events.value = result.records; eventTotal.value = result.total } } catch (cause) { if (request === eventSequence) error.value = setProblem(cause).message } }
function openEditor(id: number|null) { if (user.isAdmin) { editorId.value = id; editorVisible.value = true } }
function refresh() { load(); if (selectedId.value) selectSet(selectedId.value) }
</script>
<style scoped>
.page-header,.toolbar{display:flex;justify-content:space-between;gap:12px;margin:16px 0}.toolbar .el-input{max-width:360px}.toolbar .el-select{width:180px}.detail{padding:20px;background:white;border:1px solid #e1e7e5;border-radius:10px;margin-top:24px}.event{padding:12px;border-bottom:1px solid #e1e7e5}.preserve{white-space:pre-wrap;overflow-wrap:anywhere}.el-pagination{margin:16px 0}
</style>
