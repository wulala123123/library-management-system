<template>
  <section v-if="session.input.value" class="request-panel">
    <h3>原请求：{{ operationLabels[session.input.value.operation] }} · {{ targetName }}</h3>
    <p>操作编号 <code>{{ session.input.value.operationKey }}</code></p>
    <el-alert v-if="session.denied.value" title="当前权限不足。办理已停用，原恢复资料保留；刷新页面重新核对权限。" type="error" :closable="false" />
    <el-alert v-if="session.error.value" :title="session.error.value" type="error" :closable="false" />
    <el-alert v-if="session.phase.value === 'confirming'" title="原确认结果尚未确定。请查询或重试原确认；确认期间不能取消或另办。" type="warning" :closable="false" />
    <el-alert v-if="session.phase.value === 'cancelling'" title="原取消结果尚未确定。保留原操作编号，仅查询或重试原取消。" type="warning" :closable="false" />
    <template v-if="!session.view.value"><p class="preserve">原办理原因：{{ session.input.value.reason }}</p><p v-if="'members' in session.input.value">原明确成员：{{ session.input.value.members.map(row => `#${row.bookId}`).join('、') || '无初始成员' }}</p><p v-for="row in ('members' in session.input.value ? session.input.value.members : [])" :key="row.bookId" class="preserve">#{{ row.bookId }} {{ evidenceLabels[row.evidence.kind] }} · {{ row.evidence.reference }}</p></template>
    <template v-if="session.view.value">
      <p>创建 {{ session.view.value.createdAt }} · 有效至 {{ session.view.value.expiresAt }}</p>
      <p>办理原因：<span class="preserve">{{ session.view.value.preview.reason }}</span></p>
      <el-alert v-for="warning in session.view.value.preview.warnings" :key="warning.code" :title="warning.code === 'SIMILAR_NAME' ? `有${warning.total}个同名目录，请按套装编号区分：${warning.setIds.join('、')}${warning.truncated ? '…' : ''}` : warning.code === 'COMPLETENESS_UNVERIFIED' ? '成员完整性未核实：本笔只处理明确列出的书目。' : '实物配套未核实：仍按单册借还，不改变库存、费用或扣次。'" type="warning" :closable="false" />
      <table class="compare"><thead><tr><th>套装资料</th><th>办理前</th><th>办理后</th></tr></thead><tbody>
        <tr><th>套装编号 / 版本</th><td>{{ session.view.value.preview.before.set?.setId ?? '新建' }} / {{ session.view.value.preview.before.set?.revision ?? 0 }}</td><td>{{ resultSet.setId ?? '确认后分配' }} / {{ resultSet.revision }}</td></tr>
        <tr><th>名称</th><td class="preserve">{{ session.view.value.preview.before.set?.name ?? '无' }}</td><td class="preserve">{{ resultSet.name }}</td></tr>
        <tr><th>备注</th><td class="preserve">{{ text(session.view.value.preview.before.set?.note) }}</td><td class="preserve">{{ text(resultSet.note) }}</td></tr>
        <tr><th>状态</th><td>{{ statusText(session.view.value.preview.before.set?.status) }}</td><td>{{ statusText(resultSet.status) }}</td></tr>
        <tr><th>成员书目</th><td>有效状态{{ session.view.value.preview.before.set?.activeMemberCount ?? 0 }}种 / 已退役{{ session.view.value.preview.before.set?.retiredMemberCount ?? 0 }}种</td><td>有效状态{{ resultSet.activeMemberCount }}种 / 已退役{{ resultSet.retiredMemberCount }}种</td></tr>
      </tbody></table>
      <p v-if="'evidence' in session.input.value">本次套装依据：{{ evidenceLabels[session.input.value.evidence.kind] }} · <span class="preserve">{{ session.input.value.evidence.reference }}</span></p>
      <p>本笔增加{{ impact.addedMembers }}种、退役{{ impact.retiredMembers }}种、恢复{{ impact.restoredMembers }}种；未触及的有效状态关系{{ impact.unchangedActiveMembers }}种、退役关系{{ impact.unchangedRetiredMembers }}种，均保持原样。</p>
      <el-table :data="session.view.value.preview.after.members" max-height="380" row-key="bookId">
        <el-table-column label="明确成员书目" min-width="220"><template #default="{ row }">#{{ row.bookId }} {{ book(row.bookId)?.book.displayTitle }}<br />登记名 {{ book(row.bookId)?.book.catalogTitle }}<br />ISBN {{ book(row.bookId)?.book.isbn }}</template></el-table-column>
        <el-table-column label="关系前 → 后 / 原版本" min-width="210"><template #default="{ row }">{{ beforeMember(row.bookId)?.membershipId ?? '新关系' }} · {{ statusText(beforeMember(row.bookId)?.status) }} → {{ statusText(row.member.status) }}<br />关系版本 {{ beforeMember(row.bookId)?.revision ?? 0 }} → {{ row.member.revision }}<br />书目版本 {{ book(row.bookId)?.book.revision }} / 展示版本 {{ book(row.bookId)?.book.attributeRevision }}</template></el-table-column>
        <el-table-column label="逐条本次依据" min-width="240"><template #default="{ row }">{{ evidenceLabels[row.member.evidence.kind as keyof typeof evidenceLabels] }}<p class="preserve">{{ row.member.evidence.reference }}</p></template></el-table-column>
      </el-table>
    </template>
    <el-alert v-if="session.receipt.value" :title="`${session.receipt.value.changed ? '办理成功' : '已确认，无业务变化'}：套装 #${session.receipt.value.result.set.setId}；请求 #${session.receipt.value.requestId}，${session.receipt.value.confirmedAt}`" type="success" :closable="false" />
    <p v-if="session.receipt.value?.result.createdSet">已建立套装 #{{ session.receipt.value.result.createdSet.setId }}，成员关系编号：{{ session.receipt.value.result.members.map(row => row.membershipId).join('、') || '无初始成员' }}</p>
    <el-alert v-if="session.phase.value === 'cancelled'" title="原预览已安全取消，可以修改资料后重新预览。" type="info" :closable="false" />
    <div class="actions">
      <el-button :disabled="session.busy.value || session.denied.value" @click="session.recover">查询原请求</el-button>
      <el-button v-if="session.phase.value === 'previewing'" :disabled="session.busy.value || session.denied.value" @click="session.retryPreview">重试原预览</el-button>
      <el-button v-if="session.phase.value === 'previewRejected'" :disabled="session.busy.value || session.denied.value" @click="emit('recheck')">重新核对并编辑</el-button>
      <el-button v-if="session.phase.value === 'ready' || session.phase.value === 'confirming'" type="primary" :disabled="session.busy.value || session.denied.value || !canConfirm" @click="session.confirm">{{ session.phase.value === 'confirming' ? '重试原确认' : '确认本笔全部变更' }}</el-button>
      <el-button v-if="session.phase.value === 'ready'" :disabled="session.busy.value || session.denied.value" @click="session.cancel('EDIT')">取消预览并编辑</el-button>
      <el-button v-if="session.phase.value === 'ready'" :disabled="session.busy.value || session.denied.value" @click="session.cancel('ABANDON')">放弃本次预览</el-button>
      <el-button v-if="session.phase.value === 'cancelling'" :disabled="session.busy.value || session.denied.value" @click="session.cancel(session.cancelReason.value!)">重试原取消</el-button>
    </div>
  </section>
</template>
<script setup lang="ts">
import { computed, ref, onBeforeUnmount } from 'vue'
import { operationLabels, evidenceLabels } from './setProtocol'
import type { useSetSession } from './setSession'
const props = defineProps<{ session: ReturnType<typeof useSetSession>; draftMatches?: boolean }>()
const emit = defineEmits<{ (e: 'recheck'): void }>()
const now = ref(Date.now()), timer = setInterval(() => { now.value = Date.now() },1000)
onBeforeUnmount(() => clearInterval(timer))
const resultSet = computed(() => props.session.receipt.value?.result.set ?? props.session.view.value!.preview.after.set)
const impact = computed(() => props.session.view.value!.preview.impact)
const targetName = computed(() => props.session.view.value?.preview.after.set.name ?? (props.session.input.value?.operation === 'CREATE' ? props.session.input.value.name : `套装 #${props.session.input.value && 'setId' in props.session.input.value ? props.session.input.value.setId : ''}`))
const canConfirm = computed(() => props.session.phase.value === 'confirming' || props.draftMatches !== false && Date.parse(props.session.view.value?.expiresAt || '') > now.value)
const book = (id: number) => props.session.view.value?.preview.books.find(row => row.book.bookId === id)
const beforeMember = (id: number) => props.session.view.value?.preview.before.members.find(row => row.bookId === id)?.member
const text = (value: string | null | undefined) => value === null || value === undefined ? '未设置' : value === '' ? '空字符串' : value
const statusText = (value?: string) => value === 'ACTIVE' ? '有效' : value === 'RETIRED' ? '已退役' : '无'
</script>
<style scoped>
.request-panel{border:1px solid #cddfd7;border-radius:10px;padding:18px;margin:16px 0;background:#fafdfb}.preserve{white-space:pre-wrap;overflow-wrap:anywhere}.compare{width:100%;border-collapse:collapse;margin:16px 0}.compare td,.compare th{padding:9px;border:1px solid #e1e7e5;text-align:left}.actions{display:flex;gap:8px;flex-wrap:wrap;margin-top:16px}code{overflow-wrap:anywhere}.el-alert{margin:8px 0}
</style>
