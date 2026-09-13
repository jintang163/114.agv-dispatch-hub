import { reactive } from 'vue'

export const toasts = reactive({ items: [] })
let seq = 0

export function toast(message, isError = false) {
  const id = ++seq
  toasts.items.push({ id, message, isError })
  setTimeout(() => {
    const i = toasts.items.findIndex(t => t.id === id)
    if (i >= 0) toasts.items.splice(i, 1)
  }, 3200)
}
