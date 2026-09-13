<template>
  <div>
    <h2 class="page-title">调度看板</h2>
    <p class="page-sub">车队运行态势、任务队列与调度事件总览（数据经 MQTT 实时推送）</p>

    <div class="grid grid-kpi mb16">
      <div class="card kpi">
        <div class="label">待分配任务</div>
        <div class="value">{{ stats.queueSize ?? '—' }}</div>
        <div class="hint">优先级队列中等待派车</div>
      </div>
      <div class="card kpi">
        <div class="label">执行中任务</div>
        <div class="value">{{ (tasksByStatus.EXECUTING || 0) + (tasksByStatus.ASSIGNED || 0) }}</div>
        <div class="hint">已分配 {{ tasksByStatus.ASSIGNED || 0 }} · 执行 {{ tasksByStatus.EXECUTING || 0 }}</div>
      </div>
      <div class="card kpi">
        <div class="label">累计完成</div>
        <div class="value">{{ stats.completedTotal ?? '—' }}</div>
        <div class="hint">平均耗时 {{ stats.avgDurationMinutes ?? 0 }} 分钟</div>
      </div>
      <div class="card kpi">
        <div class="label">可用 AGV</div>
        <div class="value">{{ idleRobots }} <span class="muted" style="font-size:15px">/ {{ realtime.robots.length }}</span></div>
        <div class="hint">忙碌 {{ busyRobots }} · 故障 {{ faultRobots }}</div>
      </div>
      <div class="card kpi">
        <div class="label">异常任务</div>
        <div class="value" :style="{ color: (tasksByStatus.EXCEPTION || 0) ? 'var(--status-critical)' : undefined }">
          {{ tasksByStatus.EXCEPTION || 0 }}
        </div>
        <div class="hint">故障/阻塞触发重分配</div>
      </div>
    </div>

    <div class="grid grid-2 mb16">
      <div class="card">
        <h3>近 24 小时任务完成趋势</h3>
        <BaseChart :option="trendOption" height="280px" />
      </div>
      <div class="card">
        <h3>任务状态分布</h3>
        <BaseChart :option="pieOption" height="280px" />
      </div>
    </div>

    <div class="grid grid-2">
      <div class="card">
        <h3>实时调度队列（队首优先）</h3>
        <table v-if="queue.length">
          <thead><tr><th>#</th><th>任务</th><th>路线</th><th>优先级</th><th class="right">评分</th></tr></thead>
          <tbody>
            <tr v-for="(q, i) in queue.slice(0, 8)" :key="q.taskId">
              <td class="muted">{{ i + 1 }}</td>
              <td><router-link :to="`/tasks`">#{{ q.taskId }} {{ TASK_TYPE[q.type] }}</router-link></td>
              <td class="mono">{{ q.fromNode }} → {{ q.toNode }}</td>
              <td><span class="tag" :class="q.priority">{{ PRIORITY[q.priority] }}</span></td>
              <td class="right mono">{{ Math.round(q.score) }}</td>
            </tr>
          </tbody>
        </table>
        <p v-else class="muted" style="padding:24px 0;text-align:center">队列为空，所有任务均已派出</p>
      </div>
      <div class="card">
        <h3>调度事件流</h3>
        <div class="event-feed">
          <div v-for="(e, i) in realtime.events.slice(0, 60)" :key="i" class="event-row">
            <span class="lvl" :class="e.level"></span>
            <span class="t">{{ fmtTime(e.ts) }}</span>
            <span>
              <strong>{{ eventText(e.type) }}</strong>
              <template v-if="e.robot"> · {{ e.robot }}</template>
              <template v-if="e.taskId"> · #{{ e.taskId }}</template>
              <div class="muted">{{ e.message }}</div>
            </span>
          </div>
          <p v-if="!realtime.events.length" class="muted" style="padding:16px 0;text-align:center">
            等待调度事件…
          </p>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import BaseChart from '../components/BaseChart.vue'
import { QueueAPI, StatsAPI } from '../api'
import { realtime } from '../mqtt'
import { PRIORITY, TASK_TYPE, fmtTime } from '../constants'

const stats = ref({})
const queue = ref([])
let timer = null

async function refresh() {
  try {
    const [s, q] = await Promise.all([StatsAPI.overview(), QueueAPI.view()])
    // 合并而非覆盖，保留独立轮询的 trend 数据避免图表闪动
    stats.value = { ...stats.value, ...s }
    queue.value = q
  } catch (e) {
    /* 后端尚未就绪时静默，下个周期重试 */
  }
}

onMounted(() => {
  refresh()
  timer = setInterval(refresh, 3000)
})
onBeforeUnmount(() => clearInterval(timer))

const tasksByStatus = computed(() => stats.value.tasksByStatus || {})
const idleRobots = computed(() => realtime.robots.filter(r => r.status === 'IDLE').length)
const busyRobots = computed(() => realtime.robots.filter(r => r.status === 'BUSY').length)
const faultRobots = computed(() => realtime.robots.filter(r => r.status === 'FAULT').length)

const STATUS_COLOR = {
  PENDING: '#898781',
  ASSIGNED: '#2a78d6',
  EXECUTING: '#1baf7a',
  COMPLETED: '#0ca30c',
  CANCELLED: '#b7b2a5',
  EXCEPTION: '#d03b3b'
}
const STATUS_LABEL = {
  PENDING: '待分配', ASSIGNED: '已分配', EXECUTING: '执行中',
  COMPLETED: '完成', CANCELLED: '取消', EXCEPTION: '异常'
}

const pieOption = computed(() => {
  const entries = Object.entries(tasksByStatus.value).filter(([, v]) => v > 0)
  return {
    color: entries.map(([k]) => STATUS_COLOR[k]),
    tooltip: { trigger: 'item', formatter: (p) => `${p.name}：${p.value} 单（${p.percent}%）` },
    legend: { bottom: 0, textStyle: { color: '#52514e', fontSize: 12 } },
    series: [{
      type: 'pie',
      radius: ['46%', '70%'],
      center: ['50%', '44%'],
      itemStyle: { borderColor: '#fcfcfb', borderWidth: 2, borderRadius: 4 },
      label: { color: '#0b0b0b', fontSize: 12, formatter: '{b} {c}' },
      data: entries.map(([k, v]) => ({ name: STATUS_LABEL[k], value: v }))
    }]
  }
})

const trendOption = computed(() => ({
  grid: { left: 44, right: 16, top: 20, bottom: 40 },
  tooltip: {
    trigger: 'axis',
    axisPointer: { type: 'shadow' },
    formatter: (p) => `${p[0].axisValue}<br/>完成 <strong>${p[0].data}</strong> 单`
  },
  xAxis: {
    type: 'category',
    data: (stats.value.trend || []).map(t => t.hour.slice(11, 16)),
    axisLine: { lineStyle: { color: '#c3c2b7' } },
    axisLabel: { color: '#898781', fontSize: 10, interval: 3 },
    axisTick: { show: false }
  },
  yAxis: {
    type: 'value',
    minInterval: 1,
    splitLine: { lineStyle: { color: '#e1e0d9' } },
    axisLabel: { color: '#898781' }
  },
  series: [{
    type: 'bar',
    data: (stats.value.trend || []).map(t => t.count),
    itemStyle: { color: '#2a78d6', borderRadius: [4, 4, 0, 0] },
    barMaxWidth: 18,
    emphasis: { itemStyle: { color: '#1c5cab' } }
  }]
}))

// 趋势数据独立拉取，合并进 stats
onMounted(async () => {
  try {
    stats.value.trend = await StatsAPI.trend()
    setInterval(async () => {
      stats.value.trend = await StatsAPI.trend()
    }, 15000)
  } catch (e) { /* ignore */ }
})

const EVENT_TEXT = {
  CREATED: '任务下发', ASSIGN: '任务派发', ASSIGNED: '任务已分配',
  PREEMPT: '高优抢占', PREEMPTED: '任务被抢占', PREEMPT_ASSIGN: '抢占分配',
  REPRIORITIZED: '优先级调整', COMPLETED: '任务完成', CANCELLED: '任务取消',
  REASSIGN: '强制重分配', REASSIGNED: '重新入队', REROUTE: '受阻请求改道',
  REROUTED: '路径重规划', EXCEPTION: '任务异常',
  FAULT: 'AGV 故障', RECOVERED: 'AGV 恢复'
}
function eventText(t) {
  return EVENT_TEXT[t] || t
}
</script>
