<template>
  <el-dialog :model-value="visible" title="确认续借" width="520px" :close-on-click-modal="false"
             :close-on-press-escape="!submitting" :show-close="!submitting" @close="handleClose">
    <el-form ref="formRef" :model="form" :rules="rules" label-width="90px" size="default">
      <el-form-item v-if="showReader" label="读者">
        <span>{{ record?.userName }}</span>
      </el-form-item>
      <el-form-item label="图书">
        <span>《{{ record?.bookTitle }}》</span>
      </el-form-item>
      <el-form-item label="原应还日期">
        <span>{{ record?.dueDate?.split('T')[0] || '-' }}</span>
      </el-form-item>
      <el-form-item label="续借天数" prop="renewDays">
        <el-input-number v-model="form.renewDays" :min="1" :max="90" :precision="0" :step="1" style="width: 100%" />
        <div class="renew-hint">在原应还日期基础上延长，最多 90 天</div>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button :disabled="submitting" @click="handleClose">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="handleSubmit">确认续借</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { renewBook } from '@/api/borrow'
import type { BorrowRecord } from '@/types'

const props = defineProps<{ visible: boolean; record: BorrowRecord | null; showReader?: boolean }>()
const emit = defineEmits(['update:visible', 'success'])
const formRef = ref<FormInstance>()
const submitting = ref(false)
const form = reactive({ renewDays: 30 as number | undefined })
const rules: FormRules = {
  renewDays: [{ required: true, type: 'integer', min: 1, max: 90, message: '请输入1–90之间的整数天数', trigger: 'change' }],
}

watch(() => [props.visible, props.record?.id], () => {
  if (props.visible) {
    form.renewDays = 30
    formRef.value?.clearValidate()
  }
}, { immediate: true })

function handleClose() {
  if (!submitting.value) emit('update:visible', false)
}

async function handleSubmit() {
  if (submitting.value || !props.record || !formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid || submitting.value) return
  const days = form.renewDays!
  submitting.value = true
  try {
    await renewBook(props.record.id, days)
    ElMessage.success(`续借成功，已延长${days}天`)
    emit('success')
    emit('update:visible', false)
  } catch {
    // The request interceptor displays the error; retain the form for retry.
  } finally {
    submitting.value = false
  }
}
</script>

<style scoped>
.renew-hint { margin-top: 6px; color: #909399; font-size: 12px; line-height: 1.5; }
</style>
