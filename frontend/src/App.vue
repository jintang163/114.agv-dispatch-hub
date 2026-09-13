<template>
  <div class="layout">
    <aside class="sidebar">
      <div class="brand">
        AGV 调度中心
        <small>仓储多机器人任务调度</small>
      </div>
      <nav>
        <router-link to="/dashboard">▣ 调度看板</router-link>
        <router-link to="/tasks">▤ 任务管理</router-link>
        <router-link to="/robots">⚙ 机器人</router-link>
        <router-link to="/map">◎ 实时地图</router-link>
      </nav>
      <div class="conn">
        <span class="dot" :class="realtime.connected ? 'on' : 'off'"></span>
        {{ realtime.connected ? 'MQTT 实时连接' : 'MQTT 未连接' }}
      </div>
    </aside>
    <main class="main">
      <router-view />
    </main>
    <div class="toast-wrap">
      <div v-for="t in toasts.items" :key="t.id" class="toast" :class="{ err: t.isError }">
        {{ t.message }}
      </div>
    </div>
  </div>
</template>

<script setup>
import { onMounted } from 'vue'
import { connectMqtt, realtime } from './mqtt'
import { toasts } from './toast'

onMounted(connectMqtt)
</script>
