<script setup lang="ts">
import { computed, ref } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { ChatDotRound, Collection, Expand, Fold } from '@element-plus/icons-vue'

const route = useRoute()
const collapsed = ref(false)

const sidebarWidth = computed(() => (collapsed.value ? '72px' : '268px'))
</script>

<template>
  <aside
    class="app-sidebar"
    :class="{ 'is-collapsed': collapsed }"
    :style="{ width: sidebarWidth }"
  >
    <div class="sidebar-header">
      <div class="brand-mark">SE</div>
      <div v-show="!collapsed" class="brand-copy">
        <strong>ScholarEase</strong>
        <span>论文 RAG 工作台</span>
      </div>
      <el-tooltip :content="collapsed ? '展开侧边栏' : '收起侧边栏'" placement="right">
        <el-button
          class="collapse-button"
          :icon="collapsed ? Expand : Fold"
          circle
          @click="collapsed = !collapsed"
        />
      </el-tooltip>
    </div>

    <el-scrollbar class="sidebar-scroll">
      <section class="nav-section">
        <p v-show="!collapsed" class="section-label">聊天助手</p>
        <RouterLink class="nav-item" :class="{ active: route.name === 'chat' }" to="/">
          <el-icon><ChatDotRound /></el-icon>
          <span v-show="!collapsed">问答工作台</span>
        </RouterLink>
      </section>

      <section class="nav-section library-section">
        <p v-show="!collapsed" class="section-label">文献库</p>
        <RouterLink class="nav-item" :class="{ active: route.name === 'library' }" to="/library">
          <el-icon><Collection /></el-icon>
          <span v-show="!collapsed">文献库</span>
        </RouterLink>
        <div v-show="!collapsed" class="section-note">
          后续放置论文列表、标签、解析状态和筛选入口。
        </div>
      </section>
    </el-scrollbar>
  </aside>
</template>

<style scoped>
.app-sidebar {
  height: 100vh;
  flex: 0 0 auto;
  border-right: 1px solid var(--se-border);
  background: #f7f3ea;
  transition: width 180ms ease;
}

.sidebar-header {
  display: flex;
  align-items: center;
  gap: 12px;
  height: 72px;
  padding: 14px;
  border-bottom: 1px solid var(--se-border);
}

.brand-mark {
  display: grid;
  width: 40px;
  height: 40px;
  flex: 0 0 40px;
  place-items: center;
  border: 1px solid #26392f;
  border-radius: 8px;
  background: #26392f;
  color: #fffdf7;
  font-size: 13px;
  font-weight: 700;
}

.brand-copy {
  display: grid;
  min-width: 0;
  line-height: 1.2;
}

.brand-copy strong {
  color: #17211b;
  font-size: 15px;
  font-weight: 700;
}

.brand-copy span {
  margin-top: 4px;
  color: #6b6254;
  font-size: 12px;
}

.collapse-button {
  margin-left: auto;
}

.sidebar-scroll {
  height: calc(100vh - 72px);
}

.nav-section {
  padding: 18px 12px 8px;
}

.section-label {
  margin: 0 0 8px;
  padding: 0 8px;
  color: #817768;
  font-size: 12px;
  font-weight: 700;
}

.nav-item {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  min-height: 42px;
  padding: 0 12px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: #344038;
  font: inherit;
  text-align: left;
  text-decoration: none;
  cursor: pointer;
}

.nav-item:hover {
  background: #ece4d7;
}

.nav-item.active {
  background: #26392f;
  color: #fffdf7;
}

.nav-item .el-icon {
  flex: 0 0 auto;
  font-size: 18px;
}

.section-note {
  margin: 10px 8px 0;
  color: #817768;
  font-size: 12px;
  line-height: 1.55;
}

.is-collapsed .sidebar-header {
  justify-content: center;
  padding: 14px 10px;
}

.is-collapsed .collapse-button {
  position: absolute;
  top: 52px;
  left: 48px;
  width: 28px;
  height: 28px;
}

.is-collapsed .nav-section {
  padding-inline: 10px;
}

.is-collapsed .nav-item {
  justify-content: center;
  padding: 0;
}
</style>
