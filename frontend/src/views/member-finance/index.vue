<template><FinanceLedger :member-id="memberId" @close="close" /></template>
<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { financeId } from '@/api/memberFinance'
import FinanceLedger from './FinanceLedger.vue'
const route=useRoute(),router=useRouter(),memberId=computed(()=>typeof route.params.memberId==='string'&&financeId(route.params.memberId)?route.params.memberId:null)
function close(){router.push(memberId.value?{path:'/members',query:{userId:memberId.value}}:{path:'/members'})}
</script>
