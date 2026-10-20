<template>
  <el-drawer :model-value="visible && allowed" title="借阅订单详情" size="min(1000px, 96vw)" @close="emit('update:visible', false)">
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-button :loading="loading" @click="load">刷新订单完整明细</el-button>
    <section v-if="detail" v-loading="loading">
      <h3>订单 #{{ detail.orderId }} · {{ stateText(detail.state) }}</h3>
      <p>读者 #{{ detail.userId }} {{ detail.userName ?? '原姓名未提供' }} · 卡号 {{ detail.cardNo ?? '未设置卡号' }}</p>
      <p>借出业务日期 {{ detail.businessDate }} · 办理时间 {{ detail.createdAt }} · 操作人 #{{ detail.actorId }}</p>
      <p>全部 {{ detail.itemCount }} 册 · 未还 {{ detail.activeCount }} 册 · 已还 {{ detail.returnedCount }} 册</p>
      <p class="preserve">原借出备注：{{ detail.remark || '无' }}</p>
      <p>书名和条码保留本单借出时快照；借阅状态和日期显示当前结果。</p>
      <table><thead><tr><th>选择</th><th>原借出图书 / 条码</th><th>日期 / 当前状态</th><th>操作</th></tr></thead><tbody>
        <tr v-for="item in detail.items" :key="item.recordId"><td><input v-model="selectedIds" type="checkbox" :value="item.recordId" :disabled="!active(item.status) || !canStart || loading" :aria-label="`选择归还明细${item.recordId}`" /></td><td>{{ item.ordinal }}. {{ item.bookTitle }}<br /><code>{{ JSON.stringify(item.barcode) }}</code><br />借阅明细 #{{ item.recordId }} · 实体册 #{{ item.copyId }}</td><td>借出 {{ item.borrowDate ?? '原记录未提供' }}<br />应还 {{ dueDateText(item) }}<br />归还 {{ item.returnDate ?? '尚未归还' }} · {{ loanText(item.status) }}</td><td><el-button :disabled="!active(item.status) || !canStart || loading" @click="returnItems([item.recordId])">归还此册</el-button></td></tr>
      </tbody></table>
      <div class="actions"><el-button :disabled="!selectedIds.length || !canStart || loading" @click="returnItems(selectedIds)">归还明确所选 {{ selectedIds.length }} 册</el-button><el-button type="primary" :disabled="!detail.activeCount || !canStart || loading" @click="returnAll">归还整单全部未还册</el-button></div>
    </section>
  </el-drawer>
</template>
<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useUserStore } from '@/store/modules/user'
import { createCirculationApi, type BorrowOrderDetail, type ReturnSelection } from '@/api/circulation'
import { circulationProblem } from '../circulationProtocol'
import { dueDateText } from '@/utils/borrowDates'
const props = defineProps<{visible:boolean;orderId:number|null;canStart:boolean;refresh?:number}>()
const emit = defineEmits<{'update:visible':[value:boolean];'return-records':[records:ReturnSelection[]]}>()
const user = useUserStore(), allowed = computed(() => user.isAdmin || user.isLibrarian), detail = ref<BorrowOrderDetail|null>(null), selectedIds = ref<number[]>([]), loading = ref(false), error = ref('')
let generation = 0
watch(() => [props.visible,props.orderId,props.refresh,user.userInfo?.id,user.userInfo?.role,user.token], () => { ++generation; detail.value = null; selectedIds.value = []; error.value = ''; if (props.visible && allowed.value && props.orderId) load() },{immediate:true})
const active = (status:string) => status === 'BORROWING' || status === 'OVERDUE'
const loanText = (status:string) => status === 'RETURNED' ? '已归还' : status === 'OVERDUE' ? '已逾期' : '借阅中'
const stateText = (state:string) => state === 'RETURNED' ? '全部归还' : state === 'PARTIALLY_RETURNED' ? '部分归还' : '在借'
async function load() {
  if (!allowed.value || !props.visible || !props.orderId) return
  const sequence = ++generation, id = props.orderId, token = user.token; loading.value = true; error.value = ''; selectedIds.value = []
  try { const result = await createCirculationApi(token).order(id); if (sequence === generation && allowed.value && props.visible && props.orderId === id && user.token === token) { if (result.orderId !== id) throw Error('返回订单身份不一致。'); detail.value = result } }
  catch (cause) { if (sequence === generation) { detail.value = null; error.value = circulationProblem(cause).message } } finally { if (sequence === generation) loading.value = false }
}
function returnItems(ids:number[]) {
  if (!allowed.value || !props.canStart || loading.value || !detail.value || !ids.length) return
  const wanted = new Set(ids), items = detail.value.items.filter(row => wanted.has(row.recordId) && active(row.status))
  if (wanted.size !== ids.length || items.length !== ids.length) { error.value = '所选明细状态已变化，请刷新完整订单后重新选择。'; return }
  emit('return-records',items.map(item => ({id:item.recordId,bookTitle:item.bookTitle,copyBarcode:item.barcode,userName:detail.value!.userName,dueDate:item.dueDate,dueDateKind:item.dueDateKind})))
}
function returnAll() { if (detail.value && detail.value.items.length === detail.value.itemCount) returnItems(detail.value.items.filter(item => active(item.status)).map(item => item.recordId)) }
</script>
<style scoped>
table{width:100%;border-collapse:collapse;margin:12px 0}th,td{border:1px solid #ddd;padding:10px;text-align:left;vertical-align:top}.actions{display:flex;gap:8px;flex-wrap:wrap}.preserve,code{white-space:pre-wrap;overflow-wrap:anywhere}
</style>
