<template>
  <el-dialog :model-value="visible" title="书目的套装目录关系" width="min(880px, 95vw)" @update:model-value="emit('update:visible', $event)">
    <template v-if="allowed">
      <el-alert title="同一书目可以有多个有依据的套装关系。成员完整性及实物配套未核实；仍按单册借还，不改变库存、费用或扣次。" :closable="false" type="info" />
      <el-switch v-model="history" active-text="含退役历史" @change="search" />
      <p v-if="error">{{ error }}</p>
      <el-table :data="rows" v-loading="loading" row-key="membershipId">
        <el-table-column label="套装目录" min-width="220"><template #default="{ row }"><router-link :to="{ path: '/catalog-sets', query: { setId: row.setId } }" @click="emit('update:visible', false)">#{{ row.setId }} {{ row.set.name }}</router-link></template></el-table-column>
        <el-table-column label="关系编号" prop="membershipId" />
        <el-table-column label="套装状态"><template #default="{ row }">{{ row.set.status === 'ACTIVE' ? '有效' : '已退役' }}</template></el-table-column>
        <el-table-column label="关系状态"><template #default="{ row }">{{ row.status === 'ACTIVE' ? '有效' : '已退役' }}</template></el-table-column>
        <el-table-column label="当前有效"><template #default="{ row }">{{ row.effective ? '是' : '否' }}{{ row.book.deleted ? '（书目异常）' : '' }}</template></el-table-column>
      </el-table>
      <el-pagination v-model:current-page="page" :page-size="20" :total="total" layout="total, prev, pager, next" @current-change="load" />
    </template>
    <el-empty v-else description="仅管理员和馆员可查看套装关系。" />
  </el-dialog>
</template>
<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useUserStore } from '@/store/modules/user'
import { getBookSetMemberships, type SafeSetMembership } from '@/api/catalogSet'
import { setProblem } from './setProtocol'
const props = defineProps<{ visible: boolean; bookId: number | null }>(), emit = defineEmits<{ (e: 'update:visible', value: boolean): void }>()
const user = useUserStore(), allowed = computed(() => user.isAdmin || user.isLibrarian)
const rows = ref<SafeSetMembership[]>([]), total = ref(0), page = ref(1), history = ref(false), loading = ref(false), error = ref('')
let sequence = 0
watch(() => [props.visible, props.bookId, allowed.value], () => { ++sequence; rows.value = []; total.value = 0; page.value = 1; error.value = ''; if (props.visible && props.bookId && allowed.value) load() }, { immediate: true })
function search() { page.value = 1; load() }
async function load() { const id = props.bookId, request = ++sequence; if (!id || !allowed.value) return; loading.value = true; error.value = ''; try { const result = await getBookSetMemberships(id, page.value, 20, history.value ? 'ALL' : 'ACTIVE', history.value ? 'ALL' : 'ACTIVE'); if (request === sequence && allowed.value) { rows.value = result.records; total.value = result.total } } catch (cause) { if (request === sequence) error.value = setProblem(cause).message } finally { if (request === sequence) loading.value = false } }
</script>
