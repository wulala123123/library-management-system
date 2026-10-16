<template>
  <div class="attribute-filters">
    <label>年龄资料 <select :value="modelValue.ageState" @change="setAgeState"><option value="">不限</option><option value="KNOWN">已设置</option><option value="UNKNOWN">未设置</option></select></label>
    <fieldset :disabled="modelValue.ageState === 'UNKNOWN'"><legend>任一年龄标签（匹配任一所选标签）</legend><label v-for="item in ageOptions" :key="item.code"><input type="checkbox" :checked="modelValue.ageLabels.includes(item.code)" @change="toggleAge(item.code, $event)" />{{ item.label }}</label></fieldset>
    <label>装帧资料 <select :value="modelValue.bindingState" @change="setBindingState"><option value="">不限</option><option value="KNOWN">已设置</option><option value="UNKNOWN">未设置</option></select></label>
    <label>装帧 <select :value="modelValue.bindingCode" :disabled="modelValue.bindingState === 'UNKNOWN'" @change="setBinding"><option value="">不限</option><option v-for="(label, code) in bindingLabels" :key="code" :value="code">{{ label }}</option></select></label>
  </div>
</template>
<script setup lang="ts">
import type { AgeLabel, BindingCode } from '@/api/catalogAttribute'
import { ageOptions, bindingLabels, type AttributeFilters } from '@/utils/catalogAttributes'
const props = defineProps<{ modelValue: AttributeFilters }>()
const emit = defineEmits<{ 'update:modelValue': [value: AttributeFilters]; change: [] }>()
function update(value: Partial<AttributeFilters>) { emit('update:modelValue', { ...props.modelValue, ...value }); emit('change') }
function setAgeState(event: Event) { const ageState = (event.target as HTMLSelectElement).value as AttributeFilters['ageState']; update({ ageState, ...(ageState === 'UNKNOWN' ? { ageLabels: [] } : {}) }) }
function toggleAge(code: AgeLabel, event: Event) { update({ ageLabels: ageOptions.filter(item => item.code === code ? (event.target as HTMLInputElement).checked : props.modelValue.ageLabels.includes(item.code)).map(item => item.code) }) }
function setBindingState(event: Event) { const bindingState = (event.target as HTMLSelectElement).value as AttributeFilters['bindingState']; update({ bindingState, ...(bindingState === 'UNKNOWN' ? { bindingCode: '' } : {}) }) }
function setBinding(event: Event) { update({ bindingCode: (event.target as HTMLSelectElement).value as '' | BindingCode }) }
</script>
<style scoped>
.attribute-filters { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; margin: 12px 0; font-size: 13px; }fieldset { display: flex; gap: 10px; padding: 8px 12px; border: 1px solid #dce4ef; border-radius: 6px; }label { display: inline-flex; gap: 5px; align-items: center; }select { padding: 5px; border: 1px solid #dcdfe6; border-radius: 4px; }
</style>
