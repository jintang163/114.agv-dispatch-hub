import axios from 'axios'

const api = axios.create({ baseURL: '/api', timeout: 10000 })

export const TaskAPI = {
  list: (status) => api.get('/tasks', { params: status ? { status } : {} }).then(r => r.data),
  get: (id) => api.get(`/tasks/${id}`).then(r => r.data),
  events: (id) => api.get(`/tasks/${id}/events`).then(r => r.data),
  create: (body) => api.post('/tasks', body).then(r => r.data),
  setPriority: (id, priority) => api.put(`/tasks/${id}/priority`, { priority }).then(r => r.data),
  cancel: (id) => api.post(`/tasks/${id}/cancel`),
  reassign: (id, robotCode) => api.post(`/tasks/${id}/reassign`, robotCode ? { robotCode } : {})
}

export const RobotAPI = {
  list: () => api.get('/robots').then(r => r.data),
  fault: (code, reason) => api.post(`/robots/${code}/fault`, { reason }),
  recover: (code) => api.post(`/robots/${code}/recover`)
}

export const MapAPI = {
  get: () => api.get('/map').then(r => r.data),
  blockEdge: (from, to, blocked) => api.put('/map/edges/block', { from, to, blocked })
}

export const StatsAPI = {
  overview: () => api.get('/stats/overview').then(r => r.data),
  trend: () => api.get('/stats/completion-trend').then(r => r.data),
  recentEvents: () => api.get('/stats/recent-events').then(r => r.data)
}

export const QueueAPI = {
  view: () => api.get('/tasks/queue/view').then(r => r.data.queue || [])
}
