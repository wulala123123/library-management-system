<template>
  <section v-if="session.input.value || session.error.value" class="request-panel">
    <el-alert v-if="session.error.value" :title="session.error.value" :type="session.uncertain.value ? 'warning' : 'error'" :closable="false" />
    <p v-if="session.input.value">本请求始终属于书目 #{{ session.input.value.bookId }} · key {{ session.input.value.operationKey }}</p>
    <p v-if="session.uncertain.value">原输入、key 与预览摘要已冻结，结果不明时只查询或重试原请求。</p>
    <template v-if="preview && !session.receipt.value">
      <h3>书目 #{{ preview.bookId }} 身份变更预览</h3>
      <p>此预览尚未修改业务。有效至 {{ session.view.value?.expiresAt }}。来源采用未启用。</p>
      <table v-if="relationFields.length"><thead><tr><th>字段</th><th>变更前</th><th>确认后</th></tr></thead><tbody><tr v-for="field in relationFields" :key="field"><td>{{ field === 'publisher' ? '出版社身份' : '主题列表' }}</td><td>{{ identityValue(preview.before, field) }}</td><td>{{ identityValue(preview.after, field) }}</td></tr></tbody></table>
      <p v-if="preview.changes.publisher?.action === 'CLEAR'">本次明确解除出版社关联；历史登记出版社文本保持原值。</p>
      <section v-if="preview.changes.categoryDimensions.length"><h4>明确核实分类维度</h4><p v-for="item in preview.changes.categoryDimensions" :key="item.categoryId">分类稳定 ID #{{ item.categoryId }} → {{ dimensionLabel(item.dimension) }}</p><details><summary>展开分类及完整父链的变更前后依据</summary><pre>{{ JSON.stringify({ before: preview.targetSnapshot, after: preview.afterTargetSnapshot }, null, 2) }}</pre></details></section>
      <section v-if="preview.changes.aliases.length"><h4>别名变更（确认以下归属与原文）</h4><el-alert v-if="!aliasesComplete" title="别名预览证据不完整，禁止确认；请查询原请求并核对。" type="error" :closable="false" /><table v-if="aliasesComplete"><thead><tr><th>稳定归属</th><th>操作及别名原文</th><th>变更前全部别名</th><th>确认后全部别名</th></tr></thead><tbody><tr v-for="(alias, index) in preview.aliasChanges" :key="`${alias.kind}-${alias.ownerId}-${index}`"><td>{{ alias.kind === 'PUBLISHER' ? '出版社' : '分类' }} #{{ alias.ownerId }} {{ alias.ownerName }}</td><td>{{ alias.action === 'ADD' ? '登记别名' : '停用别名' }}<br /><code>{{ JSON.stringify(alias.name) }}</code></td><td>{{ aliasesText(alias.beforeAliases) }}</td><td>{{ aliasesText(alias.afterAliases) }}</td></tr></tbody></table></section>
      <details v-if="preview.candidates.length"><summary>登记资料候选仅供核对（{{ preview.candidates.length }} 项）</summary><p v-for="(candidate, index) in preview.candidates" :key="index">审阅 #{{ candidate.id ?? '临时' }} · 稳定目标 #{{ candidate.entityId ?? '未匹配' }} {{ candidate.name }} · {{ candidate.matchKind }} · {{ candidate.status }}</p></details>
      <el-button type="primary" :disabled="session.busy.value || session.rejected.value || expired || session.uncertain.value || !aliasesComplete" @click="session.confirm">确认身份变更</el-button>
    </template>
    <section v-if="session.receipt.value" role="status"><h3>身份变更已保存</h3><p>书目 #{{ session.receipt.value.book.bookId }} · 修订 {{ session.receipt.value.book.beforeRevision }} → {{ session.receipt.value.book.afterRevision }}</p><p>成功回执 #{{ session.receipt.value.requestId }} · {{ session.receipt.value.confirmedAt }}。历史登记、库存、实体册与借还保持。</p></section>
    <div class="buttons"><el-button v-if="session.input.value && !session.receipt.value" :disabled="session.busy.value" @click="session.recover">查询原身份请求</el-button><el-button v-if="session.uncertain.value" :disabled="session.busy.value" @click="session.retry">重试原身份请求</el-button><el-button v-if="!session.uncertain.value" :disabled="session.busy.value" @click="emit('reset')">{{ session.receipt.value ? '已查看回执，继续核对' : '返回修改本地输入' }}</el-button></div>
  </section>
</template>
<script setup lang="ts">
import { computed, onUnmounted, ref } from 'vue'
import type { CatalogIdentityBook, IdentityAlias, CategoryDimension } from '@/api/catalogIdentity'
import type { useCatalogIdentitySession } from './catalogIdentitySession'
const props = defineProps<{ session: ReturnType<typeof useCatalogIdentitySession> }>(), emit = defineEmits<{ reset: [] }>()
const now = ref(Date.now()), timer = setInterval(() => { now.value = Date.now() }, 1000)
onUnmounted(() => clearInterval(timer))
const preview = computed(() => props.session.view.value?.preview.book)
const expired = computed(() => !!props.session.view.value && Date.parse(props.session.view.value.expiresAt) <= now.value)
const relationFields = computed(() => preview.value?.changedFields.filter(field => ['publisher', 'themes'].includes(field)) || [])
const aliasesComplete = computed(() => { const value = preview.value; if (!value?.changes.aliases.length) return true; return value.aliasChanges?.length === value.changes.aliases.length && value.changes.aliases.every((change, i) => { const actual = value.aliasChanges[i]; return actual?.ownerId === change.ownerId && actual.kind === change.kind && actual.name === change.name && actual.action === change.action && typeof actual.ownerName === 'string' && Array.isArray(actual.beforeAliases) && Array.isArray(actual.afterAliases) }) })
function dimensionLabel(value: CategoryDimension) { return { TOPIC: '主题', LEGACY_AGE: '历史年龄分类', UNVERIFIED: '待核实' }[value] }
function aliasesText(values: IdentityAlias[]) { return values.length ? values.map(value => `${JSON.stringify(value.name)} · ${value.status} · 修订 ${value.revision}`).join('\n') : '无别名' }
function identityValue(value: CatalogIdentityBook, field: string) { if (field === 'publisher') return value.publisher ? `#${value.publisher.id} ${value.publisher.name}` : '未关联'; return value.themes.map(theme => `#${theme.categoryId ?? theme.id} ${theme.path || theme.name}`).join('、') || '未关联' }
</script>
<style scoped>
.request-panel { display:grid; gap:12px; border:1px solid #d8e5ef; border-radius:8px; background:#f5f9fd; padding:16px; margin-top:16px; }h3,h4,p { margin:0; }p { line-height:1.7; font-size:13px; color:#65758b; }.buttons { display:flex; flex-wrap:wrap; gap:10px; }table { width:100%; border-collapse:collapse; }th,td { text-align:left; padding:8px; border:1px solid #d9e3ee; white-space:pre-wrap; overflow-wrap:anywhere; }pre { max-height:280px; overflow:auto; }code { overflow-wrap:anywhere; }
</style>
