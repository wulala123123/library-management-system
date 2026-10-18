<template>
  <el-dialog :model-value="visible" title="维护套装目录" width="min(1180px, 96vw)" :close-on-click-modal="false" @update:model-value="emit('update:visible', $event)">
    <template v-if="allowed">
      <el-alert title="套装目录只关联已有成员书目。可分别加入多个套装，每条关系须有依据；实物配套和成员完整性仍待核实，不改变库存、单册借还、费用或扣次。" type="info" :closable="false" />
      <SetRequestPanel :session="session" :draft-matches="draftMatches" @recheck="editRejected" />
      <div class="recovery"><el-input v-model="recoveryKey" placeholder="原操作编号" aria-label="原操作编号" /><el-button :disabled="session.busy.value || !strictSetKey(recoveryKey)" @click="session.recoverKey(recoveryKey)">按编号恢复</el-button></div>
      <el-alert v-if="problem" :title="problem" type="error" :closable="false" />
      <fieldset :disabled="locked || loading || session.denied.value" class="editor-fields">
        <legend>{{ setId ? `本次目标：套装 #${setId}` : '本次新建套装目录' }}</legend>
        <el-form label-width="115px">
          <el-form-item label="办理动作"><el-select v-model="operation" :disabled="locked || loading" aria-label="办理动作" @change="actionChanged"><el-option v-for="op in availableOperations" :key="op" :label="operationLabels[op]" :value="op" /></el-select></el-form-item>
          <p v-if="currentSet">当前：{{ currentSet.name }} · {{ currentSet.status === 'ACTIVE' ? '有效' : '已退役' }} · 版本{{ currentSet.revision }}；有效状态成员{{ currentSet.activeMemberCount }}种 / 退役成员{{ currentSet.retiredMemberCount }}种。</p>
          <template v-if="operation === 'CREATE' || operation === 'UPDATE_METADATA'">
            <el-form-item v-if="operation === 'UPDATE_METADATA'" label="名称处理"><el-select v-model="nameAction" :disabled="locked" aria-label="名称处理"><el-option label="保持" value="KEEP" /><el-option label="设置" value="SET" /></el-select></el-form-item>
            <el-form-item v-if="operation === 'CREATE' || nameAction === 'SET'" label="套装名称"><el-input v-model="name" :disabled="locked" aria-label="套装名称" /></el-form-item>
            <el-form-item label="备注处理"><el-select v-model="noteAction" :disabled="locked" aria-label="备注处理"><el-option v-if="operation !== 'CREATE'" label="保持" value="KEEP" /><el-option label="设置（空字符串保留）" value="SET" /><el-option label="未设置" value="CLEAR" /></el-select></el-form-item>
            <el-form-item v-if="noteAction === 'SET'" label="备注"><el-input v-model="note" type="textarea" :disabled="locked" aria-label="套装备注" /></el-form-item>
          </template>
          <el-form-item v-if="hasSetEvidence" label="套装依据"><el-select v-model="setEvidence.kind" :disabled="locked" aria-label="套装依据类型"><el-option v-for="(label, kind) in evidenceLabels" :key="kind" :label="label" :value="kind" /></el-select><el-input v-model="setEvidence.reference" type="textarea" :disabled="locked" aria-label="套装依据" placeholder="填写本次套装资料或整体状态的核对依据" /></el-form-item>
          <el-form-item label="办理原因"><el-input v-model="reason" type="textarea" :disabled="locked" aria-label="办理原因" /></el-form-item>
        </el-form>
        <template v-if="hasMembers">
          <h3>明确选择本笔成员（{{ selected.size }} / 100）</h3>
          <p>跨页保留选择。当前页和本笔选择都不是套装全集；未列入本笔的关系保持原样。</p>
          <div class="toolbar"><el-input v-if="isNewMembers" v-model="keyword" :disabled="locked" placeholder="查找已有书目" aria-label="查找已有书目" @keyup.enter="search" /><el-button :disabled="locked || loading" @click="search">查询成员</el-button><el-button :disabled="locked || loading" @click="selectPage">选择当前页</el-button></div>
          <el-table :data="candidates" row-key="bookId" max-height="320">
            <el-table-column label="选择" width="70"><template #default="{ row }"><el-checkbox :model-value="selected.has(row.bookId)" :disabled="locked || loading" :aria-label="`选择书目${row.bookId}`" @change="toggle(row)" /></template></el-table-column>
            <el-table-column label="书目" min-width="240"><template #default="{ row }">#{{ row.bookId }} {{ row.context.book.displayTitle }}<br />登记名 {{ row.context.book.catalogTitle }} · ISBN {{ row.context.book.isbn }}</template></el-table-column>
            <el-table-column label="当前关系 / 核对版本" min-width="170"><template #default="{ row }">{{ row.member ? `关系 #${row.member.membershipId} / 版本${row.member.revision}` : '以本次明确选择增加' }}<br />书目{{ row.context.book.revision }} / 展示{{ row.context.book.attributeRevision }}</template></el-table-column>
            <el-table-column v-if="!isNewMembers" label="当前关系依据" min-width="240"><template #default="{ row }">{{ evidenceLabels[row.member.evidence.kind as keyof typeof evidenceLabels] }} · <span class="preserve">{{ row.member.evidence.reference }}</span></template></el-table-column>
          </el-table>
          <el-pagination v-model:current-page="page" :page-size="20" :total="total" layout="total, prev, pager, next" @current-change="loadCandidates" />
          <el-table :data="Array.from(selected.values())" row-key="bookId" max-height="420">
            <el-table-column label="本笔已选书目" min-width="190"><template #default="{ row }">#{{ row.bookId }} {{ row.context.book.displayTitle }}<p v-if="row.needsReview">当前书目或关系已变化，请移除后重新选择。</p></template></el-table-column>
            <el-table-column label="本次独立依据" min-width="350"><template #default="{ row }"><el-select v-model="row.evidence.kind" :disabled="locked" :aria-label="`书目${row.bookId}依据类型`"><el-option v-for="(label, kind) in evidenceLabels" :key="kind" :label="label" :value="kind" /></el-select><el-input v-model="row.evidence.reference" type="textarea" :disabled="locked" :aria-label="`书目${row.bookId}依据`" placeholder="逐条填写明确收录或退役/恢复的依据" /></template></el-table-column>
            <el-table-column width="80"><template #default="{ row }"><el-button :disabled="locked" link @click="selected.delete(row.bookId)">移除</el-button></template></el-table-column>
          </el-table>
        </template>
        <el-button :disabled="locked || loading || session.denied.value" @click="recheck">重新读取核对资料</el-button>
        <el-button type="primary" :disabled="locked || loading || session.busy.value || session.denied.value || !validDraft" @click="preview">预览本笔全部变更</el-button>
      </fieldset>
      <p v-if="locked">原请求尚未结束。先查询结果或安全取消，再修改资料。</p>
    </template>
    <el-empty v-else description="仅原操作者且当前仍为管理员可维护或恢复请求。" />
    <template #footer><el-button @click="emit('update:visible', false)">关闭窗口</el-button></template>
  </el-dialog>
</template>
<script setup lang="ts">
import { computed, ref, reactive, watch, onBeforeUnmount } from 'vue'
import { useUserStore } from '@/store/modules/user'
import { getCatalogBooks } from '@/api/catalogAttribute'
import { getCatalogSet, getMaintenanceMembers, getSetBookContexts, type ExistingSetMember, type SetBookContext, type SetMemberSnapshot, type SetOperation, type SetPreviewInput, type SetSnapshot } from '@/api/catalogSet'
import { assertSetContexts, emptySetEvidence, evidenceLabels, expectedSetBook, operationLabels, setCanonical, setProblem, strictSetKey, validSetInput } from './setProtocol'
import { useSetSession } from './setSession'
import SetRequestPanel from './SetRequestPanel.vue'
const props = defineProps<{ visible: boolean; setId: number | null }>()
const emit = defineEmits<{ (e: 'update:visible', value: boolean): void; (e: 'success'): void }>()
const user = useUserStore(), ownerId = user.userInfo?.id ?? 0
const allowed = computed(() => user.isAdmin && user.userInfo?.id === ownerId)
const session = useSetSession(ownerId, () => allowed.value)
onBeforeUnmount(session.dispose)
const operation = ref<SetOperation>('CREATE'), currentSet = ref<SetSnapshot | null>(null), setDigest = ref(''), loading = ref(false), problem = ref(''), recoveryKey = ref('')
const name = ref(''), note = ref(''), reason = ref(''), clientRef = ref('new_set'), nameAction = ref<'KEEP' | 'SET'>('KEEP'), noteAction = ref<'KEEP' | 'SET' | 'CLEAR'>('CLEAR'), setEvidence = reactive(emptySetEvidence()), rechecked = ref(false)
interface Choice { bookId: number; context: SetBookContext; member: SetMemberSnapshot | null; memberDigest: string | null; evidence: ReturnType<typeof emptySetEvidence>; needsReview?: boolean }
const selected = reactive(new Map<number, Choice>()), candidates = ref<Choice[]>([]), keyword = ref(''), page = ref(1), total = ref(0)
const locked = computed(() => !!session.phase.value && !['committed', 'cancelled', 'editing'].includes(session.phase.value))
const availableOperations = computed<SetOperation[]>(() => !props.setId ? ['CREATE'] : ['UPDATE_METADATA', ...(currentSet.value?.status === 'RETIRED' ? ['RESTORE_SET'] as SetOperation[] : ['ADD_MEMBERS', 'RETIRE_MEMBERS', 'RESTORE_MEMBERS', 'RETIRE_SET'] as SetOperation[])])
const hasMembers = computed(() => ['CREATE', 'ADD_MEMBERS', 'RETIRE_MEMBERS', 'RESTORE_MEMBERS'].includes(operation.value)), isNewMembers = computed(() => ['CREATE', 'ADD_MEMBERS'].includes(operation.value)), hasSetEvidence = computed(() => ['CREATE', 'UPDATE_METADATA', 'RETIRE_SET', 'RESTORE_SET'].includes(operation.value))
let generation = 0
function draft(key: string): SetPreviewInput | null {
  const header = { schemaVersion: 1 as const, operationKey: key, mode: 'MANUAL' as const, operation: operation.value, reason: reason.value }
  const members = [...selected.values()].sort((a,b) => a.bookId-b.bookId).map(row => ({ bookId: row.bookId, expectedBook: expectedSetBook(row.context), evidence: { ...row.evidence } }))
  if (operation.value === 'CREATE') return { ...header, operation: 'CREATE', clientRef: clientRef.value, name: name.value, note: noteAction.value === 'CLEAR' ? null : note.value, evidence: { ...setEvidence }, members }
  if (!currentSet.value || !props.setId) return null
  const target = { ...header, setId: props.setId, expectedSet: { revision: currentSet.value.revision, digest: setDigest.value } }
  if (operation.value === 'UPDATE_METADATA') return { ...target, operation: operation.value, changes: { name: nameAction.value === 'KEEP' ? { action: 'KEEP' } : { action: 'SET', value: name.value }, note: noteAction.value === 'SET' ? { action: 'SET', value: note.value } : { action: noteAction.value } }, evidence: { ...setEvidence } }
  if (operation.value === 'ADD_MEMBERS') return { ...target, operation: operation.value, members }
  if (operation.value === 'RETIRE_MEMBERS' || operation.value === 'RESTORE_MEMBERS') return { ...target, operation: operation.value, members: members.map(row => { const choice = selected.get(row.bookId)!; return { ...row, membershipId: choice.member?.membershipId ?? 0, expectedMember: { revision: choice.member?.revision ?? 0, digest: choice.memberDigest ?? '' } } }) }
  return { ...target, operation: operation.value, evidence: { ...setEvidence } }
}
const testKey = '11111111-1111-4111-8111-111111111111'
const validDraft = computed(() => rechecked.value && [...selected.values()].every(row => !row.needsReview) && validSetInput(draft(testKey)))
const draftMatches = computed(() => { const original = session.input.value; return !!original && setCanonical(draft(original.operationKey)) === setCanonical(original) })
function hydrate(input: SetPreviewInput) {
  operation.value = input.operation; reason.value = input.reason
  if ('evidence' in input) Object.assign(setEvidence, input.evidence)
  if (input.operation === 'CREATE') { clientRef.value = input.clientRef; name.value = input.name; note.value = input.note ?? ''; noteAction.value = input.note === null ? 'CLEAR' : 'SET' }
  if (input.operation === 'UPDATE_METADATA') { nameAction.value = input.changes.name.action as 'KEEP'|'SET'; if (input.changes.name.action === 'SET') name.value = input.changes.name.value; noteAction.value = input.changes.note.action; if (input.changes.note.action === 'SET') note.value = input.changes.note.value }
  if ('members' in input && session.view.value) input.members.forEach(row => { const context = session.view.value!.preview.books.find(book => book.book.bookId === row.bookId); if (context) selected.set(row.bookId, { bookId: row.bookId, context, evidence: { ...row.evidence }, member: session.view.value!.preview.before.members.find(member => member.bookId === row.bookId)?.member ?? null, memberDigest: 'expectedMember' in row ? (row as ExistingSetMember).expectedMember.digest : null }) })
}
watch(() => [props.visible, props.setId, allowed.value], async () => {
  const request = ++generation; candidates.value = []; currentSet.value = null; problem.value = ''; loading.value = false; rechecked.value = false
  if (!props.visible || !allowed.value) return
  selected.clear(); page.value = 1; total.value = 0; keyword.value = ''; name.value = ''; note.value = ''; reason.value = ''; nameAction.value = 'KEEP'; noteAction.value = 'CLEAR'; Object.assign(setEvidence, emptySetEvidence()); operation.value = props.setId ? 'UPDATE_METADATA' : 'CREATE'
  loading.value = true
  try {
    if (props.setId) { const result = await getCatalogSet(props.setId); if (request !== generation || !allowed.value) return; currentSet.value = result.set; setDigest.value = result.setDigest; name.value = result.set.name; note.value = result.set.note ?? ''; noteAction.value = 'KEEP' }
    const original = session.input.value
    if (original && (original.operation === 'CREATE' ? props.setId === null : original.setId === props.setId)) hydrate(original)
    if (hasMembers.value) await loadCandidates()
    if (request === generation) rechecked.value = true
  } catch (cause) { if (request === generation) problem.value = setProblem(cause).message } finally { if (request === generation) loading.value = false }
}, { immediate: true })
watch(() => session.phase.value, (value, old) => { if (value === 'committed' && old !== 'committed') emit('success') })
watch(() => session.view.value?.requestId, () => { const original = session.input.value; if (props.visible && original && (original.operation === 'CREATE' ? props.setId === null : original.setId === props.setId)) { selected.clear(); hydrate(original) } })
async function loadCandidates() {
  const request = generation, op = operation.value, currentPage = page.value, target = props.setId; if (!allowed.value || !hasMembers.value) return
  loading.value = true; problem.value = ''
  try {
    let records: Choice[], count: number
    if (isNewMembers.value) { const books = await getCatalogBooks({ page: currentPage, size: 20, keyword: keyword.value }); const contexts = books.records.length ? await getSetBookContexts(books.records.map(book => book.id)) : { schemaVersion: 1 as const, records: [] }; assertSetContexts(contexts); records = contexts.records.map(context => ({ bookId: context.book.bookId, context, member: null, memberDigest: null, evidence: emptySetEvidence() })); count = books.total }
    else { const result = await getMaintenanceMembers(target!, currentPage, 20, op === 'RETIRE_MEMBERS' ? 'ACTIVE' : 'RETIRED'); records = result.records.map(row => ({ bookId: row.member.bookId, context: row.book, member: row.member, memberDigest: row.memberDigest, evidence: emptySetEvidence() })); count = result.total }
    if (request !== generation || op !== operation.value || currentPage !== page.value || !allowed.value) return
    candidates.value = records; total.value = count
  } catch (cause) { if (request === generation) problem.value = setProblem(cause).message } finally { if (request === generation) loading.value = false }
}
function actionChanged() { ++generation; selected.clear(); candidates.value = []; page.value = 1; total.value = 0; if (hasMembers.value) loadCandidates() }
function search() { ++generation; page.value = 1; loadCandidates() }
function add(row: Choice) { if (selected.has(row.bookId)) return; if (selected.size >= 100) { problem.value = '本笔最多100种书目，请分笔办理；已有选择已保留。'; return } selected.set(row.bookId, { ...row, evidence: emptySetEvidence() }) }
function toggle(row: Choice) { if (locked.value) return; if (selected.has(row.bookId)) selected.delete(row.bookId); else add(row) }
function selectPage() { if (!locked.value) candidates.value.forEach(add) }
async function preview() { if (!allowed.value || locked.value || loading.value) return; const input = draft(crypto.randomUUID()); if (input) await session.start(input) }
async function editRejected() {
  if (!props.visible || !allowed.value) return
  const request = generation, target = props.setId, action = operation.value, key = session.input.value?.operationKey
  const current = () => request === generation && props.visible && allowed.value && props.setId === target && operation.value === action && session.input.value?.operationKey === key
  loading.value = true
  try {
    await session.editRejected()
    if (!current() || session.phase.value !== 'editing') return
    const original = session.input.value
    if (original && 'members' in original && !selected.size && original.members.length && (original.operation === 'CREATE' ? target === null : original.setId === target)) {
      const result = await getSetBookContexts(original.members.map(row => row.bookId))
      if (!current()) return
      assertSetContexts(result)
      result.records.forEach(context => { const row = original.members.find(row => row.bookId === context.book.bookId)!; selected.set(row.bookId,{ bookId:row.bookId,context,evidence:{...row.evidence},member:null,memberDigest:null,needsReview:true }) })
    }
    if (!current()) return
    await recheck()
  } catch(cause) { if (current()) problem.value=setProblem(cause).message }
  finally { if (current()) loading.value=false }
}
async function recheck() {
  if (locked.value || !allowed.value) return
  const request = ++generation; loading.value = true; rechecked.value = false; problem.value = ''
  try {
    if (props.setId) { const result = await getCatalogSet(props.setId); if (request !== generation || !allowed.value) return; currentSet.value = result.set; setDigest.value = result.setDigest }
    const ids = [...selected.keys()].sort((a,b)=>a-b)
    if (ids.length && isNewMembers.value) { const result = await getSetBookContexts(ids); assertSetContexts(result); if (request !== generation || !allowed.value) return; result.records.forEach(context => { const selectedRow = selected.get(context.book.bookId); if (selectedRow) { selectedRow.context = context; selectedRow.needsReview = context.book.deleted } }) }
    else if (ids.length && props.setId) {
      const wanted = new Set(ids); let currentPage = 1, pages = 1
      do { const result = await getMaintenanceMembers(props.setId, currentPage, 100, 'ALL'); if (request !== generation || !allowed.value) return; pages = result.pages; result.records.forEach(row => { const selectedRow = selected.get(row.member.bookId); if (selectedRow) { selectedRow.context = row.book; selectedRow.member = row.member; selectedRow.memberDigest = row.memberDigest; selectedRow.needsReview = row.book.book.deleted || row.member.status !== (operation.value === 'RETIRE_MEMBERS' ? 'ACTIVE' : 'RETIRED'); wanted.delete(row.member.bookId) } }); currentPage++ } while (currentPage <= pages && wanted.size)
      wanted.forEach(id => { selected.get(id)!.needsReview = true })
    }
    if (hasMembers.value) await loadCandidates()
    if (request === generation) rechecked.value = true
  } catch(cause) { if(request===generation) problem.value = setProblem(cause).message } finally { if(request===generation) loading.value=false }
}
</script>
<style scoped>
.editor-fields{border:1px solid #dce5df;border-radius:10px;padding:18px;margin-top:18px}.toolbar,.recovery{display:flex;gap:10px;margin:16px 0}.recovery .el-input{max-width:440px}.preserve{white-space:pre-wrap;overflow-wrap:anywhere}.el-select{min-width:230px}.el-pagination{margin:14px 0}.el-table{margin-bottom:16px}
</style>
