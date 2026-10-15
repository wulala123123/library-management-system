<template>
  <div class="rule-controls">
    <el-select :model-value="modelValue.mode" :aria-label="label + '类型'" @update:model-value="changeMode" style="width: 150px">
      <el-option value="UNKNOWN" label="待核实" /><el-option value="LIMITED" label="有限" /><el-option value="UNLIMITED" label="明确不限" />
    </el-select>
    <template v-if="modelValue.mode === 'LIMITED'"><el-input-number :model-value="modelValue.value" :min="0" :precision="0" :placeholder="label + '数值'" @update:model-value="changeValue" /><span>{{ unit }}</span></template>
    <span v-else class="hint">{{ modelValue.mode === 'UNKNOWN' ? '待核实' : '不限' }}</span>
  </div>
</template>
<script setup lang="ts">
import type { LimitMode, PlanLimit } from '@/api/membershipPlan'
const props = defineProps<{ modelValue: PlanLimit; label: string; unit: string }>()
const emit = defineEmits<{ (e: 'update:modelValue', value: PlanLimit): void }>()
function changeMode(mode: LimitMode) { emit('update:modelValue', { mode, value: mode === 'LIMITED' ? props.modelValue.value : null }) }
function changeValue(value: number | undefined | null) { emit('update:modelValue', { ...props.modelValue, value: value ?? null }) }
</script>
<style scoped>
.rule-controls { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; } .hint { color: var(--text-secondary); }
</style>
