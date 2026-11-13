import type { BusinessHour, MerchantShop, MerchantService, PrintPrice, TimeInterval } from '../api/types'
import { money } from '../shared/format'

export const approvalNames = { PENDING: '待审核', APPROVED: '已批准', REJECTED: '已拒绝', SUSPENDED: '已暂停' } as const
export const memberNames = { PENDING: '待激活', ACTIVE: '有效', DISABLED: '已停用' } as const
export const weekdays = ['周一', '周二', '周三', '周四', '周五', '周六', '周日']
export function copy<T>(value: T): T { return JSON.parse(JSON.stringify(value)) as T }

// Form input is decimal yuan; only integer cents cross the API boundary.
export function cents(text: string): number {
  const value = text.trim()
  if (!/^(0|[1-9][0-9]{0,3})(\.[0-9]{1,2})?$/.test(value)) throw new Error('金额须为最多两位小数的元，不接受指数或小数分。')
  const [whole, fraction = ''] = value.split('.')
  const amount = Number(whole) * 100 + Number(fraction.padEnd(2, '0'))
  if (amount < 1 || amount > 100000) throw new Error('金额须在 0.01～1000.00 元之间。')
  return amount
}
export function duration(text: string): number {
  if (!/^[0-9]+$/.test(text)) throw new Error('时长须为整数分钟。')
  const value = Number(text)
  if (value < 15 || value > 240 || value % 15 !== 0) throw new Error('理发时长须为 15～240 分钟，且为 15 的倍数。')
  return value
}
export interface ShopDraft { name: string; locationText: string; description: string; businessEnabled: boolean }
export function shopDraft(shop: MerchantShop): ShopDraft {
  return { name: shop.name, locationText: shop.locationText, description: shop.description, businessEnabled: shop.businessEnabled }
}
export function shopPatch(base: MerchantShop, draft: ShopDraft) {
  const result: Record<string, unknown> = { expectedVersion: base.version }
  for (const key of ['name', 'locationText', 'description', 'businessEnabled'] as const) if (base[key] !== draft[key]) result[key] = draft[key]
  return result
}
export interface ServiceDraft { name: string; description: string; price: string; duration: string; enabled: boolean }
export function serviceDraft(service?: MerchantService): ServiceDraft {
  return service ? { name: service.name, description: service.description, price: money(service.priceAmount), duration: String(service.durationMinutes ?? ''), enabled: service.enabled }
    : { name: '', description: '', price: '', duration: '30', enabled: false }
}
export function serviceBody(type: 'HAIRCUT' | 'PRINT', draft: ServiceDraft, base?: MerchantService) {
  const fields = { name: draft.name, description: draft.description, priceAmount: cents(draft.price), durationMinutes: type === 'PRINT' ? null : duration(draft.duration), enabled: draft.enabled }
  if (!base) return { serviceType: type, ...fields }
  const patch: Record<string, unknown> = { expectedVersion: base.version }
  for (const key of ['name', 'description', 'priceAmount', 'durationMinutes', 'enabled'] as const) if (fields[key] !== base[key]) patch[key] = fields[key]
  return patch
}
export function checkIntervals(rows: TimeInterval[], aligned = false) {
  if (rows.length > 4) throw new Error('每天最多 4 段时段。')
  const pattern = aligned ? /^([01][0-9]|2[0-3]):(00|15|30|45)$/ : /^([01][0-9]|2[0-3]):[0-5][0-9]$/
  for (const row of rows) if (!pattern.test(row.startTime) || !pattern.test(row.endTime) || row.startTime >= row.endTime) throw new Error(aligned ? '排班须为15分钟对齐的有效时段，开始早于结束。' : '营业时段须为有效的HH:mm，开始早于结束。')
  // The server sorts/merges adjacent intervals and checks overlap and existing bookings.
  return copy(rows)
}
export function weeklyBody(version: string, days: Record<number, TimeInterval[]>) {
  const intervals: BusinessHour[] = []
  for (let weekday = 1; weekday <= 7; weekday++) for (const row of checkIntervals(days[weekday] || [])) intervals.push({ weekday, ...row })
  return { expectedVersion: version, intervals }
}
export interface PriceDraft { colorMode: 'BW' | 'COLOR'; sideMode: 'SINGLE' | 'DOUBLE'; include: boolean; price: string; enabled: boolean; expectedVersion: string | null }
export function priceDrafts(prices: PrintPrice[]): PriceDraft[] {
  return (['BW', 'COLOR'] as const).flatMap(colorMode => (['SINGLE', 'DOUBLE'] as const).map(sideMode => {
    const p = prices.find(p => p.colorMode === colorMode && p.sideMode === sideMode && p.paperSize === 'A4')
    return { colorMode, sideMode, include: !!p, price: p ? money(p.pricePerImpression) : '', enabled: p?.enabled ?? false, expectedVersion: p?.version ?? null }
  }))
}
export function priceBody(rows: PriceDraft[]) {
  const selected = rows.filter(row => row.include)
  if (selected.length < 1 || selected.length > 4) throw new Error('请勾选本次保存的1～4种规格；未勾选的保留。')
  return { rules: selected.map(row => ({ paperSize: 'A4', colorMode: row.colorMode, sideMode: row.sideMode, pricePerImpression: cents(row.price), enabled: row.enabled, expectedVersion: row.expectedVersion })) }
}
export function campusDates(now = new Date()): string[] {
  const parts = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit' }).formatToParts(now)
  const part = (name: string) => parts.find(p => p.type === name)!.value
  const day = Date.UTC(Number(part('year')), Number(part('month')) - 1, Number(part('day')))
  return Array.from({ length: 7 }, (_, i) => new Date(day + i * 86400000).toISOString().slice(0, 10))
}
