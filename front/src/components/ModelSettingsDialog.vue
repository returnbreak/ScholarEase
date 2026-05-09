<script setup lang="ts">
import { computed } from 'vue'
import {
  providerPresets,
  useModelSettingsStore,
  type LangChain4jAdapter,
  type ModelProvider,
} from '@/stores/modelSettings'

const visible = defineModel<boolean>({ required: true })
const modelSettings = useModelSettingsStore()

const providerOptions = Object.entries(providerPresets).map(([value, preset]) => ({
  value: value as ModelProvider,
  label: preset.label,
  description: preset.description,
}))

const adapterOptions: LangChain4jAdapter[] = [
  'OpenAiChatModel',
  'OpenAiStreamingChatModel',
  'AnthropicChatModel',
]

const activeProvider = computed({
  get: () => modelSettings.provider,
  set: (value) => modelSettings.updateProvider(value),
})

const streamResponse = computed({
  get: () => modelSettings.streamResponse,
  set: (value) => modelSettings.updateStreaming(value),
})
</script>

<template>
  <el-dialog v-model="visible" title="模型设置" width="760px" align-center>
    <el-form class="model-form" label-position="top">
      <div class="form-grid">
        <el-form-item label="provider">
          <el-select v-model="activeProvider" filterable>
            <el-option
              v-for="option in providerOptions"
              :key="option.value"
              :label="option.label"
              :value="option.value"
            />
          </el-select>
        </el-form-item>

        <el-form-item label="LangChain4j adapter">
          <el-select v-model="modelSettings.adapter">
            <el-option
              v-for="option in adapterOptions"
              :key="option"
              :label="option"
              :value="option"
            />
          </el-select>
        </el-form-item>
      </div>

      <el-form-item label="baseUrl">
        <el-input v-model="modelSettings.baseUrl" placeholder="https://api.example.com/v1">
          <template #prepend>Base API</template>
        </el-input>
      </el-form-item>

      <div class="form-grid">
        <el-form-item label="apiKey">
          <el-input
            v-model="modelSettings.apiKey"
            :placeholder="modelSettings.activePreset.apiKeyPlaceholder"
            show-password
            type="password"
          />
        </el-form-item>

        <el-form-item label="modelName">
          <el-select
            v-model="modelSettings.modelName"
            allow-create
            default-first-option
            filterable
            placeholder="选择或输入模型名称"
          >
            <el-option
              v-for="model in modelSettings.modelOptions"
              :key="model"
              :label="model"
              :value="model"
            />
          </el-select>
        </el-form-item>
      </div>

      <div class="form-grid form-grid-three">
        <el-form-item label="temperature">
          <el-slider v-model="modelSettings.temperature" :max="2" :min="0" :step="0.1" show-input />
        </el-form-item>

        <el-form-item label="topP">
          <el-slider v-model="modelSettings.topP" :max="1" :min="0" :step="0.05" show-input />
        </el-form-item>

        <el-form-item label="maxTokens">
          <el-input-number
            v-model="modelSettings.maxTokens"
            :min="1"
            :step="512"
            controls-position="right"
          />
        </el-form-item>
      </div>

      <div class="form-grid form-grid-three">
        <el-form-item label="timeout">
          <el-input-number
            v-model="modelSettings.timeoutSeconds"
            :min="1"
            :step="10"
            controls-position="right"
          >
            <template #suffix>秒</template>
          </el-input-number>
        </el-form-item>

        <el-form-item label="maxRetries">
          <el-input-number
            v-model="modelSettings.maxRetries"
            :min="0"
            :step="1"
            controls-position="right"
          />
        </el-form-item>

        <el-form-item label="streaming">
          <el-switch
            v-model="streamResponse"
            active-text="OpenAiStreamingChatModel"
            inactive-text="普通 ChatModel"
          />
        </el-form-item>
      </div>

      <el-collapse class="advanced-collapse">
        <el-collapse-item title="高级 LangChain4j 参数" name="advanced">
          <div class="form-grid">
            <el-form-item label="organizationId">
              <el-input v-model="modelSettings.organizationId" placeholder="OpenAI 可选" />
            </el-form-item>

            <el-form-item label="projectId">
              <el-input v-model="modelSettings.projectId" placeholder="OpenAI 可选" />
            </el-form-item>
          </div>

          <el-form-item label="stop">
            <el-input
              v-model="modelSettings.stopSequences"
              placeholder="多个停止词用英文逗号分隔"
            />
          </el-form-item>

          <el-form-item label="customHeaders">
            <el-input
              v-model="modelSettings.customHeaders"
              :rows="3"
              placeholder='{"X-Request-Source":"ScholarEase"}'
              type="textarea"
            />
          </el-form-item>

          <div class="switch-row">
            <el-checkbox v-model="modelSettings.logRequests">logRequests</el-checkbox>
            <el-checkbox v-model="modelSettings.logResponses">logResponses</el-checkbox>
          </div>
        </el-collapse-item>
      </el-collapse>
    </el-form>

    <div class="provider-hint">
      <strong>{{ modelSettings.activePreset.label }}</strong>
      <span>{{ modelSettings.activePreset.description }}</span>
    </div>

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" @click="visible = false">保存设置</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.model-form {
  display: grid;
  gap: 4px;
}

.form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
}

.form-grid-three {
  grid-template-columns: 1.35fr 1.15fr 0.9fr;
}

.advanced-collapse {
  margin-top: 2px;
  border-top: 1px solid #ebe3d7;
  border-bottom: 1px solid #ebe3d7;
}

.switch-row {
  display: flex;
  flex-wrap: wrap;
  gap: 18px;
}

.provider-hint {
  display: grid;
  gap: 4px;
  margin-top: 12px;
  padding: 12px 14px;
  border: 1px solid #dfd6c9;
  border-radius: 8px;
  background: #fbf7ee;
  color: #675f52;
  font-size: 13px;
}

.provider-hint strong {
  color: #26392f;
  font-weight: 700;
}

.provider-hint span {
  color: #675f52;
}

@media (max-width: 760px) {
  .form-grid,
  .form-grid-three {
    grid-template-columns: 1fr;
  }
}
</style>
