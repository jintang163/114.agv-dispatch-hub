import { reactive } from 'vue'
import mqtt from 'mqtt'

/**
 * 全局实时状态：经 EMQX WebSocket 订阅
 *   fleet/state   调度中心 1s 全量快照（retained）
 *   fleet/events  调度事件流
 */
export const realtime = reactive({
  connected: false,
  robots: [],
  events: [],
  lastTs: null
})

let client = null

export function connectMqtt() {
  if (client) return
  const url = `${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}/mqtt`
  client = mqtt.connect(url, {
    clientId: 'web-' + Math.random().toString(16).slice(2, 10),
    reconnectPeriod: 3000,
    clean: true
  })

  client.on('connect', () => {
    realtime.connected = true
    client.subscribe('fleet/state', { qos: 0 })
    client.subscribe('fleet/events', { qos: 0 })
  })
  client.on('reconnect', () => { realtime.connected = false })
  client.on('close', () => { realtime.connected = false })
  client.on('error', () => { realtime.connected = false })

  client.on('message', (topic, payload) => {
    try {
      const msg = JSON.parse(payload.toString())
      if (topic === 'fleet/state') {
        realtime.robots = msg.robots || []
        realtime.lastTs = msg.ts
      } else if (topic === 'fleet/events') {
        realtime.events.unshift(msg)
        if (realtime.events.length > 200) realtime.events.length = 200
      }
    } catch (e) {
      /* ignore malformed */
    }
  })
}

export function robotByCode(code) {
  return realtime.robots.find(r => r.code === code)
}
