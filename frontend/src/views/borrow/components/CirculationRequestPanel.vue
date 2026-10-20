<template>
  <section v-if="session.operationKey.value || session.error.value" class="request-panel" aria-label="原借还办理">
    <h3>原借还办理<span v-if="session.view.value?.operation"> · {{ session.view.value.operation === 'BORROW' ? '借出' : '归还' }}</span></h3>
    <p v-if="session.operationKey.value">操作编号 <code>{{ session.operationKey.value }}</code></p>
    <el-alert v-if="session.error.value" :title="session.error.value" type="error" :closable="false" />
    <el-alert v-if="session.phase.value === 'confirming'" title="原确认结果尚未查清；可查询、重试原确认或请求服务端取消原编号。若已办理成功，取消会返回原成功回执；取得确定终态前不能编辑或另办。" type="warning" :closable="false" />
    <el-alert v-if="session.phase.value === 'cancelling'" title="原取消结果尚未查清；请查询或重试原取消，不能更换操作编号。" type="warning" :closable="false" />
    <p v-if="session.phase.value === 'previewing'">原预览可能正在处理。查不到也不代表已停止；可继续查询，或安全取消原编号。</p>
    <template v-if="session.view.value?.preview">
      <p>业务日期 {{ session.view.value.businessDate }} · 原预览有效至 {{ session.view.value.expiresAt }}</p>
      <p v-if="session.view.value.preview.borrower">读者 #{{ session.view.value.preview.borrower.userId }} {{ session.view.value.preview.borrower.userName ?? '原姓名未提供' }} · 卡号 {{ session.view.value.preview.borrower.cardNo ?? '未设置卡号' }}；当前在借 {{ session.view.value.preview.activeCountBefore }} 册 → {{ session.view.value.preview.activeCountAfter }} 册</p>
      <p class="preserve">本次备注：{{ session.view.value.input?.remark || '无' }}</p>
      <el-alert v-for="warning in session.view.value.preview.warnings" :key="warning" :title="warningText(warning)" type="warning" :closable="false" />
      <table><thead><tr><th>明确目标</th><th>读者</th><th>借阅期限</th><th>借阅状态 / 实体册状态</th></tr></thead><tbody>
        <tr v-for="item in displayItems" :key="item.ordinal"><td>{{ item.ordinal }}. {{ item.bookTitle }}<br /><code>{{ item.barcode === null ? '原记录无实体册关联' : JSON.stringify(item.barcode) }}</code><br />借阅明细 {{ item.recordId ?? '确认后分配' }} · 实体册 {{ item.copyId ?? '无' }}</td><td>#{{ item.userId }} {{ item.userName ?? '原姓名未提供' }}</td><td>借出 {{ item.borrowDate ?? '原记录未提供' }}<br />应还 {{ dueDateText(item) }}<br v-if="item.returnDate" /><span v-if="item.returnDate">归还 {{ item.returnDate }}</span></td><td>{{ statusText(item.beforeRecordStatus) }} → {{ statusText(item.afterRecordStatus) }}<br />{{ item.beforeCopyStatus ?? '无册关联' }} → {{ item.afterCopyStatus ?? '无册关联' }}</td></tr>
      </tbody></table>
      <p v-for="book in session.view.value.preview.books" :key="book.bookId">书目 #{{ book.bookId }}：可借 {{ book.stockAvailable }} → {{ book.afterAvailable }} 册；总库存 {{ book.stockTotal }} 册。</p>
    </template>
    <el-alert v-if="session.receipt.value" :title="`办理成功：${session.receipt.value.items.length} 册${session.receipt.value.orderId === null ? '' : `，借阅订单 #${session.receipt.value.orderId}`}；${session.receipt.value.confirmedAt}`" type="success" :closable="false" />
    <el-alert v-if="session.phase.value === 'cancelled' && session.view.value" title="原操作编号已安全取消，未执行借还；可以查看后重新办理。" type="info" :closable="false" />
    <div class="actions">
      <el-button v-if="session.operationKey.value" :disabled="session.busy.value || session.denied.value" @click="session.recover">查询原借还办理</el-button>
      <el-button v-if="session.phase.value === 'previewing' && session.input.value" :disabled="session.busy.value || session.denied.value" @click="session.retryPreview">重试原预览</el-button>
      <el-button v-if="session.phase.value === 'ready' || session.phase.value === 'confirming'" type="primary" :disabled="session.busy.value || session.denied.value || !session.view.value?.preview || session.phase.value === 'ready' && expired" @click="session.confirm">{{ session.phase.value === 'confirming' ? '重试原确认' : '确认本次全部借还' }}</el-button>
      <el-button v-if="['previewing','ready','confirming','cancelling'].includes(session.phase.value || '')" :disabled="session.busy.value || session.denied.value" @click="session.cancel">{{ session.phase.value === 'cancelling' ? '重试原取消' : session.phase.value === 'confirming' ? '请求取消原办理（已成功则显示回执）' : '安全取消原办理' }}</el-button>
      <el-button v-if="['committed','cancelled'].includes(session.phase.value || '') && session.view.value" :disabled="session.busy.value || session.denied.value" @click="acknowledge">已查看结果，继续办理</el-button>
    </div>
    <p v-if="session.phase.value === 'ready' && expired">预览已到期，请安全取消原办理后重新核对。</p>
  </section>
</template>
<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import type { CirculationSession } from '../circulationSession'
import { dueDateText } from '@/utils/borrowDates'
const props = defineProps<{session:CirculationSession}>(), emit = defineEmits<{success:[];acknowledged:[]}>()
const now = ref(Date.now()), timer = setInterval(() => { now.value = Date.now() },1000)
onUnmounted(() => clearInterval(timer))
const expired = computed(() => Date.parse(props.session.view.value?.expiresAt || '') <= now.value)
const displayItems = computed(() => props.session.receipt.value?.items ?? props.session.view.value?.preview?.items ?? [])
let delivered = ''
watch(() => props.session.receipt.value, receipt => { if (receipt && delivered !== receipt.operationKey) { delivered = receipt.operationKey; emit('success') } },{immediate:true})
async function acknowledge() { await props.session.acknowledge(); if (!props.session.operationKey.value) emit('acknowledged') }
const statusText = (status:string|null) => status === 'BORROWING' ? '借阅中' : status === 'OVERDUE' ? '已逾期' : status === 'RETURNED' ? '已归还' : '新建'
const warningText = (warning:string) => ({HISTORICAL_STOCK_DIFFERENCE:'存在历史库存登记差额，原差额保持。',UNKNOWN_LEGACY_STATUS:'存在待核实的历史册状态，请核对所选实体册。',COPY_LINK_ANOMALY:'存在借阅关联异常，请核对所选实体册。'}[warning] || warning)
</script>
<style scoped>
.request-panel{border:1px solid #b8d5c7;background:#f8fcfa;padding:18px;border-radius:12px;margin:16px 0}.actions{display:flex;gap:8px;flex-wrap:wrap;margin-top:16px}.preserve,code{white-space:pre-wrap;overflow-wrap:anywhere}table{width:100%;border-collapse:collapse;margin:12px 0}th,td{border:1px solid #d8e3dc;text-align:left;vertical-align:top;padding:10px}.el-alert{margin:8px 0}
</style>
