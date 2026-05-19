import { computed, ref } from 'vue'
import { defineStore } from 'pinia'

export type ChatRole = 'user' | 'assistant'

export interface QaCitation {
  citationId: string
  title?: string
  paperMd5?: string
  sectionPath?: string
  pageStart?: number
  pageEnd?: number
}

export interface ChatMessage {
  id: string
  role: ChatRole
  content: string
  citations?: QaCitation[]
}

export interface ChatSession {
  id: string
  title: string
  updatedAt: number
  messages: ChatMessage[]
}

const CHAT_SESSIONS_STORAGE_KEY = 'scholarease:qa:sessions'
const LEGACY_SESSION_STORAGE_KEY = 'scholarease:qa:sessionId'
const NEW_SESSION_TITLE = '新对话'

function loadStoredSessions() {
  try {
    const raw = localStorage.getItem(CHAT_SESSIONS_STORAGE_KEY)
    if (raw) {
      const parsed = JSON.parse(raw) as ChatSession[]
      if (Array.isArray(parsed) && parsed.length > 0) {
        return parsed
          .filter((session) => session?.id)
          .map((session) => ({
            id: session.id,
            title: session.title || NEW_SESSION_TITLE,
            updatedAt: session.updatedAt || Date.now(),
            messages: Array.isArray(session.messages) ? session.messages : [],
          }))
          .sort((left, right) => right.updatedAt - left.updatedAt)
      }
    }
  } catch {
    // 本地历史损坏时自动回退。
  }

  const legacySessionId = localStorage.getItem(LEGACY_SESSION_STORAGE_KEY)
  return legacySessionId
    ? [{
        id: legacySessionId,
        title: NEW_SESSION_TITLE,
        updatedAt: Date.now(),
        messages: [],
      }]
    : []
}

export const useChatSessionsStore = defineStore('chatSessions', () => {
  const initialSessions = loadStoredSessions()
  const currentSessionId = ref(initialSessions[0]?.id ?? crypto.randomUUID())
  const sessions = ref<ChatSession[]>(
    initialSessions.length > 0
      ? initialSessions
      : [{
          id: currentSessionId.value,
          title: NEW_SESSION_TITLE,
          updatedAt: Date.now(),
          messages: [],
        }],
  )

  const activeSession = computed(() => {
    let session = sessions.value.find((item) => item.id === currentSessionId.value)
    if (!session) {
      session = {
        id: currentSessionId.value,
        title: NEW_SESSION_TITLE,
        updatedAt: Date.now(),
        messages: [],
      }
      sessions.value.unshift(session)
    }
    return session
  })

  const messages = computed(() => activeSession.value.messages)
  const activeSessionTitle = computed(() => activeSession.value.title)

  function persist() {
    localStorage.setItem(CHAT_SESSIONS_STORAGE_KEY, JSON.stringify(sessions.value))
    localStorage.setItem(LEGACY_SESSION_STORAGE_KEY, currentSessionId.value)
  }

  function touchActiveSession() {
    const session = activeSession.value
    session.updatedAt = Date.now()
    sessions.value = [
      session,
      ...sessions.value
        .filter((item) => item.id !== session.id)
        .sort((left, right) => right.updatedAt - left.updatedAt),
    ]
    persist()
  }

  function titleFromQuestion(question: string) {
    const compact = question.replace(/\s+/g, ' ').trim()
    return compact.length > 24 ? `${compact.slice(0, 24)}...` : compact || NEW_SESSION_TITLE
  }

  function ensureSessionId() {
    if (!currentSessionId.value) {
      currentSessionId.value = crypto.randomUUID()
    }
    persist()
    return currentSessionId.value
  }

  function createSession() {
    const session: ChatSession = {
      id: crypto.randomUUID(),
      title: NEW_SESSION_TITLE,
      updatedAt: Date.now(),
      messages: [],
    }
    sessions.value = [session, ...sessions.value]
    currentSessionId.value = session.id
    persist()
  }

  function switchSession(sessionId: string) {
    currentSessionId.value = sessionId
    persist()
  }

  function addMessage(message: ChatMessage) {
    activeSession.value.messages.push(message)
    touchActiveSession()
  }

  function ensureTitleFromQuestion(question: string) {
    if (activeSession.value.title === NEW_SESSION_TITLE) {
      activeSession.value.title = titleFromQuestion(question)
      touchActiveSession()
    }
  }

  function appendMessageContent(messageId: string, content: string) {
    if (!content) {
      return
    }
    const message = activeSession.value.messages.find((item) => item.id === messageId)
    if (!message) {
      return
    }
    message.content += content
    touchActiveSession()
  }

  function setMessageCitations(messageId: string, citations: QaCitation[]) {
    const message = activeSession.value.messages.find((item) => item.id === messageId)
    if (!message) {
      return
    }
    message.citations = citations
    touchActiveSession()
  }

  persist()

  return {
    activeSessionTitle,
    currentSessionId,
    messages,
    sessions,
    addMessage,
    appendMessageContent,
    createSession,
    ensureSessionId,
    ensureTitleFromQuestion,
    setMessageCitations,
    switchSession,
  }
})
