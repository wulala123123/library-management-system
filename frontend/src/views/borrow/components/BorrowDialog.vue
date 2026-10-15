<template>
  <el-dialog :model-value="visible" title="办理借阅" width="520px" :close-on-click-modal="false" @close="handleClose">
    <el-form ref="formRef" :model="form" :rules="rules" label-width="90px" size="default">
      <el-form-item label="选择读者" prop="userId">
        <el-select v-model="form.userId" filterable remote placeholder="搜索姓名/手机号/卡号/登录账号" style="width: 100%" :remote-method="searchUsers" :loading="userLoading">
          <el-option v-for="u in userOptions" :key="u.id" :label="borrowerLabel(u)" :value="u.id" />
        </el-select>
      </el-form-item>

      <el-form-item label="选择图书" prop="bookId">
        <el-select v-model="form.bookId" filterable remote placeholder="搜索图书名称/ISBN/馆内条码" style="width: 100%" :remote-method="searchBooks" :loading="bookLoading" @change="loadCopies">
          <el-option v-for="b in bookOptions" :key="b.id" :label="`${b.title} (${b.isbn})`" :value="b.id">
            <div style="display: flex; justify-content: space-between; align-items: center;">
              <span>{{ b.title }}</span>
              <span style="color: #999; font-size: 12px;">库存:{{ b.stockAvailable ?? b.stockTotal }}</span>
            </div>
          </el-option>
        </el-select>
      </el-form-item>

      <el-form-item v-if="copyOptions.length" label="馆内条码">
        <el-select v-model="form.copyId" placeholder="自动分配可借册" clearable style="width: 100%">
          <el-option v-for="copy in copyOptions" :key="copy.id" :value="copy.id" :label="`${copy.barcode}${copy.status === 'AVAILABLE' ? '（可借）' : '（已借出）'}`" :disabled="copy.status !== 'AVAILABLE'" />
        </el-select>
      </el-form-item>

      <el-form-item label="借阅天数" prop="borrowDays">
        <el-input-number v-model="form.borrowDays" :min="1" :max="90" style="width: 100%" />
      </el-form-item>

      <el-form-item label="备注">
        <el-input v-model="form.remark" type="textarea" :rows="2" placeholder="选填备注信息" />
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button @click="handleClose">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="handleSubmit">确认借出</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref, reactive, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { adminBorrowBook } from '@/api/borrow'
import { getBookPage, getBookCopies, type BookCopy } from '@/api/book'
import { searchUsers as searchUsersApi, type BorrowerSummary } from '@/api/user'
import { displayValue } from '@/utils/display'

interface Props { visible: boolean }
const props = defineProps<Props>()
const emit = defineEmits(['update:visible', 'success'])

const formRef = ref<FormInstance>()
const submitting = ref(false)
const userLoading = ref(false)
const bookLoading = ref(false)

const userOptions = ref<BorrowerSummary[]>([])
const bookOptions = ref<any[]>([])
const copyOptions = ref<BookCopy[]>([])
const bookSearchText = ref('')
let copyRequest = 0
let userRequest = 0
const borrowerLabel = (user: BorrowerSummary) => `${displayValue(user.realName, '未设置姓名')} · ${user.username} · 卡号 ${displayValue(user.cardNo)} · ${displayValue(user.phone, '未设置手机号')}`

const form = reactive({
  userId: undefined as number | undefined,
  bookId: undefined as number | undefined,
  copyId: undefined as number | undefined,
  borrowDays: 30,
  remark: '',
})

const rules: FormRules = {
  userId: [{ required: true, message: '请选择读者', trigger: 'change' }],
  bookId: [{ required: true, message: '请选择图书', trigger: 'change' }],
  borrowDays: [{ required: true, message: '请输入借阅天数', trigger: 'blur' }],
}

watch(() => props.visible, (val) => {
  if (val) resetForm()
})

function resetForm() {
  if (formRef.value) formRef.value.resetFields()
  Object.assign(form, { userId: undefined, bookId: undefined, copyId: undefined, borrowDays: 30, remark: '' })
  userOptions.value = []
  bookOptions.value = []
  copyOptions.value = []
  bookSearchText.value = ''
  copyRequest++
  userRequest++
}

async function loadCopies(id: number) {
  const request = ++copyRequest
  form.copyId = undefined
  copyOptions.value = []
  try {
    const copies = await getBookCopies(id)
    if (request !== copyRequest) return
    copyOptions.value = copies || []
    const exactCopy = copies?.find(c => c.barcode === bookSearchText.value)
    form.copyId = exactCopy?.id
  } catch { /* API displays the error; keep automatic allocation available. */ }
}

async function searchUsers(query: string) {
  const request = ++userRequest
  if (!query.trim()) { userOptions.value = []; userLoading.value = false; return }
  userLoading.value = true
  try {
    const res = await searchUsersApi(query.trim())
    if (request !== userRequest) return
    userOptions.value = res?.records || []
  } catch { if (request === userRequest) userOptions.value = [] } finally { if (request === userRequest) userLoading.value = false }
}

async function searchBooks(query: string) {
  if (!query || query.length < 1) return
  bookLoading.value = true
  bookSearchText.value = query
  try {
    const res = await getBookPage({ page: 1, size: 20, keyword: query })
    bookOptions.value = res?.records || []
  } catch { bookOptions.value = [] } finally { bookLoading.value = false }
}

function handleClose() { emit('update:visible', false) }

async function handleSubmit() {
  if (!formRef.value) return
  await formRef.value.validate(async (valid) => {
    if (!valid) return
    submitting.value = true
    try {
      await adminBorrowBook({
        userId: form.userId!,
        bookId: form.bookId!,
        copyId: form.copyId,
        borrowDays: form.borrowDays,
        remark: form.remark || undefined,
      })
      ElMessage.success('借阅办理成功')
      handleClose()
      emit('success')
    } catch (e: any) {
      ElMessage.error(e.message || '借阅失败')
    } finally { submitting.value = false }
  })
}
</script>
