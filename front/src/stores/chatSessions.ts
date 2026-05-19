import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import {
  createQaSession,
  deleteQaSession,
  getQaSession,
  listQaSessions,
  type QaSessionDetail,
  type QaSessionSummary,
} from '@/api/qa'

/** 聊天消息的角色类型：用户提问 (user) 或 AI 助手回答 (assistant) */
export type ChatRole = 'user' | 'assistant'

/** 
 * 问答文献引用对象
 * 包含大模型回答时所引用的具体文献和段落信息
 */
export interface QaCitation {
  /** 引用标签 ID，例如 "S1" */
  citationId: string
  /** 文献标题 */
  title?: string
  /** 文献的唯一 MD5 标识 */
  paperMd5?: string
  /** 引用的章节路径 */
  sectionPath?: string
  /** 引用起始页码 */
  pageStart?: number
  /** 引用结束页码 */
  pageEnd?: number
}

/** 单条聊天消息对象 */
export interface ChatMessage {
  /** 消息唯一 ID（前端生成 uuid） */
  id: string
  /** 发送者的角色 */
  role: ChatRole
  /** 消息内容（通常是 Markdown 文本） */
  content: string
  /** 该条消息附带的引用列表（仅 assistant 角色在 RAG 场景下会有） */
  citations?: QaCitation[]
}

/** 
 * 聊天会话对象 
 * 对应左侧历史记录列表中的一项，包含一个对话从头到尾的所有信息
 */
export interface ChatSession {
  id: string
  title: string
  updatedAt: number
  messages: ChatMessage[]
}

// 新建会话时的默认标题
const NEW_SESSION_TITLE = '新对话'

/** 工具函数：将后端的 ISO 时间字符串安全地转换为时间戳，转换失败则兜底为当前时间 */
function toTimestamp(value?: string) {
  const timestamp = value ? Date.parse(value) : Number.NaN
  return Number.isFinite(timestamp) ? timestamp : Date.now()
}

/** 
 * 数据转换函数：将会话摘要 (不含消息，来自 list 接口) 转换为本地的 ChatSession 对象 
 * 用于初始化左侧的历史对话列表
 */
function summaryToSession(summary: QaSessionSummary): ChatSession {
  return {
    id: summary.sessionId,
    title: summary.title || NEW_SESSION_TITLE,
    updatedAt: toTimestamp(summary.updatedAt),
    messages: [],
  }
}

/** 
 * 数据转换函数：将会话详情 (包含全部历史消息，来自 detail 接口) 转换为本地的 ChatSession 对象
 * 用于用户点击某一条历史对话时加载出完整的聊天记录
 */
function detailToSession(detail: QaSessionDetail): ChatSession {
  return {
    id: detail.sessionId,
    title: detail.title || NEW_SESSION_TITLE,
    updatedAt: toTimestamp(detail.updatedAt),
    messages: Array.isArray(detail.messages)
      ? detail.messages.map((message) => ({
          id: message.id,
          role: message.role,
          content: message.content,
          citations: message.citations ?? [],
        }))
      : [],
  }
}

function titleFromQuestion(question: string) {
  const compact = question.replace(/\s+/g, ' ').trim()
  return compact.length > 24 ? `${compact.slice(0, 24)}...` : compact || NEW_SESSION_TITLE
}

/**
 * 聊天会话的 Pinia Store
 * 这是前端管理所有对话状态、页面渲染数据来源和操作方法的核心枢纽
 */
export const useChatSessionsStore = defineStore('chatSessions', () => {
  // 当前正在窗口中展示和交互的会话 ID
  const currentSessionId = ref('')
  // 所有的历史会话列表
  const sessions = ref<ChatSession[]>([])
  // 标记是否已经从后端完成了首次加载
  const isLoaded = ref(false)
  // 正在进行的加载任务（用于防止并发重复请求）
  const loadTask = ref<Promise<void> | null>(null)
  const createTask = ref<Promise<string> | null>(null)

  /** 计算属性：获取当前处于激活状态的会话对象引用 */
  const activeSession = computed(() =>
    sessions.value.find((item) => item.id === currentSessionId.value) ?? null,
  )
  // 映射当前激活会话的消息列表给 Vue 组件渲染
  const messages = computed(() => activeSession.value?.messages ?? [])
  // 映射当前激活会话的标题
  const activeSessionTitle = computed(() => activeSession.value?.title ?? NEW_SESSION_TITLE)

  /** 工具方法：将内存中的所有会话按更新时间降序重新排列 */
  function sortSessions() {
    sessions.value = [...sessions.value].sort((left, right) => right.updatedAt - left.updatedAt)
  }

  /** 
   * 工具方法：插入或更新一个会话，并置顶。
   * 利用过滤排除旧有的同 ID 会话，放入新的，并触发重新排序。
   */
  function upsertSession(session: ChatSession) {
    sessions.value = [
      session,
      ...sessions.value.filter((item) => item.id !== session.id),
    ]
    sortSessions()
  }

  /**
   * 初始化：从远端加载所有历史会话列表。
   */
  async function loadSessions() {
    if (isLoaded.value) {
      return
    }
    // 如果当前正在加载中，则直接返回正在进行中的 Promise（避免并发请求导致接口防抖）
    if (loadTask.value) {
      return loadTask.value
    }

    loadTask.value = (async () => {
      try {
        // 从后端拉取会话摘要列表
        const remoteSessions = await listQaSessions()
        sessions.value = remoteSessions.map(summaryToSession)
        sortSessions()

        if (sessions.value.length === 0) {
          // 如果用户没有任何历史记录，自动发起请求创建一个全新会话
          await createSession()
        } else {
          // 如果有历史记录，则自动拉取最近（第一个）会话的详情并激活它
          const firstSession = sessions.value[0]
          if (firstSession) {
            await switchSession(firstSession.id)
          }
        }
        isLoaded.value = true
      } finally {
        loadTask.value = null
      }
    })()

    return loadTask.value
  }

  /**
   * 确保有一个当前活动的 sessionId
   * （比如在发送消息前调用它，如果由于意外原因 ID 为空，会自动兜底新建一个）
   * @returns 当前激活的会话 ID
   */
  async function ensureSessionId() {
    await loadSessions()
    if (!currentSessionId.value) {
      await createSession()
    }
    return currentSessionId.value
  }

  /** 发起 API 请求在后端创建一个新会话，并在前端置顶为激活状态 */
  async function createSession() {
    if (createTask.value) {
      return createTask.value
    }

    createTask.value = (async () => {
      try {
        const remoteSession = await createQaSession()
        const session = summaryToSession(remoteSession)
        upsertSession(session)
        // 切换到新会话
        currentSessionId.value = session.id
        return session.id
      } finally {
        createTask.value = null
      }
    })()

    return createTask.value
  }

  /** 点击侧边栏切换会话：向后端发起拉取完整聊天记录的请求并激活 */
  async function switchSession(sessionId: string) {
    const detail = await getQaSession(sessionId)
    const session = detailToSession(detail)
    // 将包含详情的 session 对象更新到前端内存中
    upsertSession(session)
    currentSessionId.value = session.id
  }

  async function refreshCurrentSession() {
    if (!currentSessionId.value) {
      return
    }
    await switchSession(currentSessionId.value)
  }

  /** 删除侧边栏中指定的会话 */
  async function deleteSession(sessionId: string) {
    // 记录要删除的是不是当前正在查看的会话
    const wasActiveSession = currentSessionId.value === sessionId
    await deleteQaSession(sessionId)
    sessions.value = sessions.value.filter((session) => session.id !== sessionId)

    if (wasActiveSession) {
      const nextSession = sessions.value.at(0)
      if (nextSession) {
        await switchSession(nextSession.id)
      } else {
        // 如果全删光了，必须保证留有一个空白会话兜底
        await createSession()
      }
    }

    return wasActiveSession
  }

  /**
   * 刷新当前会话的更新时间，并触发界面排序（将会话提到列表最上面）
   */
  function touchActiveSession() {
    const session = activeSession.value
    if (!session) {
      return
    }
    session.updatedAt = Date.now()
    upsertSession(session)
  }

  /** 向当前的对话列表追加一条完整的消息 */
  function addMessage(message: ChatMessage) {
    const session = activeSession.value
    if (!session) {
      return
    }
    session.messages.push(message)
    touchActiveSession()
  }

  function ensureTitleFromQuestion(question: string) {
    const session = activeSession.value
    if (!session) {
      return NEW_SESSION_TITLE
    }
    if (session.title === NEW_SESSION_TITLE) {
      session.title = titleFromQuestion(question)
      touchActiveSession()
    }
    return session.title
  }

  /**
   * 往特定消息的内容末尾追加字符串片段。
   * 【打字机效果核心逻辑】：接收后端 WebSocket 流式传来的 token 时调用。
   */
  function appendMessageContent(messageId: string, content: string) {
    if (!content) {
      return
    }
    const message = activeSession.value?.messages.find((item) => item.id === messageId)
    if (!message) {
      return
    }
    message.content += content
    touchActiveSession()
  }

  /**
   * 为特定消息设置文献引用（Citations）列表。
   * 接收到后端的 metadata 阶段时调用，用于展示大模型回答所依赖的具体文献和页码出处。
   */
  function setMessageCitations(messageId: string, citations: QaCitation[]) {
    const message = activeSession.value?.messages.find((item) => item.id === messageId)
    if (!message) {
      return
    }
    message.citations = citations
    touchActiveSession()
  }

  // 对外暴露的状态和方法
  return {
    activeSessionTitle,
    currentSessionId,
    isLoaded,
    messages,
    sessions,
    addMessage,
    appendMessageContent,
    createSession,
    deleteSession,
    ensureSessionId,
    ensureTitleFromQuestion,
    loadSessions,
    refreshCurrentSession,
    setMessageCitations,
    switchSession,
  }
})
