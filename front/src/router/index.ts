import { createRouter, createWebHistory } from 'vue-router'
import ChatAssistantView from '@/views/ChatAssistantView.vue'
import LibraryView from '@/views/LibraryView.vue'

// 路由表定义“地址 -> 页面组件”的对应关系。
// App.vue 会通过 <RouterView /> 渲染当前匹配到的页面。
const router = createRouter({
  // 使用浏览器 history 模式，URL 更干净；BASE_URL 由 Vite 注入，便于以后部署到子路径。
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      // 默认首页就是科研问答工作台，也是这次 Markdown 示例渲染所在的页面。
      path: '/',
      name: 'chat',
      component: ChatAssistantView,
      meta: {
        // title 会被 App.vue 读取，用于普通页面顶部标题；聊天页会隐藏顶部栏。
        title: '科研问答工作台',
      },
    },
    {
      // 文献库页面保留为独立路由，后续承载论文列表、上传和管理能力。
      path: '/library',
      name: 'library',
      component: LibraryView,
      meta: {
        title: '文献库',
      },
    },
  ],
})

export default router
