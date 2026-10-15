<template>
  <div class="rule-controls">
    <el-select :model-value="modelValue.mode" :aria-label="label + '类型'" @update:model-value="changeMode" style="width: 150px">
      <el-option value="UNKNOWN" label="待核实" /><el-option value="ANY" label="明确不限" /><el-option value="ALLOW_LIST" label="指定允许范围" />
    </el-select>
    <el-select v-if="modelValue.mode === 'ALLOW_LIST'" :model-value="modelValue.values" :aria-label="label + '允许项'" multiple filterable allow-create default-first-option :placeholder="'输入已核实的' + label + '后回车'" @update:model-value="changeValues" style="min-width: 260px; flex: 1">
      <el-option v-for="value in modelValue.values" :key="value" :label="value" :value="value" />
    </el-select>
    <span v-else class="hint">{{ modelValue.mode === 'UNKNOWN' ? '待核实' : '不限' }}</span>
  </div>
</template>
<script setup lang="ts">
import type { PlanScope } from '@/api/membershipPlan'
const props = defineProps<{ modelValue: PlanScope; label: string }>()
const emit = defineEmits<{ (e: 'update:modelValue', value: PlanScope): void }>()
function changeMode(mode: PlanScope['mode']) { emit('update:modelValue', { mode, values: mode === 'ALLOW_LIST' ? props.modelValue.values : [] }) }
function changeValues(values: string[]) { emit('update:modelValue', { ...props.modelValue, values }) }
</script>
<style scoped>
.rule-controls { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; width: 100%; } .hint { color: var(--text-secondary); }
</style>
