import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

export type QaModelConfig = {
  baseUrl: string
  apiKey: string
  modelName: string
  temperature: number
  topP: number
  maxTokens: number
  timeoutSeconds: number
}

type ModelOption = {
  name: string
  maxTokens: number
}

const modelOptions: ModelOption[] = [
  { name: 'deepseek-v4-pro', maxTokens: 8192 },
  { name: 'deepseek-v4-flash', maxTokens: 4096 },
]

export const useModelSettingsStore = defineStore('modelSettings', () => {
  const baseUrl = ref('https://api.deepseek.com')
  const apiKey = ref('')
  const modelName = ref('deepseek-v4-pro')
  const temperature = ref(0.2)
  const topP = ref(0.9)
  const maxTokens = ref(8192)
  const timeoutSeconds = ref(60)

  const providerLabel = 'DeepSeek'
  const description = 'DeepSeek OpenAI-compatible streaming chat configuration.'
  const modelNames = computed(() => modelOptions.map((model) => model.name))

  function updateModelName(nextModelName: string) {
    modelName.value = nextModelName
    maxTokens.value = modelOptions.find((model) => model.name === nextModelName)?.maxTokens ?? 4096
  }

  function toRequestConfig(): QaModelConfig {
    return {
      baseUrl: baseUrl.value,
      apiKey: apiKey.value,
      modelName: modelName.value,
      temperature: temperature.value,
      topP: topP.value,
      maxTokens: maxTokens.value,
      timeoutSeconds: timeoutSeconds.value,
    }
  }

  return {
    apiKey,
    baseUrl,
    description,
    maxTokens,
    modelName,
    modelNames,
    providerLabel,
    temperature,
    timeoutSeconds,
    topP,
    toRequestConfig,
    updateModelName,
  }
})
