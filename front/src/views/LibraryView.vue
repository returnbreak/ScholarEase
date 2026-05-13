<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  ArrowLeft,
  ArrowRight,
  Close,
  Download,
  Filter,
  MoreFilled,
  Refresh,
  Search,
  Star,
  Upload,
  UploadFilled,
  View,
} from '@element-plus/icons-vue'
import {
  DocumentApiError,
  listDocuments,
  type DuplicatePaperData,
  type PaperSummary,
  uploadDocument,
} from '@/api/documents'
import { calculateFileMd5 } from '@/utils/md5'

type UploadPreview = {
  // 本地预览卡片 ID。按你的要求，这里使用“文件内容 MD5”，相同内容会得到相同 ID。
  id: string
  // 保留原始 File 对象，真正点击“上传 PDF”时会交给 FormData。
  file: File
  // 前端先算出的文件内容 MD5，可用于本地去重，也和后端 paper_md5 语义对齐。
  paperMd5: string
  fileName: string
  fileType: 'PDF'
  sizeLabel: string
  // objectUrl 用于 PDF <object> 预览，组件卸载或移除卡片时必须释放。
  objectUrl: string
  // ready：等待上传；uploading/success/failed：接口调用状态。
  uploadStatus: 'ready' | 'uploading' | 'success' | 'failed'
  // 每个文件自己的状态文案，避免批量上传时只能看到全局提示。
  message: string
}

// 表格搜索框关键字。当前在点击刷新或分页时带给 GET /api/documents。
const searchKeyword = ref('')
const fileInputRef = ref<HTMLInputElement>()
const isDragging = ref(false)
// 上传区的本地文件预览队列。它不等同于后端文献列表，上传成功后会再刷新 literatureItems。
const uploadPreviews = ref<UploadPreview[]>([])
const uploadNotice = ref('')
// 后端文献列表数据，对应接口文档中的 PaperSummary[]。
const literatureItems = ref<PaperSummary[]>([])
const isLoadingDocuments = ref(false)
const isUploading = ref(false)
const currentPage = ref(1)
const pageSize = ref(10)
const totalDocuments = ref(0)
const hasNextPage = ref(false)

// 只允许 PDF 进入上传队列；其它类型文件在选择阶段就直接忽略，不生成预览，也不进入后续逻辑。
const uploadablePreviews = computed(() =>
  uploadPreviews.value.filter((preview) => preview.fileType === 'PDF' && preview.uploadStatus !== 'success'),
)

// 没有可上传项或正在上传时禁用按钮，避免重复提交同一个文件。
const uploadButtonDisabled = computed(
  () => isUploading.value || uploadablePreviews.value.every((preview) => preview.uploadStatus === 'uploading'),
)

// 页面进入时先加载一次后端文献列表，让表格展示真实数据而不是静态 mock。
onMounted(() => {
  void loadDocuments()
})

// 从后端读取文献列表，并把分页信息同步到表格底部。
// catch 中只负责用户可见的提示，具体错误归一化在 getErrorMessage 中完成。
async function loadDocuments() {
  isLoadingDocuments.value = true

  try {
    const page = await listDocuments({
      keyword: searchKeyword.value.trim() || undefined,
      page: currentPage.value,
      pageSize: pageSize.value,
    })

    literatureItems.value = page.items
    totalDocuments.value = page.total
    hasNextPage.value = page.hasNext
  } catch (error) {
    ElMessage.error(getErrorMessage(error, '文献列表加载失败'))
  } finally {
    isLoadingDocuments.value = false
  }
}

// 触发隐藏的文件输入框的点击事件，从而打开系统的文件选择对话框
function openFileDialog() {
  fileInputRef.value?.click()
}

// 处理文件输入框的值改变事件（即用户通过对话框选择了文件后）
function handleFileInputChange(event: Event) {
  const input = event.target as HTMLInputElement
  // 将选择的文件列表传递给通用的处理函数
  void handleFiles(input.files)
  // 清空 input 的值，确保选择同一个文件时能再次触发 change 事件
  input.value = ''
}

// 处理拖拽文件放置到放置区时的事件
function handleDrop(event: DragEvent) {
  // 结束拖拽状态
  isDragging.value = false
  // 从事件数据中获取被拖拽的文件列表并进行处理
  void handleFiles(event.dataTransfer?.files)
}

// 通用的文件处理逻辑：只处理 PDF，读取文件内容生成 MD5，并据此创建预览数据。
async function handleFiles(fileList?: FileList | null) {
  if (!fileList?.length) {
    return
  }

  // 将 FileList 转换为普通数组
  const files = Array.from(fileList)
  // 只保留接口文档允许上传的 PDF；其它格式不做预览、不做上传、不进入状态队列。
  const supportedFiles = files.filter(isSupportedPdf)
  const rejectedCount = files.length - supportedFiles.length
  let duplicatedCount = 0

  // 为每个受支持的文件创建预览对象并添加到列表中
  for (const file of supportedFiles) {
    const paperMd5 = await calculateFileMd5(file)

    if (uploadPreviews.value.some((preview) => preview.id === paperMd5)) {
      duplicatedCount += 1
      continue
    }

    uploadPreviews.value.push({
      // 使用文件内容 MD5 作为唯一标识符，而不是文件名、修改时间或随机 UUID。
      id: paperMd5,
      file,
      paperMd5,
      fileName: file.name,
      fileType: 'PDF',
      // 将文件大小格式化为更易读的字符串 (KB 或 MB)
      sizeLabel: formatFileSize(file.size),
      // 为文件创建一个可用于在浏览器中显示/访问的本地 URL 对象
      objectUrl: URL.createObjectURL(file),
      uploadStatus: 'ready',
      message: '等待上传',
    })
  }

  // 汇总上传区提示：只展示 PDF 处理结果，其它格式按要求直接忽略。
  uploadNotice.value = [
    supportedFiles.length ? `已处理 ${supportedFiles.length} 个 PDF` : '',
    duplicatedCount ? `跳过 ${duplicatedCount} 个内容重复的 PDF` : '',
    rejectedCount ? `忽略 ${rejectedCount} 个非 PDF 或过大的文件` : '',
  ]
    .filter(Boolean)
    .join('，')
}

// 检查单个文件是否受支持：严格限制为 PDF，且大小不超过 200MB。
function isSupportedPdf(file: File) {
  const extension = file.name.split('.').pop()?.toLowerCase()
  return extension === 'pdf' && file.size <= 200 * 1024 * 1024
}

// 将文件大小（字节）格式化为带有 KB 或 MB 单位的易读字符串
function formatFileSize(size: number) {
  // 小于 1MB 时显示为 KB
  if (size < 1024 * 1024) {
    return `${Math.max(1, Math.round(size / 1024))} KB`
  }

  // 否则显示为 MB，保留一位小数
  return `${(size / 1024 / 1024).toFixed(1)} MB`
}

// 从预览列表中移除指定的文件预览项
function removePreview(previewId: string) {
  const target = uploadPreviews.value.find((preview) => preview.id === previewId)

  if (target) {
    // 释放为该文件创建的 URL 对象，避免内存泄漏
    URL.revokeObjectURL(target.objectUrl)
  }

  // 更新列表，移除匹配的项
  uploadPreviews.value = uploadPreviews.value.filter((preview) => preview.id !== previewId)
}

async function uploadPendingDocuments() {
  // 过滤掉正在上传、已成功或非 PDF 的预览项，保证一次点击只提交当前可上传的 PDF。
  const targets = uploadablePreviews.value.filter((preview) => preview.uploadStatus !== 'uploading')

  if (!targets.length) {
    ElMessage.warning('当前没有可上传的 PDF 文件')
    return
  }

  isUploading.value = true

  // 逐个上传而不是 Promise.all：
  // 1. 每个文件能独立更新成功/失败状态；
  // 2. 避免一次性并发多个大 PDF 给后端和浏览器带来压力；
  // 3. 某个文件重复或失败不会阻断后续文件继续上传。
  for (const preview of targets) {
    preview.uploadStatus = 'uploading'
    preview.message = '上传解析中'

    try {
      // 按上传接口约定，除 PDF 文件本体外，前端提交文件名、内容 MD5、字节大小和提交时间。
      const uploadProgress = await uploadDocument({
        traceId: createUploadTraceId(),
        file: preview.file,
        fileName: preview.fileName,
        paperMd5: preview.paperMd5,
        fileSizeBytes: preview.file.size,
        submissionTime: new Date().toISOString(),
      })

      preview.uploadStatus = 'success'
      preview.message = uploadProgress.parseStatus === 'PARSING' ? '已提交解析' : '上传成功'
      ElMessage.success(`已提交解析：${uploadProgress.fileName || preview.fileName}`)
    } catch (error) {
      // API 层会把后端错误码包装为 DocumentApiError，这里转换成适合用户阅读的中文文案。
      preview.uploadStatus = 'failed'
      preview.message = getErrorMessage(error, '上传失败')
      ElMessage.error(preview.message)
    }
  }

  isUploading.value = false
  // 上传结束后重新拉取列表，确保表格里的解析状态、标题和存储位置来自后端最终结果。
  await loadDocuments()
}

/**
 * 生成用于上传追踪的唯一标识符 (Trace ID)
 * 该函数优先使用现代浏览器的原生 Crypto API 生成安全的 UUID。
 * 如果当前环境不支持（例如非 HTTPS 环境或旧版浏览器），则降级使用“时间戳 + 随机数”的方案。
 * 
 * @returns {string} 返回一个不包含短横线的唯一字母数字字符串
 */
function createUploadTraceId() {
  // 检查当前环境是否支持原生且安全的 crypto.randomUUID 方法
  if (typeof crypto.randomUUID === 'function') {
    // 生成标准的 UUID v4 (格式如: 123e4567-e89b-12d3-a456-426614174000)
    // 使用 replaceAll 去除所有的短横线 '-'，得到一个纯粹的 32 位字符串
    return crypto.randomUUID().replaceAll('-', '')
  }

  // 降级方案：当 crypto.randomUUID 不可用时执行
  // Date.now().toString(36): 将当前毫秒级时间戳转换为 36 进制字符串（缩短长度并包含字母）
  // Math.random().toString(36).slice(2, 14): 生成一个随机小数，转为 36 进制，并截掉开头的 "0."，保留后面的随机字符
  // 将时间戳和随机字符串拼接在一起，以极大概率保证生成的 ID 是唯一的
  return `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 14)}`
}

// 后端返回 ISO-8601 时间；列表页只展示日期部分，和当前表格“上传日期”列保持一致。
function formatUploadDate(uploadTime?: string) {
  if (!uploadTime) {
    return '-'
  }

  return uploadTime.slice(0, 10)
}

// 将接口错误转成页面提示文案。
// DUPLICATE_PAPER 会优先展示后端返回的 existingPaper.title，帮助用户确认重复对象。
function getErrorMessage(error: unknown, fallback: string) {
  if (error instanceof DocumentApiError) {
    if (error.code === 'DUPLICATE_PAPER') {
      const duplicateData = error.data as DuplicatePaperData | undefined
      const title = duplicateData?.existingPaper?.title
      return title ? `检测到重复文献：${title}` : error.message
    }

    return error.message || fallback
  }

  return fallback
}

// 上一页：只有当前页大于 1 才触发请求，避免无意义的 page=0。
function goPreviousPage() {
  if (currentPage.value <= 1) {
    return
  }

  currentPage.value -= 1
  void loadDocuments()
}

// 下一页：使用后端 hasNext 控制按钮状态，避免前端自行推算总页数产生误差。
function goNextPage() {
  if (!hasNextPage.value) {
    return
  }

  currentPage.value += 1
  void loadDocuments()
}

// 修改每页条数后回到第一页，避免当前页号在新 pageSize 下越界。
function handlePageSizeChange() {
  currentPage.value = 1
  void loadDocuments()
}

// 组件卸载前触发清理工作
onBeforeUnmount(() => {
  // 遍历所有预览项，释放所有的 URL 对象以回收内存
  uploadPreviews.value.forEach((preview) => URL.revokeObjectURL(preview.objectUrl))
})
</script>

<template>
  <section class="workspace-view library-view">
    <section class="upload-panel" aria-label="上传文献">
      <input
        ref="fileInputRef"
        class="file-input"
        type="file"
        accept=".pdf,application/pdf"
        multiple
        @change="handleFileInputChange"
      />

      <!-- 
        上传拖拽区域 
        - :class="{'is-dragging': isDragging}": 拖拽文件悬浮时动态添加高亮样式
        - role="button" & tabindex="0": 提升无障碍访问 (a11y) 体验，允许键盘聚焦并作为按钮进行交互
        - @click, @keydown: 支持鼠标点击和键盘 (Enter/Space) 操作来触发文件选择弹窗
        - @dragenter, @dragover, @dragleave: 处理拖拽事件的生命周期，用于切换拖拽视觉状态
        - @drop.prevent: 阻止浏览器默认行为（防止在当前页直接打开文件）并处理文件拖放逻辑
      -->
      <div
        class="upload-dropzone"
        :class="{ 'is-dragging': isDragging }"
        role="button"
        tabindex="0"
        @click="openFileDialog"
        @keydown.enter.prevent="openFileDialog"
        @keydown.space.prevent="openFileDialog"
        @dragenter.prevent="isDragging = true"
        @dragover.prevent="isDragging = true"
        @dragleave.prevent="isDragging = false"
        @drop.prevent="handleDrop"
      >
        <!-- 区域内的中心上传云图标 -->
        <el-icon class="upload-cloud"><UploadFilled /></el-icon>
        
        <!-- 上传操作的主提示语 -->
        <h2>点击或拖拽文件到此处上传</h2>
        
        <!-- 支持的格式与文件大小限制说明 -->
        <p>仅支持 PDF，单个文件最大 200MB</p>
        
        <!-- 
          显式的上传按钮 
          @click.stop: 使用 .stop 修饰符阻止事件冒泡，防止点击按钮时又去触发外层 div 的点击事件
        -->
        <el-button type="primary" size="large" :icon="Upload" @click.stop="openFileDialog">
          上传文献
        </el-button>
      </div>

      <div v-if="uploadPreviews.length" class="preview-section" aria-label="待上传缩略图">
        <div class="preview-heading">
          <div>
            <h3>待处理文件</h3>
            <span>{{ uploadNotice }}</span>
          </div>
          <el-button
            type="primary"
            :icon="Upload"
            :loading="isUploading"
            :disabled="uploadButtonDisabled"
            @click="uploadPendingDocuments"
          >
            上传 PDF
          </el-button>
        </div>

        <div class="preview-grid">
          <article
            v-for="preview in uploadPreviews"
            :key="preview.id"
            class="preview-card"
          >
            <div class="thumbnail-shell" :class="preview.fileType.toLowerCase()">
              <object class="pdf-thumbnail" :data="preview.objectUrl" type="application/pdf" aria-label="PDF 缩略图">
                <span>PDF</span>
              </object>
            </div>

            <div class="preview-meta">
              <strong :title="preview.fileName">{{ preview.fileName }}</strong>
              <span>{{ preview.fileType }} · {{ preview.sizeLabel }}</span>
              <small :title="preview.paperMd5">MD5 {{ preview.paperMd5 }}</small>
              <em class="upload-status" :class="preview.uploadStatus">{{ preview.message }}</em>
            </div>

            <el-tooltip content="移除" placement="top">
              <el-button
                class="remove-preview"
                circle
                :icon="Close"
                aria-label="移除缩略图"
                @click="removePreview(preview.id)"
              />
            </el-tooltip>
          </article>
        </div>
      </div>
    </section>

    <section class="literature-card" aria-label="文献列表">
      <div class="card-header">
        <h2>文献列表</h2>
        <div class="table-tools">
          <el-input
            v-model="searchKeyword"
            class="search-input"
            :prefix-icon="Search"
            placeholder="搜索文献文件名、标题或作者"
          />
          <el-button :icon="Filter">筛选</el-button>
          <el-tooltip content="刷新" placement="top">
            <el-button :icon="Refresh" :loading="isLoadingDocuments" aria-label="刷新" @click="loadDocuments" />
          </el-tooltip>
        </div>
      </div>

      <div class="table-wrap">
        <table class="literature-table">
          <thead>
            <tr>
              <th>文献文件名称</th>
              <th>标题名称</th>
              <th>作者姓名</th>
              <th>存储位置</th>
              <th>上传日期</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody v-if="literatureItems.length">
            <tr v-for="item in literatureItems" :key="item.paperId">
              <td>
                <div class="file-cell">
                  <span class="file-badge" :class="item.fileType.toLowerCase()">
                    {{ item.fileType }}
                  </span>
                  <span>{{ item.fileName }}</span>
                </div>
              </td>
              <td>{{ item.title }}</td>
              <td>{{ item.authorText || '未识别' }}</td>
              <td>{{ item.storageLocation || '-' }}</td>
              <td>{{ formatUploadDate(item.uploadTime) }}</td>
              <td>
                <div class="action-group" :aria-label="`${item.fileName} 操作`">
                  <el-tooltip content="预览" placement="top">
                    <el-button link :icon="View" aria-label="预览" />
                  </el-tooltip>
                  <el-tooltip content="下载" placement="top">
                    <el-button link :icon="Download" aria-label="下载" />
                  </el-tooltip>
                  <el-tooltip content="收藏" placement="top">
                    <el-button link :icon="Star" aria-label="收藏" />
                  </el-tooltip>
                  <el-tooltip content="更多" placement="top">
                    <el-button class="more-button" link :icon="MoreFilled" aria-label="更多" />
                  </el-tooltip>
                </div>
              </td>
            </tr>
          </tbody>
          <tbody v-else>
            <tr>
              <td class="empty-table-cell" colspan="6">
                {{ isLoadingDocuments ? '正在加载文献列表' : '暂无文献' }}
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <footer class="table-footer">
        <span>共 {{ totalDocuments }} 条</span>
        <div class="pagination-tools">
          <el-select
            v-model="pageSize"
            class="page-size-select"
            aria-label="每页条数"
            @change="handlePageSizeChange"
          >
            <el-option label="10 条/页" :value="10" />
            <el-option label="20 条/页" :value="20" />
          </el-select>
          <el-button :icon="ArrowLeft" :disabled="currentPage <= 1" aria-label="上一页" @click="goPreviousPage" />
          <el-button class="current-page" type="primary">{{ currentPage }}</el-button>
          <el-button :icon="ArrowRight" :disabled="!hasNextPage" aria-label="下一页" @click="goNextPage" />
        </div>
      </footer>
    </section>
  </section>
</template>

<style scoped>
.library-view {
  max-width: none;
  gap: 16px;
}

.upload-panel,
.literature-card {
  border: 1px solid #e4dccf;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.88);
  box-shadow: 0 16px 36px rgba(38, 57, 47, 0.08);
}

.upload-panel {
  padding: 20px;
}

.file-input {
  display: none;
}

.upload-dropzone {
  display: grid;
  min-height: 214px;
  place-items: center;
  align-content: center;
  gap: 12px;
  border: 2px dashed #9ba89f;
  border-radius: 8px;
  background:
    linear-gradient(135deg, rgba(238, 240, 234, 0.54), rgba(255, 253, 247, 0.88)),
    #fffdf7;
  text-align: center;
  cursor: pointer;
  transition:
    border-color 180ms ease,
    background 180ms ease,
    box-shadow 180ms ease,
    transform 180ms ease;
}

/* 鼠标悬停、获得键盘焦点或正有文件拖拽到其上时的激活样式 */
.upload-dropzone:hover,
.upload-dropzone:focus-visible,
.upload-dropzone.is-dragging {
  /* 边框颜色加深为深绿色，提供清晰的视觉反馈 */
  border-color: #26392f;
  /* 使用斜向（135度）的线性渐变背景：从半透明的浅卡其色过渡到较不透明的浅灰色，叠加在米白色（#fffdf7）底色上，营造柔和的层次感 */
  background:
    linear-gradient(135deg, rgba(217, 200, 148, 0.2), rgba(238, 240, 234, 0.92)),
    #fffdf7;
  /* 添加内部阴影：设置了一个1px宽、带点透明度的深绿色内边框效果，增加立体感 */
  box-shadow: inset 0 0 0 1px rgba(38, 57, 47, 0.16);
}

.upload-dropzone.is-dragging {
  transform: translateY(-1px);
}

.upload-cloud {
  display: grid;
  width: 54px;
  height: 54px;
  place-items: center;
  border-radius: 50%;
  background: #26392f;
  color: #fffdf7;
  font-size: 28px;
  box-shadow: 0 12px 24px rgba(38, 57, 47, 0.18);
}

.upload-dropzone h2 {
  margin: 2px 0 0;
  color: #1d2a23;
  font-size: 20px;
  font-weight: 760;
}

.upload-dropzone p {
  margin: 0 0 6px;
  color: #6f675b;
}

.preview-section {
  margin-top: 16px;
}

.preview-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 10px;
}

.preview-heading div {
  display: grid;
  gap: 3px;
}

.preview-heading h3 {
  margin: 0;
  color: #1d2a23;
  font-size: 16px;
  font-weight: 760;
}

.preview-heading span {
  color: #6f675b;
  font-size: 13px;
}

.preview-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(178px, 1fr));
  gap: 12px;
}

.preview-card {
  position: relative;
  display: grid;
  gap: 10px;
  min-width: 0;
  padding: 10px;
  border: 1px solid #e8e0d4;
  border-radius: 8px;
  background: #fffdf7;
}

.thumbnail-shell {
  display: grid;
  height: 132px;
  overflow: hidden;
  place-items: center;
  border: 1px solid #e8e0d4;
  border-radius: 6px;
  background: #f7f3ea;
}

.thumbnail-shell.pdf {
  background: #fff;
}

.pdf-thumbnail {
  width: 100%;
  height: 100%;
  border: 0;
  pointer-events: none;
}

.pdf-thumbnail span {
  display: grid;
  width: 100%;
  height: 100%;
  place-items: center;
  color: #c5483a;
  font-size: 22px;
  font-weight: 800;
}

.preview-meta {
  display: grid;
  min-width: 0;
  gap: 2px;
}

.preview-meta strong {
  overflow: hidden;
  color: #1d2a23;
  font-size: 13px;
  font-weight: 720;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.preview-meta span {
  color: #6f675b;
  font-size: 12px;
}

.preview-meta small {
  overflow: hidden;
  color: #8a8278;
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.upload-status {
  overflow: hidden;
  color: #6f675b;
  font-size: 12px;
  font-style: normal;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.upload-status.ready {
  color: #526056;
}

.upload-status.uploading {
  color: #3f6fbd;
}

.upload-status.success {
  color: #2f7652;
}

.upload-status.failed {
  color: #c5483a;
}

.remove-preview {
  position: absolute;
  top: 8px;
  right: 8px;
  width: 26px;
  height: 26px;
  border-color: rgba(222, 214, 202, 0.86);
  background: rgba(255, 253, 247, 0.9);
  color: #526056;
}

.literature-card {
  padding: 14px 16px 12px;
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  margin-bottom: 12px;
}

.card-header h2 {
  position: relative;
  margin: 0;
  padding-left: 14px;
  color: #1d2a23;
  font-size: 20px;
  font-weight: 760;
  line-height: 1.2;
}

.card-header h2::before {
  position: absolute;
  top: 1px;
  bottom: 1px;
  left: 0;
  width: 4px;
  border-radius: 999px;
  background: #26392f;
  content: '';
}

.table-tools {
  display: flex;
  align-items: center;
  gap: 10px;
}

.search-input {
  width: min(34vw, 360px);
}

.table-wrap {
  width: 100%;
  overflow-x: auto;
  border: 1px solid #e8e0d4;
  border-radius: 8px;
}

.literature-table {
  width: 100%;
  min-width: 1020px;
  border-collapse: collapse;
  color: #1d2a23;
  font-size: 14px;
}

.literature-table th,
.literature-table td {
  padding: 13px 16px;
  border-right: 1px solid #e8e0d4;
  border-bottom: 1px solid #e8e0d4;
  text-align: left;
  white-space: nowrap;
}

.literature-table th:last-child,
.literature-table td:last-child {
  border-right: 0;
}

.literature-table tr:last-child td {
  border-bottom: 0;
}

.literature-table th {
  background: #f7f3ea;
  color: #26392f;
  font-weight: 760;
}

.literature-table tbody tr {
  background: rgba(255, 253, 247, 0.72);
}

.literature-table tbody tr:hover {
  background: #eef0ea;
}

.empty-table-cell {
  height: 140px;
  color: #7d7468;
  text-align: center !important;
}

.file-cell {
  display: flex;
  align-items: center;
  gap: 10px;
}

.file-badge {
  display: inline-grid;
  width: 28px;
  height: 28px;
  place-items: center;
  border-radius: 4px;
  color: #fff;
  font-size: 9px;
  font-weight: 800;
  line-height: 1;
}

.file-badge.pdf {
  background: #c5483a;
}

.action-group {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
}

.action-group .el-button {
  color: #26392f;
}

.action-group .el-button:hover {
  color: #516156;
}

.more-button {
  transform: rotate(90deg);
}

.table-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  padding-top: 12px;
  color: #6f675b;
  font-size: 14px;
}

.pagination-tools {
  display: flex;
  align-items: center;
  gap: 10px;
}

.page-size-select {
  width: 126px;
}

.current-page {
  width: 40px;
  padding-inline: 0;
}

@media (max-width: 920px) {
  .card-header,
  .table-footer {
    align-items: stretch;
    flex-direction: column;
  }

  .table-tools {
    flex-wrap: wrap;
  }

  .search-input {
    width: min(100%, 420px);
  }
}

@media (max-width: 720px) {
  .upload-panel,
  .literature-card {
    margin-inline: -2px;
  }

  .upload-panel {
    padding: 14px;
  }

  .upload-dropzone {
    min-height: 210px;
    padding: 18px;
  }

  .upload-dropzone h2 {
    font-size: 18px;
  }

  .table-tools,
  .pagination-tools {
    width: 100%;
  }

  .search-input,
  .page-size-select {
    flex: 1 1 100%;
    width: 100%;
  }
}
</style>
