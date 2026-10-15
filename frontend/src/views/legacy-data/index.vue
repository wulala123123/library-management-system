<template>
  <div>
    <h2>原系统资料</h2>
    <p class="intro">查阅迁入的会员权益、馆内条码、借阅计划和原始订单。日常借还请在借阅管理中办理。</p>
    <el-tabs v-model="tab" class="card-container">
      <el-tab-pane label="迁移概况" name="SUMMARY">
        <el-empty v-if="!summary" description="尚无迁移记录" />
        <template v-else>
          <el-descriptions :column="3" border title="迁入数据核对">
            <el-descriptions-item v-for="item in summaryItems" :key="item.key" :label="item.label">{{ summary[item.key] ?? '-' }}{{ item.unit }}</el-descriptions-item>
          </el-descriptions>
          <p class="intro">原系统不限归还日期的在借记录继续显示“不限制”；历史未提供的应还日期显示“原记录未提供”。</p>
          <p class="intro">会员卡期限、缴费、押金与借阅计划保留原记录，供办理会员业务时核对。新借阅使用当前系统的借阅规则。</p>
        </template>
      </el-tab-pane>
      <el-tab-pane label="会员权益" name="MEMBER">
        <el-table v-loading="loading" :data="members" stripe border>
          <el-table-column prop="real_name" label="会员" width="110" />
          <el-table-column prop="phone" label="手机号" width="140" />
          <el-table-column prop="card_no" label="卡号" width="120"><template #default="{row}">{{ displayValue(row.card_no) }}</template></el-table-column>
          <el-table-column prop="plan_name" label="借阅计划" min-width="130" />
          <el-table-column label="服务期限" min-width="210"><template #default="{row}">{{ displayValue(row.service_start, '待核实') }} 至 {{ displayValue(row.service_end, '待核实') }}</template></el-table-column>
          <el-table-column label="已用 / 总次数" min-width="130"><template #default="{row}">{{ displayValue(row.used_uses, '待核实') }} / {{ displayTotalUses(row.total_uses) }}</template></el-table-column>
          <el-table-column label="实缴押金（元）" min-width="150"><template #default="{row}">{{ row.deposit_amount ?? '未记录' }}（{{ row.deposit_status || '未记录' }}）</template></el-table-column>
          <el-table-column label="实缴费用（元）" min-width="120"><template #default="{row}">{{ row.paid_fee ?? '未记录' }}</template></el-table-column>
          <el-table-column label="操作" width="90" fixed="right"><template #default="{row}"><el-button link type="primary" @click="showMember(row.user_id)">完整资料</el-button></template></el-table-column>
        </el-table>
      </el-tab-pane>
      <el-tab-pane label="原始订单" name="ORDER" />
      <el-tab-pane label="馆内条码资料" name="BOOK" />
      <el-tab-pane label="借阅计划" name="PLAN" />
      <template v-if="isRecordsTab">
        <el-form inline class="search">
          <el-form-item><el-input v-model="keyword" placeholder="名称、订单号或馆内条码" clearable @keyup.enter="search" /></el-form-item>
          <el-form-item><el-button type="primary" @click="search">搜索</el-button></el-form-item>
        </el-form>
        <el-table v-loading="loading" :data="records" stripe border>
          <el-table-column prop="display_name" label="名称" min-width="220" />
          <el-table-column prop="source_key" :label="tab === 'BOOK' ? '馆内条码' : '原系统编号'" width="140" />
          <el-table-column prop="summary" label="摘要" min-width="240" show-overflow-tooltip />
          <el-table-column prop="source_time" label="记录时间" min-width="180" />
          <el-table-column label="操作" width="90"><template #default="{row}"><el-button type="primary" link @click="showRecord(row.id)">详情</el-button></template></el-table-column>
        </el-table>
        <div class="pagination-wrapper"><el-pagination v-model:current-page="page" :total="total" :page-size="20" layout="total, prev, pager, next" @current-change="fetchRecords" /></div>
      </template>
    </el-tabs>
    <el-dialog v-model="detailVisible" :title="detailTitle" width="min(850px, 94vw)">
      <el-descriptions :column="1" border>
        <el-descriptions-item v-for="(value, label) in detail" :key="label" :label="String(label)"><span class="detail-value">{{ displayValue(value, '未记录') }}</span></el-descriptions-item>
      </el-descriptions>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { getLegacyRecord, getLegacyRecords, getMigratedMembers, getMigrationSummary, type LegacyRecord, type MigratedMember } from '@/api/legacyData'
import { displayValue, displayTotalUses } from '@/utils/display'

const tab = ref('SUMMARY'), keyword = ref(''), page = ref(1), total = ref(0), loading = ref(false)
const records = ref<LegacyRecord[]>([]), members = ref<MigratedMember[]>([])
const summary = ref<Record<string, number | string> | null>(null)
const detailVisible = ref(false), detailTitle = ref(''), detail = ref<Record<string, unknown>>({})
const isRecordsTab = computed(() => ['ORDER', 'BOOK', 'PLAN'].includes(tab.value))
const summaryItems = [
  { key: 'copies', label: '迁入实体册总数', unit: ' 册' }, { key: 'bookKinds', label: '迁入图书种数', unit: ' 种' },
  { key: 'available', label: '迁入可借册数', unit: ' 册' }, { key: 'members', label: '迁入会员', unit: ' 人' },
  { key: 'activeLoans', label: '迁入在借册数', unit: ' 册' }, { key: 'returnedLoans', label: '已归还明细', unit: ' 条' },
  { key: 'loanRecords', label: '全部借还明细', unit: ' 条' }, { key: 'orders', label: '原始订单', unit: ' 单' },
  { key: 'plans', label: '借阅计划', unit: ' 种' }, { key: 'paidDeposits', label: '实缴押金合计', unit: ' 元' },
]
let requestNumber = 0
onMounted(async () => {
  loading.value = true
  try {
    const [state, people] = await Promise.all([getMigrationSummary(), getMigratedMembers()])
    members.value = people || []
    if (state?.batches.length) summary.value = JSON.parse(state.batches[0].summary_json)
  } finally { loading.value = false }
})
watch(tab, () => { requestNumber++; keyword.value = ''; page.value = 1; records.value = []; total.value = 0; if (isRecordsTab.value) fetchRecords() })
function search() { page.value = 1; fetchRecords() }
async function fetchRecords() {
  if (!isRecordsTab.value) return
  const request = ++requestNumber
  loading.value = true
  try {
    const result = await getLegacyRecords({ type: tab.value, keyword: keyword.value, page: page.value, size: 20 })
    if (request !== requestNumber) return
    records.value = result.records; total.value = result.total
  } finally { if (request === requestNumber) loading.value = false }
}
async function showRecord(id: number) {
  const record = await getLegacyRecord(id)
  try { detail.value = JSON.parse(record.details_json || '{}') }
  catch { ElMessage.error('原始资料读取失败'); return }
  detailTitle.value = record.display_name; detailVisible.value = true
}
async function showMember(userId: number) {
  const result = await getLegacyRecords({ type: 'MEMBER', page: 1, size: 1, userId })
  if (result.records[0]) await showRecord(result.records[0].id)
}
</script>

<style scoped>
.intro { color: #78716c; font-size: 14px; margin: 12px 0 20px; line-height: 1.7; }
.search { margin-top: 16px; }
.detail-value { white-space: pre-wrap; overflow-wrap: anywhere; line-height: 1.7; }
</style>
