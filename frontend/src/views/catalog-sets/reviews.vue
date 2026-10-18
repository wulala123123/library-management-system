<template>
  <div v-if="user.isAdmin">
    <div class="page-header"><h2>套装来源待核实</h2><el-button @click="router.push('/catalog-sets')">套装目录</el-button></div>
    <el-alert title="来源标记只供核对，全部来源仍待核实。已有人工关系不代表来源已全部处理，不会自动建立套装或实物配套。" :closable="false" type="warning" />
    <el-form inline class="filters">
      <el-form-item label="来源范围"><el-select v-model="query.scope" aria-label="来源范围"><el-option label="含套装中单本标记" value="MEMBER_MARKER" /><el-option label="全部（含无来源与孤立来源）" value="ALL" /></el-select></el-form-item>
      <el-form-item label="关键词"><el-input v-model="query.keyword" placeholder="登记名、展示名或ISBN" @keyup.enter="search" /></el-form-item>
      <el-form-item label="待核实原因（任一）"><el-select v-model="query.reason" multiple clearable aria-label="待核实原因"><el-option v-for="reason in reviewReasons" :key="reason" :label="reasonLabels[reason]" :value="reason" /></el-select></el-form-item>
      <el-form-item label="来源值状态（任一）"><el-select v-model="query.markerState" multiple clearable aria-label="来源值状态"><el-option v-for="state in markerStates" :key="state" :label="markerLabels[state]" :value="state" /></el-select></el-form-item>
      <el-form-item label="来源关联"><el-select v-model="query.associationState" clearable><el-option label="无来源" value="NO_SOURCE" /><el-option label="有效" value="VALID" /><el-option label="异常" value="ASSOCIATION_INVALID" /></el-select></el-form-item>
      <el-form-item label="人工目录关系"><el-select v-model="query.relationshipState" clearable><el-option label="无关系" value="NONE" /><el-option label="有当前有效关系" value="ACTIVE" /><el-option label="仅历史关系" value="HISTORICAL" /></el-select></el-form-item>
      <el-form-item label="书目状态"><el-select v-model="query.bookDeleted"><el-option label="正常书目" value="EXCLUDE" /><el-option label="仅删除异常" value="ONLY" /><el-option label="全部（含孤立来源）" value="ALL" /></el-select></el-form-item>
      <el-form-item><el-button type="primary" @click="search">查询</el-button></el-form-item>
    </el-form>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-table :data="rows" row-key="caseKey" v-loading="loading">
      <el-table-column label="待核实对象" min-width="240"><template #default="{ row }"><template v-if="row.book">#{{ row.book.bookId }} {{ row.book.displayTitle }}<br />登记名 {{ row.book.catalogTitle }} · ISBN {{ row.book.isbn }}<el-tag v-if="row.book.deleted" type="danger">书目异常</el-tag></template><template v-else>孤立来源，未关联现存书目</template></template></el-table-column>
      <el-table-column label="来源概况" min-width="180"><template #default="{ row }">{{ row.source.sourceCount }}条 · {{ markerLabels[row.source.markerState as keyof typeof markerLabels] }}<br />{{ row.source.associationState === 'VALID' ? '关联有效' : row.source.associationState === 'NO_SOURCE' ? '无来源' : '关联异常' }}<br />读取于 {{ row.source.readAt }}</template></el-table-column>
      <el-table-column label="待核实原因" min-width="240"><template #default="{ row }"><p v-for="reason in row.source.reasons" :key="reason">{{ reasonLabels[reason as keyof typeof reasonLabels] }}</p></template></el-table-column>
      <el-table-column label="人工关系 / 来源结论" min-width="180"><template #default="{ row }">当前有效套装{{ row.activeSetCount }}个<br />历史关系{{ row.historicalMembershipCount }}条<br />来源待核实 · 实物配套未核实</template></el-table-column>
      <el-table-column label="核对" width="150"><template #default="{ row }"><el-button link @click="openSources(row)">查看来源值</el-button><el-button v-if="row.book" link @click="membershipBook = row.book.bookId; membershipVisible = true">人工套装关系</el-button></template></el-table-column>
    </el-table>
    <el-pagination v-model:current-page="query.page" :page-size="20" :total="total" layout="total, prev, pager, next" @current-change="load" />
    <el-dialog v-model="sourcesVisible" title="套装标记来源核对" width="min(1100px, 96vw)">
      <p>{{ sourceTitle }} · 来源仍待核实；仅展示套装类型白名单资料。</p>
      <el-alert v-if="sourceError" :title="sourceError" type="error" :closable="false" />
      <el-table :data="sources" row-key="recordId" v-loading="sourceLoading">
        <el-table-column label="来源记录" min-width="160"><template #default="{ row }">#{{ row.recordId }}<br />来源键 <span class="preserve">{{ row.sourceKey }}</span><br />来源书目 #{{ row.sourceBookId ?? '无' }}{{ row.sourceBookDeleted ? '（已删除）' : '' }}</template></el-table-column>
        <el-table-column label="精确匹配实体册" min-width="170"><template #default="{ row }"><p v-for="copy in row.copyMatches" :key="copy.copyId">册 #{{ copy.copyId }} / 书目 #{{ copy.bookId }}{{ copy.deleted ? '（已删除）' : '' }}</p><span v-if="!row.copyMatches.length">无匹配</span></template></el-table-column>
        <el-table-column label="来源值" min-width="240"><template #default="{ row }">根类型 {{ row.rootKind }} · {{ row.cell.kind }}<p class="preserve">{{ sourceValue(row) }}</p><small>值摘要 {{ row.cell.valueSha256 }}</small></template></el-table-column>
        <el-table-column label="关联状态"><template #default="{ row }">{{ row.associationState === 'VALID' ? '有效' : '异常' }}</template></el-table-column>
      </el-table>
      <el-pagination v-model:current-page="sourcePage" :page-size="20" :total="sourceTotal" layout="total, prev, pager, next" @current-change="loadSources" />
    </el-dialog>
    <BookSetMemberships v-model:visible="membershipVisible" :book-id="membershipBook" />
  </div>
  <el-empty v-else description="仅管理员可读取套装来源待核实资料。" />
</template>
<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '@/store/modules/user'
import { getSetReviews, getSetReviewSources, markerStates, reviewReasons, type SetReviewQuery, type SetReviewCase, type SetSourceRecord } from '@/api/catalogSet'
import { markerLabels, reasonLabels, setProblem } from './setProtocol'
import BookSetMemberships from './BookSetMemberships.vue'
const user = useUserStore(), router = useRouter(), query = reactive<SetReviewQuery>({ page: 1, size: 20, scope: 'MEMBER_MARKER', keyword: '', reason: [], markerState: [], bookDeleted: 'EXCLUDE' })
const rows = ref<SetReviewCase[]>([]), total = ref(0), loading = ref(false), error = ref(''), sourcesVisible = ref(false), sourceTitle = ref(''), caseKey = ref(''), sourcePage = ref(1), sourceTotal = ref(0), sources = ref<SetSourceRecord[]>([]), sourceLoading = ref(false), sourceError = ref(''), membershipVisible = ref(false), membershipBook = ref<number|null>(null)
let sequence = 0, sourceSequence = 0
watch(() => [user.isAdmin, user.userInfo?.id], () => { ++sequence; ++sourceSequence; rows.value = []; sources.value = []; sourcesVisible.value = false; total.value = 0; if (user.isAdmin) load() }, { immediate: true })
watch(sourcesVisible, value => { if (!value) { ++sourceSequence; sources.value = [] } })
function search() { query.page = 1; load() }
async function load() { if (!user.isAdmin) return; const request = ++sequence, owner = user.userInfo?.id; loading.value = true; error.value = ''; try { const params = { ...query, associationState: query.associationState || undefined, relationshipState: query.relationshipState || undefined }; const result = await getSetReviews(params); if (request === sequence && user.isAdmin && user.userInfo?.id === owner) { rows.value = result.records; total.value = result.total } } catch (cause) { if (request === sequence) error.value = setProblem(cause).message } finally { if (request === sequence) loading.value = false } }
function openSources(row: SetReviewCase) { if (!user.isAdmin) return; caseKey.value = row.caseKey; sourceTitle.value = row.book ? `书目 #${row.book.bookId} ${row.book.displayTitle}` : '孤立来源'; sources.value = []; sourcePage.value = 1; sourceTotal.value = 0; sourcesVisible.value = true; loadSources() }
async function loadSources() { if (!user.isAdmin || !sourcesVisible.value) return; const request = ++sourceSequence, owner = user.userInfo?.id, target = caseKey.value; sourceLoading.value = true; sourceError.value = ''; try { const result = await getSetReviewSources(target, sourcePage.value); if (request === sourceSequence && target === caseKey.value && user.isAdmin && user.userInfo?.id === owner && sourcesVisible.value) { sources.value = result.records; sourceTotal.value = result.total } } catch (cause) { if (request === sourceSequence) sourceError.value = setProblem(cause).message } finally { if (request === sourceSequence) sourceLoading.value = false } }
function sourceValue(row: SetSourceRecord) { if (row.cell.kind !== 'STRING') return row.cell.kind === 'MISSING' ? '字段缺失' : row.cell.kind === 'NULL' ? '原值NULL' : '非字符串，仅保留类型与摘要'; return row.cell.rawValue === null ? '长字符串，仅保留摘要' : row.cell.rawValue === '' ? '原值为空字符串' : row.cell.rawValue }
</script>
<style scoped>
.page-header{display:flex;align-items:center;justify-content:space-between}.filters{margin:20px 0}.el-select{min-width:230px}.preserve{white-space:pre-wrap;overflow-wrap:anywhere}small{overflow-wrap:anywhere}.el-pagination{margin:16px 0}
</style>
