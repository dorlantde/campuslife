import type { PrintCreate, BookingCreate } from '../api/types'
type Port = Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>
export const PENDING_KEY = 'campuslife.pending-print.v1'
export interface PendingCreate<T, O extends string> { accountId: string; operation: O; key: string; body: T; state: 'pending' | 'rejected' | 'confirmed'; orderId?: string }
export type PendingPrint = PendingCreate<PrintCreate, 'CREATE_PRINT'>
export type PendingBooking = PendingCreate<BookingCreate, 'CREATE_BOOKING'>
// One frozen submission per tab; account + operation are checked before any manual replay.
export function createPendingStore<T, O extends string>(storage: Port | undefined, storageKey: string, operation: O, uuid: () => string = () => crypto.randomUUID()) {
  function read(accountId: string): PendingCreate<T, O> | null {
    try {
      const raw = storage?.getItem(storageKey)
      if (!raw) return null
      const item = JSON.parse(raw) as PendingCreate<T, O>
      if (item.accountId !== accountId || item.operation !== operation || !/^[A-Za-z0-9_-]{1,64}$/.test(item.key) || !item.body || !['pending', 'rejected', 'confirmed'].includes(item.state)) return null
      return item
    } catch { return null }
  }
  function save(item: PendingCreate<T, O>) {
    if (!storage) throw new Error('浏览器未允许保存下单恢复记录，暂不能提交；请允许本标签页存储后重试。')
    const snapshot = JSON.parse(JSON.stringify(item)) as PendingCreate<T, O>
    try { storage.setItem(storageKey, JSON.stringify(snapshot)) } catch { throw new Error('下单恢复记录保存失败，请检查浏览器存储；若先前已发送请求，请先核查订单。') }
    return snapshot
  }
  function start(accountId: string, body: T) {
    if (read(accountId)) throw new Error('请先核对原提交；不会为未确认结果自动创建新订单。')
    return save({ accountId, operation, key: uuid(), body, state: 'pending' })
  }
  function clear() { try { storage?.removeItem(storageKey) } catch { /* Inaccessible storage cannot be replayed. */ } }
  return { read, save, start, clear }
}
export function createPendingPrint(storage?: Port, uuid?: () => string) { return createPendingStore<PrintCreate, 'CREATE_PRINT'>(storage, PENDING_KEY, 'CREATE_PRINT', uuid) }
export function createPendingBooking(storage?: Port, uuid?: () => string) { return createPendingStore<BookingCreate, 'CREATE_BOOKING'>(storage, 'campuslife.pending-booking.v1', 'CREATE_BOOKING', uuid) }
let storage: Storage | undefined
try { storage = window.sessionStorage } catch { /* Fail closed when creating an order. */ }
export const pendingPrint = createPendingPrint(storage)
export const pendingBooking = createPendingBooking(storage)
export function hasUnconfirmedCreate(accountId: string) { return [pendingPrint.read(accountId), pendingBooking.read(accountId)].some(x => x && x.state !== 'confirmed') }
export function clearPendingCreates() { pendingPrint.clear(); pendingBooking.clear() }
