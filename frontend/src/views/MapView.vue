<template>
  <div>
    <h2 class="page-title">实时调度地图</h2>
    <p class="page-sub">
      9×5 网格仓库拓扑 · A* 规划路径与时间窗预约可视化 · 点击通道可阻塞/恢复（触发在途任务自动重规划）
    </p>

    <div class="card">
      <div class="toolbar">
        <div class="legend">
          <span><i style="background:#2a78d6"></i>取货点 P</span>
          <span><i style="background:#eb6834"></i>卸货点 D</span>
          <span><i style="background:#898781"></i>路口 J</span>
          <span><i style="background:#d03b3b"></i>高优任务路径</span>
          <span><i style="background:#2a78d6"></i>普通任务规划路径</span>
          <span><i style="background:#0ca30c"></i>实际已走轨迹</span>
          <span><i style="background:#d03b3b;border-radius:50%"></i>故障 AGV</span>
          <span><i style="background:repeating-linear-gradient(45deg,#d03b3b,#d03b3b 4px,transparent 4px,transparent 8px)"></i>阻塞通道</span>
        </div>
        <span class="spacer"></span>
        <button class="btn" @click="load">刷新路径</button>
      </div>
      <BaseChart ref="chartRef" :option="option" height="660px" @chart-click="onChartClick" />
      <div v-if="blockedEdges.length" class="toolbar" style="margin-top:12px;margin-bottom:0">
        <strong>阻塞通道：</strong>
        <span v-for="e in blockedEdges" :key="e.id" class="blocked-chip">
          {{ e.fromNode }} — {{ e.toNode }}
          <button class="btn sm" @click="toggleEdge(e, false)">恢复</button>
        </span>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import BaseChart from '../components/BaseChart.vue'
import { MapAPI, TaskAPI } from '../api'
import { realtime } from '../mqtt'
import { toast } from '../toast'

const nodes = ref([])
const edges = ref([])
const tasks = ref([])
const chartRef = ref(null)
let timer = null

const nodeMap = computed(() => Object.fromEntries(nodes.value.map(n => [n.code, n])))
const blockedEdges = computed(() =>
  edges.value.filter(e => e.blocked))

async function load() {
  try {
    const map = await MapAPI.get()
    nodes.value = map.nodes
    edges.value = map.edges
    tasks.value = await TaskAPI.list()
  } catch (e) {
    toast('地图加载失败', true)
  }
}
onMounted(() => {
  load()
  timer = setInterval(async () => {
    try { tasks.value = await TaskAPI.list() } catch (e) { /* ignore */ }
  }, 2500)
})
onBeforeUnmount(() => clearInterval(timer))

const NODE_COLOR = { JUNCTION: '#898781', PICK: '#2a78d6', DROP: '#eb6834', STORAGE: '#b7b2a5' }
const PRI_PATH = { HIGH: '#d03b3b', MEDIUM: '#2a78d6', LOW: '#86b6ef' }

function xy(code) {
  const n = nodeMap.value[code]
  return n ? [n.x, n.y] : null
}

/** AGV 实时坐标：在 node -> nextNode 边上按 progress 线性插值 */
function robotPos(r) {
  const a = xy(r.node)
  if (!a) return null
  if (!r.nextNode || !r.progress) return a
  const b = xy(r.nextNode)
  if (!b) return a
  const p = Math.min(1, Math.max(0, r.progress || 0))
  return [a[0] + (b[0] - a[0]) * p, a[1] + (b[1] - a[1]) * p]
}

const option = computed(() => {
  const active = tasks.value.filter(t =>
    ['ASSIGNED', 'EXECUTING'].includes(t.status) && t.plannedPath?.length > 1)

  const plannedLines = []
  for (const t of active) {
    const coords = t.plannedPath.map(xy).filter(Boolean)
    if (coords.length > 1) {
      plannedLines.push({
        coords,
        lineStyle: {
          color: PRI_PATH[t.priority] || '#2a78d6',
          width: t.priority === 'HIGH' ? 3 : 2,
          opacity: 0.45,
          type: 'solid'
        },
        meta: { kind: 'planned', taskId: t.id, priority: t.priority }
      })
    }
  }

  const actualLines = []
  for (const t of active) {
    const coords = (t.actualPath || []).map(xy).filter(Boolean)
    if (coords.length > 1) {
      actualLines.push({
        coords,
        lineStyle: { color: '#0ca30c', width: 3.5, opacity: 0.85 },
        meta: { kind: 'actual', taskId: t.id }
      })
    }
  }

  const edgeLines = edges.value.map(e => {
    const a = xy(e.fromNode), b = xy(e.toNode)
    return {
      coords: [a, b],
      lineStyle: e.blocked
        ? { color: '#d03b3b', width: 5, type: [6, 5], opacity: 0.9 }
        : { color: '#c9c7bd', width: 2 },
      meta: { kind: 'edge', edge: e }
    }
  }).filter(l => l.coords[0] && l.coords[1])

  const robotData = realtime.robots.map(r => {
    const pos = robotPos(r)
    if (!pos) return null
    const fault = r.status === 'FAULT'
    return {
      value: pos,
      symbol: r.loaded ? 'diamond' : 'circle',
      symbolSize: r.loaded ? 18 : 15,
      itemStyle: {
        color: fault ? '#d03b3b' : (r.status === 'BUSY' ? '#1c5cab' : '#0ca30c'),
        borderColor: '#fff', borderWidth: 2
      },
      label: {
        show: true,
        position: 'top',
        formatter: `${r.code}${r.taskId ? ' #' + r.taskId : ''}`,
        fontSize: 11, fontWeight: 600, color: '#0b0b0b'
      },
      meta: { kind: 'robot', robot: r }
    }
  }).filter(Boolean)

  const nodeData = nodes.value.map(n => ({
    value: [n.x, n.y],
    symbolSize: n.type === 'JUNCTION' ? 11 : 17,
    itemStyle: {
      color: NODE_COLOR[n.type] || '#898781',
      borderColor: '#fff', borderWidth: 2
    },
    label: {
      show: n.type !== 'JUNCTION',
      position: n.type === 'PICK' ? 'bottom' : 'top',
      formatter: n.code, fontSize: 11, color: '#52514e'
    },
    meta: { kind: 'node', node: n }
  }))

  return {
    animationDurationUpdate: 300,
    grid: { left: 30, right: 30, top: 30, bottom: 30 },
    xAxis: {
      type: 'value', min: 40, max: 960, show: false,
      scale: true
    },
    yAxis: {
      type: 'value', min: 10, max: 710, show: false,
      inverse: true, scale: true
    },
    tooltip: {
      trigger: 'item',
      formatter: (p) => {
        const m = p.data?.meta
        if (!m) return ''
        if (m.kind === 'edge') {
          const e = m.edge
          return `<b>通道</b> ${e.fromNode} → ${e.toNode}<br/>点击可${e.blocked ? '恢复' : '阻塞'}该通道`
        }
        if (m.kind === 'node') {
          return `<b>${m.node.code}</b> ${m.node.name || ''}<br/>类型：${m.node.type}`
        }
        if (m.kind === 'robot') {
          const r = m.robot
          return `<b>${r.code}</b> ${r.name || ''}<br/>状态：${r.status}${r.taskId ? ' · 任务 #' + r.taskId : ''}` +
            (r.nextNode ? `<br/>${r.node} → ${r.nextNode} (${Math.round((r.progress || 0) * 100)}%)` : `<br/>位置：${r.node}`)
        }
        if (m.kind === 'planned') return `任务 #${m.taskId} 规划路径（${m.priority === 'HIGH' ? '高' : '普通'}优先级）`
        if (m.kind === 'actual') return `任务 #${m.taskId} 实际轨迹`
        return ''
      }
    },
    series: [
      {
        type: 'lines', coordinateSystem: 'cartesian2d',
        data: edgeLines, z: 1, silent: false,
        polyline: false
      },
      {
        type: 'lines', coordinateSystem: 'cartesian2d',
        data: plannedLines, z: 2, polyline: true, silent: true,
        effect: { show: false }
      },
      {
        type: 'lines', coordinateSystem: 'cartesian2d',
        data: actualLines, z: 3, polyline: true, silent: true
      },
      {
        type: 'scatter', coordinateSystem: 'cartesian2d',
        data: nodeData, z: 4
      },
      {
        type: 'scatter', coordinateSystem: 'cartesian2d',
        data: robotData, z: 5
      }
    ]
  }
})

/** 点击通道：阻塞/恢复，后端会对受影响在途任务自动 A* 重规划 */
async function onChartClick(params) {
  const meta = params?.data?.meta
  if (!meta || meta.kind !== 'edge') return
  const e = meta.edge
  const block = !e.blocked
  if (!confirm(`确认${block ? '阻塞' : '恢复'}通道 ${e.fromNode} — ${e.toNode}？` +
    (block ? '\n阻塞后所有经过该通道的在途任务将自动重新规划绕行路径。' : ''))) return
  try {
    await MapAPI.blockEdge(e.fromNode, e.toNode, block)
    toast(block ? '通道已阻塞，在途任务正在重规划' : '通道已恢复')
    await load()
  } catch (err) {
    toast(err.response?.data?.error || '操作失败', true)
  }
}
</script>

<style scoped>
.blocked-chip {
  display: inline-flex; align-items: center; gap: 8px;
  background: #fdebe9; color: #b02a2a;
  border-radius: 999px; padding: 3px 8px 3px 12px;
  font-size: 12px;
}
</style>
