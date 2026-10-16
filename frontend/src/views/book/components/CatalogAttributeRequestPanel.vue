<template>
  <section v-if="session.input.value || session.error.value" class="request-panel">
    <el-alert v-if="session.error.value" :title="session.error.value" :type="session.uncertain.value ? 'warning' : 'error'" :closable="false" />
    <p v-if="session.uncertain.value">原输入、key 与预览摘要已冻结，结果不明时只查询或重试原请求。</p>
    <template v-if="session.view.value && !session.receipt.value">
      <h3>单书目 #{{ session.view.value.preview.book.bookId }} 属性预览</h3><p>此预览尚未修改属性。有效至 {{ session.view.value.expiresAt }}。</p>
      <p>{{ session.view.value.preview.book.willChange ? '确认后记录本次属性及依据' : '值与来源未改变，确认回执不会增加版本或事件' }}</p>
      <table><thead><tr><th>字段</th><th>变更前</th><th>确认后</th></tr></thead><tbody><tr v-for="field in session.view.value.preview.book.changedFields" :key="field"><td>{{ attributeLabels[field as AttributeField] || field }}</td><td>{{ profileValue(session.view.value.preview.book.before, field) }}</td><td>{{ profileValue(session.view.value.preview.book.after, field) }}</td></tr></tbody></table>
      <el-button type="primary" :disabled="session.busy.value || session.rejected.value || expired || session.uncertain.value" @click="session.confirm">确认本书属性变更</el-button>
    </template>
    <section v-if="session.receipt.value" role="status"><h3>属性已独立保存</h3><p>书目 #{{ session.receipt.value.book.bookId }} · {{ session.receipt.value.book.displayTitle }} · 修订 {{ session.receipt.value.book.beforeRevision }} → {{ session.receipt.value.book.afterRevision }}</p><p>成功回执 #{{ session.receipt.value.requestId }} · {{ session.receipt.value.confirmedAt }}。未办理库存或入库。</p></section>
    <div class="buttons"><el-button v-if="session.input.value && !session.receipt.value" :loading="session.busy.value" @click="session.recover">查询原属性请求</el-button><el-button v-if="session.uncertain.value" :loading="session.busy.value" @click="session.retry">重试原属性请求</el-button><el-button v-if="!session.uncertain.value" :disabled="session.busy.value" @click="emit('reset')">{{ session.receipt.value ? '已查看回执，继续核对' : '返回修改本地输入' }}</el-button></div>
  </section>
</template>
<script setup lang="ts">
import { computed } from 'vue'
import type { AttributeField, ProfileSnapshot } from '@/api/catalogAttribute'
import { attributeLabels, ageText, bindingText } from '@/utils/catalogAttributes'
import type { useCatalogAttributeSession } from './catalogAttributeSession'
const props = defineProps<{ session: ReturnType<typeof useCatalogAttributeSession> }>()
const emit = defineEmits<{ reset: [] }>()
const expired = computed(() => !!props.session.view.value && Date.parse(props.session.view.value.expiresAt) <= Date.now())
function profileValue(profile: ProfileSnapshot, field: string) { if (field === 'ageLabels') return ageText(profile.ageLabels); if (field === 'bindingCode') return bindingText(profile.bindingCode); return JSON.stringify(profile[field as keyof ProfileSnapshot] ?? null) }
</script>
<style scoped>
.request-panel { display: grid; gap: 12px; border: 1px solid #d8e5ef; border-radius: 8px; background: #f5f9fd; padding: 16px; margin-top: 16px; }h3,p { margin: 0; }p { line-height: 1.7; font-size: 13px; color: #65758b; }.buttons { display: flex; flex-wrap: wrap; gap: 10px; }table { width: 100%; border-collapse: collapse; }th,td { text-align: left; padding: 8px; border: 1px solid #d9e3ee; white-space: pre-wrap; overflow-wrap: anywhere; }
</style>
