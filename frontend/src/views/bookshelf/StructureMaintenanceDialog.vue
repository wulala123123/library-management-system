<template>
  <el-dialog :model-value="visible" :title="title" width="min(850px, 96vw)" append-to-body :close-on-click-modal="false" :close-on-press-escape="!busy && !needsReview" :before-close="beforeClose">
    <template v-if="canManage">
      <el-alert v-if="error" :title="error" type="error" :closable="false" />
      <el-alert v-if="needsReview" title="结果或原版本需要核对。输入已保留，请查看当前原值及最新事件；系统不会自动重复提交。" type="warning" :closable="false" />
      <section v-if="currentOriginal"><h4>本次待改原值</h4><p>ID {{ currentOriginal.id }} · 版本 {{ currentOriginal.layoutVersion }}</p><p v-if="entityType === 'LOCATION'">旧 book_id：<code>{{ JSON.stringify(currentOriginal.bookId ?? null) }}</code>；原 status：<code>{{ JSON.stringify(currentOriginal.status) }}</code>。解除绑定只处理旧书目字段，关联实体册保留；停用地址仍保持停用，未知原状态拒绝。</p><details><summary>展开完整原值</summary><pre>{{ JSON.stringify(currentOriginal, null, 2) }}</pre></details></section>
      <fieldset :disabled="busy || needsReview" class="draft"><el-form label-width="130px">
        <template v-if="activeOperation === 'CREATE' || activeOperation === 'UPDATE'">
          <el-form-item v-if="entityType === 'STOREY'" label="所属书架 ID"><el-input-number v-model="form.bookshelfId" :min="1" /></el-form-item>
          <el-form-item v-if="entityType === 'LOCATION'" label="所属层 ID"><el-input-number v-model="form.storeyId" :min="1" /></el-form-item>
          <el-form-item v-if="entityType !== 'STOREY'" label="编号"><el-input v-model="form.code" aria-label="维护编号" /></el-form-item>
          <el-form-item v-if="entityType !== 'LOCATION'" label="名称"><el-input v-model="form.name" aria-label="维护名称" /></el-form-item>
          <template v-if="entityType === 'BOOKSHELF'">
            <el-form-item label="部门 ID"><el-input-number v-model="form.deptId" :min="1" /><el-button @click="form.deptId = undefined">明确无部门</el-button></el-form-item>
            <el-form-item label="书架描述"><el-input v-model="form.location" :disabled="form.locationNull" /><el-checkbox v-model="form.locationNull">明确 NULL</el-checkbox></el-form-item>
          </template>
          <el-form-item v-if="entityType === 'STOREY'" label="层号"><el-input-number v-model="form.levelNum" /></el-form-item>
          <el-form-item v-if="entityType !== 'LOCATION'" label="历史容量资料"><el-input-number v-model="form.capacity" :min="1" :max="99999" /><span>不限制关联册数</span></el-form-item>
          <el-form-item label="状态"><el-select v-if="entityType === 'LOCATION'" v-model="form.locationStatus" aria-label="地址原状态"><el-option value="EMPTY" label="EMPTY（地址可用）" /><el-option v-if="activeOperation === 'UPDATE'" value="OCCUPIED" label="OCCUPIED（旧绑定标记）" /><el-option value="DISABLED" label="DISABLED（地址停用）" /></el-select><el-select v-else v-model="form.status" aria-label="结构启停状态"><el-option :value="1" label="启用" /><el-option :value="0" label="停用" /></el-select></el-form-item>
        </template>
        <el-form-item v-if="activeOperation === 'ASSIGN'" label="旧绑定书目 ID"><el-input-number v-model="form.bookId" :min="1" /></el-form-item>
        <el-form-item label="本次维护依据"><el-input v-model="form.reason" type="textarea" :rows="2" aria-label="本次维护依据" /></el-form-item>
      </el-form></fieldset>
      <section v-if="readback"><h4>重新查询结果（请人工核对）</h4><pre>{{ JSON.stringify(readback.current, null, 2) }}</pre><article v-for="event in readback.events" :key="event.id"><p>最新事件 {{ event.id }} · {{ event.operation }} · 操作人 {{ event.operatorId }} · {{ event.occurredAt }} · 版本 {{ event.beforeVersion ?? 'NULL' }} → {{ event.afterVersion }}</p><p>依据：<code>{{ JSON.stringify(event.reason) }}</code></p><details><summary>事件原值及新值</summary><pre>{{ JSON.stringify({ before: event.before, after: event.after }, null, 2) }}</pre></details></article></section>
    </template><el-empty v-else description="仅管理员和馆员可维护地址。" />
    <template #footer><el-button :disabled="busy || needsReview" @click="close">关闭</el-button><el-button v-if="needsReview" :disabled="busy" @click="session.recover">重读当前原值与最新事件</el-button><el-button v-if="needsReview && readback?.current && readback.current.deleted === 0" :disabled="busy" @click="reviewCurrent">已核对，以当前版本继续编辑</el-button><el-button v-if="needsReview && readback?.current" :disabled="busy" @click="finishReview">已核对当前记录，结束本次维护</el-button><el-button v-if="canManage && !needsReview" type="primary" :disabled="busy || (activeOperation === 'DELETE' && !user.isAdmin)" :loading="busy" @click="submit">{{ activeOperation === 'DELETE' ? '按原值删除' : activeOperation === 'CLEAR' ? '解除上述旧书目绑定' : '提交本次维护' }}</el-button></template>
  </el-dialog>
</template>
<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useUserStore } from '@/store/modules/user'
import { getBookshelves, getBookshelfById, getStoreys, getLocations, createBookshelf, updateBookshelf, deleteBookshelf, createStorey, updateStorey, deleteStorey, createLocation, updateLocation, deleteLocation, assignBookToLocation, clearLocation } from '@/api/bookshelf'
import { getStructureEvents, type StructureEntityType, type StructureSnapshot } from '@/api/locationAddress'
import { useLocationMaintenanceSession, type MaintenanceReadback } from './locationMaintenanceSession'
const props = defineProps<{ visible: boolean; entityType: StructureEntityType; operation: 'CREATE' | 'UPDATE' | 'DELETE' | 'ASSIGN' | 'CLEAR'; original: Record<string, unknown> | null; parentId?: number }>()
const emit = defineEmits<{ 'update:visible': [value: boolean]; success: []; refresh: [] }>()
const user = useUserStore(), canManage = computed(() => user.isAdmin || user.isLibrarian)
const entityNames = { BOOKSHELF: '书架', STOREY: '书架层', LOCATION: '地址' }, operationNames = { CREATE: '新增', UPDATE: '编辑', DELETE: '删除', ASSIGN: '绑定旧书目', CLEAR: '解除旧书目绑定' }
const title = computed(() => ['ASSIGN', 'CLEAR'].includes(activeOperation.value) ? operationNames[activeOperation.value] : `${operationNames[activeOperation.value]}${entityNames[props.entityType]}`)
const form = reactive({ code: '', name: '', deptId: undefined as number | undefined, location: '', locationNull: false, capacity: 50, status: 1, bookshelfId: undefined as number | undefined, storeyId: undefined as number | undefined, levelNum: 1, locationStatus: 'EMPTY', bookId: undefined as number | undefined, reason: '' })
const session = useLocationMaintenanceSession(), { busy, needsReview, error, readback } = session
const currentOriginal = ref<Record<string, unknown> | null>(null)
const activeOperation = ref(props.operation)
watch(() => props.visible, visible => { if (!visible || !session.reset()) return; activeOperation.value = props.operation; const row = props.original; currentOriginal.value = row ? { ...row } : null; Object.assign(form, { code: row?.code ?? '', name: row?.name ?? '', deptId: row?.deptId ?? undefined, location: row?.location ?? '', locationNull: !!row && (row.location === null || row.location === undefined), capacity: row?.capacity ?? 50, status: row?.status ?? 1, bookshelfId: row?.bookshelfId ?? props.parentId, storeyId: row?.storeyId ?? props.parentId, levelNum: row?.levelNum ?? 1, locationStatus: row?.status ?? 'EMPTY', bookId: undefined, reason: '' }) }, { immediate: true })
const validText = (text: string, max: number, blank = false) => (blank || !!text.trim()) && [...text].length <= max && !/[\p{Cc}\p{Cs}]/u.test(text)
async function readCurrent(target: Record<string, unknown> | null, draft: typeof form): Promise<MaintenanceReadback> {
  let id = target?.id as number | undefined, row: Record<string, unknown> | undefined
  if (props.entityType === 'BOOKSHELF') {
    if (id) { try { row = { ...await getBookshelfById(id) } } catch { /* A delete may leave only its audit snapshot. */ } }
    else {
      const candidates: Record<string, unknown>[] = []; let page = 1, total = 0
      do { const result = await getBookshelves({ page, size: 100, keyword: draft.code }); total = result.total; candidates.push(...result.records.filter(value => value.code === draft.code).map(value => ({ ...value }))); page++ } while ((page - 1) * 100 < total)
      if (candidates.length === 1) { row = candidates[0]; id = row!.id as number }
    }
  } else if (props.entityType === 'STOREY') {
    const parents = [...new Set([target?.bookshelfId as number | undefined, draft.bookshelfId].filter((value): value is number => !!value))]
    const candidates = (await Promise.all(parents.map(getStoreys))).flat().filter(value => id ? value.id === id : value.bookshelfId === draft.bookshelfId && value.levelNum === draft.levelNum && value.name === draft.name)
    if (candidates.length === 1) { row = { ...candidates[0]! }; id = candidates[0]!.id }
  } else {
    const parents = [...new Set([target?.storeyId as number | undefined, draft.storeyId].filter((value): value is number => !!value))]
    // Search every server page in the two explicit parent scopes; never infer absence from a partial page.
    const candidates: Record<string, unknown>[] = []
    for (const parent of parents) { let page = 1, total = 0; do { const result = await getLocations({ page, size: 100, storeyId: parent }); total = result.total; candidates.push(...result.records.filter(value => id ? value.id === id : value.code === draft.code).map(value => ({ ...value }))); page++ } while ((page - 1) * 100 < total) }
    if (candidates.length === 1) { row = candidates[0]; id = row!.id as number }
  }
  if (!id) return { current: null, events: [] }
  const events = (await getStructureEvents(props.entityType, id, 1, 5)).records
  const latest = events[0]?.after
  if (latest && (!row || latest.layoutVersion >= Number(row.layoutVersion))) return { current: latest, events }
  return { current: row ? { ...row, deleted: Number(row.deleted ?? 0) } as unknown as StructureSnapshot : null, events }
}
async function submit() {
  if (!canManage.value || (activeOperation.value === 'DELETE' && !user.isAdmin)) return
  if (!validText(form.reason, 500)) { error.value = '维护依据需为 1 至 500 字，不能含控制字符。'; return }
  const original = currentOriginal.value, id = original?.id as number, expectedVersion = original?.layoutVersion as number
  if (activeOperation.value !== 'CREATE' && (!Number.isSafeInteger(id) || !Number.isSafeInteger(expectedVersion) || expectedVersion < 0)) { error.value = '原版本缺失，请关闭并刷新原值。'; return }
  if (activeOperation.value === 'CREATE' || activeOperation.value === 'UPDATE') {
    if (props.entityType !== 'STOREY' && !/^[A-Za-z0-9_-]{1,50}$/.test(form.code)) { error.value = '编号需为 1 至 50 位字母、数字、下划线或连字符。'; return }
    if (props.entityType !== 'LOCATION' && (!validText(form.name, 100) || !Number.isSafeInteger(form.capacity) || form.capacity < 1 || form.capacity > 99999 || ![0, 1].includes(form.status))) { error.value = '请核对名称、历史容量资料及启停状态。'; return }
    if (props.entityType === 'BOOKSHELF' && ((!form.locationNull && !validText(form.location, 200, true)) || (form.deptId !== undefined && (!Number.isSafeInteger(form.deptId) || form.deptId <= 0)))) { error.value = '请核对书架描述与部门 ID。'; return }
    if (props.entityType === 'STOREY' && (!form.bookshelfId || !Number.isSafeInteger(form.bookshelfId) || form.bookshelfId <= 0 || !Number.isSafeInteger(form.levelNum))) { error.value = '请核对书架 ID 与层号。'; return }
    if (props.entityType === 'LOCATION' && (!form.storeyId || !Number.isSafeInteger(form.storeyId) || form.storeyId <= 0 || !(activeOperation.value === 'CREATE' ? ['EMPTY', 'DISABLED'] : ['EMPTY', 'OCCUPIED', 'DISABLED']).includes(form.locationStatus))) { error.value = '请核对所属层与地址状态。'; return }
  }
  if (activeOperation.value === 'ASSIGN' && (!Number.isSafeInteger(form.bookId) || !form.bookId || form.bookId <= 0)) { error.value = '请明确旧绑定书目 ID。'; return }
  if (activeOperation.value === 'CLEAR' && !['EMPTY', 'OCCUPIED', 'DISABLED'].includes(String(original?.status))) { error.value = '原地址状态未知，不能解除旧绑定，请先明确核实。'; return }
  if (activeOperation.value === 'ASSIGN' && (original?.status !== 'EMPTY' || original?.bookId != null)) { error.value = '请先核实并解除已有旧书目绑定，停用或未知地址不能绑定。'; return }
  const draft = JSON.parse(JSON.stringify(form)) as typeof form, identity = { expectedVersion, reason: form.reason }
  let write: () => Promise<unknown>
  if (activeOperation.value === 'DELETE') write = () => props.entityType === 'BOOKSHELF' ? deleteBookshelf(id, identity) : props.entityType === 'STOREY' ? deleteStorey(id, identity) : deleteLocation(id, identity)
  else if (activeOperation.value === 'CLEAR') write = () => clearLocation(id, identity)
  else if (activeOperation.value === 'ASSIGN') write = () => assignBookToLocation(id, draft.bookId!, identity)
  else if (props.entityType === 'BOOKSHELF') { const fields = { name: draft.name, code: draft.code, deptId: draft.deptId ?? null, location: draft.locationNull ? null : draft.location, capacity: draft.capacity, status: draft.status, reason: draft.reason }; write = () => activeOperation.value === 'CREATE' ? createBookshelf(fields) : updateBookshelf({ ...fields, id, expectedVersion }) }
  else if (props.entityType === 'STOREY') { const fields = { bookshelfId: draft.bookshelfId!, name: draft.name, levelNum: draft.levelNum, capacity: draft.capacity, status: draft.status, reason: draft.reason }; write = () => activeOperation.value === 'CREATE' ? createStorey(fields) : updateStorey({ ...fields, id, expectedVersion }) }
  else { const fields = { storeyId: draft.storeyId!, code: draft.code, status: draft.locationStatus, reason: draft.reason }; write = () => activeOperation.value === 'CREATE' ? createLocation(fields) : updateLocation({ ...fields, id, expectedVersion }) }
  if (await session.submit(write, () => readCurrent(original, draft))) { emit('success'); emit('update:visible', false) }
}
function reviewCurrent() { if (session.reviewed()) { currentOriginal.value = { ...readback.value!.current! }; if (activeOperation.value === 'CREATE') activeOperation.value = 'UPDATE'; emit('refresh') } }
function close() { if (busy.value || needsReview.value) return false; emit('update:visible', false); return true }
function beforeClose(done: () => void) { if (close()) done() }
function finishReview() { if (busy.value || !readback.value?.current) return; emit('refresh'); needsReview.value = false; close() }
</script>
<style scoped>.draft { border: 0; min-width: 0; padding: 0; }.draft:disabled { pointer-events: none; opacity: .7; }pre, code { white-space: pre-wrap; overflow-wrap: anywhere; }p { font-size: 13px; line-height: 1.6; }</style>
