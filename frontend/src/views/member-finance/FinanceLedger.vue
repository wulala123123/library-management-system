<template>
  <section v-if="reader.canRead.value" class="finance-ledger">
    <header class="ledger-heading"><div><h2>会员 #{{ memberId }} 财务台账</h2><p>会费与押金分别核对 · 人民币元</p></div><div class="actions"><el-button @click="emit('close')">返回会员档案</el-button><el-button :disabled="reader.financeLoading.value" @click="reader.refresh">刷新台账</el-button></div></header>
    <el-alert title="当前提供台账及来源核对，收款、退款和期初登记办理尚未开放。未知金额不等于零；历史曾缴金额不代表当前余额。" type="info" :closable="false" />
    <el-alert v-if="reader.financeError.value" :title="reader.financeError.value" type="error" :closable="false" />
    <p v-if="!memberId || !validMemberId" role="alert">会员编号无效，请返回档案重新打开。</p>
    <p v-else-if="reader.financeLoading.value && !reader.finance.value" role="status">正在读取财务台账…</p>
    <template v-if="reader.finance.value">
      <p class="scope">统计来源：会员财务台账 · 已登记资料截至 <time>{{ reader.finance.value.asRecordedAt }}</time>（UTC）。{{ coverageText }}</p>
      <p>余额仅反映已核实期初与后续有效收退，不代表应收、欠费或已获准退款金额。会费退款登记不会自动改变服务期、次数或套餐权益。</p>
      <div class="accounts">
        <article v-for="account in reader.finance.value.accounts" :key="account.fund" :aria-label="`${financeFundLabels[account.fund]}账户核对`" class="account">
          <h3>{{ financeFundLabels[account.fund] }}</h3>
          <p>{{ account.openingState === 'VERIFIED' ? '期初已核实' : '期初未核实，完整余额未知' }}</p>
          <dl><dt>已核实累计实缴</dt><dd>{{ financeAmountText(account.verifiedPaid) }}</dd><dt>已核实累计已退</dt><dd>{{ financeAmountText(account.verifiedRefunded) }}</dd><dt>已核实余额</dt><dd>{{ financeAmountText(account.balance) }}</dd><template v-if="account.feeHeadroom"><dt>会费退款金额上限（仅供核对）</dt><dd>{{ financeAmountText(account.feeHeadroom) }}</dd></template></dl>
          <h4>新登记收退小计</h4><dl><dt>有效收款</dt><dd>{{ account.newReceipts }} 元</dd><dt>有效退款</dt><dd>{{ account.newRefunds }} 元</dd><dt>收退净额</dt><dd>{{ account.newNet }} 元</dd></dl><p>以上小计不包含历史期初；期初未知时，小计不能代替完整余额。</p>
          <p v-if="account.scopeCutoffAt">期初范围：{{ account.scopeCutoffAt }}（UTC）之前；后续实际收退从该时点起单独记录。</p><p v-else>期初覆盖边界尚未记录。</p>
          <p class="meta">账户编号 {{ account.accountId ?? '尚无台账账户' }} · 财务修订 {{ account.financeRevision }}<span v-if="account.openingEntryId"> · 期初条目 #{{ account.openingEntryId }}</span></p>
        </article>
      </div>
      <section class="source-section">
        <h3>原来源对照</h3><p>旧业务表金额与原档案字段均为待核实的来源事实。旧导入保存的 0.00 不能证明明确零期初；“押金”原字段语义待核实，不据文本推算已退金额。</p>
        <p v-if="reader.sourceLoading.value" role="status">正在读取原来源…</p>
        <el-alert v-if="reader.sourceError.value" :title="reader.sourceError.value" type="error" :closable="false" /><el-button v-if="reader.sourceError.value" @click="reader.retrySource">重试来源读取</el-button>
        <template v-if="reader.source.value">
          <p>{{ sourceStateLabels[reader.source.value.sourceState] }} · 读取于 {{ reader.source.value.asOf }}（UTC）</p>
          <p v-for="reason in reader.source.value.reasonCodes" :key="reason">{{ sourceReasonText(reason) }}</p>
          <dl class="profile"><dt>原业务档案曾缴会费</dt><dd>{{ reader.source.value.profile.legacyPaidFee === null ? '未记录' : `${reader.source.value.profile.legacyPaidFee} 元（未核实）` }}</dd><dt>原业务档案曾缴押金</dt><dd>{{ reader.source.value.profile.legacyDepositAmount === null ? '未记录' : `${reader.source.value.profile.legacyDepositAmount} 元（未核实）` }}</dd><dt>原业务档案押金状态</dt><dd class="raw">{{ financeOptionalText(reader.source.value.profile.legacyDepositStatus) }}</dd><dt>原业务档案来源键</dt><dd class="raw">{{ financeOptionalText(reader.source.value.profile.sourceKey) }}</dd><dt>原业务档案来源系统</dt><dd>{{ financeOptionalText(reader.source.value.profile.sourceSystem) }}</dd></dl>
          <p v-if="reader.source.value.archive" class="raw">原系统档案 #{{ reader.source.value.archive.archiveId }} · 原来源键 {{ reader.source.value.archive.sourceKey }}</p>
          <p v-else>没有可用的原系统档案关联。</p>
          <p>字符串保留原文本；来源数值按数据库投影显示，不能据其显示格式认定历史精度，仍需核实金额。</p>
          <table class="source-table"><thead><tr><th>原字段</th><th>来源值（未经金额核实）</th><th>来源值类型</th></tr></thead><tbody><tr v-for="field in reader.source.value.fields" :key="field.key"><th>{{ field.key }}{{ field.key === '押金' ? '（语义待核实）' : '' }}</th><td class="raw">{{ financeSourceText(field) }}</td><td>{{ sourceTypeLabels[field.jsonType] }}</td></tr></tbody></table>
        </template>
      </section>
      <section class="entries-section">
        <div class="ledger-heading"><h3>登记条目</h3><label>资金类别 <select v-model="reader.fund.value" aria-label="台账资金类别"><option value="MEMBER_FEE">会费</option><option value="DEPOSIT">押金</option></select></label></div>
        <p>按条目编号倒序读取，编号是稳定记录标识，不是列表序号。分页始终使用同一已登记资料截至时点；刷新后重新读取最新资料。</p>
        <p v-if="reader.entriesLoading.value" role="status">正在读取{{ financeFundLabels[reader.fund.value] }}条目…</p>
        <el-alert v-if="reader.entriesError.value" :title="reader.entriesError.value" type="error" :closable="false" /><el-button v-if="reader.entriesError.value" @click="reader.retryEntries">重试本页读取</el-button>
        <template v-if="reader.page.value">
          <p>本次分页截至 {{ reader.page.value.asRecordedAt }}（UTC）；以下条目的有效性均按该时点判断。</p>
          <p v-if="!reader.page.value.entries.length" class="empty-ledger">当前资金类别在本次时点没有登记条目。这不表示历史实缴、余额或欠费为零。</p>
          <article v-for="entry in reader.page.value.entries" :key="entry.id" class="entry" :aria-label="`财务条目${entry.id}`">
            <header class="entry-heading"><h4>条目 #{{ entry.id }} · {{ financeKindLabels[entry.kind] }}</h4><strong>{{ entry.amount }} 元</strong></header>
            <p>{{ financeEntryState(entry) }}</p>
            <dl><dt>系统登记时刻</dt><dd>{{ entry.recordedAt }}（UTC）</dd><dt>实际收退时刻</dt><dd>{{ entry.actualAt ? `${entry.actualAt}（UTC）` : '不适用 / 未记录实际收退时刻' }}</dd><dt>登记操作者编号</dt><dd>{{ entry.operatorId }}</dd><dt>柜台支付方式原登记值</dt><dd>{{ financeOptionalText(entry.methodCode) }}</dd><dt>登记原因</dt><dd class="raw">{{ entry.reason }}</dd><dt>业务关联</dt><dd class="raw">{{ businessTypeLabels[entry.businessRef.type] }} · {{ entry.businessRef.id }}</dd></dl>
            <p v-if="entry.kind === 'OPENING'">核实期初累计实缴 {{ entry.openingReceived }} 元，累计已退 {{ entry.openingRefunded }} 元，覆盖 {{ entry.cutoffAt }}（UTC）之前。期初不计入新登记收退小计。</p>
            <p v-if="entry.reversesEntryId">冲正原条目 #{{ entry.reversesEntryId }}；保留原登记及依据，不产生新的现金动作。</p><p v-if="entry.replacesEntryId">替代原条目 #{{ entry.replacesEntryId }}，属于原事实的纠错登记。</p><p v-if="entry.reversalEntryId">由冲正条目 #{{ entry.reversalEntryId }} 抵销。</p>
            <section v-if="entry.allocations.length"><h5>本笔退款分摊</h5><ul><li v-for="allocation in entry.allocations" :key="allocation.lotEntryId">收款批次条目 #{{ allocation.lotEntryId }}：{{ allocation.amount }} 元</li></ul></section>
            <h5>核实依据 / 凭证引用</h5><ul><li v-for="(evidence,index) in entry.evidenceRefs" :key="index" class="raw">{{ evidenceTypeLabels[evidence.type] }} · {{ evidence.reference }}<span v-if="evidence.digest"> · 凭证摘要 {{ evidence.digest }}</span></li></ul>
          </article>
          <nav aria-label="财务条目分页" class="actions"><el-button :disabled="reader.pageIndex.value === 0 || reader.entriesLoading.value" @click="reader.previous">上一页</el-button><span>第 {{ reader.pageIndex.value + 1 }} 页</span><el-button :disabled="!reader.page.value.hasMore || reader.entriesLoading.value" @click="reader.next">下一页</el-button></nav>
        </template>
        <el-button v-else-if="reader.pageIndex.value > 0 && !reader.entriesLoading.value" @click="reader.previous">返回上一页</el-button>
      </section>
    </template>
  </section>
  <section v-else class="finance-unavailable"><h2 ref="accessNotice" tabindex="-1">会员财务台账</h2><p role="alert">{{ reader.accessRevoked.value ? '财务读取权限已失效，内容已清除。请重新登录并核对权限。' : '仅管理员可读取会员财务台账。' }}</p><el-button @click="emit('close')">返回会员档案</el-button></section>
</template>
<script setup lang="ts">
import { computed, nextTick, ref, toRef, watch } from 'vue'
import { useUserStore } from '@/store/modules/user'
import { financeId } from '@/api/memberFinance'
import { useFinanceReadSession } from './financeReadSession'
import { financeAmountText, financeEntryState, financeFundLabels, financeKindLabels, financeOptionalText, financeSourceText, sourceReasonText, sourceStateLabels, sourceTypeLabels, businessTypeLabels, evidenceTypeLabels } from './financeDisplay'
const props=withDefaults(defineProps<{memberId:string|null;active?:boolean}>(),{active:true}),emit=defineEmits<{(e:'close'):void}>()
const user=useUserStore(),identity=computed(()=>({id:user.userInfo?.id===undefined?null:String(user.userInfo.id),role:user.userInfo?.role??null,token:user.token||''}))
const reader=useFinanceReadSession(toRef(props,'memberId'),identity,toRef(props,'active')),validMemberId=computed(()=>financeId(props.memberId))
const accessNotice=ref<HTMLElement|null>(null)
watch(reader.canRead,canRead=>{if(!canRead)nextTick(()=>accessNotice.value?.focus())})
const coverageText=computed(()=>reader.finance.value?.historicalCoverage==='COMPLETE'?'历史收退覆盖完整。':reader.finance.value?.historicalCoverage==='PARTIAL'?'历史收退仅部分核实。':'历史收退覆盖范围未知。')
defineExpose({reader})
</script>
<style scoped>
.finance-ledger{max-width:1250px;margin:auto}.ledger-heading,.entry-heading{display:flex;align-items:center;justify-content:space-between;gap:16px;flex-wrap:wrap}.actions{display:flex;gap:12px;align-items:center;flex-wrap:wrap}.accounts{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:18px;margin:22px 0}.account,.source-section,.entries-section{background:#fff;border:1px solid #dbe4df;border-radius:10px;padding:20px;margin:20px 0}.account{margin:0}.entry{border:1px solid #dbe4df;border-radius:8px;padding:18px;margin:18px 0}dl{display:grid;grid-template-columns:minmax(120px,1fr) 2fr;gap:10px 16px}dt{color:#5a6962}dd{margin:0;overflow-wrap:anywhere}.raw{white-space:pre-wrap;overflow-wrap:anywhere}.source-table{width:100%;border-collapse:collapse}.source-table td,.source-table th{padding:10px;border:1px solid #dbe4df;text-align:left}.scope,.meta{font-size:13px;color:#52655c}p,li{line-height:1.7}select{padding:8px;border:1px solid #c6d5cc;border-radius:6px;background:#fff}h5{font-size:14px;margin:16px 0 8px}strong{font-size:18px}.empty-ledger{padding:18px;background:#f6f9f7}.finance-unavailable{padding:24px;background:white}@media(max-width:780px){.accounts{grid-template-columns:1fr}dl{grid-template-columns:1fr}dd{padding-bottom:8px}.account,.source-section,.entries-section{padding:14px}.source-table{font-size:13px}}
</style>
