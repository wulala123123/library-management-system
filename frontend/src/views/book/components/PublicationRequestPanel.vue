<template>
  <section v-if="session.input.value || session.error.value" class="publication-request">
    <el-alert v-if="session.error.value" :title="session.error.value" :type="session.uncertain.value ? 'warning' : 'error'" :closable="false" />
    <p v-if="session.input.value">原出版资料请求 · {{ session.input.value.entries.length }} 种书目 · key <code>{{ session.input.value.operationKey }}</code></p>
    <p v-if="session.uncertain.value">输入和 key 已冻结，结果不明时只查询或重试原请求。</p>
    <template v-if="session.view.value && !session.receipt.value">
      <h3>出版资料完整预览</h3>
      <p>本次原因：{{ session.view.value.preview.reason }}</p>
      <p>预览尚未改动书目。有效至 {{ session.view.value.expiresAt }}；全批成功或全批回滚。</p>
      <section v-for="book in session.view.value.preview.books" :key="book.bookId" class="publication-book">
        <h4>#{{ book.bookId }} {{ book.before.displayTitle }}</h4>
        <p>登记书名：{{ book.before.catalogTitle }} · ISBN：{{ publicationValue(book.before.isbn) }} · 修订 {{ book.before.revision }} → {{ book.after.revision }}</p>
        <table><thead><tr><th>字段</th><th>本次操作</th><th>变更前</th><th>确认后</th></tr></thead><tbody><tr v-for="field in publicationFields" :key="field"><th>{{ publicationLabels[field] }}</th><td>{{ actionText(book.changes[field]) }}</td><td><pre>{{ publicationValue(book.before[field]) }}</pre></td><td><pre>{{ publicationValue(book.after[field]) }}</pre></td></tr></tbody></table>
        <p>馆员/管理员填写的依据：{{ evidenceLabels[book.evidence.kind] }}</p><pre>{{ book.evidence.reference }}</pre>
        <p>{{ book.willChange ? '确认后保存以上变更。' : '与当前原值相同：产生无变化回执，不新增事件或版本。' }}</p>
      </section>
      <el-button type="primary" :disabled="session.busy.value || session.uncertain.value || session.rejected.value || expired" @click="session.confirm">确认出版资料变更</el-button>
      <p v-if="expired">预览已到期，请核实原请求后返回修改并重新预览。</p>
    </template>
    <section v-if="session.receipt.value" role="status">
      <h3>出版资料已保存</h3><p>成功回执 #{{ session.receipt.value.requestId }} · {{ session.receipt.value.confirmedAt }}</p>
      <section v-for="book in session.receipt.value.books" :key="book.bookId" class="publication-book">
        <h4>#{{ book.bookId }} {{ book.publication.displayTitle }} · {{ book.changed ? '已变更' : '无变化' }}</h4>
        <p>修订 {{ book.beforeRevision }} → {{ book.afterRevision }} · 事件 {{ book.eventId ?? '无' }}</p>
        <table><tbody><tr v-for="field in publicationFields" :key="field"><th>{{ publicationLabels[field] }}</th><td><pre>{{ publicationValue(book.publication[field]) }}</pre></td></tr></tbody></table>
        <p>馆员/管理员填写的依据：{{ evidenceLabels[book.evidence.kind] }}</p><pre>{{ book.evidence.reference }}</pre>
      </section>
    </section>
    <div class="request-actions">
      <el-button v-if="session.input.value && !session.receipt.value" :disabled="session.busy.value" @click="session.recover">查询原出版资料请求</el-button>
      <el-button v-if="session.uncertain.value && session.input.value" :disabled="session.busy.value" @click="session.retry">重试原出版资料请求</el-button>
      <el-button v-if="!session.uncertain.value" :disabled="session.busy.value" @click="emit('reset')">{{ session.receipt.value ? '已查看回执，继续补录' : '返回修改本地输入' }}</el-button>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, onUnmounted, ref } from 'vue'
import type { PublicationChange } from '@/api/catalogPublication'
import { publicationFields, publicationLabels, publicationValue, evidenceLabels } from './publicationDraft'
import type { usePublicationSession } from './publicationSession'
const props = defineProps<{ session: ReturnType<typeof usePublicationSession> }>()
const emit = defineEmits<{ reset: [] }>()
const now = ref(Date.now()), timer = setInterval(() => { now.value = Date.now() }, 1000)
onUnmounted(() => clearInterval(timer))
const expired = computed(() => !props.session.view.value || now.value >= Date.parse(props.session.view.value.expiresAt))
function actionText(change: PublicationChange<string | number>) { return change === null ? '保持原值' : change.action === 'CLEAR' ? '清除为 NULL' : '明确设置' }
</script>

<style scoped>
.publication-book { border: 1px solid #dcdfe6; padding: 12px; margin: 12px 0; }
table { width: 100%; border-collapse: collapse; table-layout: fixed; }
th, td { border: 1px solid #dcdfe6; padding: 8px; text-align: left; vertical-align: top; }
pre { white-space: pre-wrap; overflow-wrap: anywhere; margin: 0; font: inherit; }
.request-actions { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 16px; }
</style>
