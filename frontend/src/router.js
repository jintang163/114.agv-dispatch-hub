import { createRouter, createWebHashHistory } from 'vue-router'
import Dashboard from './views/Dashboard.vue'
import Tasks from './views/Tasks.vue'
import Robots from './views/Robots.vue'
import MapView from './views/MapView.vue'

export default createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/', redirect: '/dashboard' },
    { path: '/dashboard', component: Dashboard, name: '调度看板' },
    { path: '/tasks', component: Tasks, name: '任务管理' },
    { path: '/robots', component: Robots, name: '机器人' },
    { path: '/map', component: MapView, name: '实时地图' }
  ]
})
