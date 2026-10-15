<template>
  <section class="member-benefits">
    <h3>现有权益</h3>
    <el-descriptions :column="2" border>
      <el-descriptions-item label="原套餐名称">{{ displayValue(planName) }}</el-descriptions-item>
      <el-descriptions-item label="服务状态"><el-tag :type="serviceStateTag(serviceState)">{{ serviceStateText(serviceState) }}</el-tag></el-descriptions-item>
      <el-descriptions-item label="服务开始日">{{ dateText(serviceStart) }}</el-descriptions-item>
      <el-descriptions-item label="服务结束日">{{ dateText(serviceEnd) }}</el-descriptions-item>
      <el-descriptions-item label="已用次数">{{ displayValue(usedUses, '待核实') }}</el-descriptions-item>
      <el-descriptions-item label="总次数">{{ totalUsesText(usesType, totalUses) }}</el-descriptions-item>
      <el-descriptions-item label="剩余次数">{{ remainingUsesText(usesType, usedUses, totalUses, remainingUses) }}</el-descriptions-item>
    </el-descriptions>
    <p class="explanation">{{ asOfDate ? `统计日期：${asOfDate}（Asia/Shanghai）。` : '' }}服务期限与账号登录状态分别展示；次数保留原累计事实。套餐版本及后续办理规则尚未接入。</p>
  </section>
</template>

<script setup lang="ts">
import { displayValue } from '@/utils/display'
import { dateText, remainingUsesText, serviceStateTag, serviceStateText, totalUsesText } from '../memberDisplay'
defineProps<{
  planName?: string | null; serviceState?: string | null; serviceStart?: string | null; serviceEnd?: string | null
  usesType?: string | null; usedUses?: number | null; totalUses?: number | null; remainingUses?: number | null; asOfDate?: string | null
}>()
</script>

<style scoped>
h3 { font-size: 16px; margin: 0 0 12px; }
.explanation { color: var(--text-secondary); font-size: 13px; line-height: 1.7; margin: 10px 0; }
</style>
