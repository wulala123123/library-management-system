<template>
  <el-dialog :model-value="visible" title="管理员逐字段核对并采用来源" width="min(1000px, 96vw)" :close-on-click-modal="false" :close-on-press-escape="!locked" :before-close="beforeClose">
    <template v-if="store.isAdmin">
      <el-alert v-if="error" :title="error" type="error" :closable="false" /><p v-if="loading">正在读取本书白名单来源…</p>
      <template v-if="source && attributes && source.bookId === bookId">
        <p>书目 #{{ bookId }} · 登记书名 {{ attributes.profile.catalogTitle }} · 当前展示 {{ attributes.displayTitle }}</p><p>来源关联：{{ source.associationState }} · {{ source.sourceCount }} 条。只有明确勾选的合法字段才提交 ADOPT。</p>
        <table><thead><tr><th>采用</th><th>字段</th><th>来源状态 / 候选</th><th>当前采用值</th></tr></thead><tbody><tr v-for="field in fields" :key="field"><td><input v-if="adoptableFields.includes(field as AttributeField)" v-model="selected" type="checkbox" :value="field" :disabled="locked || !source.fields[field].canAdopt" :aria-label="`采用${fieldLabels[field]}`" /><span v-else>仅核对</span></td><td>{{ fieldLabels[field] }}</td><td>{{ source.fields[field].state }}<br /><code>{{ JSON.stringify(source.fields[field].proposedValue) }}</code></td><td><code>{{ JSON.stringify(currentValue(field)) }}</code></td></tr></tbody></table>
        <details><summary>逐册来源原文（仅管理员）</summary><article v-for="record in source.records" :key="record.recordId"><h4>来源 #{{ record.recordId }} · 码 <code>{{ JSON.stringify(record.sourceKey) }}</code> · 册 {{ record.copyId ?? 'NULL' }}</h4><p v-for="field in fields" :key="field">{{ fieldLabels[field] }}：{{ record.cells[field].kind }} · <code>{{ JSON.stringify(record.cells[field].rawValue) }}</code></p></article></details>
        <el-input v-model="reason" type="textarea" :disabled="locked" placeholder="本书逐字段采用依据（1–500 字）" /><el-button type="primary" :disabled="locked || !selected.length" @click="preview">预览所选来源字段</el-button>
      </template>
      <CatalogAttributeRequestPanel :session="operation" @reset="resetRequest" />
      <el-button v-if="!locked" @click="load">重新读取本书来源</el-button>
    </template><el-empty v-else description="仅当前管理员可核对、采用或恢复来源请求。" />
    <template #footer><el-button :disabled="operation.busy.value || operation.uncertain.value" @click="close">关闭来源窗口</el-button></template>
  </el-dialog>
</template>
<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useUserStore } from '@/store/modules/user'
import { getBookSource, getCatalogAttributes, type AttributeChanges, type AttributeField, type AttributeView, type BookSource, type SourceKey } from '@/api/catalogAttribute'
import { attributeLabels, validAttributeText } from '@/utils/catalogAttributes'
import CatalogAttributeRequestPanel from './CatalogAttributeRequestPanel.vue'
import { useCatalogAttributeSession } from './catalogAttributeSession'
const props = defineProps<{ visible: boolean; bookId: number | null }>(), emit = defineEmits<{ 'update:visible': [value: boolean]; success: [] }>()
const store = useUserStore(), operation = useCatalogAttributeSession(store.userInfo?.id ?? 0, 'SOURCE', () => store.isAdmin)
const locked = computed(() => operation.busy.value || operation.uncertain.value || !!operation.input.value)
const source = ref<BookSource | null>(null), attributes = ref<AttributeView | null>(null), selected = ref<AttributeField[]>([]), reason = ref(''), error = ref(''), loading = ref(false)
const adoptableFields: AttributeField[] = ['displayTitleOverride','bindingCode','ageLabels','adoptedSourceTitle'], fields: SourceKey[] = [...adoptableFields,'publisher','setType'], fieldLabels: Record<SourceKey,string> = { ...attributeLabels, publisher:'出版社原文（不建身份）', setType:'套装类型原文（不建关系）' }
let request = 0, notified = '', opened = false
function currentValue(field: SourceKey) { return field === 'publisher' || field === 'setType' ? '本批仅核对' : attributes.value?.profile[field] ?? null }
async function load() { if (!props.visible || !props.bookId || !store.isAdmin) return; const current = ++request, id = props.bookId; loading.value = true; source.value = null; attributes.value = null; selected.value = []; try { const [a,s] = await Promise.all([getCatalogAttributes(id),getBookSource(id)]); if (current === request && props.visible && props.bookId === id && store.isAdmin) { attributes.value = a; source.value = s; error.value = a.catalogDigest === s.catalogDigest ? '' : '属性与来源读取时刻已变化，请重新读取后核对。' } } catch { if (current === request) error.value = '来源读取失败；本书不可采用，手工属性维护仍可独立进行。' } finally { if (current === request) loading.value = false } }
watch(() => [props.visible, props.bookId, store.isAdmin], () => { ++request; source.value = null; attributes.value = null; selected.value = []; reason.value = ''; error.value = ''; if (opened && !operation.busy.value && !operation.uncertain.value) operation.reset(); if (!props.visible || !store.isAdmin) return; opened = true; operation.restore(); if (operation.input.value) operation.recover(); load() }, { immediate: true })
watch(operation.receipt, value => { if (value && value.operationKey !== notified) { notified = value.operationKey; emit('success') } })
function preview() { if (!store.isAdmin || locked.value || !source.value || !attributes.value || source.value.bookId !== props.bookId) return; try { if (!validAttributeText(reason.value,500)) throw new Error('请填写 1–500 字采用依据。'); if (!selected.value.length || source.value.associationState !== 'VALID' || source.value.catalogDigest !== attributes.value.catalogDigest || selected.value.some(field => !source.value!.fields[field].canAdopt)) throw new Error('所选字段或来源关联不可采用，请重新核对。'); const changes: AttributeChanges = { displayTitleOverride:null,bindingCode:null,ageLabels:null,adoptedSourceTitle:null }; for (const field of selected.value) changes[field] = { action:'ADOPT' }; operation.preview({ operationKey:crypto.randomUUID(),mode:'SOURCE',reason:reason.value,bookId:props.bookId!,expectedRevision:source.value.revision,expectedCatalogDigest:source.value.catalogDigest,sourceDigest:source.value.sourceDigest,changes }); error.value = '' } catch (cause) { error.value = (cause as Error).message } }
function resetRequest() { if (operation.reset()) load() }
function close() { if (operation.busy.value || operation.uncertain.value) return; operation.reset(); ++request; source.value = null; selected.value = []; emit('update:visible',false) }
function beforeClose(done:()=>void) { if (!operation.busy.value && !operation.uncertain.value) { close(); done() } }
</script>
<style scoped>
table { border-collapse: collapse; width: 100%; margin: 14px 0; }td,th { padding: 10px; border: 1px solid #dce4ef; text-align: left; }code { white-space: pre-wrap; overflow-wrap: anywhere; }p { color:#65758b; font-size:13px; line-height:1.7; }article { border-bottom:1px solid #dce4ef; }
</style>
