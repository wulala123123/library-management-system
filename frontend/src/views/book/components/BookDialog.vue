<template>
  <el-dialog :model-value="visible" :title="isEdit ? '编辑书目资料' : '新书目与首批实体册'" width="720px" :close-on-click-modal="false" :close-on-press-escape="!loading && !intakeVisible" :before-close="beforeClose">
    <el-alert v-if="loadError" :title="loadError" type="error" :closable="false" />
    <el-alert v-if="isEdit" title="这里只保存书目资料。增加库存请使用“登记实体册”，现有总数和可借数由业务流程维护。" type="info" :closable="false" />
    <section v-if="isEdit || createdBookId" class="attribute-entry"><p>{{ createdBookId ? '入库已完成，属性待补充；属性第二步失败不重复入库。' : '展示名称、装帧和年龄标签独立预览确认，保存反馈与登记资料分开。' }}</p><el-button :disabled="loading || intakeVisible" @click="attributeVisible = true">独立编辑书目属性</el-button><p v-if="attributeSaved">本书属性已独立保存；登记资料表单的未保存输入仍保留。</p></section>
    <el-form v-if="canManage" ref="formRef" :model="form" :rules="rules" :disabled="loading || !!loadError" label-width="100px" size="default">
      <div class="cover-upload-section">
        <label class="section-label">封面图片</label>
        <div class="upload-area">
          <div class="cover-preview" v-if="form.coverImage || previewUrl">
            <img :src="previewUrl || form.coverImage || undefined" alt="封面预览" />
            <div class="preview-actions">
              <el-button type="danger" size="small" circle @click="removeCover">
                <el-icon><Delete /></el-icon>
              </el-button>
              <el-button type="primary" size="small" circle @click="triggerUpload">
                <el-icon><Upload /></el-icon>
              </el-button>
            </div>
          </div>
          <div class="upload-placeholder" v-else @click="triggerUpload">
            <el-icon :size="36"><Picture /></el-icon>
            <p>点击上传封面</p>
            <span>支持 JPG、PNG、GIF，最大 5MB</span>
          </div>
          <input ref="fileInputRef" type="file" accept=".jpg,.jpeg,.png,.gif,.webp" style="display: none" @change="handleFileChange" />
        </div>
      </div>

      <el-row :gutter="20">
        <el-col :span="16">
          <el-form-item label="ISBN" prop="isbn"><el-input v-model="form.isbn" placeholder="请输入ISBN" /></el-form-item>
        </el-col>
        <el-col :span="8">
          <el-form-item label="分类" prop="categoryId">
            <el-select v-model="form.categoryId" placeholder="选择分类" style="width: 100%">
              <el-option v-for="cat in categories" :key="cat.id" :label="cat.name" :value="cat.id" />
            </el-select>
          </el-form-item>
        </el-col>
      </el-row>

      <el-form-item label="登记书名" prop="title"><el-input v-model="form.title" placeholder="请输入书名" /></el-form-item>
      <el-form-item label="作者" prop="author"><el-input v-model="form.author" placeholder="请输入作者" /></el-form-item>

      <el-row :gutter="20">
        <el-col :span="12">
          <el-form-item label="出版社"><el-input v-model="form.publisher" placeholder="请输入出版社" /></el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="出版日期"><el-date-picker v-model="form.publishDate" type="date" value-format="YYYY-MM-DD" placeholder="未设置" style="width: 100%" /></el-form-item>
        </el-col>
      </el-row>

      <el-row :gutter="20">
        <el-col :span="8">
          <el-form-item label="价格(元)"><el-input-number v-model="form.price" :min="0" :max="99999.99" :precision="2" style="width: 100%" /></el-form-item>
        </el-col>
        <el-col :span="8">
          <el-form-item label="页数"><el-input-number v-model="form.pages" :min="0" :max="99999" :precision="0" placeholder="未设置" style="width: 100%" /></el-form-item>
        </el-col>
      </el-row>

      <el-form-item label="馆藏位置"><el-input v-model="form.location" placeholder="未设置，选填，例如：A区-3层-01架" :maxlength="100" /></el-form-item>
      <el-form-item label="简介"><el-input v-model="form.description" type="textarea" :rows="3" placeholder="待补充（选填图书简介）" /></el-form-item>
    </el-form>

    <template #footer>
      <el-button :disabled="loading || intakeVisible" @click="handleClose">取消</el-button>
      <el-button v-if="canManage && !created" type="primary" :loading="loading" :disabled="!!loadError || intakeVisible" @click="handleSubmit">{{ isEdit ? '保存书目资料' : '下一步：登记实体册' }}</el-button>
    </template>
  </el-dialog>
  <IntakeDialog :visible="intakeVisible" :new-book="preparedBook" @update:visible="onIntakeVisible" @success="onIntakeSuccess" />
  <BookAttributeDialog v-model:visible="attributeVisible" :book-id="createdBookId || bookId" :intake-completed="!!createdBookId" @success="onAttributeSuccess" />
</template>

<script setup lang="ts">
import { ref, reactive, computed, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Delete, Upload, Picture } from '@element-plus/icons-vue'
import type { FormInstance, FormRules } from 'element-plus'
import { getBookMetadata, updateBookMetadata, uploadBookCover, type BookMetadata } from '@/api/book'
import { getCategoryList } from '@/api/category'
import { useUserStore } from '@/store/modules/user'
import type { IntakeReceipt } from '@/api/inventoryIntake'
import IntakeDialog from './IntakeDialog.vue'
import BookAttributeDialog from './BookAttributeDialog.vue'
import { metadataChanges, metadataKeys } from './intakeDraft'

interface Props { visible: boolean; bookId: number | null }
const props = defineProps<Props>()
const emit = defineEmits(['update:visible', 'success'])
const user = useUserStore(), canManage = computed(() => user.isAdmin || user.isLibrarian)

const formRef = ref<FormInstance>()
const fileInputRef = ref<HTMLInputElement>()
const loading = ref(false)
const uploadingCover = ref(false)
const pendingFile = ref<File | null>(null)
const previewUrl = ref('')
const isEdit = computed(() => !!props.bookId)
const categories = ref<any[]>([])
const loadError = ref(''), metadataDigest = ref(''), original = ref<Partial<BookMetadata>>({})
const intakeVisible = ref(false), preparedBook = ref<BookMetadata | null>(null), created = ref(false)
const createdBookId = ref<number | null>(null), attributeVisible = ref(false), attributeSaved = ref(false)
let detailRequest = 0
function onAttributeSuccess() { attributeSaved.value = true; emit('success') }

const form = reactive({
  isbn: '', title: '', author: '' as string | null, publisher: '' as string | null, publishDate: null as string | null,
  categoryId: undefined as number | undefined,
  price: undefined as number | null | undefined,
  pages: undefined as number | null | undefined,
  location: '' as string | null, description: '' as string | null,
  coverImage: '' as string | null | undefined, status: 1 as number | null,
})

const rules: FormRules = {
  isbn: [{ required: true, message: '请输入ISBN', trigger: 'blur' }],
  title: [{ required: true, message: '请输入书名', trigger: 'blur' }],
  categoryId: [{ required: true, message: '请选择分类', trigger: 'change' }],
}

watch(() => [props.visible, props.bookId], async () => {
  const request = ++detailRequest
  if (props.visible) {
    if (!canManage.value) return
    resetForm(); loadError.value = ''; created.value = false; createdBookId.value = null; attributeSaved.value = false
    try {
      const values = await getCategoryList() || []; if (request === detailRequest) categories.value = values
    } catch {}
    if (request === detailRequest && props.bookId) await loadBookDetail()
  } else resetForm()
})

async function loadBookDetail() {
  const request = ++detailRequest, id = props.bookId!
  loading.value = true
  try {
    const { book, metadataDigest: digest } = await getBookMetadata(id)
    if (request !== detailRequest || !props.visible || props.bookId !== id) return
    const values = Object.fromEntries(metadataKeys.map(key => [key, book[key] ?? null]))
    Object.assign(form, values); original.value = { ...values }; metadataDigest.value = digest
    previewUrl.value = ''
    pendingFile.value = null
  } catch { if (request === detailRequest) loadError.value = '书目资料读取失败，请关闭后重试，当前不能保存。' }
  finally { if (request === detailRequest) loading.value = false }
}

function resetForm() {
  if (formRef.value) formRef.value.resetFields()
  Object.assign(form, { isbn: '', title: '', author: '', publisher: '', publishDate: null, categoryId: undefined, price: undefined, pages: undefined, location: '', description: '', coverImage: '', status: 1 })
  metadataDigest.value = ''; original.value = {}; loadError.value = ''
  previewUrl.value = ''
  pendingFile.value = null
}

function triggerUpload() { fileInputRef.value?.click() }

async function handleFileChange(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  const validTypes = ['image/jpeg', 'image/png', 'image/gif', 'image/webp']
  if (!validTypes.includes(file.type)) { ElMessage.error('仅支持 JPG、PNG、GIF、WEBP 格式'); return }
  if (file.size > 5 * 1024 * 1024) { ElMessage.error('图片大小不能超过5MB'); return }

  previewUrl.value = URL.createObjectURL(file)
  pendingFile.value = file
  input.value = ''
}

function removeCover() {
  form.coverImage = ''
  previewUrl.value = ''
  pendingFile.value = null
}

function handleClose() { if (!loading.value && !intakeVisible.value) emit('update:visible', false) }
function beforeClose(done: () => void) { if (!loading.value && !intakeVisible.value) { handleClose(); done() } }
function onIntakeVisible(value: boolean) { intakeVisible.value = value }
async function savePendingCover(bookId: number) {
  const file = pendingFile.value
  if (!file) return
  uploadingCover.value = true
  try { await uploadBookCover(bookId, file); pendingFile.value = null }
  catch { ElMessage.warning('书目与入库已保存，封面上传失败；可稍后编辑封面，不要重复入库。') }
  finally { uploadingCover.value = false }
}
async function onIntakeSuccess(receipt: IntakeReceipt) {
  created.value = true
  const bookId = receipt.copies[0]?.bookId
  createdBookId.value = bookId ?? null
  if (bookId) await savePendingCover(bookId)
  emit('success')
}

async function handleSubmit() {
  if (!canManage.value || !formRef.value || loading.value || loadError.value || intakeVisible.value || created.value) return
  try { await formRef.value.validate() } catch { return }
  if (loading.value) return
  loading.value = true
  try {
    const metadata: BookMetadata = { ...form, categoryId: form.categoryId!, publishDate: form.publishDate || null, price: form.price ?? null, pages: form.pages ?? null }
    if (isEdit.value) {
      if (!metadataDigest.value) return
      const changes = metadataChanges(original.value, metadata)
      if (Object.keys(changes).length) await updateBookMetadata(props.bookId!, metadataDigest.value, changes)
      await savePendingCover(props.bookId!)
      ElMessage.success('书目资料已保存')
      emit('update:visible', false); emit('success')
    } else {
      preparedBook.value = metadata; intakeVisible.value = true
    }
  } catch {
    // The request interceptor displays API errors; retain the form for correction.
  } finally { loading.value = false }
}
</script>

<style lang="scss" scoped>
.cover-upload-section {
  margin-bottom: 20px;
  .section-label {
    display: block;
    font-size: 14px;
    font-weight: 500;
    color: var(--text-regular);
    margin-bottom: 10px;
  }
}

.upload-area {
  .cover-preview {
    width: 160px;
    height: 210px;
    border-radius: 10px;
    overflow: hidden;
    position: relative;
    border: 2px solid var(--border-color);
    img { width: 100%; height: 100%; object-fit: cover; }
    .preview-actions {
      position: absolute;
      bottom: 0;
      left: 0;
      right: 0;
      padding: 8px;
      background: linear-gradient(transparent, rgba(0,0,0,0.6));
      display: flex;
      justify-content: center;
      gap: 6px;
      opacity: 0;
      transition: opacity 0.25s;
    }
    &:hover .preview-actions { opacity: 1; }
  }

  .upload-placeholder {
    width: 160px;
    height: 210px;
    border-radius: 10px;
    border: 2px dashed var(--border-color);
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    cursor: pointer;
    transition: all 0.3s;
    color: var(--text-secondary);

    &:hover {
      border-color: var(--primary-color);
      color: var(--primary-color);
      background: var(--primary-bg);
    }

    p { margin: 8px 0 4px; font-size: 13px; font-weight: 500; }
    span { font-size: 11px; color: var(--text-placeholder); }
  }
}
</style>
