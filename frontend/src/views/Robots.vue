<template>
  <div>
    <h2 class="page-title">机器人管理</h2>
    <p class="page-sub">AGV 状态监控 · 心跳与故障检测 · 故障注入 / 恢复演练</p>

    <div class="grid grid-3 mb16">
      <div class="card kpi">
        <div class="label">在线空闲</div>
        <div class="value" style="color:var(--status-good)">{{ count('IDLE') }}</div>
      </div>
      <div class="card kpi">
        <div class="label">执行任务中</div>
        <div class="value" style="color:var(--series-1)">{{ count('BUSY') }}</div>
      </div>
      <div class="card kpi">
        <div class="label">故障 / 离线</div>
        <div class="value" style="color:var(--status-critical)">{{ count('FAULT') + count('OFFLINE') }}</div>
      </div>
    </div>

    <div class="card">
      <div class="toolbar">
        <span class="muted">共 {{ robots.length }} 台 AGV，状态每 3 秒刷新，位置由 MQTT 实时推送</span>
        <span class="spacer"></span>
        <button class="btn" @click="load">刷新</button>
      </div>
      <table>
        <thead>
          <tr>
            <th>编号</th><th>名称</th><th>状态</th><th>实时位置</th><th>当前任务</th>
            <th>阶段</th><th>载货</th><th>电量</th><th>最近心跳</th><th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="r in robots" :key="r.id">
            <td class="mono"><strong>{{ r.code }}</strong></td>
            <td>{{ r.name }}</td>
            <td><span class="tag" :class="liveOf(r.code)?.status || r.status">
              {{ ROBOT_STATUS[liveOf(r.code)?.status || r.status] }}
            </span></td>
            <td class="mono">
              {{ posText(liveOf(r.code), r.currentNode) }}
            </td>
            <td>
              <router-link v-if="liveOf(r.code)?.taskId || r.currentTaskId" to="/tasks">
                #{{ liveOf(r.code)?.taskId || r.currentTaskId }}
              </router-link>
              <span v-else class="muted">—</span>
            </td>
            <td class="muted">{{ PHASE[liveOf(r.code)?.phase] || '—' }}</td>
            <td>
              <span v-if="liveOf(r.code)?.loaded" class="tag HIGH">载货</span>
              <span v-else class="muted">空车</span>
            </td>
            <td>
              <div class="battery">
                <div :style="{
                  width: (liveOf(r.code) ? r.battery : r.battery) + '%',
                  background: batteryColor(r.battery)
                }"></div>
                <span>{{ r.battery }}%</span>
              </div>
            </td>
            <td class="muted" style="font-size:12px">{{ heartbeatText(r.lastHeartbeat) }}</td>
            <td style="white-space:nowrap">
              <button class="btn sm danger" :disabled="isFault(r)"
                      @click="injectFault(r)">注入故障</button>
              <button class="btn sm" :disabled="!isFault(r)" @click="recover(r)">恢复</button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { RobotAPI } from '../api'
import { realtime } from '../mqtt'
import { PHASE, ROBOT_STATUS, fmtTime } from '../constants'
import { toast } from '../toast'

const robots = ref([])
let timer = null

async function load() {
  try {
    robots.value = await RobotAPI.list()
  } catch (e) { /* 忽略轮询错误 */ }
}
onMounted(() => {
  load()
  timer = setInterval(load, 3000)
})
onBeforeUnmount(() => clearInterval(timer))

function liveOf(code) {
  return realtime.robots.find(r => r.code === code)
}
function count(status) {
  return realtime.robots.filter(r => r.status === status).length
}
function isFault(r) {
  const s = liveOf(r.code)?.status || r.status
  return s === 'FAULT' || s === 'OFFLINE'
}
function posText(live, fallback) {
  if (!live) return fallback || '—'
  if (live.nextNode) return `${live.node} → ${live.nextNode} (${Math.round((live.progress || 0) * 100)}%)`
  return live.node || fallback || '—'
}
function batteryColor(v) {
  if (v < 20) return 'var(--status-critical)'
  if (v < 45) return 'var(--status-warning)'
  return 'var(--status-good)'
}
function heartbeatText(iso) {
  if (!iso || iso.startsWith('1970')) return '从未上报'
  const ageSec = Math.round((Date.now() - new Date(iso)) / 1000)
  if (ageSec < 3) return `${fmtTime(iso)} · 刚刚`
  if (ageSec < 60) return `${fmtTime(iso)} · ${ageSec}s 前`
  return fmtTime(iso)
}

async function injectFault(r) {
  const reason = prompt(`向 ${r.code} 注入故障（其在途任务将被强制重分配）：`, '模拟轮系故障')
  if (reason === null) return
  try {
    await RobotAPI.fault(r.code, reason || '模拟故障')
    toast(`${r.code} 已上报故障，任务将重分配`)
    setTimeout(load, 500)
  } catch (e) {
    toast(e.response?.data?.error || '操作失败', true)
  }
}
async function recover(r) {
  try {
    await RobotAPI.recover(r.code)
    toast(`${r.code} 已恢复空闲`)
    setTimeout(load, 500)
  } catch (e) {
    toast(e.response?.data?.error || '操作失败', true)
  }
}
</script>

<style scoped>
.battery {
  position: relative;
  width: 78px; height: 18px;
  border: 1px solid var(--baseline);
  border-radius: 4px;
  overflow: hidden;
  font-size: 11px;
}
.battery div { height: 100%; opacity: .85; transition: width .5s; }
.battery span {
  position: absolute; inset: 0;
  display: flex; align-items: center; justify-content: center;
  color: var(--ink); font-variant-numeric: tabular-nums;
}
</style>
