import 'element-plus/dist/index.css'
import './assets/main.css'

import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'

import App from './App.vue'
import router from './router'

// 创建 Vue 根应用实例，App.vue 是整个单页应用的布局入口。
const app = createApp(App)

// Pinia 用来承载全局状态；当前主要用于模型设置 store。
app.use(createPinia())

// 注册 Vue Router，让不同 URL 映射到不同页面视图。
app.use(router)

// 注册 Element Plus，项目里的 el-button、el-input、el-container 等组件都来自这里。
app.use(ElementPlus)

// 把 Vue 应用挂载到 index.html 中的 #app 节点。
app.mount('#app')
