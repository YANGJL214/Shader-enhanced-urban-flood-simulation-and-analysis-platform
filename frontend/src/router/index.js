import { createRouter, createWebHistory } from 'vue-router'
import UrbanFlood from '@/views/warningModel/UrbanFlood/index.vue'

export default createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/',
      name: 'UrbanFlood',
      component: UrbanFlood,
      meta: { publicEntry: true }
    }
  ]
})
