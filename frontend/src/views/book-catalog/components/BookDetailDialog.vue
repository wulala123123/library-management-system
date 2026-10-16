<template>
  <el-dialog
    :model-value="visible"
    :title="null"
    width="680px"
    destroy-on-close
    @update:model-value="(val: boolean) => emit('update:visible', val)"
    class="book-detail-dialog"
  >
    <div v-if="loading" v-loading="true" style="min-height: 300px"></div>

    <template v-else-if="book">
      <div class="detail-body">
        <div class="detail-cover">
          <img v-if="book.coverImage" :src="getCoverUrl(book.coverImage)" :alt="bookDisplayTitle(book)" />
          <div v-else class="cover-placeholder-lg">
            <span>{{ bookDisplayTitle(book).charAt(0) }}</span>
          </div>
          <div class="cover-actions">
            <el-button type="primary" size="large" round @click="handleBorrow" :disabled="loading || generating || (book.stockAvailable ?? 0) <= 0">
              {{ (book.stockAvailable ?? 0) > 0 ? '生成借阅码' : '暂无库存' }}
            </el-button>
          </div>
        </div>

        <div class="detail-info">
          <h2 class="detail-title">{{ bookDisplayTitle(book) }}</h2>
          <p class="detail-author">{{ book.author || '未知作者' }}<span v-if="book.publisher"> · {{ book.publisher }}</span></p>

          <div class="info-grid">
            <div class="info-item"><span class="info-label">登记书名</span><span>{{ book.catalogTitle }}</span></div>
            <div class="info-item"><span class="info-label">已采用来源原书名</span><span>{{ book.adoptedSourceTitle ?? '未设置' }}</span></div>
            <div class="info-item"><span class="info-label">装帧</span><span>{{ bindingText(book.bindingCode) }}</span></div>
            <div class="info-item"><span class="info-label">适读年龄标签</span><span>{{ ageText(book.ageLabels) }}</span></div>
            <div class="info-item">
              <span class="info-label">ISBN</span>
              <span class="info-value">{{ book.isbn || '-' }}</span>
            </div>
            <div class="info-item">
              <span class="info-label">分类</span>
              <span class="info-value">{{ book.categoryName || '-' }}</span>
            </div>
            <div class="info-item">
              <span class="info-label">页数</span>
              <span class="info-value">{{ displayValue(book.pages) }}</span>
            </div>
            <div class="info-item">
              <span class="info-label">价格</span>
              <span class="info-value price">{{ displayPrice(book.price) }}</span>
            </div>
            <div class="info-item">
              <span class="info-label">出版日期</span>
              <span class="info-value">{{ displayValue(book.publishDate?.split('T')[0]) }}</span>
            </div>
            <div class="info-item">
              <span class="info-label">馆藏位置</span>
              <span class="info-value">{{ displayValue(book.location) }}</span>
            </div>
            <div class="info-item">
              <span class="info-label">可借册数</span>
              <span class="info-value" :class="{ 'stock-low': (book.stockAvailable ?? 0) <= 3, 'stock-out': (book.stockAvailable ?? 0) <= 0 }">
                {{ displayValue(book.stockAvailable, '待核实') }} 册
              </span>
            </div>
            <div class="info-item">
              <span class="info-label">实体册总数</span>
              <span class="info-value">{{ displayValue(book.stockTotal, '待核实') }} 册</span>
            </div>
          </div>

          <div class="detail-desc">
            <h4>内容简介</h4>
            <p>{{ displayValue(book.description, '待补充') }}</p>
          </div>
        </div>
      </div>
    </template>

    <template #footer>
      <el-button @click="emit('update:visible', false)">关闭</el-button>
      <el-button type="primary" @click="handleBorrow" :disabled="loading || generating || !book || (book.stockAvailable ?? 0) <= 0">生成借阅码</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getCatalogBook, type CatalogBook } from '@/api/catalogAttribute'
import { bookDisplayTitle, ageText, bindingText } from '@/utils/catalogAttributes'
import { generateBorrowCode } from '@/api/borrowCode'
import { displayValue, displayPrice } from '@/utils/display'

const props = defineProps<{ visible: boolean; bookId: number | null }>()
const emit = defineEmits<{ (e: 'update:visible', val: boolean): void; (e: 'borrowed'): void }>()

const loading = ref(false)
const book = ref<CatalogBook | null>(null), generating = ref(false)
let detailRequest = 0

watch(() => [props.visible, props.bookId], () => { ++detailRequest; book.value = null; if (props.visible && props.bookId) fetchDetail() }, { immediate: true })
async function fetchDetail() {
  if (!props.bookId) return
  const request = ++detailRequest, id = props.bookId; loading.value = true
  try { const result = await getCatalogBook(id); if (request === detailRequest && props.visible && props.bookId === id) book.value = result }
  catch { if (request === detailRequest) book.value = null }
  finally { if (request === detailRequest) loading.value = false }
}

async function handleBorrow() {
  if (!book.value || (book.value.stockAvailable ?? 0) <= 0 || generating.value || loading.value) return
  const selected = book.value, request = detailRequest; generating.value = true
  try {
    const codeRes = await generateBorrowCode(selected.id)
    if (request !== detailRequest || !props.visible || props.bookId !== selected.id) return
    const borrowCode = codeRes?.code || '未知'
    ElMessageBox.alert(
      `已为您生成借阅码《${bookDisplayTitle(selected)}》！\n\n您的借阅码为：${borrowCode}\n有效期30分钟，核销后完成借阅。借阅码不预留库存，请及时核销。`,
      '已生成借阅码，核销后完成借阅',
      { confirmButtonText: '我知道了', type: 'success' }
    )
    emit('update:visible', false)
    emit('borrowed')
  } catch (error: any) {
    ElMessage.error(error.message || '借阅失败')
  } finally { generating.value = false }
}

function getCoverUrl(url: string) {
  if (!url) return ''
  if (url.startsWith('http')) return url
  if (url.startsWith('/')) return url
  return '/' + url
}
</script>

<style lang="scss" scoped>
.detail-body {
  display: flex;
  gap: 28px;
}

.detail-cover {
  flex-shrink: 0;
  width: 200px;

  img {
    width: 100%;
    height: 280px;
    object-fit: cover;
    border-radius: 10px;
    box-shadow: 0 4px 16px rgba(0,0,0,0.1);
  }

  .cover-placeholder-lg {
    width: 100%;
    height: 280px;
    border-radius: 10px;
    background: linear-gradient(145deg, #e8ecf1, #d5dbe6);
    display: flex;
    align-items: center;
    justify-content: center;
    font-size: 72px;
    font-weight: 700;
    color: rgba(24,144,255,0.12);
    box-shadow: 0 4px 16px rgba(0,0,0,0.08);
  }

  .cover-actions {
    margin-top: 16px;
    text-align: center;
  }
}

.detail-info {
  flex: 1;
  min-width: 0;

  .detail-title {
    font-size: 22px;
    font-weight: 700;
    color: #1c1917;
    line-height: 1.3;
    margin-bottom: 6px;
  }

  .detail-author {
    font-size: 14px;
    color: #78716c;
    margin-bottom: 20px;
  }

  .info-grid {
    display: grid;
    grid-template-columns: repeat(2, 1fr);
    gap: 12px 24px;
    margin-bottom: 20px;
  }

  .info-item {
    display: flex;
    flex-direction: column;
    gap: 3px;

    .info-label {
      font-size: 12px;
      color: #8c8c8c;
    }

    .info-value {
      font-size: 14px;
      color: #262626;
      font-weight: 500;

      &.price { color: #ff7875; }
      &.stock-low { color: #faad14; font-weight: 700; }
      &.stock-out { color: #ff4d4f; font-weight: 700; }
    }
  }

  .detail-desc {
    h4 {
      font-size: 14px;
      font-weight: 600;
      color: #262626;
      margin-bottom: 8px;
    }

    p {
      font-size: 13px;
      color: #595959;
      line-height: 1.7;
      max-height: 120px;
      overflow-y: auto;
    }
  }
}
</style>

<style lang="scss">
.book-detail-dialog {
  .el-dialog__header {
    display: none;
  }
  .el-dialog__body {
    padding: 28px 24px 16px;
  }
}
</style>
