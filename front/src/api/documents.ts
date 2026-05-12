// 后端接口文档中的通用响应结构。
// 所有文献接口都会返回 code/message/data/traceId/timestamp，前端统一在这里解析，
// 页面组件只关心成功后的 data 或抛出的 DocumentApiError。
export type ApiResponse<T> = {
  code: string
  message: string
  data: T
  traceId: string
  timestamp: string
}

// 文献列表和上传成功后共用的摘要模型，对应接口文档中的 PaperSummary。
// 字段名保持后端语义：fileName/storageLocation/uploadTime 直接服务文献库表格展示。
export type PaperSummary = {
  paperId: number
  paperMd5: string
  fileName: string
  fileType: 'PDF'
  title: string
  authors: string[]
  authorText: string
  storageLocation: string
  uploadTime: string
  year?: number | null
  venue?: string | null
  doi?: string | null
  parseStatus: 'PENDING' | 'PARSING' | 'PARSED' | 'FAILED'
}

// 后端分页响应的 data 结构。列表页只使用 items/total/hasNext 驱动表格和分页按钮。
export type PageResult<T> = {
  items: T[]
  page: number
  pageSize: number
  total: number
  hasNext: boolean
}

// 上传接口入参。页面层使用更自然的 authors/keywords 数组，
// API 层负责转成后端 multipart 表单要求的 authorsJson/keywordsJson 字符串。
export type UploadDocumentInput = {
  file: File
  title?: string
  authors?: string[]
  keywords?: string[]
  language?: string
  year?: number
  venue?: string
  doi?: string
}

// 文献列表查询参数。未填写的筛选项不会拼到 URL 中，避免传递空字符串影响后端查询。
export type ListDocumentsParams = {
  keyword?: string
  parseStatus?: PaperSummary['parseStatus']
  year?: number
  venue?: string
  page?: number
  pageSize?: number
}

// DUPLICATE_PAPER 错误的 data 结构，用于前端展示“已存在文献”的标题等信息。
export type DuplicatePaperData = {
  duplicateReason?: 'PDF_MD5_MATCHED' | 'DOI_MATCHED' | 'TITLE_AUTHOR_YEAR_SIMILAR'
  existingPaper?: Partial<PaperSummary>
}

// 将业务错误和 HTTP 错误统一成一个 Error 类型，页面层可以根据 code 做精确提示。
// 例如 DUPLICATE_PAPER 展示重复文献信息，INVALID_JSON_FIELD 展示后端校验消息。
export class DocumentApiError extends Error {
  code: string
  status: number
  data: unknown
  traceId?: string

  constructor(message: string, options: { code: string; status: number; data?: unknown; traceId?: string }) {
    super(message)
    this.name = 'DocumentApiError'
    this.code = options.code
    this.status = options.status
    this.data = options.data
    this.traceId = options.traceId
  }
}

// 默认走 Vite 代理的 /api；如果部署环境需要直连其他后端地址，可在 .env 中配置 VITE_API_BASE_URL。
const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? '/api'

// 统一构造接口 URL：
// 1. 兼容 VITE_API_BASE_URL 是否以 / 结尾；
// 2. 自动拼接查询参数；
// 3. 跳过 undefined 和空字符串，保持请求干净。
function buildApiUrl(path: string, params?: Record<string, string | number | undefined>) {
  const baseUrl = API_BASE_URL.endsWith('/') ? API_BASE_URL.slice(0, -1) : API_BASE_URL
  const url = new URL(`${baseUrl}${path}`, window.location.origin)

  Object.entries(params ?? {}).forEach(([key, value]) => {
    if (value !== undefined && value !== '') {
      url.searchParams.set(key, String(value))
    }
  })

  return url.toString()
}

// 统一解析后端响应：
// - JSON 解析失败：说明不是约定的接口响应，抛 INVALID_RESPONSE；
// - HTTP 非 2xx 或业务 code 非 SUCCESS：抛 DocumentApiError，并保留 code/data/traceId；
// - 成功：只返回 data，减少页面组件里的样板判断。
async function parseApiResponse<T>(response: Response): Promise<T> {
  const payload = (await response.json().catch(() => null)) as ApiResponse<T> | null

  if (!payload) {
    throw new DocumentApiError('接口响应格式异常', {
      code: 'INVALID_RESPONSE',
      status: response.status,
    })
  }

  if (!response.ok || payload.code !== 'SUCCESS') {
    throw new DocumentApiError(payload.message || '请求失败', {
      code: payload.code || 'REQUEST_FAILED',
      status: response.status,
      data: payload.data,
      traceId: payload.traceId,
    })
  }

  return payload.data
}

// 上传本地 PDF。
// 注意这里不手动设置 Content-Type，浏览器会为 FormData 自动生成 multipart boundary。
export async function uploadDocument(input: UploadDocumentInput) {
  const formData = new FormData()

  // file 是唯一必填字段；其他元数据都是可选字段，空值不提交。
  formData.append('file', input.file)
  appendIfPresent(formData, 'title', input.title)
  appendIfPresent(formData, 'authorsJson', input.authors?.length ? JSON.stringify(input.authors) : undefined)
  appendIfPresent(formData, 'keywordsJson', input.keywords?.length ? JSON.stringify(input.keywords) : undefined)
  appendIfPresent(formData, 'language', input.language)
  appendIfPresent(formData, 'year', input.year)
  appendIfPresent(formData, 'venue', input.venue)
  appendIfPresent(formData, 'doi', input.doi)

  const response = await fetch(buildApiUrl('/documents/upload'), {
    method: 'POST',
    body: formData,
  })

  return parseApiResponse<PaperSummary>(response)
}

// 查询文献列表。默认 page=1/pageSize=20，与接口文档的分页约定保持一致。
export async function listDocuments(params: ListDocumentsParams = {}) {
  const response = await fetch(
    buildApiUrl('/documents', {
      keyword: params.keyword,
      parseStatus: params.parseStatus,
      year: params.year,
      venue: params.venue,
      page: params.page ?? 1,
      pageSize: params.pageSize ?? 20,
    }),
  )

  return parseApiResponse<PageResult<PaperSummary>>(response)
}

// 只追加有值字段，避免 FormData 中出现 year=""、doi="" 这类容易让后端误判的空参数。
function appendIfPresent(formData: FormData, key: string, value?: string | number) {
  if (value !== undefined && value !== '') {
    formData.append(key, String(value))
  }
}
