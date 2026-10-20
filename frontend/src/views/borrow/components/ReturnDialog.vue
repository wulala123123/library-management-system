<template>
  <el-dialog :model-value="visible && allowed" title="柜台归还预览" width="min(900px, 96vw)" :close-on-click-modal="false" @close="emit('update:visible', false)">
    <p>本次只归还清单中明确列出的借阅明细，最多 100 册；扫描不会直接办理归还。</p>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <fieldset :disabled="busy || !canStart">
      <el-form @submit.prevent><el-form-item label="扫描馆内条码"><el-input v-model="barcode" aria-label="归还馆内条码" placeholder="完整条码，保留前导零和首尾空格" @keyup.enter="scan" /><el-button :loading="scanning" :disabled="scanning || !barcode.length" @click="scan">查找在借明细并加入</el-button></el-form-item></el-form>
      <h3>本次明确归还 {{ selected.length }} / 100 册</h3>
      <table><thead><tr><th>借阅明细 / 读者</th><th>图书 / 条码</th><th>应还日期</th><th>操作</th></tr></thead><tbody>
        <tr v-for="row in selected" :key="row.id"><td>#{{ row.id }}<br />{{ row.userName ?? '原姓名未提供' }}</td><td>{{ row.bookTitle }}<br /><code>{{ row.copyBarcode === null || row.copyBarcode === undefined ? '原记录无实体册关联' : JSON.stringify(row.copyBarcode) }}</code></td><td>{{ dueDateText(row) }}</td><td><el-button @click="remove(row.id)">移除此册</el-button></td></tr>
      </tbody></table>
      <el-form><el-form-item label="本次备注"><el-input v-model="remark" aria-label="归还备注" type="textarea" :rows="2" placeholder="选填，最多 500 字，不含控制字符" /></el-form-item></el-form>
    </fieldset>
    <template #footer><el-button @click="emit('update:visible', false)">关闭窗口</el-button><el-button type="primary" :disabled="!allowed || !canStart || busy || scanning || !selected.length || selected.length > 100" @click="preview">预览本次明确归还</el-button></template>
  </el-dialog>
</template>
<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useUserStore } from '@/store/modules/user'
import { getBookCopies as getBookCopyPage, getBookCopy } from '@/api/bookCopyQuery'
import type { ReturnSelection } from '@/api/circulation'
import { dueDateText } from '@/utils/borrowDates'
import { assertCirculationInput } from '../circulationProtocol'
const props = withDefaults(defineProps<{ visible: boolean; canStart: boolean; busy?: boolean; initialRecords?: ReturnSelection[] }>(), { initialRecords: () => [] })
const emit = defineEmits<{ 'update:visible': [value:boolean]; preview: [value:{operation:'RETURN';recordIds:number[];remark:string}] }>()
const user = useUserStore(), allowed = computed(() => user.isAdmin || user.isLibrarian)
const selected = ref<ReturnSelection[]>([]), barcode = ref(''), remark = ref(''), error = ref(''), scanning = ref(false)
let generation = 0
watch(() => props.visible, visible => { ++generation; scanning.value = false; if (visible && allowed.value) { selected.value = []; error.value = ''; remark.value = ''; barcode.value = ''; if (props.initialRecords.length > 100 || new Set(props.initialRecords.map(row => row.id)).size !== props.initialRecords.length || props.initialRecords.some(row => !Number.isSafeInteger(row.id) || row.id < 1)) error.value = '初始归还清单必须为最多 100 条不同的明确借阅明细，未截断办理。'; else props.initialRecords.forEach(add) } }, { immediate:true })
watch(() => [user.userInfo?.id,user.userInfo?.role,user.token], () => { ++generation; selected.value = []; barcode.value = ''; remark.value = ''; error.value = ''; scanning.value = false })
function add(row:ReturnSelection) { if (selected.value.some(item => item.id === row.id)) { error.value = '此借阅明细已在本次清单中。'; return } if (selected.value.length >= 100) { error.value = '本次最多明确归还 100 册。'; return } selected.value.push({...row}) }
function remove(id:number) { if (props.canStart && !props.busy) selected.value = selected.value.filter(row => row.id !== id) }
async function scan() {
  if (!allowed.value || !props.visible || !props.canStart || props.busy || scanning.value || !barcode.value.length) return
  const sequence = ++generation, raw = barcode.value; scanning.value = true; error.value = ''
  try {
    const page = await getBookCopyPage({page:1,size:2,barcode:raw})
    if (sequence !== generation || !allowed.value || !props.visible) return
    if (page.total !== 1 || page.records.length !== 1 || page.records[0]!.barcode !== raw) { error.value = '未找到唯一匹配的完整馆内条码，请核对原文。'; return }
    const detail = await getBookCopy(page.records[0]!.id)
    if (sequence !== generation || !allowed.value || !props.visible) return
    if (detail.copy.id !== page.records[0]!.id || detail.copy.barcode !== raw || detail.needsVerification || detail.currentLoans.length !== 1 || !detail.currentLoans[0]!.bookMatches) { error.value = detail.currentLoans.length === 0 ? '此册暂无可归还的在借关联，请查询原归还结果。' : '此册借阅关联需要核实，未自动加入。'; return }
    const loan = detail.currentLoans[0]!
    if (!['BORROWING','OVERDUE'].includes(loan.status)) { error.value = '此借阅当前不能归还，请重新查询。'; return }
    add({id:loan.borrowId,bookTitle:detail.copy.book.title,copyBarcode:raw,userName:loan.memberName,dueDate:loan.dueDate,dueDateKind:loan.dueDateKind})
    if (!error.value && barcode.value === raw) barcode.value = ''
  } catch { if (sequence === generation) error.value = '条码或在借明细查询失败，未加入清单。' } finally { if (sequence === generation) scanning.value = false }
}
function preview() { if (allowed.value && props.canStart && !props.busy && !scanning.value && selected.value.length && selected.value.length <= 100) { const input={operation:'RETURN' as const,recordIds:selected.value.map(row => row.id),remark:remark.value}; try { assertCirculationInput(input); emit('preview',input) } catch (cause) { error.value=(cause as Error).message } } }
</script>
<style scoped>
fieldset{border:0;margin:0;padding:0}table{width:100%;border-collapse:collapse;margin:12px 0}th,td{border:1px solid #ddd;padding:10px;text-align:left;vertical-align:top}code{white-space:pre-wrap;overflow-wrap:anywhere}
</style>
