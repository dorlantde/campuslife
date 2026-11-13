import { createApp } from 'vue'
import { ElAlert, ElButton, ElConfigProvider, ElDialog, ElEmpty, ElInput, ElOption, ElPagination, ElSelect, ElSkeleton } from 'element-plus'
import 'element-plus/dist/index.css'
import './style.css'
import './merchant/management.css'
import './orders/orders.css'
import './admin/admin.css'
import App from './App.vue'
import { router } from './router'
const app = createApp(App).use(router)
for (const component of [ElAlert, ElButton, ElConfigProvider, ElDialog, ElEmpty, ElInput, ElOption, ElPagination, ElSelect, ElSkeleton]) app.use(component)
app.mount('#app')
