<template>
  <section>
    <el-alert title="入库成功，请按本次回执核对实物。已有条码沿用原贴标。" type="success" :closable="false" />
    <p>回执 {{ receipt.intakeId }} · {{ receipt.confirmedAt }} · 本次 {{ receipt.copies.length }} 册</p>
    <el-table :data="receipt.books" border>
      <el-table-column prop="bookId" label="书目 ID" /><el-table-column prop="addedQuantity" label="本次新增" />
      <el-table-column label="总库存"><template #default="{ row }">{{ row.stockTotalBefore }} → {{ row.stockTotalAfter }}</template></el-table-column>
      <el-table-column label="可借库存"><template #default="{ row }">{{ row.stockAvailableBefore }} → {{ row.stockAvailableAfter }}</template></el-table-column>
    </el-table>
    <p v-for="progress in receipt.purchaseProgress" :key="progress.purchaseItemId">采购项 {{ progress.purchaseItemId }}：本次 {{ progress.receivingQuantity }} 册，累计 {{ progress.instockQuantity }} / {{ progress.quantity }} 册，仍待收 {{ progress.pendingQuantity }} 册</p>
    <details><summary>本次全部实体册</summary><p v-for="copy in receipt.copies" :key="copy.copyId">{{ copy.input === 'GENERATED' ? '新生成码' : '已有条码' }} <code>{{ visibleBarcode(copy.barcode) }}</code> · {{ copy.location === null ? '未分配位置' : copy.location }}</p></details>
    <template v-if="generated.length">
      <h3>新生成码贴标</h3>
      <p>以下仅包含本次成功回执的新生成码。使用 140mm × 45mm 或更大的纸张，以实际尺寸打印；先试扫一张再贴标。</p>
      <el-alert v-if="invalidGenerated" title="回执包含异常生成码，请先核对，暂不能打印。" type="error" :closable="false" />
      <el-button type="primary" :disabled="invalidGenerated" @click="printLabels">打印本次新生成码（{{ generated.length }} 张）</el-button>
      <div ref="labels" class="labels">
        <article v-for="copy in generated" :key="copy.copyId" class="barcode-label">
          <div class="label-title">{{ titles[copy.lineKey] || `书目 ${copy.bookId}` }}</div>
          <GeneratedBarcode v-if="!invalidGenerated" :barcode="copy.barcode" />
          <div class="barcode-text">{{ copy.barcode }}</div>
        </article>
      </div>
    </template>
  </section>
</template>
<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import type { IntakeReceipt } from '@/api/inventoryIntake'
import GeneratedBarcode from './GeneratedBarcode.vue'
import { visibleBarcode } from './intakeDraft'
const props = defineProps<{ receipt: IntakeReceipt; titles: Record<string, string> }>()
const labels = ref<HTMLElement>()
const generated = computed(() => props.receipt.copies.filter(copy => copy.input === 'GENERATED'))
const invalidGenerated = computed(() => generated.value.some(copy => !/^LMS-C-[0-9a-f]{12}4[0-9a-f]{3}[89ab][0-9a-f]{15}$/.test(copy.barcode)))
function printLabels() {
  if (invalidGenerated.value || !labels.value || !generated.value.length) return
  const frame = document.createElement('iframe')
  frame.title = '本次新生成码打印'; frame.style.cssText = 'position:fixed;width:0;height:0;border:0;'
  document.body.appendChild(frame)
  const page = frame.contentDocument, target = frame.contentWindow
  if (!page || !target) { frame.remove(); ElMessage.error('无法打开打印预览，请重试。'); return }
  const style = page.createElement('style')
  style.textContent = '@page{size:140mm 45mm;margin:3mm}body{margin:0;color:#000;background:#fff}.barcode-label{width:134mm;box-sizing:border-box;break-after:page;break-inside:avoid;text-align:center;padding:1mm 3mm}.barcode-label:last-child{break-after:auto}.label-title{font:12px sans-serif;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}svg{display:block;width:128mm;height:auto;margin:1mm auto}.barcode-text{font:12px monospace;white-space:pre}'
  page.head.appendChild(style)
  // Clone already rendered SVG + text nodes. User metadata is never interpolated into HTML.
  for (const label of labels.value.children) page.body.appendChild(label.cloneNode(true))
  target.addEventListener('afterprint', () => frame.remove(), { once: true })
  try { target.focus(); target.print() } catch { frame.remove(); ElMessage.error('打印预览失败，请重试。') }
}
</script>
<style scoped>
.labels { overflow-x: auto; margin-top: 16px; }.barcode-label { width: 140mm; padding: 12px; box-sizing: border-box; border: 1px dashed var(--border-color); margin: 12px 0; color: #000; background: white; text-align: center; }
.label-title { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }.barcode-text { font-family: monospace; white-space: pre; font-size: 13px; } code { white-space: pre-wrap; overflow-wrap: anywhere; }
</style>
