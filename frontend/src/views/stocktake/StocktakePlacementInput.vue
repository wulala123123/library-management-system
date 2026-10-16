<template>
  <fieldset class="placement-input" :disabled="disabled">
    <legend>实际观察处</legend>
    <el-radio-group :model-value="modelValue.kind" :disabled="disabled" @update:model-value="setKind">
      <el-radio-button value="REGISTERED">结构化库位</el-radio-button>
      <el-radio-button value="TEXT">文本原文</el-radio-button>
      <el-radio-button value="UNASSIGNED">明确未分配</el-radio-button>
    </el-radio-group>
    <LocationAddressPicker v-if="modelValue.kind === 'REGISTERED'" :model-value="modelValue.address" :disabled="disabled" :require-selectable="false" @update:model-value="setAddress" />
    <el-input v-if="modelValue.kind === 'TEXT'" :model-value="modelValue.text" type="textarea" :disabled="disabled" placeholder="记录实际看到处，保留原文" @update:model-value="setText" />
    <p class="hint">这里只记录看见事实。已停用地址也可作为观察处；登记位置须另行办理移位。</p>
  </fieldset>
</template>
<script setup lang="ts">
import LocationAddressPicker from '@/components/locations/LocationAddressPicker.vue'
import type { AddressRow } from '@/api/locationAddress'
export interface ObservationPlaceDraft { kind: 'REGISTERED' | 'TEXT' | 'UNASSIGNED'; text: string; address: AddressRow | null }
const props = defineProps<{ modelValue: ObservationPlaceDraft; disabled?: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: ObservationPlaceDraft] }>()
function setKind(value: string | number | boolean | undefined) { if (value === 'REGISTERED' || value === 'TEXT' || value === 'UNASSIGNED') emit('update:modelValue', { ...props.modelValue, kind: value }) }
function setAddress(value: AddressRow | null) { emit('update:modelValue', { ...props.modelValue, address: value }) }
function setText(value: string) { emit('update:modelValue', { ...props.modelValue, text: value }) }
</script>
<style scoped>
.placement-input { border: 1px solid #dce4ef; border-radius: 8px; padding: 14px; display: grid; gap: 12px; min-width: 0; }
.hint { margin: 0; color: #65758b; font-size: 13px; }
</style>
