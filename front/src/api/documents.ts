import axios, { type AxiosResponse } from 'axios'

export type ApiResponse<T> = {
  code: string
  message: string
  data: T | null
  traceId: string
  timestamp: string
}

export type ParseStatus = 'PENDING' | 'PARSING' | 'PARSED' | 'FAILED'

// 文献库列表中的文献摘要。papers 表只保存已经解析确认并落到 Zotero/MinIO 的文献。
export type PaperSummary = {
  paperId: number
  paperMd5: string
  fileName: string
  fileSizeBytes: number
  fileType: 'PDF'
  title: string
  authors: string[]
  authorText: string
  storageLocation: string
  uploadTime: string
  year?: number | null
  venue?: string | null
  doi?: string | null
}

export type PageResult<T> = {
  items: T[]
  page: number
  pageSize: number
  total: number
  hasNext: boolean
}

export type UploadDocumentInput = {
  traceId: string
  file: File
  fileName: string
  paperMd5: string
  fileSizeBytes: number
  submissionTime: string
}

// 上传接口返回的是解析进度，不是文献摘要；文献主表要等解析完成后才创建。
export type UploadProgress = {
  traceId: string
  paperMd5: string
  fileName: string
  fileSizeBytes: number
  submissionTime: string
  parseStatus: ParseStatus
  fullZipUrl?: string | null
}

export type ListDocumentsParams = {
  keyword?: string
  year?: number
  venue?: string
  page?: number
  pageSize?: number
}

export type DuplicatePaperData = {
  duplicateReason?: 'PDF_MD5_MATCHED'
  existingPaper?: Partial<PaperSummary>
}

export class DocumentApiError extends Error {
  code: string
  status: number
  data: unknown
  traceId?: string

  constructor(
    message: string,
    options: { code: string; status: number; data?: unknown; traceId?: string },
  ) {
    super(message)
    this.name = 'DocumentApiError'
    this.code = options.code
    this.status = options.status
    this.data = options.data
    this.traceId = options.traceId
  }
}

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? '/api'

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

function parseApiResponse<T>(response: AxiosResponse<ApiResponse<T> | null>): T {
  const payload = response.data

  if (!payload) {
    throw new DocumentApiError('接口响应格式异常', {
      code: 'INVALID_RESPONSE',
      status: response.status,
    })
  }

  if (response.status < 200 || response.status >= 300 || payload.code !== 'SUCCESS') {
    throw new DocumentApiError(payload.message || '请求失败', {
      code: payload.code || 'REQUEST_FAILED',
      status: response.status,
      data: payload.data,
      traceId: payload.traceId,
    })
  }

  if (payload.data === null) {
    throw new DocumentApiError('接口响应数据为空', {
      code: 'INVALID_RESPONSE',
      status: response.status,
      traceId: payload.traceId,
    })
  }

  return payload.data
}

export async function uploadDocument(input: UploadDocumentInput) {
  const formData = new FormData()
  formData.append('traceId', input.traceId)
  formData.append('file', input.file)
  formData.append('fileName', input.fileName)
  formData.append('paperMd5', input.paperMd5)
  formData.append('fileSizeBytes', String(input.fileSizeBytes))
  formData.append('submissionTime', input.submissionTime)

  const response = await axios.post<ApiResponse<UploadProgress> | null>(
    buildApiUrl('/documents/upload'),
    formData,
    {
      headers: {
        'X-Trace-Id': input.traceId,
      },
      validateStatus: () => true,
    },
  )

  return parseApiResponse<UploadProgress>(response)
}

export type DeleteResult = {
  deleted: boolean
  paperId: number
}

export async function deleteDocument(paperId: number) {
  const response = await axios.delete<ApiResponse<DeleteResult> | null>(
    buildApiUrl(`/documents/${paperId}`),
    { validateStatus: () => true },
  )
  return parseApiResponse(response)
}

export async function listDocuments(params: ListDocumentsParams = {}) {
  const response = await axios.get<ApiResponse<PageResult<PaperSummary>> | null>(
    buildApiUrl('/documents', {
      keyword: params.keyword,
      year: params.year,
      venue: params.venue,
      page: params.page ?? 1,
      pageSize: params.pageSize ?? 20,
    }),
    {
      validateStatus: () => true,
    },
  )

  return parseApiResponse<PageResult<PaperSummary>>(response)
}
