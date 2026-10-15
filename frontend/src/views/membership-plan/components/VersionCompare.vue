<template>
  <section class="version-compare">
    <h3>版本查看与比较</h3>
    <p v-if="!versions.length" class="note">尚无已发布版本。</p>
    <template v-else>
      <div class="compare-selects">
        <el-select v-model="leftId" aria-label="版本A" placeholder="选择版本 A"><el-option v-for="version in sorted" :key="version.id" :value="version.id" :label="`版本 ${version.versionNumber} · ${version.name}`" /></el-select>
        <el-select v-model="rightId" aria-label="版本B" clearable placeholder="选择版本 B 进行比较"><el-option v-for="version in sorted" :key="version.id" :value="version.id" :label="`版本 ${version.versionNumber} · ${version.name}`" /></el-select>
      </div>
      <div v-if="left" class="version-meta">版本 A：{{ left.versionNumber }} · 发布于 {{ left.createdAt }}<p>核实依据：{{ left.confirmationBasis }}</p></div>
      <div v-if="right" class="version-meta">版本 B：{{ right.versionNumber }} · 发布于 {{ right.createdAt }}<p>核实依据：{{ right.confirmationBasis }}</p></div>
      <el-table :data="rows" border size="small">
        <el-table-column prop="label" label="规则" width="150" />
        <el-table-column prop="value" :label="left ? `版本 A（${left.versionNumber}）` : '版本 A'" min-width="200" />
        <el-table-column v-if="right" prop="otherValue" :label="`版本 B（${right.versionNumber}）`" min-width="200" />
        <el-table-column v-if="right" label="比较" width="90"><template #default="{ row }"><el-tag v-if="row.changed" type="warning" size="small">有差异</el-tag><span v-else>一致</span></template></el-table-column>
      </el-table>
    </template>
  </section>
</template>
<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { PlanVersion } from '@/api/membershipPlan'
import { definitionRows } from '../planDefinition'
const props = defineProps<{ versions: PlanVersion[] }>()
const sorted = computed(() => [...props.versions].sort((a, b) => b.versionNumber - a.versionNumber))
const leftId = ref<number | null>(null), rightId = ref<number | null>(null)
watch(sorted, versions => {
  if (!versions.some(version => version.id === leftId.value)) leftId.value = versions[0]?.id ?? null
  if (!versions.some(version => version.id === rightId.value)) rightId.value = versions[1]?.id ?? null
}, { immediate: true })
const left = computed(() => props.versions.find(version => version.id === leftId.value))
const right = computed(() => props.versions.find(version => version.id === rightId.value))
const rows = computed(() => {
  const other = right.value ? definitionRows(right.value) : []
  return left.value ? definitionRows(left.value).map(row => ({ ...row, otherValue: other.find(item => item.key === row.key)?.value, changed: !!right.value && other.find(item => item.key === row.key)?.value !== row.value })) : []
})
</script>
<style scoped>
h3 { font-size: 16px; margin: 24px 0 14px; } .compare-selects { display: flex; gap: 12px; flex-wrap: wrap; }
.version-meta, .note { margin: 12px 0; color: var(--text-secondary); font-size: 13px; line-height: 1.7; overflow-wrap: anywhere; } p { margin: 4px 0; }
</style>
