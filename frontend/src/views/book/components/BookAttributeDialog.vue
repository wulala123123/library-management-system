<template>
  <el-dialog :model-value="visible" title="独立维护书目属性" width="min(840px, 96vw)" :close-on-click-modal="false" :close-on-press-escape="!locked" :before-close="beforeClose">
    <template v-if="canManage">
      <el-alert v-if="intakeCompleted" title="入库已完成，属性待补充。属性保存失败只重试本步骤，不重复入库。" type="success" :closable="false" />
      <el-alert v-if="error" :title="error" type="error" :closable="false" />
      <p v-if="loading">正在读取本书属性…</p>
      <template v-if="attributes && attributes.profile.bookId === bookId">
        <p>书目 #{{ bookId }} · 登记书名：{{ attributes.profile.catalogTitle }}</p><p>当前展示名称：{{ attributes.displayTitle }} · 修订 {{ attributes.profile.revision }}</p>
        <p>已采用来源原书名：{{ attributes.profile.adoptedSourceTitle ?? '未设置' }}</p>
        <fieldset :disabled="locked || loading" class="manual-form"><legend>本次手工变更（未选择字段保持原值）</legend>
          <label>展示名称覆盖 <select v-model="displayAction"><option value="KEEP">不变</option><option value="SET">设置覆盖</option><option value="CLEAR">明确清除覆盖，回到最新登记名</option></select></label><el-input v-if="displayAction === 'SET'" v-model="displayText" placeholder="展示名称原文，1–200 字" />
          <label>装帧 <select v-model="bindingAction"><option value="KEEP">不变</option><option value="SET">设置</option><option value="CLEAR">明确清除为未知</option></select></label><select v-if="bindingAction === 'SET'" v-model="binding"><option v-for="(label, code) in bindingLabels" :key="code" :value="code">{{ label }}</option></select><p>当前装帧：{{ bindingText(attributes.profile.bindingCode) }}</p>
          <label>适读年龄标签 <select v-model="ageAction"><option value="KEEP">不变</option><option value="SET">设置标签</option><option value="CLEAR">明确清除为未知</option></select></label><div v-if="ageAction === 'SET'" class="ages"><label v-for="item in ageOptions" :key="item.code"><input v-model="ages" type="checkbox" :value="item.code" />{{ item.label }}</label></div><p>当前年龄：{{ ageText(attributes.profile.ageLabels) }}；只保存标签，不推算连续年龄范围。</p>
          <label v-if="store.isAdmin"><input v-model="clearSourceTitle" type="checkbox" />明确撤回已采用来源原书名</label>
          <el-input v-model="reason" type="textarea" placeholder="本次属性维护依据（1–500 字）" />
          <el-button type="primary" :disabled="locked || loading" @click="preview">预览本书手工属性</el-button>
        </fieldset>
      </template>
      <CatalogAttributeRequestPanel :session="operation" @reset="resetRequest" />
      <p v-if="operation.input.value && operation.input.value.bookId !== bookId">当前恢复的是书目 #{{ operation.input.value.bookId }} 的原请求。核实前不能向另一书目发送新请求。</p>
      <el-button v-if="!attributes && !loading && !locked" @click="load">重读本书属性</el-button>
    </template><el-empty v-else description="仅管理员和馆员可维护属性；来源采用另需管理员权限。" />
    <template #footer><el-button :disabled="operation.busy.value || operation.uncertain.value" @click="close">关闭属性窗口</el-button></template>
  </el-dialog>
</template>
<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useUserStore } from '@/store/modules/user'
import { getCatalogAttributes, type AgeLabel, type AttributeChanges, type AttributeView, type BindingCode } from '@/api/catalogAttribute'
import { ageOptions, ageText, bindingLabels, bindingText, validAttributeText } from '@/utils/catalogAttributes'
import CatalogAttributeRequestPanel from './CatalogAttributeRequestPanel.vue'
import { useCatalogAttributeSession } from './catalogAttributeSession'
const props = defineProps<{ visible: boolean; bookId: number | null; intakeCompleted?: boolean }>()
const emit = defineEmits<{ 'update:visible': [value: boolean]; success: [] }>()
const store = useUserStore(), canManage = computed(() => store.isAdmin || store.isLibrarian)
const operation = useCatalogAttributeSession(store.userInfo?.id ?? 0, 'MANUAL', () => canManage.value)
const locked = computed(() => operation.busy.value || operation.uncertain.value || !!operation.input.value)
const attributes = ref<AttributeView | null>(null), loading = ref(false), error = ref('')
const displayAction = ref('KEEP'), bindingAction = ref('KEEP'), ageAction = ref('KEEP'), displayText = ref(''), binding = ref<BindingCode>('BOARD'), ages = ref<AgeLabel[]>([]), clearSourceTitle = ref(false), reason = ref('')
let loadRequest = 0, notified = '', opened = false
function resetDraft() { displayAction.value = bindingAction.value = ageAction.value = 'KEEP'; displayText.value = ''; binding.value = 'BOARD'; ages.value = []; clearSourceTitle.value = false; reason.value = ''; error.value = '' }
async function load() { if (!canManage.value || !props.visible || !props.bookId) return; const current = ++loadRequest, id = props.bookId; loading.value = true; attributes.value = null; try { const value = await getCatalogAttributes(id); if (current === loadRequest && props.visible && props.bookId === id) attributes.value = value } catch { if (current === loadRequest) error.value = '属性读取失败，当前不能预览；手工输入保留。' } finally { if (current === loadRequest) loading.value = false } }
watch(() => [props.visible, props.bookId], () => { ++loadRequest; attributes.value = null; if (opened && !operation.uncertain.value && !operation.busy.value) operation.reset(); resetDraft(); if (!props.visible || !canManage.value) return; opened = true; operation.restore(); if (operation.input.value) operation.recover(); load() }, { immediate: true })
watch(operation.receipt, value => { if (value && value.operationKey !== notified) { notified = value.operationKey; emit('success') } })
function preview() { if (!attributes.value || attributes.value.profile.bookId !== props.bookId || locked.value || !canManage.value) return; try { if (!validAttributeText(reason.value,500)) throw new Error('请填写 1–500 字维护依据。'); const changes: AttributeChanges = { displayTitleOverride: null, bindingCode: null, ageLabels: null, adoptedSourceTitle: null }; if (displayAction.value === 'SET') { if (!validAttributeText(displayText.value)) throw new Error('展示名称必须为 1–200 字有效原文，不能全空白。'); changes.displayTitleOverride = { action: 'SET', value: displayText.value } } else if (displayAction.value === 'CLEAR') changes.displayTitleOverride = { action: 'CLEAR' }; if (bindingAction.value === 'SET') changes.bindingCode = { action: 'SET', value: binding.value }; else if (bindingAction.value === 'CLEAR') changes.bindingCode = { action: 'CLEAR' }; if (ageAction.value === 'SET') { if (!ages.value.length) throw new Error('设置年龄时至少明确选择一个标签；未知请使用清除。'); changes.ageLabels = { action: 'SET', value: ageOptions.filter(item => ages.value.includes(item.code)).map(item => item.code) } } else if (ageAction.value === 'CLEAR') changes.ageLabels = { action: 'CLEAR' }; if (store.isAdmin && clearSourceTitle.value) changes.adoptedSourceTitle = { action: 'CLEAR' }; if (!Object.values(changes).some(Boolean)) throw new Error('请明确选择至少一个属性操作。'); operation.preview({ operationKey: crypto.randomUUID(), mode: 'MANUAL', reason: reason.value, bookId: props.bookId!, expectedRevision: attributes.value.profile.revision, expectedCatalogDigest: attributes.value.catalogDigest, sourceDigest: null, changes }); error.value = '' } catch (cause) { error.value = (cause as Error).message } }
function resetRequest() { const completed = !!operation.receipt.value; if (operation.reset()) { if (completed) resetDraft(); load() } }
function close() { if (operation.busy.value || operation.uncertain.value) return; operation.reset(); ++loadRequest; resetDraft(); emit('update:visible', false) }
function beforeClose(done: () => void) { if (!operation.busy.value && !operation.uncertain.value) { close(); done() } }
</script>
<style scoped>
.manual-form { display: grid; gap: 12px; border: 1px solid #dce4ef; border-radius: 8px; padding: 16px; }.manual-form>label { display: flex; gap: 10px; align-items: center; }p { font-size: 13px; color: #65758b; line-height: 1.7; }select { padding: 6px; border: 1px solid #dcdfe6; border-radius: 4px; }.ages { display: flex; flex-wrap: wrap; gap: 14px; }
</style>
