import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus, { ElMessage, ElMessageBox } from 'element-plus'
import 'element-plus/dist/index.css'
import 'vue-virtual-scroller/dist/vue-virtual-scroller.css'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'
import mitt from 'mitt'

import App from './App.vue'
import router from './router'
import API from './services/api'
import { t } from './i18n'
import './styles.css'

const app = createApp(App)
const bus = mitt()

Object.entries(ElementPlusIconsVue).forEach(([name, component]) => {
  app.component(name, component)
})

app.config.globalProperties.API = API
app.config.globalProperties.bus = bus
app.config.globalProperties.MxMsg = ElMessage
app.config.globalProperties.MxMsgBox = ElMessageBox
app.config.globalProperties.$t = t

app.use(createPinia())
app.use(router)
app.use(ElementPlus)
app.mount('#app')
