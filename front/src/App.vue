<script setup lang="ts">
import { computed } from 'vue'
import { RouterView, useRoute } from 'vue-router'
import AppSidebar from '@/components/AppSidebar.vue'

const route = useRoute()

// 当前页面标题来自路由 meta，普通页面会显示在顶部栏。
const pageTitle = computed(() => route.meta.title ?? 'ScholarEase')

// 聊天页需要更沉浸的满屏布局，因此用路由名判断后隐藏顶部栏并清掉主区域 padding。
const isChatRoute = computed(() => route.name === 'chat')
</script>

<template>
  <!-- Element Plus 的外层容器：左侧固定应用导航，右侧是当前页面内容。 -->
  <el-container class="app-shell">
    <AppSidebar />

    <el-container class="content-shell">
      <!-- 聊天页自身已经有模型工具栏和输入框，所以这里不重复显示普通页面顶部栏。 -->
      <el-header v-if="!isChatRoute" class="topbar">
        <div>
          <span class="topbar-kicker">ScholarEase</span>
          <h1>{{ pageTitle }}</h1>
        </div>
      </el-header>

      <!-- RouterView 会渲染 router/index.ts 中当前路由对应的页面组件。 -->
      <el-main class="main-panel" :class="{ 'chat-main-panel': isChatRoute }">
        <RouterView v-slot="{ Component }">
          <KeepAlive include="ChatAssistantView">
            <component :is="Component" />
          </KeepAlive>
        </RouterView>
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
/* 应用根布局铺满视口，背景色沿用全局 ScholarEase 主题变量。 */
.app-shell {
  min-height: 100vh;
  background: var(--se-page);
}

.content-shell {
  min-width: 0;
}

/* 顶部栏只用于非聊天页面，显示当前路由标题。 */
.topbar {
  display: flex;
  align-items: center;
  height: 72px;
  border-bottom: 1px solid var(--se-border);
  background: rgba(255, 253, 247, 0.9);
  backdrop-filter: blur(12px);
}

.topbar-kicker {
  color: #7d7468;
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0;
}

.topbar h1 {
  margin: 2px 0 0;
  color: #1d2a23;
  font-size: 20px;
  font-weight: 700;
  line-height: 1.2;
}

/* 普通页面保留统一内边距；聊天页由 ChatAssistantView 自己控制满屏布局。 */
.main-panel {
  min-height: calc(100vh - 72px);
  padding: 28px;
}

.chat-main-panel {
  min-height: 100vh;
  padding: 0;
  background: #fffdf7;
}

@media (max-width: 720px) {
  .main-panel {
    padding: 18px;
  }

  .chat-main-panel {
    padding: 0;
  }
}
</style>
