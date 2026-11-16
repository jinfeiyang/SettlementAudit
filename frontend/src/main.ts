import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus, { ElIcon } from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'

import App from './App.vue'
import router from './router'

const app = createApp(App)
app.use(createPinia())
app.use(router)
app.use(ElementPlus, { locale: zhCn })
app.component('ElIcon', ElIcon)

app.mount('#app')
