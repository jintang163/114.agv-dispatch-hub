<template>
  <div>
    <h2 class="page-title">任务管理</h2>
    <p class="page-sub">WMS 任务接入 · 紧急度/截止时间排序 · 高优插队 · 取消与强制重分配</p>

    <div class="grid grid-2">
      <!-- 左：任务列表 -->
      <div class="card">
        <div class="toolbar">
          <select v-model="filter" @change="load">
            <option value="">全部状态</option>
            <option v-for="(label, k) in TASK_STATUS" :key="k" :value="k">{{ label }}</option>
          </select>
          <span class="spacer"></span>
          <button class="btn" @click="load">刷新</button>
          <button class="btn primary" @click="showCreate = true">+ 下发任务</button>
        </div>

        <table>
          <thead>
            <tr>
              <th>#</th><th>类型</th><th>路线</th><th>优先级</th><th>状态</th>
              <th>AGV</th><th>阶段</th><th>截止</th><th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="t in tasks" :key="t.id">
              <td><a href="#" @click.prevent="openDetail(t)">{{ t.id }}</a></td>
              <td>{{ TASK_TYPE[t.type] }}</td>
              <td class="mono">{{ t.fromNode }} → {{ t.toNode }}</td>
              <td>
                <select v-if="t.status === 'PENDING'"
                        class="pri-select"
                        :value="t.priority"
                        @change="changePriority(t, $event.target.value)">
                  <option value="HIGH">高</option>
                  <option value="MEDIUM">中</option>
                  <option value="LOW">低</option>
                </select>
                <span v-else class="tag" :class="t.priority">{{ PRIORITY[t.priority] }}</span>
              </td>
              <td><span class="tag" :class="t.status">{{ TASK_STATUS[t.status] }}</span></td>
              <td class="mono">{{ robotCodeOf(t.robotId) || '—' }}</td>
              <td class="muted">{{ t.phase ? (PHASE[t.phase] || t.phase) : '—' }}</td>
              <td class="muted" style="font-size:12px;white-space:nowrap">
                {{ t.deadline ? fmtDeadline(t.deadline) : '无' }}
              </td>
              <td style="white-space:nowrap">
                <button class="btn sm" @click="openDetail(t)">详情</button>
                <button v-if="canReassign(t)" class="btn sm warn" @click="reassign(t)">重分配</button>
                <button v-if="!isDone(t)" class="btn sm danger" @click="cancel(t)">取消</button>
              </td>
            </tr>
          </tbody>
        </table>
        <p v-if="!tasks.length" class="muted" style="text-align:center;padding:30px 0">暂无任务</p>
      </div>

      <!-- 右：实时调度队列 -->
      <div class="card">
        <h3>优先级调度队列 <span class="muted" style="font-weight:400;font-size:12px">（每 3s 刷新，队首先派车）</span></h3>
        <div v-if="queue.length" class="queue-list">
          <div v-for="(q, i) in queue" :key="q.taskId" class="queue-item">
            <span class="queue-rank" :class="{ top: i === 0 }">{{ i + 1 }}</span>
            <div class="queue-main">
              <div>
                <strong>#{{ q.taskId }} {{ TASK_TYPE[q.type] }}</strong>
                <span class="tag" :class="q.priority" style="margin-left:8px">{{ PRIORITY[q.priority] }}</span>
              </div>
              <div class="mono muted" style="font-size:12px">{{ q.fromNode }} → {{ q.toNode }}</div>
            </div>
            <div class="queue-score">
              <div class="mono">{{ Math.round(q.score) }}</div>
              <div class="muted" style="font-size:11px">评分</div>
            </div>
          </div>
        </div>
        <p v-else class="muted" style="text-align:center;padding:30px 0">队列为空</p>

        <h3 style="margin-top:22px">调度排序规则</h3>
        <ul class="muted" style="font-size:12.5px;line-height:1.9;padding-left:18px;margin:0">
          <li><strong>紧急度档位优先</strong>：高 &gt; 中 &gt; 低，权重差 1,000,000，低优永不越级</li>
          <li><strong>同档比截止</strong>：距期望完成时间越近，加分越高（0~144,000）</li>
          <li><strong>等待防饥饿</strong>：排队每分钟 +1，720 分钟封顶</li>
          <li>高优任务下发或调权后 ZSET 立即重排，实现"插队"</li>
        </ul>
      </div>
    </div>

    <!-- 下发任务弹窗 -->
    <div v-if="showCreate" class="modal-mask" @click.self="showCreate = false">
      <div class="modal">
        <h3>WMS 下发任务</h3>
        <div class="form-grid">
          <label class="field">任务类型
            <select v-model="form.type">
              <option value="TRANSPORT">搬运</option>
              <option value="PICKING">拣选</option>
            </select>
          </label>
          <label class="field">紧急程度
            <select v-model="form.priority">
              <option value="HIGH">高（可抢占）</option>
              <option value="MEDIUM">中</option>
              <option value="LOW">低</option>
            </select>
          </label>
          <label class="field">起点（取货点）
            <select v-model="form.fromNode">
              <option v-for="n in pickNodes" :key="n.code" :value="n.code">
                {{ n.code }}（{{ n.name }}）
              </option>
            </select>
          </label>
          <label class="field">终点（卸货点）
            <select v-model="form.toNode">
              <option v-for="n in dropNodes" :key="n.code" :value="n.code">
                {{ n.code }}（{{ n.name }}）
              </option>
            </select>
          </label>
          <label class="field">货物重量 kg（可空，校验 AGV 载重）
            <input type="number" min="0" v-model.number="form.payloadWeight" placeholder="如 300">
          </label>
          <label class="field full">期望完成时间（可空，本地时区）
            <input type="datetime-local" v-model="form.deadlineLocal">
          </label>
          <label class="field full">备注
            <input v-model="form.remark" placeholder="如：WMS 波次号 WO-20260913-01">
          </label>
        </div>
        <div class="modal-actions">
          <button class="btn" @click="showCreate = false">取消</button>
          <button class="btn primary" @click="submit">下发</button>
        </div>
      </div>
    </div>

    <!-- 任务详情/时间线弹窗 -->
    <div v-if="detail" class="modal-mask" @click.self="detail = null">
      <div class="modal">
        <h3>任务 #{{ detail.task.id }} 详情</h3>
        <div class="grid" style="grid-template-columns:1fr 1fr;gap:10px 20px;font-size:13px">
          <div><span class="muted">类型：</span>{{ TASK_TYPE[detail.task.type] }}</div>
          <div><span class="muted">优先级：</span>{{ PRIORITY[detail.task.priority] }}</div>
          <div><span class="muted">状态：</span>{{ TASK_STATUS[detail.task.status] }}</div>
          <div><span class="muted">阶段：</span>{{ detail.task.phase ? PHASE[detail.task.phase] : '—' }}</div>
          <div><span class="muted">路线：</span><span class="mono">{{ detail.task.fromNode }} → {{ detail.task.toNode }}</span></div>
          <div><span class="muted">AGV：</span>{{ robotCodeOf(detail.task.robotId) || '—' }}</div>
          <div class="full muted">规划路径：<span class="mono">{{ detail.task.plannedPath?.join(' → ') || '尚未规划' }}</span></div>
          <div class="full muted">实际轨迹：<span class="mono">{{ detail.task.actualPath?.join(' → ') || '—' }}</span></div>
        </div>
        <h3 style="margin-top:18px">事件时间线</h3>
        <ul class="timeline">
          <li v-for="e in detail.events" :key="e.id">
            <div class="ev">{{ EVENT_TEXT[e.event] || e.event }}
              <span v-if="e.robotCode" class="muted" style="font-weight:400"> · {{ e.robotCode }}</span>
            </div>
            <div class="meta">{{ fmtTime(e.occurredAt) }} · {{ e.detail }}</div>
          </li>
        </ul>
        <div class="modal-actions">
          <button class="btn" @click="detail = null">关闭</button>
          <button v-if="canReassign(detail.task)" class="btn warn"
                  @click="reassign(detail.task); detail = null">强制重分配</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { MapAPI, QueueAPI, TaskAPI } from '../api'
import { realtime } from '../mqtt'
import { PHASE, PRIORITY, TASK_STATUS, TASK_TYPE, fmtTime, fmtDeadline } from '../constants'
import { toast } from '../toast'

const tasks = ref([])
const queue = ref([])
const filter = ref('')
const showCreate = ref(false)
const detail = ref(null)
const pickNodes = ref([])
const dropNodes = ref([])

const form = ref({ type: 'TRANSPORT', priority: 'MEDIUM', fromNode: '', toNode: '', deadlineLocal: '', payloadWeight: null, remark: '' })

let timer = null

async function load() {
  try {
    tasks.value = await TaskAPI.list(filter.value || undefined)
    queue.value = await QueueAPI.view()
  } catch (e) {
    toast(e.response?.data?.error || '加载失败', true)
  }
}

onMounted(async () => {
  await load()
  const map = await MapAPI.get()
  pickNodes.value = map.nodes.filter(n => n.type === 'PICK')
  dropNodes.value = map.nodes.filter(n => n.type === 'DROP')
  if (pickNodes.value.length) form.value.fromNode = pickNodes.value[0].code
  if (dropNodes.value.length) form.value.toNode = dropNodes.value[0].code
  timer = setInterval(load, 3000)
})
onBeforeUnmount(() => clearInterval(timer))

function robotCodeOf(robotId) {
  return robotMap.value[robotId]
}
const robotMap = ref({})
async function loadRobots() {
  const { RobotAPI } = await import('../api')
  const list = await RobotAPI.list()
  robotMap.value = Object.fromEntries(list.map(r => [r.id, r.code]))
}
onMounted(loadRobots)

function isDone(t) {
  return t.status === 'COMPLETED' || t.status === 'CANCELLED'
}
function canReassign(t) {
  return ['ASSIGNED', 'EXECUTING', 'EXCEPTION'].includes(t.status)
}

async function submit() {
  try {
    const body = { ...form.value }
    delete body.deadlineLocal
    if (body.payloadWeight === '' || body.payloadWeight == null) body.payloadWeight = null
    body.deadline = form.value.deadlineLocal
      ? new Date(form.value.deadlineLocal).toISOString()
      : null
    await TaskAPI.create(body)
    showCreate.value = false
    toast('任务已下发并进入优先级队列')
    await load()
  } catch (e) {
    toast(e.response?.data?.error || '下发失败', true)
  }
}

async function changePriority(t, priority) {
  try {
    await TaskAPI.setPriority(t.id, priority)
    t.priority = priority
    toast(`任务 #${t.id} 已调整为「${PRIORITY[priority]}」优先级`)
    await load()
  } catch (e) {
    toast(e.response?.data?.error || '调整失败', true)
  }
}

async function cancel(t) {
  if (!confirm(`确认取消任务 #${t.id}？已在途的 AGV 将收到 CANCEL 指令。`)) return
  try {
    await TaskAPI.cancel(t.id)
    toast(`任务 #${t.id} 已取消`)
    await load()
  } catch (e) {
    toast(e.response?.data?.error || '取消失败', true)
  }
}

async function reassign(t) {
  if (!confirm(`强制重分配任务 #${t.id}？原 AGV 任务将被撤回。`)) return
  try {
    await TaskAPI.reassign(t.id)
    toast(`任务 #${t.id} 已重新进入队列等待派车`)
    await load()
  } catch (e) {
    toast(e.response?.data?.error || '重分配失败', true)
  }
}

async function openDetail(t) {
  try {
    const [full, events] = await Promise.all([TaskAPI.get(t.id), TaskAPI.events(t.id)])
    detail.value = { task: full, events }
  } catch (e) {
    toast('详情加载失败', true)
  }
}

const EVENT_TEXT = {
  CREATED: '任务下发', ASSIGNED: '分配 AGV', PREEMPT_ASSIGN: '抢占分配',
  STARTED: '开始执行', GOING_PICKUP: '前往取货点', AT_PICKUP: '到达取货点',
  PICKED_UP: '完成取货', GOING_DELIVERY: '前往卸货点', AT_DELIVERY: '到达卸货点',
  DROPPED: '完成卸货', COMPLETED: '任务完成', CANCELLED: '任务取消',
  EXCEPTION: '发生异常', REASSIGNED: '重新分配', PREEMPTED: '被高优任务抢占',
  REPRIORITIZED: '优先级调整', REROUTED: '路径重规划',
  GOING_CHARGER: '前往充电桩', CHARGING: '开始充电', CHARGING_STARTED: '接入充电桩'
}
</script>

<style scoped>
.pri-select { padding: 3px 6px; font-size: 12px; }
.queue-list { display: flex; flex-direction: column; gap: 8px; }
.queue-item {
  display: flex; align-items: center; gap: 12px;
  border: 1px solid var(--hairline); border-radius: 8px; padding: 10px 12px;
}
.queue-rank {
  width: 26px; height: 26px; border-radius: 50%;
  background: #eceae4; color: var(--ink-2);
  display: flex; align-items: center; justify-content: center;
  font-weight: 700; font-size: 13px; flex-shrink: 0;
}
.queue-rank.top { background: var(--status-critical); color: #fff; }
.queue-main { flex: 1; min-width: 0; }
.queue-score { text-align: right; font-size: 13px; color: var(--ink-2); }
</style>
