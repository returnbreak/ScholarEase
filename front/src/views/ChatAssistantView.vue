<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue' // 从 Vue 引入 computed 和 ref：computed 用于派生渲染后的 HTML，ref 用于声明可响应的界面状态。
import { Promotion, Setting } from '@element-plus/icons-vue' // 从 Element Plus 图标库引入发送图标和设置图标，供模板中的按钮使用。
import { storeToRefs } from 'pinia'
import katex from 'katex' // 引入 KaTeX，用于把 LaTeX 公式字符串渲染成真正的数学排版 HTML。
import 'katex/dist/katex.min.css' // 引入 KaTeX 官方样式，否则生成的公式 HTML 没有正确字体、间距和上下标排版。
import ModelSettingsDialog from '@/components/ModelSettingsDialog.vue' // 引入模型设置弹窗组件，点击右上角设置按钮时显示。
import { useChatSessionsStore, type ChatMessage, type QaCitation } from '@/stores/chatSessions'
import { useModelSettingsStore } from '@/stores/modelSettings' // 引入模型设置 Pinia store，用来读取当前模型预设和模型名称。

defineOptions({ name: 'ChatAssistantView' })

const modelSettingsVisible = ref(false) // 控制模型设置弹窗是否显示；false 表示页面初始不打开弹窗。
const inputMessage = ref('') // 保存底部输入框中的用户输入内容；模板通过 v-model 和它双向绑定。
const chatSessions = useChatSessionsStore()
const modelSettings = useModelSettingsStore() // 获取模型设置 store 实例；模板会读取 activePreset.label 和 modelName。
const { activeSessionTitle, currentSessionId: sessionId, messages } = storeToRefs(chatSessions)

interface QaWebSocketResponse {
  type?: 'connection' | 'metadata' | 'token' | 'completion' | 'error'
  content?: string
  data?: {
    sessionId?: string
    messageId?: string
    citations?: QaCitation[]
  }
  error?: string
}

const isStreaming = ref(false)
const thinkingMessageId = ref('')
const qaSocket = ref<WebSocket | null>(null)
const socketReadyTask = ref<Promise<WebSocket> | null>(null)
let activeAssistantMessageId = ''

// 响应式的消息列表，用于存储当前对话的所有历史记录。
// 计算属性：遍历 messages，把里面原始的 Markdown 文本预先渲染成 HTML。
// 这样模板里直接 v-html="message.renderedContent" 即可，当 messages 变化时自动更新。
const renderedMessages = computed(() =>
  messages.value.map((message) => ({
    ...message,
    renderedContent: renderMarkdown(message.content, message.citations ?? []),
  })),
)

function resetStreamingState() {
  isStreaming.value = false
  thinkingMessageId.value = ''
  activeAssistantMessageId = ''
}

function handleExternalSessionSwitch() {
  resetStreamingState()
  closeQaSocket()
}

window.addEventListener('scholarease:qa-session-switched', handleExternalSessionSwitch)

/**
 * 确保当前有一个可用的会话 ID (sessionId)。
 * 如果已经存在（从 localStorage 中读取的），则直接返回；
 * 否则在前端生成一个新的会话 ID，并持久化到本地。
 */
function ensureSession() {
  return chatSessions.ensureSessionId()
}

/**
 * 发送用户消息并处理流式回复的核心逻辑。
 */
async function sendMessage() {
  const text = inputMessage.value.trim()
  // 防抖：空内容或正在输出流式消息时不允许发送新消息
  if (!text || isStreaming.value) {
    return
  }

  // 1. 将用户的提问加入到消息列表中
  chatSessions.addMessage({
    id: crypto.randomUUID(),
    role: 'user',
    content: text,
  })
  chatSessions.ensureTitleFromQuestion(text)
  // 2. 预先创建一个空的助手回复对象，用于后续接收流式生成的字符
  const assistantMessage: ChatMessage = {
    id: crypto.randomUUID(),
    role: 'assistant',
    content: '',
  }
  chatSessions.addMessage(assistantMessage)
  
  // 3. 清空输入框并设置状态为正在流式输出
  inputMessage.value = ''
  isStreaming.value = true
  thinkingMessageId.value = assistantMessage.id
  activeAssistantMessageId = assistantMessage.id

  try {
    // 4. 复用当前会话的 WebSocket 发送问题，后端会持续推送本轮 token。
    const socket = await ensureQaSocket()
    socket.send(
      JSON.stringify({
        sessionId: ensureSession(),
        message: text,
      }),
    )
  } catch (error) {
    // 发生异常时，把错误信息也拼接到回复气泡里展示给用户
    appendAssistantContent(
      assistantMessage.id,
      `\n\n${error instanceof Error ? error.message : 'WebSocket 问答失败'}`,
    )
    resetStreamingState()
  } finally {
    // 正常流式结束由 WebSocket completion 事件负责；这里仅兜底处理同步失败。
  }
}

function appendAssistantContent(messageId: string, content: string) {
  if (!content) {
    return
  }
  chatSessions.appendMessageContent(messageId, content)
}

function setAssistantCitations(messageId: string, citations: QaCitation[]) {
  chatSessions.setMessageCitations(messageId, citations)
}

function qaWebSocketUrl() {
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
  return `${protocol}//${window.location.host}/api/qa/ws`
}

function ensureQaSocket() {
  const existing = qaSocket.value
  if (existing?.readyState === WebSocket.OPEN) {
    return Promise.resolve(existing)
  }
  if (socketReadyTask.value) {
    return socketReadyTask.value
  }

  socketReadyTask.value = new Promise<WebSocket>((resolve, reject) => {
    const socket = new WebSocket(qaWebSocketUrl())
    qaSocket.value = socket

    socket.onopen = () => {
      socketReadyTask.value = null
      resolve(socket)
    }

    socket.onmessage = handleQaSocketMessage

    socket.onerror = () => {
      const wasConnecting = socket.readyState === WebSocket.CONNECTING
      reject(new Error('WebSocket 连接失败'))
      if (!wasConnecting && activeAssistantMessageId) {
        appendAssistantContent(activeAssistantMessageId, '\n\nWebSocket 连接失败')
      }
      isStreaming.value = false
      thinkingMessageId.value = ''
      activeAssistantMessageId = ''
      closeQaSocket()
    }

    socket.onclose = () => {
      if (qaSocket.value === socket) {
        qaSocket.value = null
      }
      socketReadyTask.value = null
      if (isStreaming.value && activeAssistantMessageId) {
        appendAssistantContent(activeAssistantMessageId, '\n\nWebSocket 连接已关闭')
      }
      isStreaming.value = false
      thinkingMessageId.value = ''
      activeAssistantMessageId = ''
    }
  })

  return socketReadyTask.value
}

function handleQaSocketMessage(event: MessageEvent) {
  let payload: QaWebSocketResponse
  try {
    payload = JSON.parse(event.data)
  } catch {
    handleQaSocketError('WebSocket 响应解析失败')
    return
  }

  if (payload.type === 'token') {
    thinkingMessageId.value = ''
    appendAssistantContent(activeAssistantMessageId, payload.content ?? '')
  } else if (payload.type === 'metadata') {
    setAssistantCitations(activeAssistantMessageId, payload.data?.citations ?? [])
  } else if (payload.type === 'error') {
    handleQaSocketError(payload.error ?? 'WebSocket 问答失败')
  } else if (payload.type === 'completion') {
    resetStreamingState()
  }
}

function handleQaSocketError(message: string) {
  if (activeAssistantMessageId) {
    appendAssistantContent(activeAssistantMessageId, `\n\n${message}`)
  }
  resetStreamingState()
  closeQaSocket()
}

function closeQaSocket() {
  const socket = qaSocket.value
  qaSocket.value = null
  socketReadyTask.value = null
  if (socket && socket.readyState !== WebSocket.CLOSED && socket.readyState !== WebSocket.CLOSING) {
    socket.close()
  }
}

onBeforeUnmount(() => {
  window.removeEventListener('scholarease:qa-session-switched', handleExternalSessionSwitch)
  closeQaSocket()
})

function escapeHtml(value: string) { // 定义 HTML 转义函数，参数 value 是即将被插入 HTML 的原始文本。
  return value // 返回连续替换后的安全字符串；这里使用链式调用逐个处理特殊字符。
    .replace(/&/g, '&amp;') // 先转义 &，避免后续生成的 &lt; 等实体被再次误处理。
    .replace(/</g, '&lt;') // 转义小于号，防止用户文本中的标签被浏览器当成 HTML 开始标签。
    .replace(/>/g, '&gt;') // 转义大于号，防止用户文本中的标签被浏览器当成 HTML 结束符。
    .replace(/"/g, '&quot;') // 转义双引号，避免文本进入属性场景时破坏 HTML 属性边界。
    .replace(/'/g, '&#39;') // 转义单引号，补齐另一种常见属性边界字符的安全处理。
} // HTML 转义函数结束。

function citationName(citation: QaCitation) {
  return citation.title?.trim() || citation.paperMd5 || citation.citationId
}

function citationTitle(citation: QaCitation) {
  const page = citation.pageStart
    ? `第 ${citation.pageStart}${citation.pageEnd ? `-${citation.pageEnd}` : ''} 页`
    : ''
  return [citation.citationId, citation.title, citation.sectionPath, page].filter(Boolean).join(' · ')
}

function renderCitationRefs(value: string, citations: QaCitation[], protectSegment: (segment: string) => string) {
  if (!citations.length) {
    return value
  }
  const citationMap = new Map(citations.map((citation) => [citation.citationId.toUpperCase(), citation]))
  return value.replace(/\[(S\d+)]/gi, (raw, citationId: string) => {
    const citation = citationMap.get(citationId.toUpperCase())
    if (!citation) {
      return raw
    }
    return protectSegment(
      `<span class="citation-ref" title="${escapeHtml(citationTitle(citation))}">${escapeHtml(citationName(citation))}</span>`,
    )
  })
}

function renderInline(value: string, citations: QaCitation[] = []) { // 定义行内 Markdown 渲染函数，用于处理段落、标题、表格单元格等短文本。
  const protectedSegments: string[] = [] // 存放已经渲染好的行内代码和行内公式，避免后续粗体/斜体正则误改它们。
  const protectSegment = (segment: string) => `@@SEGMENT_${protectedSegments.push(segment) - 1}@@` // 把 HTML 片段存入数组，并返回一个临时占位符。
  const safeValue = escapeHtml(value) // 先对原始文本做 HTML 转义，再把 Markdown 标记替换成允许的 HTML 标签。
  const withCitations = renderCitationRefs(safeValue, citations, protectSegment)
  const withCode = withCitations.replace(/`([^`]+)`/g, (_, code: string) => protectSegment(`<code>${code}</code>`)) // 把 `代码` 转为受保护的 <code> 片段。
  const withMath = withCode.replace(/\$(?!\$)([^$\n]+?)\$(?!\$)/g, (_, formula: string) => protectSegment(renderLatex(formula, false))) // 把 $公式$ 交给 KaTeX 渲染，并保护生成的 HTML。
  const withTextStyle = withMath
    .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>') // 把 **粗体** 转为 <strong>粗体</strong>。
    .replace(/\*([^*]+)\*/g, '<em>$1</em>') // 把 *斜体* 转为 <em>斜体</em>。

  return withTextStyle.replace(/@@SEGMENT_(\d+)@@/g, (_, index: string) => protectedSegments[Number(index)] ?? '') // 把临时占位符还原成行内代码/公式 HTML。
} // 行内 Markdown 渲染函数结束。

function renderLatex(formula: string, displayMode: boolean) { // 定义公式渲染函数，formula 是 LaTeX 内容，displayMode 控制行内/块级排版。
  return katex.renderToString(formula, { // 调用 KaTeX，把 LaTeX 字符串转换成带排版结构的 HTML 字符串。
    displayMode, // true 表示块级公式，false 表示行内公式。
    throwOnError: false, // 公式有小错误时不抛异常中断页面，而是在原位置显示错误提示。
    strict: 'ignore', // 忽略部分非严格 LaTeX 写法警告，兼容模型生成内容和论文抽取内容。
    trust: false, // 禁止公式内容生成不可信 HTML，避免安全风险。
  }) // KaTeX 渲染结束，返回可插入 v-html 的公式 HTML。
} // 公式渲染函数结束。

function isTableDivider(line: string) { // 定义表格分隔行判断函数，参数 line 是候选的 Markdown 表格第二行。
  return /^\s*\|?\s*:?-{3,}:?\s*(\|\s*:?-{3,}:?\s*)+\|?\s*$/.test(line) // 用正则匹配 |---|---| 这类表格分隔语法，兼容两侧可选竖线和对齐冒号。
} // 表格分隔行判断函数结束。

function splitTableRow(line: string) { // 定义表格行拆分函数，参数 line 是一整行 Markdown 表格文本。
  return line // 返回清洗并拆分后的单元格数组。
    .trim() // 去掉整行首尾空白，避免空格影响竖线处理。
    .replace(/^\|/, '') // 如果行首有竖线，则去掉它，避免拆出空白首列。
    .replace(/\|$/, '') // 如果行尾有竖线，则去掉它，避免拆出空白尾列。
    .split('|') // 按竖线拆分单元格内容。
    .map((cell) => cell.trim()) // 去掉每个单元格首尾空白，让表格内容更干净。
} // 表格行拆分函数结束。

function getLine(lines: string[], index: number) { // 定义安全取行函数，参数 lines 是行数组，index 是当前读取位置。
  return lines[index] ?? '' // 如果 index 越界或该位置不存在，就返回空字符串，避免严格模式下 undefined 报错。
} // 安全取行函数结束。

function renderMarkdown(markdown: string, citations: QaCitation[] = []) { // 定义轻量 Markdown 渲染器，参数 markdown 是待展示的原始 Markdown 文本。
  const lines = markdown.trim().split(/\r?\n/) // 去掉首尾空白后按换行切分，兼容 Windows 的 \r\n 和 Unix 的 \n。
  const html: string[] = [] // 用数组收集生成的 HTML 片段，最后再 join，避免频繁字符串拼接。
  let index = 0 // 当前解析到的行号；解析器会根据不同语法块主动推进它。

  while (index < lines.length) { // 主循环：只要还有未解析的行，就继续识别当前行属于哪种 Markdown 块。
    const line = getLine(lines, index) // 读取当前行原文，保留可能存在的缩进和代码内容。
    const trimmed = line.trim() // 得到去掉首尾空白后的版本，用于判断标题、列表、引用等语法。

    if (!trimmed) { // 如果当前行是空行，只把它当作 Markdown 块之间的分隔符。
      index += 1 // 跳过当前空行，移动到下一行。
      continue // 空行不输出任何 HTML，直接开始下一轮解析。
    } // 空行处理分支结束。

    if (trimmed.startsWith('$$')) { // 如果当前行以两个美元符号开头，说明进入块级 LaTeX 公式。
      const formulaLines: string[] = [] // 用数组收集块级公式内部的每一行内容。
      const firstFormulaPart = trimmed.slice(2).trim() // 取出起始 $$ 后面同一行的内容，兼容 $$E=mc^2$$ 这种单行写法。

      if (firstFormulaPart.endsWith('$$') && firstFormulaPart.length > 2) { // 如果公式在同一行内闭合，就直接按单行块级公式处理。
        const singleLineFormula = firstFormulaPart.slice(0, -2).trim() // 去掉末尾的 $$，只保留公式主体。
        html.push(renderLatex(singleLineFormula, true)) // 使用 KaTeX 输出真正排版后的块级公式 HTML。
        index += 1 // 单行块级公式只占一行，处理完后推进到下一行。
        continue // 当前块级公式已经处理完，进入下一轮主循环。
      } // 单行块级公式处理分支结束。

      if (firstFormulaPart) { // 如果起始 $$ 后面还有公式内容，就先把这一部分加入公式数组。
        formulaLines.push(firstFormulaPart) // 保存起始行中的公式内容。
      } // 起始行公式内容处理结束。

      index += 1 // 跳过起始 $$ 行，从下一行开始收集中间公式内容。

      while (index < lines.length && !getLine(lines, index).trim().startsWith('$$')) { // 持续读取，直到遇到结束 $$ 或文件结束。
        formulaLines.push(getLine(lines, index)) // 把公式行原样加入数组，保留反斜杠、下标、分式等 LaTeX 语法。
        index += 1 // 处理完当前公式行后推进到下一行。
      } // 块级公式内容收集循环结束。

      if (index < lines.length) { // 如果确实遇到了结束 $$ 行，就处理结束行。
        const closingFormulaPart = getLine(lines, index).trim().slice(2).trim() // 取出结束 $$ 后可能残留的内容，兼容 $$ 后面继续写内容的边缘情况。

        if (closingFormulaPart) { // 如果结束行里还有内容，就也加入公式数组。
          formulaLines.push(closingFormulaPart) // 保存结束行中的剩余公式内容。
        } // 结束行剩余内容处理结束。

        index += 1 // 跳过结束 $$ 行，让下一轮从公式块之后继续解析。
      } // 结束 $$ 行处理结束。

      html.push(renderLatex(formulaLines.join('\n'), true)) // 把多行公式合并后交给 KaTeX 输出块级公式 HTML。
      continue // 当前块级公式已经处理完，进入下一轮主循环。
    } // 块级公式处理分支结束。

    if (trimmed.startsWith('```')) { // 如果当前行以三个反引号开头，说明进入 fenced code block 代码块。
      const language = trimmed.slice(3).trim() // 取出反引号后面的语言名，例如 text、ts、json，后续放到 class 中。
      const codeLines: string[] = [] // 用数组收集代码块内部的每一行原文。
      index += 1 // 跳过代码块起始行，从下一行开始收集代码内容。

      while (index < lines.length && !getLine(lines, index).trim().startsWith('```')) { // 持续读取，直到遇到代码块结束反引号或文件结束。
        codeLines.push(getLine(lines, index)) // 把代码行原样加入数组，保留缩进和符号。
        index += 1 // 读取完一行代码后推进到下一行。
      } // 代码块内容收集循环结束。

      html.push( // 把代码块转换成 <pre><code> 结构，这是浏览器和代码高亮库常用的语义结构。
        `<pre><code${language ? ` class="language-${escapeHtml(language)}"` : ''}>${escapeHtml( // 如果写了语言名，就生成 language-xxx 类名；同时语言名也要转义。
          codeLines.join('\n'), // 把收集到的代码行重新用换行连接，保持代码块原始换行。
        )}</code></pre>`, // 对代码内容整体转义后放进 code 标签，避免代码里的 < > 被当成 HTML。
      ) // 代码块 HTML 片段加入完成。
      index += 1 // 跳过代码块结束行 ```，让下一轮从代码块之后继续解析。
      continue // 当前代码块已经处理完，进入下一轮主循环。
    } // 代码块处理分支结束。

    if (/^#{1,6}\s/.test(trimmed)) { // 如果当前行符合 “1 到 6 个 # 后跟空格”，说明它是 Markdown 标题。
      const level = trimmed.match(/^#{1,6}/)?.[0].length ?? 2 // 统计 # 的数量，决定生成 h1 到 h6；兜底使用 h2。
      const text = trimmed.replace(/^#{1,6}\s+/, '') // 去掉开头的 # 和空格，只保留标题正文。
      html.push(`<h${level}>${renderInline(text, citations)}</h${level}>`) // 把标题正文做行内渲染后包进对应级别的 h 标签。
      index += 1 // 标题只占一行，处理完后推进到下一行。
      continue // 标题已经处理完，进入下一轮主循环。
    } // 标题处理分支结束。

    if (trimmed.startsWith('>')) { // 如果当前行以 > 开头，说明它属于 Markdown 引用块。
      const quoteLines: string[] = [] // 用数组收集连续引用行的正文内容。

      while (index < lines.length && getLine(lines, index).trim().startsWith('>')) { // 连续的 > 行会被合并为同一个 blockquote。
        quoteLines.push(getLine(lines, index).trim().replace(/^>\s?/, '')) // 去掉每行开头的 > 和一个可选空格，只保留引用正文。
        index += 1 // 处理完当前引用行后继续看下一行是否也是引用。
      } // 引用块收集循环结束。

      html.push(`<blockquote>${quoteLines.map((line) => renderInline(line, citations)).join('<br>')}</blockquote>`) // 把每行引用内容做行内渲染，并用 <br> 保留多行引用的换行。
      continue // 引用块已经处理完，进入下一轮主循环。
    } // 引用块处理分支结束。

    if ( // 开始判断当前行是否是 Markdown 表格的表头行。
      trimmed.includes('|') && // 表头行必须包含竖线，否则不可能是表格。
      index + 1 < lines.length && // 表格至少需要下一行作为分隔行，所以必须确保下一行存在。
      isTableDivider(getLine(lines, index + 1)) // 下一行必须符合 |---|---| 这类分隔行语法，才确认这是表格。
    ) { // 表格判断成立，进入表格解析分支。
      const headers = splitTableRow(trimmed) // 把当前表头行拆成表头单元格数组。
      const rows: string[][] = [] // 用二维数组收集表格主体数据，每一行都是一个单元格数组。
      index += 2 // 跳过表头行和分隔行，从第一行表格数据开始读取。

      while (index < lines.length && getLine(lines, index).trim().includes('|')) { // 只要后续行仍包含竖线，就继续视为表格数据行。
        rows.push(splitTableRow(getLine(lines, index))) // 拆分当前数据行并加入 rows。
        index += 1 // 处理完当前数据行后推进到下一行。
      } // 表格数据行收集循环结束。

      html.push( // 开始拼接完整表格 HTML，并加入 html 片段数组。
        `<div class="markdown-table-wrap"><table><thead><tr>${headers // 外层 div 用于提供边框和横向滚动；table/thead/tr 是标准表格结构。
          .map((cell) => `<th>${renderInline(cell, citations)}</th>`) // 每个表头单元格渲染为 th，并允许单元格内部使用行内 Markdown。
          .join('')}</tr></thead><tbody>${rows // 所有 th 拼好后关闭表头，再开始拼接 tbody。
          .map( // 遍历每一行表格数据，把二维数组转成多行 tr。
            (row) => // row 表示一行数据，是若干单元格字符串组成的数组。
              `<tr>${row.map((cell) => `<td>${renderInline(cell, citations)}</td>`).join('')}</tr>`, // 把当前行每个单元格转为 td，再包进 tr。
          ) // 单行 tr 生成逻辑结束。
          .join('')}</tbody></table></div>`, // 把所有 tr 拼接起来，并关闭 tbody、table 和外层 div。
      ) // 表格 HTML 片段加入完成。
      continue // 表格已经处理完，进入下一轮主循环。
    } // 表格处理分支结束。

    if (/^\d+\.\s+/.test(trimmed)) { // 如果当前行符合 “数字 + 点 + 空格”，说明进入有序列表。
      const items: string[] = [] // 用数组收集连续有序列表项的正文。

      while (index < lines.length && /^\d+\.\s+/.test(getLine(lines, index).trim())) { // 连续的有序列表行会合并为同一个 ol。
        items.push(getLine(lines, index).trim().replace(/^\d+\.\s+/, '')) // 去掉每项开头的编号，只保留列表项内容。
        index += 1 // 处理完当前列表项后推进到下一行。
      } // 有序列表项收集循环结束。

      html.push(`<ol>${items.map((item) => `<li>${renderInline(item, citations)}</li>`).join('')}</ol>`) // 把每个列表项渲染为 li，再包进 ol。
      continue // 有序列表已经处理完，进入下一轮主循环。
    } // 有序列表处理分支结束。

    if (/^[-*]\s+/.test(trimmed)) { // 如果当前行以 “- 空格” 或 “* 空格” 开头，说明进入无序列表。
      const items: string[] = [] // 用数组收集连续无序列表项的正文。

      while (index < lines.length && /^[-*]\s+/.test(getLine(lines, index).trim())) { // 连续的无序列表行会合并为同一个 ul。
        items.push(getLine(lines, index).trim().replace(/^[-*]\s+/, '')) // 去掉每项开头的 - 或 *，只保留列表项内容。
        index += 1 // 处理完当前列表项后推进到下一行。
      } // 无序列表项收集循环结束。

      html.push(`<ul>${items.map((item) => `<li>${renderInline(item, citations)}</li>`).join('')}</ul>`) // 把每个列表项渲染为 li，再包进 ul。
      continue // 无序列表已经处理完，进入下一轮主循环。
    } // 无序列表处理分支结束。

    const paragraphLines: string[] = [] // 如果当前行不属于上述语法，就准备按普通段落收集连续文本。

    while ( // 开始收集普通段落，直到遇到空行或新的块级 Markdown 语法。
      index < lines.length && // 条件 1：不能越过最后一行。
      getLine(lines, index).trim() && // 条件 2：当前行不能为空，空行表示段落结束。
      !/^#{1,6}\s/.test(getLine(lines, index).trim()) && // 条件 3：遇到标题语法时停止，交给标题分支处理。
      !getLine(lines, index).trim().startsWith('>') && // 条件 4：遇到引用语法时停止，交给引用分支处理。
      !getLine(lines, index).trim().startsWith('```') && // 条件 5：遇到代码块语法时停止，交给代码块分支处理。
      !getLine(lines, index).trim().startsWith('$$') && // 条件 6：遇到块级公式语法时停止，交给公式分支处理。
      !/^\d+\.\s+/.test(getLine(lines, index).trim()) && // 条件 7：遇到有序列表语法时停止，交给有序列表分支处理。
      !/^[-*]\s+/.test(getLine(lines, index).trim()) && // 条件 8：遇到无序列表语法时停止，交给无序列表分支处理。
      !( // 条件 9：遇到表格表头加分隔行时停止，交给表格分支处理。
        getLine(lines, index).trim().includes('|') && // 表格判断子条件：当前行包含竖线。
        index + 1 < lines.length && // 表格判断子条件：下一行存在。
        isTableDivider(getLine(lines, index + 1)) // 表格判断子条件：下一行是表格分隔行。
      ) // 表格停止条件结束。
    ) { // 普通段落收集条件成立，进入循环体。
      paragraphLines.push(getLine(lines, index).trim()) // 把当前普通文本行加入段落数组，并去掉首尾空白。
      index += 1 // 处理完当前普通文本行后推进到下一行。
    } // 普通段落收集循环结束。

    html.push(`<p>${renderInline(paragraphLines.join(' '), citations)}</p>`) // 把连续普通文本用空格合并为一个段落，并做行内 Markdown 渲染。
  } // 主解析循环结束，说明所有 Markdown 行都已经处理完。

  return html.join('') // 把所有 HTML 片段拼成最终字符串，交给模板的 v-html 渲染。
} // 轻量 Markdown 渲染器结束。
</script>

<template>
  <section class="chat-workspace">
    <!-- 顶部工具栏展示当前模型，并提供模型设置入口。 -->
    <div class="chat-toolbar">
      <div class="active-session-pill">{{ activeSessionTitle }}</div>
      <div class="model-pill">
        <span>{{ modelSettings.activePreset.label }}</span>
        <strong>{{ modelSettings.modelName }}</strong>
      </div>
      <el-tooltip content="模型设置" placement="bottom">
        <el-button
          class="settings-button"
          :icon="Setting"
          circle
          @click="modelSettingsVisible = true"
        />
      </el-tooltip>
    </div>

    <div class="conversation-shell">
      <div class="messages-container">
        <article
          v-for="message in renderedMessages"
          :key="message.id"
          class="message-row"
          :class="message.role === 'user' ? 'is-user' : 'is-assistant'"
        >
          <div class="message-bubble markdown-body">
            <div
              v-if="message.id === thinkingMessageId && !message.content"
              class="thinking-indicator"
            >
              <span></span>
              <span></span>
              <span></span>
              <em>Thinking</em>
            </div>
            <div v-else v-html="message.renderedContent"></div>
          </div>
        </article>
      </div>
    </div>

    <!-- 底部输入区固定在页面底部。 -->
    <div class="composer-wrap">
      <div class="composer">
        <el-input
          v-model="inputMessage"
          class="composer-input"
          :autosize="{ minRows: 2, maxRows: 6 }"
          placeholder="给 ScholarEase 发送消息"
          resize="none"
          type="textarea"
          @keydown.enter.exact.prevent="sendMessage"
        />

        <div class="composer-actions">
          <el-button
            class="send-button"
            :disabled="!inputMessage.trim() || isStreaming"
            :icon="Promotion"
            circle
            @click="sendMessage"
          />
        </div>
      </div>
    </div>

    <ModelSettingsDialog v-model="modelSettingsVisible" />
  </section>
</template>

<style scoped>
/* 聊天页使用独立的满屏工作区，避免受 App.vue 普通页面 padding 影响。 */
.chat-workspace {
  position: relative;
  min-height: 100vh;
  overflow: hidden;
  background:
    radial-gradient(circle at 50% -18%, rgba(217, 200, 148, 0.24), transparent 34rem), #fffdf7;
  color: #1d2a23;
}

/* 顶部模型状态与设置按钮。 */
.chat-toolbar {
  position: absolute;
  z-index: 3;
  top: 18px;
  right: 24px;
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 10px;
}

.active-session-pill {
  overflow: hidden;
  max-width: 280px;
  color: #6f675b;
  font-size: 14px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 当前模型展示胶囊：左侧是供应商/预设，右侧是实际模型名。 */
.model-pill {
  display: flex;
  align-items: center;
  gap: 8px;
  max-width: 360px;
  min-height: 38px;
  padding: 0 14px;
  border: 1px solid #ded6ca;
  border-radius: 999px;
  background: rgba(247, 243, 234, 0.96);
  box-shadow: 0 10px 24px rgba(38, 57, 47, 0.12);
}

.model-pill span {
  color: #6f675b;
  font-size: 13px;
}

.model-pill strong {
  overflow: hidden;
  color: #26392f;
  font-size: 13px;
  font-weight: 700;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.settings-button {
  border-color: #ded6ca;
  background: #f7f3ea;
  color: #26392f;
}

/* 中间会话滚动区，底部留出输入框高度，防止最后一条消息被遮住。 */
.conversation-shell {
  height: 100vh;
  overflow-y: auto;
  padding: 80px 28px 220px;
  scrollbar-color: #cfc4b4 transparent;
}

/* 消息区域约束最大宽度，保证宽屏下阅读行长不会太长。 */
.messages-container {
  width: min(100%, 1040px);
  min-height: calc(100vh - 300px);
  margin-inline: auto;
}

/* 消息行负责左右对齐；用户消息靠右，助手消息靠左。 */
.message-row {
  display: flex;
  width: 100%;
  margin-bottom: 28px;
}

.message-row.is-user {
  justify-content: flex-end;
}

.message-row.is-assistant {
  justify-content: flex-start;
}

.message-bubble {
  max-width: min(760px, 78%);
  padding: 12px 16px;
  border-radius: 18px;
  line-height: 1.7;
}

.message-row.is-user .message-bubble {
  max-width: min(680px, 72%);
  border-radius: 18px 18px 6px 18px;
  background: #26392f;
  color: #fffdf7;
  font-size: 16px;
  line-height: 1.65;
}

.message-row.is-assistant .message-bubble {
  max-width: min(820px, 82%);
  background: transparent;
  color: #1d2a23;
  font-size: 16px;
}

/* Markdown 内容的基础字号，具体元素样式在下面按标签细分。 */
.markdown-body {
  font-size: 16px;
}

.message-row.is-user .markdown-body :deep(p),
.message-row.is-user .markdown-body :deep(ol),
.message-row.is-user .markdown-body :deep(ul) {
  margin: 0;
}

.message-row.is-user .markdown-body :deep(code) {
  background: rgba(255, 253, 247, 0.16);
  color: #fffdf7;
}

.thinking-indicator {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-height: 28px;
  color: #6f675b;
}

.thinking-indicator span {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #8fa08f;
  animation: thinking-bounce 1.15s ease-in-out infinite;
}

.thinking-indicator span:nth-child(2) {
  animation-delay: 0.14s;
}

.thinking-indicator span:nth-child(3) {
  animation-delay: 0.28s;
}

.thinking-indicator em {
  margin-left: 4px;
  color: #6f675b;
  font-size: 15px;
  font-style: normal;
}

@keyframes thinking-bounce {
  0%,
  80%,
  100% {
    transform: translateY(0);
    opacity: 0.42;
  }

  40% {
    transform: translateY(-5px);
    opacity: 1;
  }
}

/* Markdown 标题样式：保持层级明显，同时不做过大的英雄式标题。 */
.markdown-body :deep(h1),
.markdown-body :deep(h2),
.markdown-body :deep(h3),
.markdown-body :deep(h4),
.markdown-body :deep(h5),
.markdown-body :deep(h6) {
  margin: 1.05em 0 0.45em;
  color: #1d2a23;
  font-weight: 760;
  line-height: 1.25;
}

.markdown-body :deep(h1:first-child),
.markdown-body :deep(h2:first-child),
.markdown-body :deep(h3:first-child) {
  margin-top: 0;
}

.markdown-body :deep(h1) {
  font-size: 26px;
}

.markdown-body :deep(h2) {
  padding-bottom: 6px;
  border-bottom: 1px solid #e8e0d4;
  font-size: 20px;
}

/* Markdown 段落、列表、引用、表格、代码块的排版。 */
.markdown-body :deep(p) {
  margin: 0.8em 0;
}

.markdown-body :deep(ol),
.markdown-body :deep(ul) {
  margin: 0.7em 0 1em;
  padding-left: 1.35em;
}

.markdown-body :deep(li) {
  margin: 0.28em 0;
  padding-left: 0.2em;
}

.markdown-body :deep(blockquote) {
  margin: 1em 0;
  padding: 10px 14px;
  border-left: 4px solid #d9c894;
  border-radius: 0 8px 8px 0;
  background: #f7f3ea;
  color: #344038;
}

/* 表格外层容器负责边框和横向滚动，避免小屏幕下表格撑破页面。 */
.markdown-body :deep(.markdown-table-wrap) {
  max-width: 100%;
  margin: 1em 0;
  overflow-x: auto;
  border: 1px solid #ded6ca;
  border-radius: 8px;
}

.markdown-body :deep(table) {
  width: 100%;
  min-width: 620px;
  border-collapse: collapse;
  background: #fffdf7;
  font-size: 14px;
}

.markdown-body :deep(th),
.markdown-body :deep(td) {
  padding: 10px 12px;
  border-bottom: 1px solid #e8e0d4;
  text-align: left;
  vertical-align: top;
}

.markdown-body :deep(th) {
  background: #f7f3ea;
  color: #26392f;
  font-weight: 720;
}

.markdown-body :deep(tr:last-child td) {
  border-bottom: 0;
}

/* KaTeX 公式排版：行内公式跟随正文，块级公式居中并允许长公式横向滚动。 */
.markdown-body :deep(.katex) {
  color: #1d2a23;
  font-size: 1.05em;
}

.markdown-body :deep(.katex-display) {
  max-width: 100%;
  margin: 1em 0;
  padding: 14px 16px;
  overflow-x: auto;
  border: 1px solid #e8e0d4;
  border-radius: 8px;
  background: #fffaf0;
}

.markdown-body :deep(.katex-display > .katex) {
  white-space: normal;
}

/* 行内代码与代码块分别设计：行内代码轻底色，代码块深底色。 */
.markdown-body :deep(code) {
  padding: 2px 5px;
  border-radius: 5px;
  background: #f0eadf;
  color: #26392f;
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
  font-size: 0.92em;
}

.markdown-body :deep(.citation-ref) {
  display: inline-block;
  max-width: 360px;
  margin: 0 2px;
  padding: 1px 7px;
  overflow: hidden;
  border: 1px solid #d9c894;
  border-radius: 999px;
  background: #fffaf0;
  color: #2c4738;
  font-size: 0.9em;
  line-height: 1.55;
  text-overflow: ellipsis;
  vertical-align: baseline;
  white-space: nowrap;
}

.markdown-body :deep(pre) {
  margin: 1em 0 0;
  overflow-x: auto;
  border-radius: 8px;
  background: #1d2a23;
}

.markdown-body :deep(pre code) {
  display: block;
  padding: 14px 16px;
  background: transparent;
  color: #fffdf7;
  line-height: 1.65;
  white-space: pre;
}

/* 底部输入框区域使用渐变遮罩，让内容滚动到下方时过渡更自然。 */
.composer-wrap {
  position: absolute;
  right: 0;
  bottom: 0;
  left: 0;
  z-index: 3;
  display: flex;
  justify-content: center;
  padding: 24px 28px 28px;
  background: linear-gradient(180deg, rgba(255, 253, 247, 0), #fffdf7 26%, #fffdf7 100%);
}

.composer {
  width: min(100%, 1040px);
  min-height: 148px;
  padding: 18px;
  border: 1px solid #ded6ca;
  border-radius: 26px;
  background: #f7f3ea;
  box-shadow: 0 24px 60px rgba(38, 57, 47, 0.12);
}

.composer-input {
  margin-bottom: 16px;
}

.composer-input :deep(.el-textarea__inner) {
  min-height: 54px !important;
  border: 0;
  background: transparent;
  box-shadow: none;
  color: #1d2a23;
  font-size: 17px;
  line-height: 1.5;
}

.composer-input :deep(.el-textarea__inner::placeholder) {
  color: #9d9486;
}

.composer-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
}

.send-button {
  --el-button-bg-color: #d9c894;
  --el-button-border-color: #d9c894;
  --el-button-hover-bg-color: #eadba9;
  --el-button-hover-border-color: #eadba9;
  --el-button-active-bg-color: #cdbb84;
  --el-button-active-border-color: #cdbb84;
  --el-button-disabled-bg-color: #ded6ca;
  --el-button-disabled-border-color: #ded6ca;
  --el-button-disabled-text-color: #9d9486;
  border: 0;
  color: #26392f;
}

.send-button:hover {
  color: #26392f;
}

/* 移动端收窄边距和消息气泡宽度，保持主要内容可读。 */
@media (max-width: 860px) {
  .chat-toolbar {
    top: 18px;
    right: 16px;
    left: 16px;
    justify-content: flex-end;
  }

  .model-pill {
    max-width: calc(100% - 48px);
  }

  .conversation-shell {
    padding: 78px 18px 220px;
  }

  .message-bubble {
    max-width: 88%;
    border-radius: 20px;
  }

  .composer-wrap {
    left: 0;
    padding: 18px;
  }

  .composer {
    border-radius: 22px;
  }
}
</style>
