<template>
  <el-dialog :model-value="visible" title="出版社与主题身份" width="min(1040px, 96vw)" :close-on-click-modal="false" :close-on-press-escape="!locked" :before-close="beforeClose">
    <template v-if="canRead">
      <el-alert v-if="error" :title="error" type="error" :closable="false" />
      <p v-if="loading">正在读取书目身份…</p>
      <el-alert v-if="originalOtherBook" :title="`正在恢复书目 #${operation.input.value?.bookId} 的原请求；当前书目 #${bookId} 暂不可编辑。`" type="warning" :closable="false" />
      <template v-if="identity && identity.bookId === bookId && !originalOtherBook">
        <section class="identity-card">
          <h3>书目 #{{ identity.bookId }} · 修订 {{ identity.identityRevision }}</h3>
          <p>历史登记出版社文本：<code>{{ JSON.stringify(identity.publisherRaw) }}</code></p>
          <p>历史登记分类：{{ identity.categoryId == null ? '未登记' : `ID #${identity.categoryId}` }}（保留原登记，不解释为主题或年龄）</p>
          <p>生效出版社身份：{{ displayRef(identity.publisher) }}</p>
          <p>生效主题：{{ identity.themes.map(theme => `#${theme.categoryId ?? theme.id} ${theme.path || theme.name}`).join('、') || '未关联' }}</p>
          <p>年龄标签独立维护。本窗口仅办理手工身份核实；原系统来源采用尚未启用。</p>
        </section>
        <el-alert v-if="!canWrite" title="馆员只读已生效身份；身份与别名变更由管理员核实。" type="info" :closable="false" />
        <fieldset v-if="canWrite" :disabled="locked || loading" class="identity-form">
          <legend>管理员明确核实</legend>
          <section>
            <h4>出版社身份</h4>
            <label>本次操作 <select v-model="publisherAction" aria-label="出版社操作"><option value="KEEP">保持原关联</option><option value="LINK">关联已核实身份</option><option value="CLEAR">解除出版社关联</option></select></label>
            <div class="search-row"><el-input v-model="publisherKeyword" placeholder="搜索出版社规范名称" @keyup.enter="searchPublishers(1)" /><el-button :disabled="publisherLoading" @click="searchPublishers(1)">搜索出版社</el-button></div>
            <p v-if="publisherError" role="alert">{{ publisherError }} <el-button @click="searchPublishers(publisherPage)">重试出版社列表</el-button></p>
            <p v-if="publisherLoading">正在读取出版社列表…</p>
            <label v-if="publisherAction === 'LINK'">目标出版社 <select v-model.number="publisherId" aria-label="出版社身份"><option :value="null">请明确选择稳定身份</option><option v-for="item in publisherOptions" :key="item.id" :value="item.id" :disabled="item.status !== 1">#{{ item.id }} {{ item.name }}{{ item.status === 1 ? '' : '（停用）' }}</option></select></label>
            <p v-if="publisherAction === 'CLEAR'">确认后解除出版社身份关联，历史登记出版社文本保持原值。</p>
            <div class="search-row"><el-button :disabled="publisherPage <= 1 || publisherLoading" @click="searchPublishers(publisherPage - 1)">出版社上一页</el-button><span>第 {{ publisherPage }} 页，共 {{ publisherTotal }} 个身份（每页 50）</span><el-button :disabled="publisherPage * 50 >= publisherTotal || publisherLoading" @click="searchPublishers(publisherPage + 1)">出版社下一页</el-button></div>
          </section>
          <section>
            <h4>主题及分类维度</h4>
            <p>历史分类默认待核实。只有明确核实为主题、且完整父链启用的分类才能新关联；本次可同时核实维度并关联。</p>
            <label>本次主题操作 <select v-model="themesAction" aria-label="主题操作"><option value="KEEP">保持原主题</option><option value="REPLACE">明确替换主题列表（可多选）</option></select></label>
            <div class="search-row"><el-input v-model="categoryKeyword" placeholder="搜索分类名称" @keyup.enter="searchCategories(1)" /><el-button :disabled="categoryLoading" @click="searchCategories(1)">搜索分类</el-button></div>
            <p v-if="categoryError" role="alert">{{ categoryError }} <el-button @click="searchCategories(categoryPage)">重试分类列表</el-button></p>
            <p v-if="categoryLoading">正在读取分类列表…</p>
            <table><thead><tr><th>关联主题</th><th>分类稳定身份 / 父链</th><th>当前维度</th><th>本次核实维度</th></tr></thead><tbody>
              <tr v-for="item in categoryOptions" :key="item.id"><td><input v-model="themeIds" type="checkbox" :value="item.id" :aria-label="`关联主题 ${item.id}`" :disabled="themesAction !== 'REPLACE' || (!themeIds.includes(item.id) && !canSelectTheme(item))" /></td><td>#{{ item.id }} {{ categoryLabel(item) }}<br />{{ item.status === 1 ? '' : '本级停用；' }}{{ item.parentChainActive ? '完整父链启用' : '父链不可用或未读取，不能新关联' }}</td><td>{{ dimensionLabel(item.dimension) }}</td><td><select v-model="dimensions[item.id]" :aria-label="`核实分类维度 ${item.id}`"><option value="KEEP">不变</option><option value="TOPIC">主题</option><option value="LEGACY_AGE">历史年龄分类</option><option value="UNVERIFIED">待核实</option></select></td></tr>
            </tbody></table>
            <p>已选主题 ID：{{ themeIds.join('、') || '空列表' }}；替换为空列表须通过预览确认。</p>
            <div class="search-row"><el-button :disabled="categoryPage <= 1 || categoryLoading" @click="searchCategories(categoryPage - 1)">分类上一页</el-button><span>第 {{ categoryPage }} 页，共 {{ categoryTotal }} 个身份（每页 50）</span><el-button :disabled="categoryPage * 50 >= categoryTotal || categoryLoading" @click="searchCategories(categoryPage + 1)">分类下一页</el-button></div>
          </section>
          <section v-if="identity.candidates?.length" class="candidate-list"><h4>登记资料规范化候选（仅提示）</h4><p v-for="(candidate, index) in identity.candidates" :key="`${candidate.id}-${index}`">{{ candidate.kind === 'PUBLISHER' ? '出版社' : '分类' }} · {{ candidate.matchKind }} · 稳定目标 #{{ candidate.entityId ?? '未匹配' }} {{ candidate.name }} · 登记原文 <code>{{ JSON.stringify(candidate.rawName) }}</code> · 审阅 {{ candidate.status }}</p><p>候选审阅编号与稳定身份 ID 分开；近似名、规范化同名均不自动选择或新建身份。</p></section>
          <section><h4>别名核实（可选）</h4><div class="search-row"><select v-model="aliasKind" aria-label="别名类型"><option value="PUBLISHER">出版社</option><option value="CATEGORY">分类</option></select><select v-model.number="aliasOwnerId" aria-label="别名归属"><option :value="null">选择已核实稳定身份</option><option v-for="item in aliasOptions" :key="item.id" :value="item.id" :disabled="item.status !== 1">#{{ item.id }} {{ item.name }}</option></select><select v-model="aliasAction" aria-label="别名操作"><option value="ADD">登记别名</option><option value="CLEAR">停用该别名</option></select><el-input v-model="aliasName" :maxlength="aliasLimit" :placeholder="`别名原文（1–${aliasLimit}字）`" /></div><p>确认前会展示归属、操作、别名原文及全部变更前后别名。别名不自动重映射书目。</p></section>
          <el-input v-model="reason" type="textarea" placeholder="身份核实依据（1–500 字）" />
          <el-button type="primary" :disabled="locked || loading || publisherLoading || categoryLoading" @click="preview">预览出版社/分类身份</el-button>
        </fieldset>
      </template>
      <CatalogIdentityRequestPanel v-if="canWrite" :session="operation" @reset="resetRequest" />
      <el-button v-if="!identity && !loading && !locked" @click="load">重读身份资料</el-button>
    </template>
    <el-empty v-else description="当前账号无身份资料权限。" />
    <template #footer><el-button :disabled="operation.busy.value || (canWrite && operation.uncertain.value)" @click="close">关闭</el-button></template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useUserStore } from '@/store/modules/user'
import { emptyIdentityChanges, getCatalogIdentityBook, getCatalogIdentityCategories, getCatalogIdentityPublishers, type CatalogIdentityBook, type CatalogIdentityRef, type CategoryDimension } from '@/api/catalogIdentity'
import { validAttributeText } from '@/utils/catalogAttributes'
import CatalogIdentityRequestPanel from './CatalogIdentityRequestPanel.vue'
import { useCatalogIdentitySession } from './catalogIdentitySession'

const props = defineProps<{ visible: boolean; bookId: number | null }>()
const emit = defineEmits<{ 'update:visible': [value: boolean]; success: [] }>()
const store = useUserStore(), ownerId = store.userInfo?.id ?? 0
const sameOwner = computed(() => store.userInfo?.id === ownerId)
const canRead = computed(() => sameOwner.value && (store.isAdmin || store.isLibrarian)), canWrite = computed(() => sameOwner.value && store.isAdmin)
const operation = useCatalogIdentitySession(ownerId, () => canWrite.value)
const locked = computed(() => operation.busy.value || operation.uncertain.value || !!operation.input.value)
const originalOtherBook = computed(() => !!operation.input.value && operation.input.value.bookId !== props.bookId)
const identity = ref<CatalogIdentityBook | null>(null), loading = ref(false), error = ref('')
const publisherKeyword = ref(''), categoryKeyword = ref(''), publisherError = ref(''), categoryError = ref('')
const publisherLoading = ref(false), categoryLoading = ref(false), publisherPage = ref(1), categoryPage = ref(1), publisherTotal = ref(0), categoryTotal = ref(0)
const publisherRows = ref<CatalogIdentityRef[]>([]), categoryRows = ref<CatalogIdentityRef[]>([])
const publishers = reactive<Record<number, CatalogIdentityRef>>({}), categories = reactive<Record<number, CatalogIdentityRef>>({})
const publisherAction = ref<'KEEP' | 'LINK' | 'CLEAR'>('KEEP'), publisherId = ref<number | null>(null), themesAction = ref<'KEEP' | 'REPLACE'>('KEEP'), themeIds = ref<number[]>([])
const dimensions = reactive<Record<number, CategoryDimension | 'KEEP'>>({})
const aliasKind = ref<'PUBLISHER' | 'CATEGORY'>('PUBLISHER'), aliasOwnerId = ref<number | null>(null), aliasAction = ref<'ADD' | 'CLEAR'>('ADD'), aliasName = ref(''), reason = ref('')
const publisherOptions = computed(() => unique([...publisherRows.value, ...(publisherId.value && publishers[publisherId.value] ? [publishers[publisherId.value]!] : [])]))
const categoryOptions = computed(() => unique([...categoryRows.value.flatMap(chainRows), ...themeIds.value.flatMap(id => categories[id] ? chainRows(categories[id]!) : [])]))
const aliasOptions = computed(() => Object.values(aliasKind.value === 'PUBLISHER' ? publishers : categories))
const aliasLimit = computed(() => aliasKind.value === 'PUBLISHER' ? 200 : 100)
let generation = 0, publisherRequest = 0, categoryRequest = 0, notified = ''
function unique(rows: CatalogIdentityRef[]) { return [...new Map(rows.map(row => [row.id, row])).values()] }
function displayRef(value: CatalogIdentityRef | null) { return value ? `#${value.id} ${value.name}（${value.status === 1 ? '启用' : '停用'}）` : '未关联' }
function categoryLabel(item: CatalogIdentityRef) { return item.path || `${item.name}（父 ID #${item.parentId ?? 0}，层级 ${item.level ?? '待核实'}）` }
function chainRows(item: CatalogIdentityRef): CatalogIdentityRef[] { const chain = item.parentChain || []; return [...chain.map((parent, index) => ({ ...parent, normalizedName: parent.name, aliases: [], path: chain.slice(0, index + 1).map(part => part.name).join(' / '), parentChain: chain.slice(0, index + 1), parentChainActive: item.parentChainActive })), item] }
function dimensionLabel(value?: CategoryDimension) { return ({ TOPIC: '主题', LEGACY_AGE: '历史年龄分类', UNVERIFIED: '待核实' } as const)[value || 'UNVERIFIED'] }
function isTopic(item: Pick<CatalogIdentityRef, 'id' | 'dimension'>) { return dimensions[item.id] === 'TOPIC' || ((dimensions[item.id] ?? 'KEEP') === 'KEEP' && item.dimension === 'TOPIC') }
function canSelectTheme(item: CatalogIdentityRef) { return item.status === 1 && item.parentChainActive === true && isTopic(item) && (item.parentChain || []).every(parent => parent.status === 1 && isTopic(parent)) }
function current(g: number) { return g === generation && props.visible && canRead.value }

async function searchPublishers(page = 1) {
  if (!canWrite.value || !props.visible || locked.value) return
  const g = generation, request = ++publisherRequest; publisherLoading.value = true; publisherError.value = ''
  try { const result = await getCatalogIdentityPublishers({ keyword: publisherKeyword.value || undefined, page, size: 50 }); if (!current(g) || !canWrite.value || request !== publisherRequest) return; publisherRows.value = result.records; publisherTotal.value = result.total; publisherPage.value = result.page; result.records.forEach(item => { publishers[item.id] = item }) }
  catch { if (current(g) && request === publisherRequest) { publisherRows.value = []; publisherError.value = '出版社列表读取失败，原选择和输入保留。' } }
  finally { if (g === generation && request === publisherRequest) publisherLoading.value = false }
}
async function searchCategories(page = 1) {
  if (!canWrite.value || !props.visible || locked.value) return
  const g = generation, request = ++categoryRequest; categoryLoading.value = true; categoryError.value = ''
  try { const result = await getCatalogIdentityCategories({ keyword: categoryKeyword.value || undefined, page, size: 50 }); if (!current(g) || !canWrite.value || request !== categoryRequest) return; categoryRows.value = result.records; categoryTotal.value = result.total; categoryPage.value = result.page; result.records.flatMap(chainRows).forEach(item => { categories[item.id] = item; if (!dimensions[item.id]) dimensions[item.id] = 'KEEP' }) }
  catch { if (current(g) && request === categoryRequest) { categoryRows.value = []; categoryError.value = '分类列表读取失败，原选择和输入保留。' } }
  finally { if (g === generation && request === categoryRequest) categoryLoading.value = false }
}
async function load() {
  if (!canRead.value || !props.visible || !props.bookId || originalOtherBook.value) return
  const g = generation, id = props.bookId; loading.value = true; error.value = ''
  try { const detail = await getCatalogIdentityBook(id); if (!current(g) || props.bookId !== id || originalOtherBook.value) return; identity.value = detail; publisherId.value = detail.publisherId; themeIds.value = detail.themes.map(item => item.categoryId ?? item.id!).filter(Number.isSafeInteger); if (detail.publisher) publishers[detail.publisher.id] = detail.publisher; detail.themes.forEach(item => { const id = item.categoryId ?? item.id; if (id) chainRows({ ...item, id }).forEach(current => { categories[current.id] = current; dimensions[current.id] = 'KEEP' }) }); await Promise.all([searchPublishers(), searchCategories()]) }
  catch { if (current(g)) error.value = '身份资料读取失败，当前不能预览；原书目资料保持不变。' }
  finally { if (g === generation) loading.value = false }
}
function clearDraft() { identity.value = null; publisherRows.value = []; categoryRows.value = []; Object.keys(publishers).forEach(key => delete publishers[Number(key)]); Object.keys(categories).forEach(key => delete categories[Number(key)]); Object.keys(dimensions).forEach(key => delete dimensions[Number(key)]); publisherAction.value = themesAction.value = 'KEEP'; publisherId.value = null; themeIds.value = []; aliasOwnerId.value = null; aliasName.value = ''; reason.value = ''; error.value = ''; publisherError.value = categoryError.value = ''; publisherKeyword.value = categoryKeyword.value = ''; publisherLoading.value = categoryLoading.value = loading.value = false }
watch(() => [props.visible, props.bookId, canRead.value, canWrite.value], () => { ++generation; clearDraft(); if (!props.visible || !canRead.value) return; if (canWrite.value) { operation.restore(); if (operation.input.value) operation.recover() } if (!originalOtherBook.value) load() }, { immediate: true })
watch(operation.receipt, value => { if (value && canWrite.value && value.operationKey !== notified) { notified = value.operationKey; emit('success') } })
watch(aliasKind, () => { aliasOwnerId.value = null })

function preview() {
  if (!canWrite.value || locked.value || !identity.value || identity.value.bookId !== props.bookId) return
  try {
    if (!validAttributeText(reason.value, 500)) throw new Error('请填写 1–500 字身份核实依据。')
    const changes = emptyIdentityChanges()
    if (publisherAction.value === 'CLEAR') changes.publisher = { action: 'CLEAR', publisherId: null }
    if (publisherAction.value === 'LINK') { if (!publisherId.value || publishers[publisherId.value]?.status !== 1) throw new Error('请明确选择列表中当前启用的出版社稳定身份。'); changes.publisher = { action: 'LINK', publisherId: publisherId.value } }
    if (themesAction.value === 'REPLACE') { for (const id of themeIds.value) { if (!categories[id] || !canSelectTheme(categories[id]!)) throw new Error(`分类 #${id} 须明确核实为主题且完整父链启用。`) } changes.themes = { action: 'REPLACE', categoryIds: [...new Set(themeIds.value)] } }
    changes.categoryDimensions = Object.entries(dimensions).filter(([id, value]) => value !== 'KEEP' && categories[Number(id)]?.dimension !== value).map(([id, dimension]) => ({ categoryId: Number(id), dimension: dimension as CategoryDimension }))
    if (aliasName.value) { if (!validAttributeText(aliasName.value, aliasLimit.value)) throw new Error(`别名须为 1–${aliasLimit.value} 字有效原文。`); const owner = aliasOwnerId.value && (aliasKind.value === 'PUBLISHER' ? publishers[aliasOwnerId.value] : categories[aliasOwnerId.value]); if (!owner || owner.status !== 1) throw new Error('请明确选择当前启用的别名归属稳定身份。'); changes.aliases = [{ kind: aliasKind.value, ownerId: owner.id, action: aliasAction.value, name: aliasName.value }] }
    if (!changes.publisher && !changes.themes && !changes.aliases.length && !changes.categoryDimensions.length) throw new Error('请明确选择至少一个身份操作。')
    operation.preview({ operationKey: crypto.randomUUID(), mode: 'MANUAL', reason: reason.value, bookId: identity.value.bookId, expectedRevision: identity.value.identityRevision, expectedCatalogDigest: identity.value.catalogDigest, sourceDigest: null, changes }); error.value = ''
  } catch (cause) { error.value = (cause as Error).message }
}
function resetRequest() { if (operation.reset()) { ++generation; clearDraft(); load() } }
function close() { if (operation.busy.value || (canWrite.value && operation.uncertain.value)) return; ++generation; emit('update:visible', false) }
function beforeClose(done: () => void) { if (!operation.busy.value && (!canWrite.value || !operation.uncertain.value)) { close(); done() } }
</script>

<style scoped>
.identity-card,.identity-form { border:1px solid #dce4ef; border-radius:8px; padding:16px; margin:14px 0; }.identity-form { display:grid; gap:20px; }.identity-form section+section { border-top:1px dashed #dce4ef; padding-top:16px; }h3,h4 { margin:0 0 10px; }p { font-size:13px; line-height:1.7; color:#65758b; }select { padding:6px; border:1px solid #dcdfe6; border-radius:4px; max-width:100%; }label,.search-row { display:flex; flex-wrap:wrap; gap:10px; align-items:center; margin:8px 0; }.search-row .el-input { max-width:340px; }table { width:100%; border-collapse:collapse; }td,th { padding:8px; text-align:left; border:1px solid #dce4ef; }code { overflow-wrap:anywhere; white-space:pre-wrap; }
</style>
