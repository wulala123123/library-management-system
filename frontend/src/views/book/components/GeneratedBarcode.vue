<template><svg ref="svg" role="img" :aria-label="`Code128 馆内条码 ${barcode}`" /></template>
<script setup lang="ts">
import { ref, watch, onMounted } from 'vue'
import JsBarcode from 'jsbarcode'
const props = defineProps<{ barcode: string }>()
const svg = ref<SVGSVGElement>()
function render() {
  if (!svg.value) return
  JsBarcode(svg.value, props.barcode, { format: 'CODE128', displayValue: false, width: 1, height: 64, margin: 16, lineColor: '#000000', background: '#ffffff' })
  svg.value.setAttribute('viewBox', `0 0 ${svg.value.getAttribute('width')} ${svg.value.getAttribute('height')}`)
}
onMounted(render); watch(() => props.barcode, render, { flush: 'post' })
</script>
<style scoped>svg { display: block; width: 128mm; max-width: 100%; height: auto; }</style>
