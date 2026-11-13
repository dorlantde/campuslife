import { ref, onScopeDispose } from 'vue'
import { ApiError, errorText } from '../api/client'

/** Scoped reads may be superseded; business writes are never automatically retried or cancelled. */
export function useLatestRead() {
  const loading = ref(false), error = ref('')
  let sequence = 0, controller: AbortController | undefined
  onScopeDispose(() => { sequence++; controller?.abort() })
  async function run<T>(load: (signal: AbortSignal) => Promise<T>, commit: (value: T) => void) {
    controller?.abort(); controller = new AbortController(); const seq = ++sequence
    loading.value = true; error.value = ''
    try { const value = await load(controller.signal); if (seq === sequence) { commit(value); return true } }
    catch (e) { if (seq === sequence) error.value = errorText(e) }
    finally { if (seq === sequence) loading.value = false }
    return false
  }
  return { loading, error, run }
}
export function useWrite() {
  const busy = ref(false), blocked = ref(false), error = ref(''), message = ref('')
  let live = true
  onScopeDispose(() => { live = false })
  function reset() { if (!busy.value) { blocked.value = false; error.value = ''; message.value = '' } }
  async function run<T>(work: () => Promise<T>, success = '已保存。'): Promise<T | undefined> {
    if (busy.value || blocked.value) return undefined
    busy.value = true; error.value = ''; message.value = ''
    try { const value = await work(); if (live) { message.value = success; return value } }
    catch (e) {
      if (live) {
        error.value = errorText(e)
        if (e instanceof ApiError && (e.status === 0 || ['CONFIG_VERSION_CONFLICT', 'SHOP_STATE_CONFLICT'].includes(e.code))) {
          blocked.value = true
          error.value += ' 请先重新读取并核对当前结果，再决定是否保存；不会自动重试。'
        }
      }
    } finally { busy.value = false }
    return undefined
  }
  return { busy, blocked, error, message, reset, run }
}
