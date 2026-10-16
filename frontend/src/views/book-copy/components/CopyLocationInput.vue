<template>
  <div class="copy-location">
    <el-select :model-value="modelValue.kind" :disabled="disabled" aria-label="位置登记方式" placeholder="明确选择新位置" @update:model-value="update('kind', $event)">
      <el-option value="TEXT" label="文本位置" /><el-option value="UNASSIGNED" label="明确未分配（清空）" />
      <el-option v-if="allowRegistered" value="REGISTERED" label="登记地址" />
    </el-select>
    <el-input v-if="modelValue.kind === 'TEXT'" :model-value="modelValue.text" :disabled="disabled" placeholder="本册实际位置，不继承书目位置" @update:model-value="update('text', $event)" />
    <span v-else-if="modelValue.kind === 'UNASSIGNED'">将本册位置明确设为未分配</span>
    <LocationAddressPicker v-else-if="modelValue.kind === 'REGISTERED' && allowRegistered" :model-value="modelValue.address || null" :disabled="disabled" @update:model-value="emit('update:modelValue', { ...modelValue, address: $event })" />
  </div>
</template>
<script setup lang="ts">
import type { AddressRow } from '@/api/locationAddress'
import LocationAddressPicker from '@/components/locations/LocationAddressPicker.vue'
export interface LocationDraft { kind: '' | 'TEXT' | 'UNASSIGNED' | 'REGISTERED'; text: string; address?: AddressRow | null }
const props = defineProps<{ modelValue: LocationDraft; disabled?: boolean; allowRegistered?: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: LocationDraft] }>()
function update(key: keyof LocationDraft, value: string) { emit('update:modelValue', { ...props.modelValue, [key]: value }) }
</script>
<style scoped>
.copy-location { display: flex; gap: 8px; flex-wrap: wrap; align-items: center; }.el-select { width: 210px; }.el-input { flex: 1; min-width: 200px; }
</style>
