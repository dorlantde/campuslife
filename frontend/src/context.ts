import { createSession } from './auth/session'
import { createClient } from './api/client'
import type { Account, LoginView } from './api/types'
let storage: Storage | undefined
try { storage = window.sessionStorage } catch { /* Memory-only use remains possible. */ }
export const session = createSession(storage)
export const api = createClient(session)
let verification: { epoch: number; promise: Promise<boolean> } | undefined
export function verifySession(force = false): Promise<boolean> {
  if (!session.current.value) return Promise.resolve(false)
  if (Date.parse(session.current.value.expiresAt) <= Date.now()) { session.clear('登录已过期，请重新登录。'); return Promise.resolve(false) }
  if (session.verified.value && !force) return Promise.resolve(true)
  const epoch = session.generation.value
  if (verification?.epoch === epoch) return verification.promise
  const promise = api<Account>('/api/v1/me').then(account => session.update(account, epoch)).finally(() => { if (verification?.epoch === epoch) verification = undefined })
  verification = { epoch, promise }
  return promise
}
export async function login(username: string, password: string) {
  const result = await api<LoginView>('/api/v1/auth/login', { method: 'POST', body: { username, password }, auth: false })
  session.set(result)
}
