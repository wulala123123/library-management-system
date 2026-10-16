<template>
  <section v-if="canRead" class="address-picker">
    <p>按编号或路径搜索登记地址。一个地址可关联多册，旧书目绑定不占册容量。</p>
    <div class="filters">
      <el-input v-model="keyword" placeholder="搜索地址编号 / 书架 / 层名称" :disabled="disabled" @keyup.enter="search" />
      <el-input-number v-model="bookshelfId" :min="1" placeholder="书架 ID（可选）" :disabled="disabled" />
      <el-select v-model="storeyId" clearable placeholder="限定书架层（可选）" :disabled="disabled || !bookshelfId"><el-option v-for="row in storeys" :key="row.id" :label="`${row.name} · 层 ${row.levelNum} · #${row.id}`" :value="row.id" /></el-select>
      <el-button :disabled="disabled" @click="search">搜索地址</el-button>
      <el-button v-if="modelValue" :disabled="disabled" @click="emit('update:modelValue', null)">清除所选地址</el-button>
    </div>
    <p v-if="error">{{ error }} <el-button :disabled="disabled" @click="load">重试地址查询</el-button></p>
    <details :open="!modelValue"><summary>查看分页地址候选</summary><div v-loading="loading" class="address-results">
      <article v-for="row in rows" :key="row.address.locationId">
        <CopyPlacement :placement="{ kind: 'REGISTERED', text: null, registeredLocation: row.address }" :show-raw="false" />
        <p>旧书目绑定 {{ row.legacyBinding.bookId ?? 'NULL' }} · 原状态 {{ JSON.stringify(row.legacyBinding.rawStatus) }}{{ row.legacyBinding.needsReview ? ' · 待核实' : '' }}；关联实体册 {{ row.linkedCopyCount }} 册（含在借及报损）</p>
        <el-button :disabled="disabled || (requireSelectable && !row.address.selectable)" @click="emit('update:modelValue', row)">{{ modelValue?.address.locationId === row.address.locationId ? '已选择此地址' : `选择地址 #${row.address.locationId}` }}</el-button>
      </article>
    </div></details>
    <el-pagination v-model:current-page="page" :total="total" :page-size="10" layout="total, prev, pager, next" :disabled="disabled" @current-change="load" />
    <div v-if="modelValue"><strong>已选目标</strong><CopyPlacement :placement="{ kind: 'REGISTERED', text: null, registeredLocation: modelValue.address }" :show-raw="false" /></div>
  </section>
</template>
<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { useUserStore } from '@/store/modules/user'
import { getLocationAddresses, type AddressRow } from '@/api/locationAddress'
import { getStoreys } from '@/api/bookshelf'
import type { BookshelfStorey } from '@/types'
import CopyPlacement from './CopyPlacement.vue'
const props = withDefaults(defineProps<{ modelValue: AddressRow | null; disabled?: boolean; requireSelectable?: boolean }>(), { requireSelectable: true })
const emit = defineEmits<{ 'update:modelValue': [value: AddressRow | null] }>()
const user = useUserStore(), canRead = computed(() => user.isAdmin || user.isLibrarian)
const keyword = ref(''), bookshelfId = ref<number>(), storeyId = ref<number>(), storeys = ref<BookshelfStorey[]>([])
const page = ref(1), total = ref(0), rows = ref<AddressRow[]>([]), loading = ref(false), error = ref('')
let generation = 0, parentGeneration = 0
onUnmounted(() => { ++generation; ++parentGeneration })
async function load() {
  if (!canRead.value || props.disabled) return
  const current = ++generation; loading.value = true; error.value = ''
  try { const result = await getLocationAddresses({ page: page.value, size: 10, keyword: keyword.value || undefined, bookshelfId: bookshelfId.value, storeyId: storeyId.value, selectableOnly: false }); if (current === generation) { rows.value = result.records; total.value = result.total } }
  catch { if (current === generation) { rows.value = []; error.value = '地址读取失败，请重试。' } }
  finally { if (current === generation) loading.value = false }
}
function search() { page.value = 1; load() }
watch(bookshelfId, async id => { storeyId.value = undefined; storeys.value = []; const current = ++parentGeneration; if (!canRead.value || !id) return; try { const result = await getStoreys(id); if (current === parentGeneration) storeys.value = result } catch { if (current === parentGeneration) error.value = '书架层读取失败，可按地址编号搜索。' } })
watch(() => props.disabled, value => { if (!value && !rows.value.length) load() }, { immediate: true })
</script>
<style scoped>
.filters { display: flex; gap: 8px; flex-wrap: wrap; }.filters .el-input { width: 240px; }.filters .el-select { width: 230px; }article { padding: 8px; margin: 8px 0; border: 1px solid var(--border-color); }p { font-size: 13px; }
.address-results { max-height: 340px; overflow-y: auto; }summary { cursor: pointer; margin: 8px 0; }
</style>
