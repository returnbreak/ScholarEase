/**
 * 模型设置状态管理 Store（基于 Pinia）。
 *
 * 该 Store 负责管理 LLM 对话模型的所有可配置参数，包括：
 * <ul>
 *   <li><b>连接信息</b>：提供商、适配器、API 地址、密钥。</li>
 *   <li><b>模型选择</b>：模型名称及对应的最大 Token 数。</li>
 *   <li><b>生成参数</b>：temperature（温度）、topP（核采样）、maxTokens（最大输出长度）。</li>
 *   <li><b>请求控制</b>：timeoutSeconds（超时时间）、maxRetries（最大重试次数）。</li>
 * </ul>
 *
 * 采用 Composition API 风格定义 Store，通过 {@link providerPresets} 预设
 * 不同提供商的默认配置，切换提供商时自动同步 adapter、baseUrl 和模型列表。
 *
 * 使用示例：
 * ```
 * const store = useModelSettingsStore()
 * store.updateProvider('deepseek')       // 切换到 DeepSeek
 * store.updateModelName('deepseek-v4-pro') // 选择具体模型
 * ```
 */
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

/** 支持的 LLM 提供商类型标识，目前仅支持 DeepSeek */
export type ModelProvider = 'deepseek'

/** LangChain4j 中使用的流式聊天模型适配器类型，用于匹配后端 Java 模型实现 */
export type LangChain4jAdapter = 'OpenAiStreamingChatModel'

/** 单个模型的配置项 */
type ModelOption = {
  /** 模型名称（例如 'deepseek-v4-pro'），用于 API 调用时指定具体模型 */
  name: string
  /** 该模型单次请求允许生成的最大 Token 数 */
  maxTokens: number
}

/** 提供商的预设配置，包含连接信息、可用模型列表和 UI 展示文本 */
type ProviderPreset = {
  /** UI 中显示的提供商名称 */
  label: string
  /** 对应的 LangChain4j 适配器类名，后端据此路由到正确的模型实现 */
  adapter: LangChain4jAdapter
  /** API 端点基础地址 */
  baseUrl: string
  /** 默认使用的模型名称 */
  defaultModel: string
  /** 该提供商可用的模型列表及其最大 Token 数 */
  models: ModelOption[]
  /** API 密钥输入框的占位提示文本 */
  apiKeyPlaceholder: string
  /** UI 中显示的提供商描述说明 */
  description: string
}

/**
 * 所有支持的 LLM 提供商的预设配置映射。
 *
 * 每个提供商预设了以下信息：
 * - label：UI 中显示的名称
 * - adapter：LangChain4j 适配器类名（用于后端路由到正确的模型实现）
 * - baseUrl：API 端点地址
 * - defaultModel：默认使用的模型名称
 * - models：该提供商可用的模型列表及其最大 Token 数
 * - apiKeyPlaceholder：API 密钥输入框的占位提示文本
 * - description：UI 中显示的提供商描述说明
 *
 * 新增提供商只需在此对象中添加对应的条目，UI 会自动适配。
 */
export const providerPresets: Record<ModelProvider, ProviderPreset> = {
  deepseek: {
    label: 'DeepSeek',
    adapter: 'OpenAiStreamingChatModel',
    baseUrl: 'https://api.deepseek.com',
    defaultModel: 'deepseek-v4-pro',
    models: [
      { name: 'deepseek-v4-pro', maxTokens: 8192 },
      { name: 'deepseek-v4-flash', maxTokens: 4096 },
    ],
    apiKeyPlaceholder: 'sk-...',
    description: 'DeepSeek V4 OpenAI-compatible 对话配置，默认使用 deepseek-v4-pro。',
  },
}

/**
 * 模型设置的 Pinia Store（Setup Store 语法 / Composition API 风格）。
 *
 * 所有状态通过 ref 管理，派生状态通过 computed 计算，
 * 修改操作通过对外暴露的函数执行，确保状态变更的可控性和可追踪性。
 */
export const useModelSettingsStore = defineStore('modelSettings', () => {
  // ==================== 基本连接参数 ====================

  /** 当前选中的 LLM 提供商标识 */
  const provider = ref<ModelProvider>('deepseek')

  /** 当前使用的 LangChain4j 适配器类名，随提供商自动切换 */
  const adapter = ref<LangChain4jAdapter>(providerPresets.deepseek.adapter)

  /** LLM API 的基础 URL 地址 */
  const baseUrl = ref(providerPresets.deepseek.baseUrl)

  /** API 密钥，用于鉴权。运行时由用户输入，不预设默认值以避免泄露 */
  const apiKey = ref('')

  /** 当前使用的具体模型名称 */
  const modelName = ref(providerPresets.deepseek.defaultModel)

  // ==================== 生成参数 ====================

  /**
   * 温度参数（temperature），控制模型输出的随机性和创造性。
   *
   * 取值范围 [0, 2]，步长 0.1：
   * - 接近 0：输出更确定、更一致，适合代码生成、事实问答等需要精确性的场景
   * - 接近 1：平衡随机性与确定性
   * - 接近 2：输出更具发散性和创造性，适合创意写作、头脑风暴等场景
   * 默认值 0.2，偏向确定性输出。
   */
  const temperature = ref(0.2)

  /**
   * Top-P 核采样参数，控制模型生成时词汇选择的多样性。
   *
   * 取值范围 [0, 1]，步长 0.05：
   * - 1.0：考虑所有可能的词汇分布（无限制）
   * - 0.9：仅从累计概率达到 90% 的最小词汇集合中采样，过滤低概率词汇
   * - 值越小，候选词汇越少，输出越确定
   * 默认值 0.9。
   */
  const topP = ref(0.9)

  /** 单次请求允许生成的最大 Token 数，初始值跟随默认模型的配置 */
  const maxTokens = ref(8192)

  // ==================== 请求控制参数 ====================

  /** API 请求超时时间，单位秒。超过此时长未收到响应则中断请求 */
  const timeoutSeconds = ref(60)

  /** API 请求失败后的最大重试次数，0 表示不重试 */
  const maxRetries = ref(2)

  // ==================== 派生状态（Getters） ====================

  /** 根据当前选中的 provider 推导出的完整预设配置对象 */
  const activePreset = computed(() => providerPresets[provider.value])

  /** 当前提供商支持的所有模型名称列表，用于填充 UI 中的模型下拉选择框 */
  const modelOptions = computed(() => activePreset.value.models.map((model) => model.name))

  // ==================== 状态变更方法（Actions） ====================

  /**
   * 切换 LLM 提供商。
   *
   * 切换时会自动同步以下信息到新提供商的默认值：
   * - adapter（适配器类名）
   * - baseUrl（API 地址）
   * - modelName（默认模型名称，同时触发 maxTokens 的自动更新）
   *
   * @param nextProvider 目标提供商的标识
   */
  function updateProvider(nextProvider: ModelProvider) {
    provider.value = nextProvider

    const preset = providerPresets[nextProvider]
    adapter.value = preset.adapter
    baseUrl.value = preset.baseUrl
    // 切换提供商后，自动选中该提供商的默认模型
    updateModelName(preset.defaultModel)
  }

  /**
   * 切换模型名称并自动同步 maxTokens。
   *
   * 选择新模型时，会根据当前提供商的模型预设自动查找并更新
   * maxTokens 为该模型支持的最大 Token 数。
   *
   * @param nextModelName 目标模型名称
   */
  function updateModelName(nextModelName: string) {
    modelName.value = nextModelName
    maxTokens.value = modelMaxTokens(nextModelName)
  }

  /**
   * 查询指定模型名称对应的最大 Token 数。
   *
   * 在当前提供商的模型列表中查找对应模型；
   * 若未找到匹配项（例如用户手动输入了预设之外的模型名），则回退到默认值 4096。
   *
   * @param name 模型名称
   * @returns 该模型支持的最大 Token 数，未找到时返回 4096
   */
  function modelMaxTokens(name: string) {
    return activePreset.value.models.find((model) => model.name === name)?.maxTokens ?? 4096
  }

  // ==================== 导出给组件使用 ====================

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
    activePreset,
    modelOptions,
    updateProvider,
    updateModelName,
  }
})
