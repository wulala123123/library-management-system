<template>
  <section class="entitlement-rules">
    <h3>已登记借阅规则</h3>
    <el-descriptions :column="2" border>
      <el-descriptions-item v-for="row in rows" :key="row.key" :label="row.label">{{ row.value }}</el-descriptions-item>
    </el-descriptions>
    <p class="note">规则未核实的项目显示待核实。当前仅登记和展示，借阅限制尚未启用。</p>
  </section>
</template>
<script setup lang="ts">
import { computed } from 'vue'
import type { PlanRules } from '@/api/membershipPlan'
import { definitionRows, emptyDefinition } from '@/views/membership-plan/planDefinition'
const props = defineProps<{ rules: PlanRules }>()
const rows = computed(() => definitionRows({ ...emptyDefinition(), rules: props.rules }).filter(row => !['name', 'sourceReferences', 'notes', 'totalUses'].includes(row.key)))
</script>
<style scoped>
h3 { font-size: 16px; margin: 18px 0 12px; } .note { color: var(--text-secondary); font-size: 13px; line-height: 1.7; }
</style>
