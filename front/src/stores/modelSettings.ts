import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

export type ModelProvider =
  | 'deepseek'
  | 'qwen'
  | 'openai'
  | 'anthropic'
  | 'minimax'
  | 'siliconflow'
  | 'local'

export type LangChain4jAdapter =
  | 'OpenAiChatModel'
  | 'OpenAiStreamingChatModel'
  | 'AnthropicChatModel'

type ProviderPreset = {
  label: string
  adapter: LangChain4jAdapter
  baseUrl: string
  defaultModel: string
  models: string[]
  apiKeyPlaceholder: string
  description: string
}

export const providerPresets: Record<ModelProvider, ProviderPreset> = {
  deepseek: {
    label: 'DeepSeek',
    adapter: 'OpenAiChatModel',
    baseUrl: 'https://api.deepseek.com/v1',
    defaultModel: 'deepseek-chat',
    models: ['deepseek-chat', 'deepseek-reasoner'],
    apiKeyPlaceholder: 'sk-...',
    description: 'OpenAI-compatible 接入，适合当前 MVP 的中文问答与推理任务。',
  },
  qwen: {
    label: 'Qwen',
    adapter: 'OpenAiChatModel',
    baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1',
    defaultModel: 'qwen-plus',
    models: ['qwen-plus', 'qwen-max', 'qwen-turbo', 'qwen-long'],
    apiKeyPlaceholder: 'sk-...',
    description: '通过 DashScope OpenAI-compatible 接口接入，适合中文科研问答。',
  },
  openai: {
    label: 'OpenAI',
    adapter: 'OpenAiChatModel',
    baseUrl: 'https://api.openai.com/v1',
    defaultModel: 'gpt-4.1-mini',
    models: ['gpt-4.1-mini', 'gpt-4.1', 'gpt-4o-mini', 'gpt-4o'],
    apiKeyPlaceholder: 'sk-...',
    description: '标准 OpenAI ChatModel 配置，可用于通用问答和摘要任务。',
  },
  anthropic: {
    label: 'Anthropic',
    adapter: 'AnthropicChatModel',
    baseUrl: 'https://api.anthropic.com',
    defaultModel: 'claude-sonnet-4-5',
    models: ['claude-sonnet-4-5', 'claude-opus-4-1', 'claude-3-5-haiku-latest'],
    apiKeyPlaceholder: 'sk-ant-...',
    description: 'AnthropicChatModel 接入，适合长上下文阅读、归纳和审阅。',
  },
  minimax: {
    label: 'MiniMax',
    adapter: 'OpenAiChatModel',
    baseUrl: 'https://api.minimaxi.com/v1',
    defaultModel: 'MiniMax-M2.7',
    models: ['MiniMax-M2.7', 'MiniMax-Text-01'],
    apiKeyPlaceholder: 'sk-...',
    description: 'OpenAI-compatible 接入，保留 Base URL 可按供应商控制台调整。',
  },
  siliconflow: {
    label: 'SiliconFlow',
    adapter: 'OpenAiChatModel',
    baseUrl: 'https://api.siliconflow.cn/v1',
    defaultModel: 'Qwen/Qwen2.5-72B-Instruct',
    models: ['Qwen/Qwen2.5-72B-Instruct', 'deepseek-ai/DeepSeek-V3', 'deepseek-ai/DeepSeek-R1'],
    apiKeyPlaceholder: 'sk-...',
    description: 'OpenAI-compatible 聚合平台，方便切换国产开源模型。',
  },
  local: {
    label: '本地模型',
    adapter: 'OpenAiChatModel',
    baseUrl: 'http://localhost:8000/v1',
    defaultModel: 'local-rag-model',
    models: ['local-rag-model', 'qwen-local', 'bge-reranker-service'],
    apiKeyPlaceholder: 'none',
    description: '本地 OpenAI-compatible 服务；无鉴权时 apiKey 可填 none。',
  },
}

export const useModelSettingsStore = defineStore('modelSettings', () => {
  const provider = ref<ModelProvider>('deepseek')
  const adapter = ref<LangChain4jAdapter>(providerPresets.deepseek.adapter)
  const baseUrl = ref(providerPresets.deepseek.baseUrl)
  const apiKey = ref('')
  const modelName = ref('deepseek-chat')
  const temperature = ref(0.2)
  const topP = ref(0.9)
  const maxTokens = ref(4096)
  const timeoutSeconds = ref(60)
  const maxRetries = ref(2)
  const organizationId = ref('')
  const projectId = ref('')
  const stopSequences = ref('')
  const customHeaders = ref('')
  const streamResponse = ref(true)
  const logRequests = ref(false)
  const logResponses = ref(false)

  const activePreset = computed(() => providerPresets[provider.value])
  const modelOptions = computed(() => activePreset.value.models)

  function updateProvider(nextProvider: ModelProvider) {
    provider.value = nextProvider

    const preset = providerPresets[nextProvider]
    adapter.value =
      streamResponse.value && preset.adapter === 'OpenAiChatModel'
        ? 'OpenAiStreamingChatModel'
        : preset.adapter
    baseUrl.value = preset.baseUrl
    modelName.value = preset.defaultModel
    organizationId.value = ''
    projectId.value = ''
  }

  function updateStreaming(nextValue: boolean) {
    streamResponse.value = nextValue
    adapter.value =
      nextValue && activePreset.value.adapter === 'OpenAiChatModel'
        ? 'OpenAiStreamingChatModel'
        : activePreset.value.adapter
  }

  return {
    provider,
    adapter,
    baseUrl,
    apiKey,
    modelName,
    temperature,
    topP,
    maxTokens,
    timeoutSeconds,
    maxRetries,
    organizationId,
    projectId,
    stopSequences,
    customHeaders,
    streamResponse,
    logRequests,
    logResponses,
    activePreset,
    modelOptions,
    updateProvider,
    updateStreaming,
  }
})
