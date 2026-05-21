<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { storeToRefs } from 'pinia'
import {
  ArrowDown,
  ArrowRight,
  ChatDotRound,
  Collection,
  Delete,
  Expand,
  Fold,
  FolderOpened,
  Plus,
  Star,
} from '@element-plus/icons-vue'
import { useChatSessionsStore } from '@/stores/chatSessions'

const route = useRoute()
const router = useRouter()
const chatSessions = useChatSessionsStore()
const { currentSessionId, isLoaded, sessions } = storeToRefs(chatSessions)
const collapsed = ref(false)
const historyExpanded = ref(true)

interface SessionSwitchDetail {
  previousSessionId: string
  nextSessionId: string
}

const sidebarWidth = computed(() => (collapsed.value ? '72px' : '268px'))

function notifySessionSwitch(previousSessionId: string, nextSessionId: string) {
  if (previousSessionId === nextSessionId) {
    return
  }
  window.dispatchEvent(
    new CustomEvent<SessionSwitchDetail>('scholarease:qa-session-switched', {
      detail: { previousSessionId, nextSessionId },
    }),
  )
}

onMounted(() => {
  void chatSessions.loadSessions()
})

async function createChatSession() {
  const previousSessionId = currentSessionId.value
  const nextSessionId = await chatSessions.createSession()
  notifySessionSwitch(previousSessionId, nextSessionId)
  router.push({ name: 'chat' })
}

async function switchChatSession(sessionId: string) {
  const previousSessionId = currentSessionId.value
  if (sessionId !== previousSessionId) {
    await chatSessions.switchSession(sessionId)
    notifySessionSwitch(previousSessionId, sessionId)
  }
  router.push({ name: 'chat' })
}

async function deleteChatSession(sessionId: string) {
  const previousSessionId = currentSessionId.value
  const wasActiveSession = await chatSessions.deleteSession(sessionId)
  if (wasActiveSession) {
    notifySessionSwitch(previousSessionId, currentSessionId.value)
    router.push({ name: 'chat' })
  }
}
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

        <div v-show="!collapsed" class="chat-history">
          <div class="chat-history-header">
            <button
              class="history-toggle"
              type="button"
              @click="historyExpanded = !historyExpanded"
            >
              <el-icon>
                <ArrowDown v-if="historyExpanded" />
                <ArrowRight v-else />
              </el-icon>
              <span>对话历史</span>
            </button>
            <el-tooltip content="新建对话" placement="right">
              <el-button
                class="history-new-button"
                :icon="Plus"
                circle
                :disabled="!isLoaded"
                text
                @click="createChatSession"
              />
            </el-tooltip>
          </div>

          <div v-show="historyExpanded" class="history-list">
            <div
              v-for="session in sessions"
              :key="session.id"
              class="history-item"
              :class="{ active: session.id === currentSessionId }"
            >
              <button
                class="history-select-button"
                type="button"
                @click="switchChatSession(session.id)"
              >
                <span>{{ session.title }}</span>
              </button>
              <el-popconfirm
                title="删除这个对话？"
                confirm-button-text="删除"
                cancel-button-text="取消"
                width="180"
                @confirm="deleteChatSession(session.id)"
              >
                <template #reference>
                  <button
                    class="history-delete-button"
                    type="button"
                    title="删除对话"
                    @click.stop
                  >
                    <el-icon><Delete /></el-icon>
                  </button>
                </template>
              </el-popconfirm>
            </div>
          </div>
        </div>
      </section>

      <section class="nav-section library-section">
        <p v-show="!collapsed" class="section-label">文献库</p>
        <RouterLink
          class="nav-item"
          :class="{ active: route.name === 'library' }"
          to="/library"
        >
          <el-icon><Collection /></el-icon>
          <span v-show="!collapsed">文献库</span>
        </RouterLink>
        <RouterLink
          class="nav-item nav-subitem"
          :class="{ active: route.name === 'library-folders' }"
          :to="{ name: 'library-folders' }"
        >
          <el-icon><FolderOpened /></el-icon>
          <span v-show="!collapsed">文件夹管理</span>
        </RouterLink>
        <RouterLink
          class="nav-item nav-subitem"
          :class="{ active: route.name === 'library-favorites' }"
          :to="{ name: 'library-favorites' }"
        >
          <el-icon><Star /></el-icon>
          <span v-show="!collapsed">我的收藏</span>
        </RouterLink>
        <RouterLink
          class="nav-item nav-subitem"
          :class="{ active: route.name === 'library-trash' }"
          :to="{ name: 'library-trash' }"
        >
          <el-icon><Delete /></el-icon>
          <span v-show="!collapsed">回收站</span>
        </RouterLink>
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

.chat-history {
  margin-top: 10px;
  padding: 8px 0 0;
}

.chat-history-header {
  display: flex;
  align-items: center;
  gap: 6px;
}

.history-toggle {
  display: flex;
  min-width: 0;
  flex: 1;
  align-items: center;
  gap: 6px;
  height: 30px;
  padding: 0 8px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: #6b6254;
  cursor: pointer;
  font: inherit;
  font-size: 12px;
  font-weight: 700;
  text-align: left;
}

.history-toggle:hover,
.history-new-button:hover {
  background: #ece4d7;
}

.history-toggle .el-icon {
  flex: 0 0 auto;
  font-size: 14px;
}

.history-new-button {
  width: 30px;
  height: 30px;
  color: #2c4738;
}

.history-list {
  display: grid;
  gap: 4px;
  margin-top: 6px;
}

.history-item {
  display: flex;
  align-items: center;
  gap: 6px;
  width: 100%;
  min-height: 34px;
  padding-right: 6px;
  border-radius: 8px;
  background: transparent;
}

.history-select-button {
  display: block;
  min-width: 0;
  flex: 1;
  padding: 8px 4px 8px 28px;
  border: 0;
  background: transparent;
  color: #514b43;
  cursor: pointer;
  font: inherit;
  font-size: 13px;
  text-align: left;
}

.history-select-button span {
  display: block;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.history-delete-button {
  display: inline-grid;
  width: 24px;
  height: 24px;
  flex: 0 0 24px;
  place-items: center;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: #897f71;
  cursor: pointer;
  opacity: 0;
  transition:
    background 140ms ease,
    color 140ms ease,
    opacity 140ms ease;
}

.history-item:hover .history-delete-button,
.history-delete-button:focus-visible {
  opacity: 1;
}

.history-delete-button:hover {
  background: rgba(129, 47, 47, 0.12);
  color: #7f2f2f;
}

.history-item.active .history-delete-button {
  color: #405648;
}

.history-item:hover {
  background: #ece4d7;
}

.history-item:hover .history-select-button {
  color: #26392f;
}

.history-item.active {
  background: rgba(38, 57, 47, 0.12);
}

.history-item.active .history-select-button {
  color: #26392f;
  font-weight: 700;
}

.nav-subitem {
  margin-top: 6px;
  color: #526056;
}

.nav-subitem .el-icon {
  font-size: 17px;
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
