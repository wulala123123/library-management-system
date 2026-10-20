<template>
  <section v-if="session.canAccess.value" class="receipt-composer" aria-label="登记已收款">
    <h3>登记当天已收款</h3>
    <p>仅登记已实际收到的现金或微信，不执行付款，不改变套餐、服务期或次数。会费和押金须分别登记。期初未核实时，总余额和可退额仍为未知。</p>
    <p>实际支付时间一律填写北京时间（Asia/Shanghai）。日期范围、正式启用日及能否办理由服务器核验；本机日期不作为依据。</p>
    <p>跨日失效后，不得把昨日实际收款时间改成今天；历史补录须按后续规则另行处理。实际收款时刻不会由系统当前时刻自动代填。</p>
    <p v-if="!session.storageReady.value" role="status">{{session.error.value || '正在检查安全恢复存储；写入暂不可用，只读台账继续。'}}</p>
    <p v-if="session.error.value" role="alert">{{session.error.value}}</p>
    <p v-if="session.busy.value" role="status">正在核对原办理，请勿另开一笔重复登记…</p>
    <template v-if="session.record.value">
      <p class="recovery">存在待核对登记：会员 #{{session.record.value.memberId}} · {{stageText[session.record.value.stage]}}。关闭页面或暂未查询到结果都不表示未入账；请先恢复登记。</p>
      <p v-if="!session.targetMatches.value">请返回并打开原会员台账恢复此办理，当前会员不能开始第二笔。</p>
      <template v-else>
        <details><summary>登记恢复编号（核对用）</summary><p>{{session.record.value.operationKey}}</p></details>
        <div class="controls"><button :disabled="session.busy.value" @click="session.observe">恢复登记</button><button :disabled="session.busy.value" @click="session.resolve">核对登记结果</button></div>
        <p v-if="session.operation.value">登记状态：{{stateText[session.operation.value.state]}} · 核对时刻：{{beijingTime(session.operation.value.serverNow)}}（北京时间）</p>
        <p v-if="!session.terminal.value && !session.owns.value && session.operation.value">此页面尚无编辑权。接管会使旧页面失去编辑权；同一服务端草稿不能修改。</p>
        <button v-if="!session.terminal.value && !session.owns.value && session.operation.value" :disabled="session.busy.value" @click="session.claim">接管当前登记</button>
        <p v-if="session.operation.value?.state==='RESERVED'">若本页已无原填写内容，请先取消本次登记，再重新录入；不能猜测金额。</p>
        <button v-if="session.owns.value && session.operation.value?.state==='RESERVED'" :disabled="session.busy.value" @click="session.retryDraft">继续保存本页登记</button>
        <section v-if="input && input.action==='RECEIPT'" class="frozen" aria-label="服务端冻结收款">
          <h4>已保存的收款登记</h4><dl><dt>资金类别</dt><dd>{{financeFundLabels[input.fund]}}</dd><dt>金额</dt><dd>{{input.payload.amount}} 元</dd><dt>实际收款时刻</dt><dd>{{beijingTime(input.payload.actualAt)}}（北京时间）</dd><dt>已收款方式</dt><dd>{{input.payload.methodCode==='CASH'?'现金':input.payload.methodCode==='WECHAT'?'微信':'方式待核对'}}</dd><dt>登记依据</dt><dd class="raw">{{input.reason}}</dd><dt>凭证引用</dt><dd class="raw"><p v-for="(e,i) in input.evidenceRefs" :key="i">{{e.reference}}</p></dd></dl>
          <details><summary>同笔款项原登记编号（重新录入核对用）</summary>{{input.payload.factKey}}</details>
          <p>修改必须先取消本次登记，再为同一笔款项沿用原同笔款项编号。使用不同编号的人为重复无法保证自动识别。</p>
        </section>
        <button v-if="session.owns.value && session.operation.value?.state==='DRAFT_READY'" :disabled="session.busy.value" @click="session.previewDraft">查看登记预览</button>
        <section v-if="session.preview.value" aria-label="服务器收款预览">
          <h4>确认前核对</h4><p>会员 #{{session.preview.value.identity.memberId}} · {{session.preview.value.identity.realName??'姓名未记录'}} · 卡号 {{session.preview.value.identity.cardNo??'未记录'}}</p>
          <p>预计完整余额：{{financeAmountText(session.preview.value.impact.after.balance)}}；预计累计实缴：{{financeAmountText(session.preview.value.impact.after.verifiedPaid)}}。新收款不使未知期初变为已核实。</p>
          <p v-if="session.preview.value.policy.cutoffAt">登记起点 {{beijingTime(session.preview.value.policy.cutoffAt)}}（北京时间），首批只登记服务端北京时间当天已实际收款。</p>
          <ul v-if="session.preview.value.blocks.length" role="alert"><li v-for="(block,i) in session.preview.value.blocks" :key="i">{{block.message}}</li></ul>
          <p v-if="!session.preview.value.canConfirm">服务器未允许确认。请先处理阻断条件，不绕过政策。</p>
          <label v-if="!session.terminal.value"><input v-model="reviewed" type="checkbox" :disabled="session.busy.value">我已核对会员、资金类别、金额、实际时间及依据；这是已收款登记，不执行付款、不改权益。</label>
          <button v-if="!session.terminal.value" :disabled="!reviewed || !session.canConfirm.value" @click="confirm">确认登记这笔已收款</button>
        </section>
        <button v-if="!session.terminal.value" :disabled="session.busy.value || (!!session.operation.value && !session.owns.value && !session.record.value.cancelIntent)" @click="session.cancel">{{session.record.value.cancelIntent?'核对原取消结果':'取消本次登记'}}</button>
        <section v-if="session.terminal.value" aria-label="原办理终态">
          <h4>{{session.terminal.value==='COMMITTED'?'本笔已登记入账':'本次登记'+stateText[session.terminal.value]}}</h4>
          <p v-if="session.cancelReceipt.value?.outcome==='ALREADY_COMMITTED'">确认先完成，取消未撤销已入账事实。以下为原回执。</p>
          <template v-if="session.receipt.value"><p>原回执 {{session.receipt.value.requestId}} · 确认时刻 {{beijingTime(session.receipt.value.confirmedAt)}}（北京时间）</p><p v-for="entry in session.receipt.value.entriesAtCommit" :key="entry.id">条目 #{{entry.id}} · {{financeFundLabels[session.receipt.value.fund]}} · {{entry.amount}} 元</p><p>提交时完整余额：{{financeAmountText(session.receipt.value.audit.after.balance)}}。权益未变，不执行付款。</p></template>
          <p>核对登记结果后才可结束本次办理。同笔款项重新录入时须沿用原登记编号。</p>
          <button v-if="session.terminal.value!=='COMMITTED' && !session.reentering.value" :disabled="session.busy.value" @click="session.reenterSame">已核对未入账，为同笔款项重新录入</button>
          <button :disabled="session.busy.value" @click="acknowledge">{{session.terminal.value==='COMMITTED'?'已核对登记凭证，完成本次登记':'已核对结果，结束本次登记'}}</button>
        </section>
      </template>
    </template>
    <form v-if="(!session.record.value || session.reentering.value) && session.storageReady.value && session.scanned.value" @submit.prevent="session.start">
      <label>资金类别<select v-model="session.form.value.fund" aria-label="收款资金类别"><option value="MEMBER_FEE">会费</option><option value="DEPOSIT">押金</option></select></label>
      <label>已收金额（人民币元，最多两位小数）<input v-model="session.form.value.amount" aria-label="已收金额" inputmode="decimal" required autocomplete="off"></label>
      <label>实际收到时间（北京时间）<input v-model="session.form.value.actualAt" aria-label="实际收款北京时间" type="datetime-local" step="1" required autocomplete="off"></label>
      <label>已收方式<select v-model="session.form.value.methodCode" aria-label="已收方式"><option value="CASH">现金</option><option value="WECHAT">微信</option></select></label>
      <label>登记依据<textarea v-model="session.form.value.reason" aria-label="收款登记依据" required maxlength="1000" autocomplete="off"/></label>
      <label>凭证引用<input v-model="session.form.value.evidence" aria-label="收款凭证引用" required maxlength="128" autocomplete="off"></label>
      <details><summary>同笔款项重新录入：核对原登记编号</summary><label>原登记编号<input v-model="session.factKey.value" :readonly="session.reentering.value" aria-label="同笔款项原登记编号" placeholder="首次登记无需填写；同一笔重新录入须沿用" autocomplete="off"></label></details>
      <p>同笔款项编号用于同一笔款项跨办理防重复；不同编号的人为重复不能保证识别。金额、姓名及依据仅保留内存和服务端，不写入本机恢复存储。</p>
      <button :disabled="!session.canStart.value" type="submit">保存并核对已收款</button>
    </form>
  </section>
</template>
<script setup lang="ts">
import { computed, ref, toRef, watch } from 'vue'
import type { MemberFinance } from '@/api/memberFinance'
import type { FinanceReaderIdentity } from './financeReadSession'
import { beijingTime, useFinanceReceiptSession } from './financeReceiptSession'
import { financeAmountText, financeFundLabels } from './financeDisplay'
const props=defineProps<{memberId:string|null;identity:FinanceReaderIdentity;summary:MemberFinance|null;active:boolean}>()
const emit=defineEmits<{(e:'settled'):void}>()
const session=useFinanceReceiptSession(toRef(props,'memberId'),toRef(props,'identity'),toRef(props,'summary'),toRef(props,'active'))
const input=computed(()=>session.operation.value?.input??null),reviewed=ref(false)
const stateText={RESERVED:'待保存',DRAFT_READY:'待预览',PREVIEWED:'待确认',COMMITTED:'已入账',CANCELLED:'已取消',EXPIRED:'已过期',REJECTED:'未通过核验'}
const stageText={RESERVING:'正在保留登记',DRAFTING:'正在保存',PREVIEWING:'正在核对预览',ACTIVE:'待核对',CONFIRMING:'正在核对入账结果',CANCELLING:'正在核对取消结果',TERMINAL_UNACKNOWLEDGED:'结果待您核对'}
watch(()=>[session.operation.value?.operationKey,session.operation.value?.editor.epoch,session.operation.value?.request?.previewDigest,session.canAccess.value],()=>{reviewed.value=false},{flush:'sync'})
function confirm(){if(reviewed.value)void session.confirm()}
async function acknowledge(){await session.acknowledge();if(!session.record.value&&session.canAccess.value)emit('settled')}
defineExpose({session})
</script>
<style scoped>
.receipt-composer{background:#fff;border:1px solid #dbe4df;border-radius:10px;padding:20px;margin:20px 0}form{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:16px}label{display:flex;flex-direction:column;gap:6px;margin:8px 0}input,select,textarea,button{font:inherit;padding:9px;border:1px solid #bacbc1;border-radius:6px}button{cursor:pointer;margin:8px 8px 8px 0;background:#eef7f1}button:disabled{opacity:.55;cursor:not-allowed}label:has(input[type=checkbox]){display:block}p,li{line-height:1.7}.recovery{background:#fff5de;padding:12px}.raw{white-space:pre-wrap;overflow-wrap:anywhere}dl{display:grid;grid-template-columns:150px 1fr;gap:10px}dd{margin:0;overflow-wrap:anywhere}.frozen{border-block:1px solid #dbe4df;padding:12px 0}@media(max-width:780px){form{grid-template-columns:1fr}dl{grid-template-columns:1fr}}
</style>
