<script setup lang="ts">
/**
 * 模型设置对话框组件。
 *
 * 提供一个可视化的配置面板，允许用户调整当前使用的 LLM 模型参数，
 * 包括提供商选择、API 地址、密钥、模型名称、温度、Top-P、最大 Token 数、
 * 超时时间和最大重试次数。
 *
 * 该组件通过 v-model 控制显示/隐藏，内部使用 Pinia Store（modelSettings）
 * 来持久化用户的配置选择。
 */
import { computed } from 'vue'
import {
  providerPresets,
  useModelSettingsStore,
  type ModelProvider,
} from '@/stores/modelSettings'

/** 控制对话框可见性的双向绑定 */
const visible = defineModel<boolean>({ required: true })

/** 模型设置的 Pinia Store 实例 */
const modelSettings = useModelSettingsStore()

/**
 * 将预设的提供商配置转换为下拉选项格式。
 * 每个选项包含 value（提供商标识）、label（显示名称）、description（描述信息）。
 */
const providerOptions = Object.entries(providerPresets).map(([value, preset]) => ({
  value: value as ModelProvider,
  label: preset.label,
  description: preset.description,
}))

/** 当前所选提供商的 computed 读写属性，变更时自动同步到 Store */
const activeProvider = computed({
  get: () => modelSettings.provider,
  set: (value) => modelSettings.updateProvider(value),
})

/** 当前所选模型名称的 computed 读写属性，变更时自动同步到 Store 并更新 maxTokens */
const activeModelName = computed({
  get: () => modelSettings.modelName,
  set: (value) => modelSettings.updateModelName(value),
})
</script>

<template>
  <!-- Element Plus 对话框，标题为"模型设置"，宽度 760px，居中显示 -->
  <el-dialog v-model="visible" title="模型设置" width="760px" align-center>
    <el-form class="model-form" label-position="top">
      <!-- 第一行：提供商选择 + 适配器名称（双列布局） -->
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

        <!-- LangChain4j 适配器为只读字段，随提供商自动切换 -->
        <el-form-item label="LangChain4j adapter">
          <el-input v-model="modelSettings.adapter" disabled />
        </el-form-item>
      </div>

      <!-- API 基础地址输入 -->
      <el-form-item label="baseUrl">
        <el-input v-model="modelSettings.baseUrl" placeholder="https://api.example.com/v1">
          <template #prepend>Base API</template>
        </el-input>
      </el-form-item>

      <!-- 第二行：API 密钥 + 模型名称（双列布局） -->
      <div class="form-grid">
        <!-- API 密钥输入，密码模式显示，placeholder 根据当前提供商动态变化 -->
        <el-form-item label="apiKey">
          <el-input
            v-model="modelSettings.apiKey"
            :placeholder="modelSettings.activePreset.apiKeyPlaceholder"
            show-password
            type="password"
          />
        </el-form-item>

        <!-- 模型名称：下拉选择，支持手动输入自定义模型名 -->
        <el-form-item label="modelName">
          <el-select
            v-model="activeModelName"
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

      <!-- 第三行：temperature + topP + maxTokens（三列布局） -->
      <div class="form-grid form-grid-three">
        <!-- 温度参数：控制输出的随机性，0~2，步长 0.1 -->
        <el-form-item label="temperature">
          <el-slider v-model="modelSettings.temperature" :max="2" :min="0" :step="0.1" show-input />
        </el-form-item>

        <!-- Top-P 采样参数：核采样阈值，0~1，步长 0.05 -->
        <el-form-item label="topP">
          <el-slider v-model="modelSettings.topP" :max="1" :min="0" :step="0.05" show-input />
        </el-form-item>

        <!-- 最大 Token 数：步长 512，带增减按钮 -->
        <el-form-item label="maxTokens">
          <el-input-number
            v-model="modelSettings.maxTokens"
            :min="1"
            :step="512"
            controls-position="right"
          />
        </el-form-item>
      </div>

      <!-- 第四行：超时时间 + 最大重试次数（双列布局） -->
      <div class="form-grid">
        <!-- 超时时间，单位秒 -->
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

        <!-- 最大重试次数 -->
        <el-form-item label="maxRetries">
          <el-input-number
            v-model="modelSettings.maxRetries"
            :min="0"
            :step="1"
            controls-position="right"
          />
        </el-form-item>
      </div>
    </el-form>

    <!-- 底部提示：显示当前提供商的名称和描述 -->
    <div class="provider-hint">
      <strong>{{ modelSettings.activePreset.label }}</strong>
      <span>{{ modelSettings.activePreset.description }}</span>
    </div>

    <!-- 对话框底部按钮 -->
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" @click="visible = false">保存设置</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
/* 表单整体布局：纵向排列，间距 4px */
.model-form {
  display: grid;
  gap: 4px;
}

/* 双列网格布局，用于 provider/adapter、apiKey/modelName、timeout/maxRetries */
.form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
}

/* 三列网格布局，用于 temperature/topP/maxTokens，比例约为 1.35:1.15:0.9 */
.form-grid-three {
  grid-template-columns: 1.35fr 1.15fr 0.9fr;
}

/* 提供商提示信息块的样式：米色背景、圆角边框 */
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

/* 提供商名称加粗深色显示 */
.provider-hint strong {
  color: #26392f;
  font-weight: 700;
}

.provider-hint span {
  color: #675f52;
}

/* 小屏响应式：双列和三列布局退化为单列 */
@media (max-width: 760px) {
  .form-grid,
  .form-grid-three {
    grid-template-columns: 1fr;
  }
}
</style>
