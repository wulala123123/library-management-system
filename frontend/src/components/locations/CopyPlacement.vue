<template>
  <section class="placement">
    <template v-if="placement?.kind === 'REGISTERED'">
      <strong>登记地址 #{{ placement.registeredLocation?.locationId ?? '待核实' }}</strong>
      <template v-if="placement.registeredLocation">
        <p>书架 #{{ placement.registeredLocation.bookshelfId ?? '缺失' }} {{ quoted(placement.registeredLocation.bookshelfCode) }} {{ quoted(placement.registeredLocation.bookshelfName) }} / 层 #{{ placement.registeredLocation.storeyId ?? '缺失' }} {{ quoted(placement.registeredLocation.storeyName) }}（{{ placement.registeredLocation.levelNum ?? '未知' }}）/ 地址 {{ quoted(placement.registeredLocation.code) }}</p>
        <p>书架描述 {{ quoted(placement.registeredLocation.bookshelfLocation) }} · 版本 地址 / 层 / 书架：{{ placement.registeredLocation.locationVersion ?? '未知' }} / {{ placement.registeredLocation.storeyVersion ?? '未知' }} / {{ placement.registeredLocation.bookshelfVersion ?? '未知' }}</p>
        <p v-if="!placement.registeredLocation.selectable">地址不可选：{{ placement.registeredLocation.unavailableReason }}；已有关联保留，可经核对移出。</p>
      </template>
    </template>
    <p v-else-if="placement?.kind === 'TEXT'">文本位置 <code>{{ quoted(placement.text) }}</code></p>
    <p v-else-if="placement?.kind === 'UNASSIGNED'">未分配</p>
    <p v-else>位置投影暂不可用，请刷新后核对。</p>
    <p v-if="anomaly">位置来源或父链待核实（LOCATION_REFERENCE_ANOMALY）。</p>
    <details v-if="showRaw"><summary>展开保留原值</summary><p>原文本 <code>{{ quoted(rawText) }}</code>；它不会作为登记地址的备用位置。</p></details>
  </section>
</template>
<script setup lang="ts">
import type { Placement } from '@/api/locationAddress'
withDefaults(defineProps<{ placement?: Placement | null; rawText?: string | null; anomaly?: boolean; showRaw?: boolean }>(), { rawText: null, showRaw: true })
const quoted = (value: string | null) => value === null ? 'NULL' : JSON.stringify(value)
</script>
<style scoped>
.placement { font-size: 13px; line-height: 1.6; }p { margin: 4px 0; }code { white-space: pre-wrap; overflow-wrap: anywhere; }summary { cursor: pointer; }
</style>
