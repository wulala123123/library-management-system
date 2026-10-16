<template>
  <section>
    <h3>逐册前后核对</h3>
    <article v-for="copy in copies" :key="copy.copyId" class="change">
      <p>第 {{ copy.ordinal }} 项 · 实体册 {{ copy.copyId }} · 条码 <code>{{ rawText(copy.barcode) }}</code>（{{ utf8Bytes(copy.barcode) }} 字节）</p>
      <p>状态 <code>{{ rawText(copy.beforeStatus) }}</code> → <code>{{ rawText(copy.afterStatus) }}</code>；版本 {{ copy.beforeVersion }} → {{ copy.afterVersion }}</p>
      <template v-if="schemaVersion === 2 && 'beforePlacement' in copy"><strong>办理前位置快照</strong><CopyPlacement :placement="copy.beforePlacement.placement" :raw-text="copy.beforePlacement.rawText" /><strong>办理后位置快照</strong><CopyPlacement :placement="copy.afterPlacement.placement" :raw-text="copy.afterPlacement.rawText" /><p>原位置来源 {{ copy.beforeLocationActionId ?? 'NULL' }} · {{ copy.locationSourceBehavior === 'KEEP' ? '保留来源' : '本次成功事件作为来源' }}<span v-if="'afterLocationActionId' in copy"> · 成功后来源 {{ copy.afterLocationActionId ?? 'NULL' }}</span></p></template>
      <p v-else-if="schemaVersion === 1">位置（V1 原记录）<code>{{ rawText(copy.beforeLocation) }}</code> → <code>{{ rawText(copy.afterLocation) }}</code></p>
      <p v-else>位置快照版本无法识别，请核对原请求。</p>
      <p>总库存增量 {{ signed(copy.deltaTotal) }} · 可借增量 {{ signed(copy.deltaAvailable) }}<span v-if="copy.restoreOfActionId"> · 恢复来源事件 {{ copy.restoreOfActionId }}</span></p>
      <p v-if="'eventId' in copy">成功事件 {{ copy.eventId }} · 库存流水 {{ copy.stockFlowId ?? '无（位置移动）' }}</p>
    </article>
    <h3>按书目汇总</h3>
    <article v-for="book in books" :key="book.bookId" class="change">
      <strong>{{ book.title }}</strong><p>书目 {{ book.bookId }} · ISBN {{ book.isbn }}</p>
      <p>总库存 {{ book.stockTotalBefore }} → {{ book.stockTotalAfter }}（{{ signed(book.deltaTotal) }}）；可借 {{ book.stockAvailableBefore }} → {{ book.stockAvailableAfter }}（{{ signed(book.deltaAvailable) }}）</p>
      <details><summary>原核对口径</summary><CopyReconciliationV2 :value="book.reconciliationV2Before" /></details>
      <CopyReconciliationV2 :value="book.reconciliationV2After" />
    </article>
  </section>
</template>
<script setup lang="ts">
import type { CopyBookChange, CopyChange, CopyActionReceiptCopy, CopyChangeV2, CopyActionReceiptCopyV2 } from '@/api/bookCopyAction'
import CopyPlacement from '@/components/locations/CopyPlacement.vue'
import CopyReconciliationV2 from './CopyReconciliationV2.vue'
import { rawText, utf8Bytes } from './copyActionPresentation'
withDefaults(defineProps<{ copies: (CopyChange | CopyActionReceiptCopy | CopyChangeV2 | CopyActionReceiptCopyV2)[]; books: CopyBookChange[]; schemaVersion?: 1 | 2 }>(), { schemaVersion: 1 })
const signed = (value: number) => value > 0 ? `+${value}` : String(value)
</script>
<style scoped>
.change { padding: 10px; margin: 8px 0; border: 1px solid var(--border-color); }p { font-size: 13px; line-height: 1.6; margin: 6px 0; }code { white-space: pre-wrap; overflow-wrap: anywhere; }
</style>
