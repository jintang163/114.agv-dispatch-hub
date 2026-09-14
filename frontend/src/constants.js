// 与后端枚举保持一致的展示字典
export const TASK_STATUS = {
  PENDING: '待分配', ASSIGNED: '已分配', EXECUTING: '执行中',
  COMPLETED: '完成', CANCELLED: '取消', EXCEPTION: '异常'
}
export const ROBOT_STATUS = { OFFLINE: '离线', IDLE: '空闲', BUSY: '执行', CHARGING: '充电', FAULT: '故障' }
export const PRIORITY = { HIGH: '高', MEDIUM: '中', LOW: '低' }
export const TASK_TYPE = { TRANSPORT: '搬运', PICKING: '拣选', CHARGING: '充电' }
export const PHASE = {
  GOING_PICKUP: '前往取货', AT_PICKUP: '取货中',
  GOING_DELIVERY: '载货送达', AT_DELIVERY: '卸货中',
  GOING_CHARGER: '前往充电桩', CHARGING: '充电中', DONE: '完成'
}

/** 航向角（度）转罗盘方位 */
export function headingText(deg) {
  if (deg === null || deg === undefined || Number.isNaN(deg)) return '—'
  const dirs = ['东', '东南', '南', '西南', '西', '西北', '北', '东北']
  return dirs[Math.round((((deg % 360) + 360) % 360) / 45) % 8]
}

export function fmtTime(iso) {
  if (!iso) return '—'
  const d = new Date(iso)
  const p = (n) => String(n).padStart(2, '0')
  return `${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

export function fmtDeadline(iso) {
  if (!iso) return '无'
  const d = new Date(iso)
  const diffMin = Math.round((d - Date.now()) / 60000)
  const p = (n) => String(n).padStart(2, '0')
  let left
  if (diffMin < 0) left = `已逾期 ${-diffMin} 分钟`
  else if (diffMin < 60) left = `剩 ${diffMin} 分钟`
  else left = `剩 ${(diffMin / 60).toFixed(1)} 小时`
  return `${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}（${left}）`
}
