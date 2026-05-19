import axios, { type AxiosResponse } from 'axios'
import type { QaCitation } from '@/stores/chatSessions'

export type ApiResponse<T> = {
  code: string
  message: string
  data: T | null
  traceId: string
  timestamp: string
}

export type QaChatMessage = {
  id: string
  role: 'user' | 'assistant'
  content: string
  citations?: QaCitation[]
}

export type QaSessionSummary = {
  sessionId: string
  title: string
  createdAt: string
  updatedAt: string
}

export type QaSessionDetail = QaSessionSummary & {
  messages: QaChatMessage[]
}

export type QaDeleteSessionResult = {
  sessionId: string
  deleted: boolean
}

class QaApiError extends Error {
  code: string
  status: number

  constructor(message: string, options: { code: string; status: number }) {
    super(message)
    this.name = 'QaApiError'
    this.code = options.code
    this.status = options.status
  }
}

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? '/api'

function buildApiUrl(path: string) {
  const baseUrl = API_BASE_URL.endsWith('/') ? API_BASE_URL.slice(0, -1) : API_BASE_URL
  return new URL(`${baseUrl}${path}`, window.location.origin).toString()
}

function parseApiResponse<T>(response: AxiosResponse<ApiResponse<T> | null>): T {
  const payload = response.data
  if (!payload) {
    throw new QaApiError('接口响应格式异常', {
      code: 'INVALID_RESPONSE',
      status: response.status,
    })
  }
  if (response.status < 200 || response.status >= 300 || payload.code !== 'SUCCESS') {
    throw new QaApiError(payload.message || '请求失败', {
      code: payload.code || 'REQUEST_FAILED',
      status: response.status,
    })
  }
  if (payload.data === null) {
    throw new QaApiError('接口响应数据为空', {
      code: 'INVALID_RESPONSE',
      status: response.status,
    })
  }
  return payload.data
}

export async function listQaSessions() {
  const response = await axios.get<ApiResponse<QaSessionSummary[]> | null>(
    buildApiUrl('/qa/sessions'),
    { validateStatus: () => true },
  )
  return parseApiResponse<QaSessionSummary[]>(response)
}

export async function createQaSession() {
  const response = await axios.post<ApiResponse<QaSessionSummary> | null>(
    buildApiUrl('/qa/sessions'),
    null,
    { validateStatus: () => true },
  )
  return parseApiResponse<QaSessionSummary>(response)
}

export async function getQaSession(sessionId: string) {
  const response = await axios.get<ApiResponse<QaSessionDetail> | null>(
    buildApiUrl(`/qa/sessions/${sessionId}`),
    { validateStatus: () => true },
  )
  return parseApiResponse<QaSessionDetail>(response)
}

export async function deleteQaSession(sessionId: string) {
  const response = await axios.delete<ApiResponse<QaDeleteSessionResult> | null>(
    buildApiUrl(`/qa/sessions/${sessionId}`),
    { validateStatus: () => true },
  )
  return parseApiResponse<QaDeleteSessionResult>(response)
}
