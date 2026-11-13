import { shallowRef, ref } from 'vue'
import type { Account, Session } from '../api/types'

export const SESSION_KEY = 'campuslife.session.v1'
type StoragePort = Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>
const roles = new Set(['STUDENT', 'MERCHANT', 'ADMIN'])
function valid(s: Session): boolean {
  return typeof s?.accessToken === 'string' && !!s.accessToken && typeof s.expiresAt === 'string'
    && Number.isFinite(Date.parse(s.expiresAt)) && s.account?.status === 'ACTIVE'
    && roles.has(s.account?.role) && typeof s.account.id === 'string'
    && typeof s.account.version === 'string' && typeof s.account.nickname === 'string' && typeof s.account.username === 'string'
}

export function createSession(storage?: StoragePort) {
  const current = shallowRef<Session | null>(null)
  const verified = ref(false)
  const generation = ref(0)
  const notice = ref('')
  const persistent = ref(!!storage)
  function persist(s: Session | null) {
    try { if (s) storage?.setItem(SESSION_KEY, JSON.stringify(s)); else storage?.removeItem(SESSION_KEY) }
    catch { persistent.value = false; notice.value = '浏览器未允许保存会话，刷新后可能需要重新登录。' }
  }
  function clear(message = '') {
    generation.value++
    current.value = null
    verified.value = false
    persist(null)
    notice.value = message
  }
  function set(s: Session) {
    if (!valid(s) || Date.parse(s.expiresAt) <= Date.now()) throw new Error('登录响应无效或已过期')
    generation.value++
    current.value = s
    verified.value = true
    notice.value = ''
    persist(s)
  }
  function update(account: Account, epoch: number) {
    if (generation.value !== epoch || !current.value || current.value.account.id !== account.id) return false
    if (account.status !== 'ACTIVE' || !roles.has(account.role)) { clear('账号已停用或身份已变化，请重新登录。'); return false }
    current.value = { ...current.value, account }
    verified.value = true
    persist(current.value)
    return true
  }
  try {
    const raw = storage?.getItem(SESSION_KEY)
    if (raw) {
      const s = JSON.parse(raw) as Session
      if (valid(s) && Date.parse(s.expiresAt) > Date.now()) current.value = s
      else clear('登录已过期，请重新登录。')
    }
  } catch { clear('本地会话不可用，请重新登录。') }
  return { current, verified, generation, notice, persistent, set, clear, update }
}
export type SessionStore = ReturnType<typeof createSession>
