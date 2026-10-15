<template>
  <div class="copy-inputs">
    <div class="copy-toolbar">
      <span>本次册数</span><el-input-number :model-value="modelValue.length" :min="0" :max="max" :precision="0" aria-label="本次登记册数" @update:model-value="resize" />
      <el-button :disabled="!modelValue.some(copy => copy.input === 'SCANNED' && copy.barcode === '')" @click="generateBlank">空白条码改为生成新码</el-button>
    </div>
    <div v-for="(copy, index) in modelValue" :key="index" class="copy-row">
      <span class="ordinal">第 {{ index + 1 }} 册</span>
      <el-select :model-value="copy.input" aria-label="条码来源" @update:model-value="change(index, 'input', $event)">
        <el-option value="SCANNED" label="已有条码" /><el-option value="GENERATE" label="生成新码" />
      </el-select>
      <el-input v-if="copy.input === 'SCANNED'" :model-value="copy.barcode" placeholder="扫描或输入已有条码，保留原文" @update:model-value="change(index, 'barcode', $event)" @keydown.enter.prevent />
      <span v-else class="generated-note">预览固定新码，成功后贴标</span>
      <el-input :model-value="copy.location" placeholder="册位置（可不填）" :maxlength="200" @update:model-value="change(index, 'location', $event)" />
      <el-button type="danger" link aria-label="移除此册" @click="emit('update:modelValue', modelValue.filter((_, i) => i !== index))">移除</el-button>
      <small v-if="copy.input === 'SCANNED' && copy.barcode">原文 {{ visibleBarcode(copy.barcode) }} · {{ barcodeBytes(copy.barcode) }} 字节</small>
    </div>
    <el-button :disabled="modelValue.length >= max" @click="emit('update:modelValue', [...modelValue, newCopy()])">+ 增加一册</el-button>
  </div>
</template>
<script setup lang="ts">
import { barcodeBytes, newCopy, visibleBarcode, type CopyDraft } from './intakeDraft'
const props = defineProps<{ modelValue: CopyDraft[]; max: number }>()
const emit = defineEmits<{ 'update:modelValue': [value: CopyDraft[]] }>()
function change(index: number, key: keyof CopyDraft, value: string) {
  emit('update:modelValue', props.modelValue.map((copy, i) => i === index ? { ...copy, [key]: value } : copy))
}
function resize(value: number | undefined | null) {
  if (!Number.isSafeInteger(value) || value === null || value === undefined || value < 0 || value > props.max) return
  emit('update:modelValue', Array.from({ length: value }, (_, index) => props.modelValue[index] || newCopy()))
}
function generateBlank() { emit('update:modelValue', props.modelValue.map(copy => copy.input === 'SCANNED' && copy.barcode === '' ? { ...copy, input: 'GENERATE' } : copy)) }
</script>
<style scoped>
.copy-toolbar { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; }
.copy-row { display: grid; grid-template-columns: 65px 130px minmax(180px, 2fr) minmax(140px, 1fr) 45px; gap: 8px; align-items: center; padding: 10px 0; border-bottom: 1px solid var(--border-color); }
.copy-row small { grid-column: 3 / 6; white-space: pre-wrap; overflow-wrap: anywhere; color: var(--text-secondary); }
.generated-note, .ordinal { font-size: 13px; }.generated-note { color: var(--text-secondary); }
@media (max-width: 720px) { .copy-row { grid-template-columns: 60px 130px 1fr; }.copy-row small { grid-column: 1 / -1; } }
</style>
