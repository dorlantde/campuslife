export function money(cents: number): string {
  if (!Number.isSafeInteger(cents) || cents < 0) return '—'
  return `${Math.floor(cents / 100)}.${String(cents % 100).padStart(2, '0')}`
}
export function campusTime(utc: string): string {
  if (!Number.isFinite(Date.parse(utc))) return '—'
  return new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).format(new Date(utc))
}
export function safeReturn(value: unknown): string {
  return typeof value === 'string' && /^\/(?!\/)[A-Za-z0-9/?=&%_.:-]*$/.test(value) && !value.startsWith('/login') && !value.startsWith('/register') && !value.startsWith('/session') ? value : '/workspace'
}
export const roleName = { STUDENT: '学生', MERCHANT: '商家', ADMIN: '管理员' } as const
