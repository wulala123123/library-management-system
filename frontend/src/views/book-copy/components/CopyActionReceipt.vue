<template>
  <section>
    <el-alert title="本次办理成功，实体册、汇总库存和动作记录已一并保存。" type="success" :closable="false" />
    <p>回执 {{ receipt.requestId }} · {{ actionNames[receipt.action] }} · 操作人 {{ receipt.operatorId }} · {{ receipt.committedAt }}</p>
    <p v-if="receipt.action === 'RESTORE'">已恢复为临时停用，仍不可借；核实可借后请独立办理启用。</p>
    <p>记录版本 V{{ receipt.schemaVersion }}</p><CopyActionChanges :copies="receipt.copies" :books="receipt.books" :schema-version="receipt.schemaVersion" />
  </section>
</template>
<script setup lang="ts">
import type { AnyCopyActionReceipt } from '@/api/bookCopyAction'
import { actionNames } from './copyActionPresentation'
import CopyActionChanges from './CopyActionChanges.vue'
defineProps<{ receipt: AnyCopyActionReceipt }>()
</script>
