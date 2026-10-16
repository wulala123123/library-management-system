<template>
  <el-dialog :model-value="visible" title="登记实体册入库" width="min(1080px, 96vw)" append-to-body :close-on-click-modal="false" :close-on-press-escape="!busy && !uncertain" :before-close="beforeClose">
    <template v-if="canManage">
      <el-alert v-if="loadError || error" :title="loadError || error" type="error" :closable="false" />
      <el-alert v-if="uncertain" title="结果尚未确认。请查询原请求，或用相同请求重试；暂不能修改输入或另建请求。" type="warning" :closable="false" />
      <IntakeReceiptPanel v-if="receipt" :receipt="receipt" :titles="titles" />
      <template v-else>
        <p>仅登记本次实际收到的新册，不补造历史实体册。每批最多 1000 册；已有贴标请录入原条码，无条码时选择生成新码。</p>
        <el-skeleton v-if="loading" :rows="3" animated />
        <fieldset v-else :disabled="!!request || busy" class="draft">
          <el-form label-width="100px">
            <el-form-item label="来源依据"><el-input v-model="reason" type="textarea" :rows="2" :maxlength="500" placeholder="本次入库来源或实收依据（必填）" :disabled="!!request || busy" /></el-form-item>
          </el-form>
          <section v-for="line in lines" :key="line.lineKey" class="intake-line">
            <h3>{{ line.label }}</h3>
            <p v-if="purchaseOrderId">仍待收 {{ line.pending === null ? '待核实' : line.pending }} 册 · 本次 {{ line.copies.length }} 册</p>
            <CopyInputs v-if="line.pending !== null && line.pending > 0" v-model="line.copies" :max="Math.min(line.pending, MAX_INTAKE_COPIES - totalCopies + line.copies.length)" />
            <p v-else>本项当前没有可登记的待收数量。</p>
          </section>
        </fieldset>
        <p>本次合计 {{ totalCopies }} 册</p>
        <template v-if="view">
          <h3>待确认预览</h3>
          <el-alert title="预览尚未入库，不可据此打印或贴标。确认时将再次核对条码、库存和采购剩余量。" type="info" :closable="false" />
          <p :class="{ expired }">有效至 {{ new Date(view.expiresAt).toLocaleString() }}（15 分钟）{{ expired ? ' · 已过期，请重新预览' : '' }}</p>
          <el-alert v-if="view.warnings.length" title="存在历史库存登记差额，继续保留待核对；本次新增不会补平差额。" type="warning" :closable="false" />
          <el-table :data="view.books" border>
            <el-table-column prop="title" label="书目" min-width="150" />
            <el-table-column label="当前总 / 可借"><template #default="{ row }">{{ row.stockTotal }} / {{ row.stockAvailable }}</template></el-table-column>
            <el-table-column label="登记总 / 可借"><template #default="{ row }">{{ row.registeredCopies }} / {{ row.registeredAvailableCopies }}</template></el-table-column>
            <el-table-column label="总数 / 可借差额"><template #default="{ row }">{{ row.totalDifference }} / {{ row.availableDifference }}</template></el-table-column>
          </el-table>
          <section v-if="currentReconciliation.length">
            <h4>当前在库核对（查询时刻，不改原预览或回执）</h4>
            <article v-for="item in currentReconciliation" :key="item.isbn"><p>ISBN {{ item.isbn }} · 书目 {{ item.bookId ?? '新书目待确认' }}</p><CopyReconciliationV2 :value="item.reconciliationV2" /></article>
          </section>
          <p v-for="progress in view.purchaseProgress" :key="progress.purchaseItemId">采购项 {{ progress.purchaseItemId }}：已收 {{ progress.instockQuantity }} / {{ progress.quantity }} 册，本次 {{ progress.receivingQuantity }} 册</p>
          <section v-for="line in view.lines" :key="line.lineKey" class="preview-line">
            <h4>{{ titles[line.lineKey] }}</h4>
            <p v-for="copy in line.copies" :key="copy.ordinal">第 {{ copy.ordinal }} 册 · {{ copy.input === 'GENERATED' ? '待确认新码' : '已有条码' }} <code>{{ visibleBarcode(copy.barcode) }}</code> · {{ barcodeBytes(copy.barcode) }} 字节 · {{ copy.location === null ? '未分配位置' : copy.location }}</p>
          </section>
        </template>
        <details v-if="request"><summary>本次请求记录</summary><p>请求编号 <code>{{ request.idempotencyKey }}</code>{{ view ? `，预览 ${view.intakeId}` : '' }}。超时后查询此原请求，不会重复入库。</p></details>
      </template>
    </template>
    <el-empty v-else description="仅管理员和馆员可办理实体册入库。" />
    <template #footer>
      <el-button :disabled="busy || uncertain" @click="close">{{ receipt ? '完成' : '关闭' }}</el-button>
      <template v-if="canManage && !receipt">
        <el-button v-if="request" :loading="busy" @click="session.recover">查询原请求</el-button>
        <el-button v-if="request && !uncertain" :disabled="busy" @click="editAgain">返回修改，重新预览</el-button>
        <el-button v-if="!view" type="primary" :disabled="loading || !!loadError || busy" :loading="busy" @click="preview">{{ request ? '使用原请求重试预览' : '预览本次入库' }}</el-button>
        <el-button v-else type="primary" :disabled="busy || (!uncertain && (expired || needsReview))" :loading="busy" @click="confirm">{{ uncertain ? '使用原请求重试确认' : '确认本次入库' }}</el-button>
      </template>
    </template>
  </el-dialog>
</template>
<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/store/modules/user'
import type { BookMetadata } from '@/api/book'
import { getPurchaseOrderById } from '@/api/purchase'
import type { IntakeReceipt } from '@/api/inventoryIntake'
import CopyInputs from './CopyInputs.vue'
import IntakeReceiptPanel from './IntakeReceipt.vue'
import { barcodeBytes, copiesInput, MAX_INTAKE_COPIES, newCopy, validateDraft, visibleBarcode, type IntakeDraftLine } from './intakeDraft'
import { useIntakeSession } from './intakeSession'
import type { CopyReconciliationV2 as ReconciliationV2 } from '@/api/bookCopyAction'
import CopyReconciliationV2 from '@/views/book-copy/components/CopyReconciliationV2.vue'

const props = defineProps<{ visible: boolean; bookId?: number | null; bookTitle?: string; newBook?: BookMetadata | null; purchaseOrderId?: number | null }>()
const emit = defineEmits<{ 'update:visible': [value: boolean]; success: [receipt: IntakeReceipt] }>()
const user = useUserStore(), canManage = computed(() => user.isAdmin || user.isLibrarian)
const session = useIntakeSession(), { request, view, receipt, busy, uncertain, error, reasonCode } = session
const reason = ref(''), lines = ref<IntakeDraftLine[]>([]), loading = ref(false), loadError = ref(''), now = ref(Date.now())
let generation = 0, reportedReceipt: number | null = null
const timer = setInterval(() => { now.value = Date.now() }, 1000)
onUnmounted(() => { clearInterval(timer); ++generation })
const totalCopies = computed(() => lines.value.reduce((count, line) => count + line.copies.length, 0))
const expired = computed(() => !!view.value && Date.parse(view.value.expiresAt) <= now.value)
const currentReconciliation = computed(() => (view.value as (typeof view.value & { reconciliationV2ByBook?: { bookId: number | null; isbn: string; reconciliationV2: ReconciliationV2 }[] }))?.reconciliationV2ByBook || [])
const needsReview = computed(() => ['PREVIEW_STALE', 'PREVIEW_EXPIRED', 'BARCODE_CONFLICT', 'CATALOG_CONFLICT', 'QUANTITY_EXCEEDS_PENDING', 'PURCHASE_NOT_APPROVED'].includes(reasonCode.value))
const titles = computed(() => Object.fromEntries((view.value?.lines || lines.value).map(line => [line.lineKey,
  lines.value.find(draft => draft.lineKey === line.lineKey)?.label || ('newBook' in line && line.newBook?.title) || view.value?.books.find(book => book.bookId === ('bookId' in line ? line.bookId : line.target.bookId))?.title || '本次入库书目'])))

watch(receipt, value => { if (value && value.intakeId !== reportedReceipt) { reportedReceipt = value.intakeId; emit('success', value) } })
watch(() => props.visible, async visible => {
  const current = ++generation
  if (!visible || !canManage.value || request.value) return
  lines.value = []; reason.value = ''; loadError.value = ''; reportedReceipt = null; loading.value = true
  try {
    if (props.purchaseOrderId) {
      const order = await getPurchaseOrderById(props.purchaseOrderId)
      if (current !== generation) return
      if (order.status !== 'APPROVED') throw new Error('采购单当前未处于可入库状态，请刷新后核对。')
      lines.value = order.items.map(item => ({ lineKey: crypto.randomUUID(), label: item.bookTitle, target: { bookId: null, newBook: null, purchaseItemId: item.id },
        pending: Number.isSafeInteger(item.quantity) && Number.isSafeInteger(item.instockQuantity) && item.quantity >= item.instockQuantity && item.instockQuantity >= 0 ? item.quantity - item.instockQuantity : null, copies: [] }))
    } else {
      if (!props.bookId && !props.newBook) throw new Error('请先选择书目或填写新书目资料。')
      lines.value = [{ lineKey: crypto.randomUUID(), label: props.newBook?.title || props.bookTitle || `书目 ${props.bookId}`,
        target: { bookId: props.bookId || null, newBook: props.newBook || null, purchaseItemId: null }, pending: MAX_INTAKE_COPIES, copies: [newCopy()] }]
    }
  } catch (cause) { if (current === generation) loadError.value = cause instanceof Error ? cause.message : '读取入库来源失败，请关闭后重试。' }
  finally { if (current === generation) loading.value = false }
}, { immediate: true })

async function preview() {
  if (!canManage.value || busy.value) return
  if (request.value) { await session.preview(request.value); return }
  const invalid = validateDraft(lines.value, reason.value)
  if (invalid) { error.value = invalid; return }
  await session.preview({ idempotencyKey: crypto.randomUUID(), source: { type: props.purchaseOrderId ? 'PURCHASE' : 'MANUAL', purchaseOrderId: props.purchaseOrderId || null }, reason: reason.value,
    lines: lines.value.filter(line => line.copies.length).map(line => ({ lineKey: line.lineKey, target: line.target, copies: copiesInput(line.copies) })) })
}
async function confirm() { if (canManage.value && (!expired.value || uncertain.value) && (!needsReview.value || uncertain.value)) await session.confirm() }
async function editAgain() {
  if (!session.reset()) return
  reportedReceipt = null
  if (!props.purchaseOrderId || !canManage.value) return
  loading.value = true; loadError.value = ''
  try {
    const order = await getPurchaseOrderById(props.purchaseOrderId)
    if (order.status !== 'APPROVED') throw new Error('采购单当前未处于可入库状态，请刷新后核对。')
    const previous = lines.value
    lines.value = order.items.map(item => ({ lineKey: previous.find(line => line.target.purchaseItemId === item.id)?.lineKey || crypto.randomUUID(),
      label: item.bookTitle, target: { bookId: null, newBook: null, purchaseItemId: item.id },
      pending: Number.isSafeInteger(item.quantity) && Number.isSafeInteger(item.instockQuantity) && item.quantity >= item.instockQuantity && item.instockQuantity >= 0 ? item.quantity - item.instockQuantity : null,
      copies: previous.find(line => line.target.purchaseItemId === item.id)?.copies || [] }))
  } catch (cause) { loadError.value = cause instanceof Error ? cause.message : '读取采购剩余量失败，请重试。' }
  finally { loading.value = false }
}
function close() {
  if (busy.value || uncertain.value) { ElMessage.warning('请先查询本次入库结果，确认后再关闭。'); return false }
  emit('update:visible', false); session.reset(); return true
}
function beforeClose(done: () => void) { if (close()) done() }
</script>
<style scoped>
.draft { border: 0; padding: 0; min-width: 0; }.draft:disabled { opacity: .7; pointer-events: none; }.intake-line { margin: 16px 0; padding: 12px; border: 1px solid var(--border-color); border-radius: 6px; }.intake-line h3 { margin: 0 0 8px; font-size: 15px; } code { white-space: pre-wrap; overflow-wrap: anywhere; }.expired { color: var(--el-color-danger); } p { line-height: 1.6; font-size: 13px; }
</style>
