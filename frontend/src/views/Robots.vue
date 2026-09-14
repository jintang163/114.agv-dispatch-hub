<template>
  <div>
    <h2 class="page-title">AGV 状态管理</h2>
    <p class="page-sub">
      实时位置 / 速度 / 方向 / 电量 / 运行状态 · 车辆注册与维护（型号·载重·允许任务类型）· 低电自动回充
    </p>

    <div class="grid grid-4 mb16">
      <div class="card kpi">
        <div class="label">在线空闲</div>
        <div class="value" style="color:var(--status-good)">{{ count('IDLE') }}</div>
      </div>
      <div class="card kpi">
        <div class="label">执行任务中</div>
        <div class="value" style="color:var(--series-1)">{{ count('BUSY') }}</div>
      </div>
      <div class="card kpi">
        <div class="label">充电中 / 低电</div>
        <div class="value" style="color:var(--status-warning)">{{ count('CHARGING') }} <span class="muted" style="font-size:15px">/ {{ lowCount }}</span></div>
      </div>
      <div class="card kpi">
        <div class="label">故障 / 离线</div>
        <div class="value" style="color:var(--status-critical)">{{ count('FAULT') + count('OFFLINE') }}</div>
      </div>
    </div>

    <div class="card">
      <div class="toolbar">
        <span class="muted">共 {{ robots.length }} 台 AGV · 档案每 3 秒刷新，位置/速度/电量由 MQTT 实时推送（1s）</span>
        <span class="spacer"></span>
        <button class="btn" @click="load">刷新</button>
        <button class="btn primary" @click="openCreate">+ 注册 AGV</button>
      </div>
      <table>
        <thead>
          <tr>
            <th>编号 / 名称</th><th>型号 · 载重</th><th>允许任务</th>
            <th>状态</th><th>实时位置（站点）</th><th>速度 / 方向</th>
            <th>当前任务</th><th>阶段</th><th>电量</th><th>最近心跳</th><th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="r in robots" :key="r.id" :class="{ 'row-low': isLow(r), 'row-critical': isCritical(r) }">
            <td>
              <div class="mono"><strong>{{ r.code }}</strong></div>
              <div class="muted" style="font-size:12px">{{ r.name || '—' }}</div>
            </td>
            <td>
              <div>{{ r.model || '—' }}</div>
              <div class="muted" style="font-size:12px">载重 {{ r.payloadCapacity ?? '—' }} kg</div>
            </td>
            <td>
              <span v-if="!r.allowedTaskTypes?.length" class="tag MEDIUM">全部</span>
              <span v-for="t in r.allowedTaskTypes" :key="t" class="tag" style="margin-right:4px">
                {{ TASK_TYPE[t] || t }}
              </span>
            </td>
            <td><span class="tag" :class="statusOf(r)">
              {{ ROBOT_STATUS[statusOf(r)] }}
            </span></td>
            <td class="mono" style="font-size:12px">
              <div>{{ posText(liveOf(r.code), r.currentNode) }}</div>
              <div v-if="coordText(liveOf(r.code))" class="muted" style="font-size:11px">{{ coordText(liveOf(r.code)) }}</div>
            </td>
            <td class="mono" style="font-size:12px">
              <div>{{ (liveOf(r.code)?.speed ?? 0) }} <span class="muted">单位/s</span></div>
              <div class="muted">{{ headingText(liveOf(r.code)?.heading) }}</div>
            </td>
            <td>
              <router-link v-if="liveOf(r.code)?.taskId || r.currentTaskId" to="/tasks">
                #{{ liveOf(r.code)?.taskId || r.currentTaskId }}
              </router-link>
              <span v-else class="muted">—</span>
            </td>
            <td class="muted" style="font-size:12px">{{ PHASE[liveOf(r.code)?.phase] || '—' }}</td>
            <td>
              <div class="battery">
                <div :style="{ width: batteryOf(r) + '%', background: batteryColor(batteryOf(r)) }"></div>
                <span>{{ batteryOf(r) }}%</span>
              </div>
              <span v-if="isCritical(r)" class="tag FAULT" style="margin-top:3px">严重低电</span>
              <span v-else-if="isLow(r)" class="tag CHARGING" style="margin-top:3px">低电</span>
            </td>
            <td class="muted" style="font-size:12px">{{ heartbeatText(r.lastHeartbeat) }}</td>
            <td style="white-space:nowrap">
              <button class="btn sm" :disabled="!canCharge(r)" @click="charge(r)" title="生成充电任务回桩">回充</button>
              <button class="btn sm danger" :disabled="isFaultOrOffline(r)" @click="injectFault(r)">故障</button>
              <button class="btn sm" :disabled="!isFault(r)" @click="recover(r)">恢复</button>
              <button class="btn sm" @click="openEdit(r)">编辑</button>
              <button class="btn sm danger" :disabled="r.status !== 'OFFLINE'" @click="deregister(r)">注销</button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- 注册 / 编辑弹窗 -->
    <div v-if="showForm" class="modal-mask" @click.self="showForm = false">
      <div class="modal">
        <h3>{{ formIsEdit ? '编辑 AGV 档案' : '注册新 AGV' }}</h3>
        <div class="form-grid">
          <label class="field">车辆编号（MQTT clientId）
            <input v-model="form.code" :disabled="formIsEdit" placeholder="如 AGV-07">
          </label>
          <label class="field">名称
            <input v-model="form.name" placeholder="如 叉车机器人 7 号">
          </label>
          <label class="field">型号
            <input v-model="form.model" placeholder="如 FL-500 潜伏顶升式">
          </label>
          <label class="field">额定载重（kg）
            <input type="number" min="0" v-model.number="form.payloadCapacity">
          </label>
          <label class="field">归属充电桩（可空=就近选桩）
            <select v-model="form.homeCharger">
              <option value="">就近选择</option>
              <option v-for="c in chargers" :key="c.code" :value="c.code">{{ c.code }}（{{ c.name }}）</option>
            </select>
          </label>
          <label class="field" v-if="!formIsEdit">初始停靠节点
            <select v-model="form.currentNode">
              <option v-for="n in anchorNodes" :key="n.code" :value="n.code">{{ n.code }}（{{ n.name || n.type }}）</option>
            </select>
          </label>
          <div class="field full">
            允许任务类型（不勾选=全部允许；充电任务由系统自动管理）
            <div class="check-row">
              <label><input type="checkbox" value="TRANSPORT" v-model="form.allowedTaskTypes"> 搬运</label>
              <label><input type="checkbox" value="PICKING" v-model="form.allowedTaskTypes"> 拣选</label>
            </div>
          </div>
          <label class="field" v-if="!formIsEdit">初始电量（%）
            <input type="number" min="0" max="100" v-model.number="form.battery">
          </label>
        </div>
        <div class="modal-actions">
          <button class="btn" @click="showForm = false">取消</button>
          <button class="btn primary" @click="submit">{{ formIsEdit ? '保存' : '注册' }}</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { RobotAPI, MapAPI } from '../api'
import { realtime } from '../mqtt'
import { PHASE, ROBOT_STATUS, TASK_TYPE, fmtTime, headingText } from '../constants'
import { toast } from '../toast'

const robots = ref([])
const chargers = ref([])
const anchorNodes = ref([])
let timer = null

const emptyForm = () => ({
  code: '', name: '', model: '', payloadCapacity: 500,
  allowedTaskTypes: [], homeCharger: '', currentNode: '', battery: 100
})
const form = ref(emptyForm())
const showForm = ref(false)
const formIsEdit = ref(false)

async function load() {
  try {
    robots.value = await RobotAPI.list()
  } catch (e) { /* 忽略轮询错误 */ }
}
async function loadMeta() {
  try {
    chargers.value = await RobotAPI.chargers()
    const map = await MapAPI.get()
    anchorNodes.value = map.nodes.filter(n => n.type === 'JUNCTION' || n.type === 'CHARGER')
    if (anchorNodes.value.length && !form.value.currentNode) {
      form.value.currentNode = anchorNodes.value[0].code
    }
  } catch (e) { /* ignore */ }
}
onMounted(() => {
  load(); loadMeta()
  timer = setInterval(load, 3000)
})
onBeforeUnmount(() => clearInterval(timer))

function liveOf(code) {
  return realtime.robots.find(r => r.code === code)
}
function statusOf(r) {
  return liveOf(r.code)?.status || r.status
}
function batteryOf(r) {
  return liveOf(r.code)?.battery ?? r.battery ?? 100
}
function count(status) {
  return realtime.robots.filter(r => r.status === status).length
}
const lowCount = computed(() =>
  robots.value.filter(r => batteryOf(r) < 25 && ['IDLE', 'BUSY'].includes(statusOf(r))).length
)
function isLow(r) {
  const b = batteryOf(r); return b < 25 && statusOf(r) !== 'CHARGING'
}
function isCritical(r) {
  return batteryOf(r) < 15 && statusOf(r) !== 'CHARGING'
}
function isFault(r) {
  const s = statusOf(r); return s === 'FAULT'
}
function isFaultOrOffline(r) {
  const s = statusOf(r); return s === 'FAULT' || s === 'OFFLINE'
}
function canCharge(r) {
  const s = statusOf(r); return s === 'IDLE' || s === 'BUSY'
}
function posText(live, fallback) {
  if (!live) return fallback || '—'
  if (live.nextNode) return `${live.node} → ${live.nextNode} (${Math.round((live.progress || 0) * 100)}%)`
  return live.node || fallback || '—'
}
function coordText(live) {
  if (!live || live.x == null) return ''
  return `坐标 (${live.x}, ${live.y})`
}
function batteryColor(v) {
  if (v < 15) return 'var(--status-critical)'
  if (v < 25) return 'var(--status-warning)'
  return 'var(--status-good)'
}
function heartbeatText(iso) {
  if (!iso || iso.startsWith('1970')) return '从未上报'
  const ageSec = Math.round((Date.now() - new Date(iso)) / 1000)
  if (ageSec < 3) return `${fmtTime(iso)} · 刚刚`
  if (ageSec < 60) return `${fmtTime(iso)} · ${ageSec}s 前`
  return fmtTime(iso)
}

function openCreate() {
  form.value = emptyForm()
  if (anchorNodes.value.length) form.value.currentNode = anchorNodes.value[0].code
  formIsEdit.value = false
  showForm.value = true
}
function openEdit(r) {
  form.value = {
    code: r.code, name: r.name || '', model: r.model || '',
    payloadCapacity: r.payloadCapacity ?? 500,
    allowedTaskTypes: r.allowedTaskTypes ? [...r.allowedTaskTypes] : [],
    homeCharger: r.homeCharger || '', currentNode: r.currentNode || '',
    battery: r.battery ?? 100
  }
  formIsEdit.value = true
  showForm.value = true
}
async function submit() {
  try {
    const body = { ...form.value }
    if (!body.homeCharger) body.homeCharger = null
    if (formIsEdit.value) {
      await RobotAPI.update(form.value.code, body)
      toast(`${form.value.code} 档案已更新`)
    } else {
      if (!body.currentNode) body.currentNode = null
      await RobotAPI.register(body)
      toast(`${form.value.code} 已注册，等待设备上线`)
    }
    showForm.value = false
    await load()
  } catch (e) {
    toast(e.response?.data?.error || '保存失败', true)
  }
}

async function charge(r) {
  if (!confirm(`让 ${r.code} 立即回充电桩？${r.status === 'BUSY' ? '\n（执行中车辆将在当前任务送达后由系统安排回充）' : ''}`)) return
  try {
    const res = await RobotAPI.charge(r.code)
    toast(`${r.code} 充电任务 #${res.data.taskId} 已生成`)
    setTimeout(load, 500)
  } catch (e) {
    toast(e.response?.data?.error || '回充失败', true)
  }
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
async function deregister(r) {
  if (!confirm(`确认注销 ${r.code}？仅离线 AGV 可注销。`)) return
  try {
    await RobotAPI.deregister(r.code)
    toast(`${r.code} 已注销`)
    await load()
  } catch (e) {
    toast(e.response?.data?.error || '注销失败', true)
  }
}
</script>

<style scoped>
.grid-4 { grid-template-columns: repeat(4, 1fr); }
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
tr.row-low td { background: #fffaf0; }
tr.row-critical td { background: #fdf1f0; }
.check-row { display: flex; gap: 22px; margin-top: 6px; }
.check-row label { display: flex; align-items: center; gap: 6px; font-size: 13px; color: var(--ink); }
.check-row input { width: auto; }
</style>
