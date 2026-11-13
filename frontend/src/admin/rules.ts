import type { Account, OrderDetail } from '../api/types'
export function reasonBody(reason: string) {
  // Match Java Character.isWhitespace/strip; NBSP is intentionally not stripped.
  const stripped = reason.replace(/^[\u0009-\u000d\u001c-\u0020\u1680\u2000-\u2006\u2008-\u200a\u2028\u2029\u205f\u3000]+|[\u0009-\u000d\u001c-\u0020\u1680\u2000-\u2006\u2008-\u200a\u2028\u2029\u205f\u3000]+$/g, '')
  if ([...stripped].length < 1 || [...stripped].length > 300) throw new Error('原因须为去首尾空白后1～300个字符')
  return { reason }
}
export function exceptionBody(order: OrderDetail, reason: string) {
  if (order.paymentStatus !== 'PAID' || !['PENDING_ACCEPTANCE', 'ACCEPTED', 'IN_SERVICE'].includes(order.status)) throw new Error('当前订单不能核实退款；已完成不可退款')
  return { ...reasonBody(reason), expectedVersion: order.version }
}
export function accountStatusBody(account: Account, actorId: string, status: Account['status']) {
  if (account.id === actorId) throw new Error('不能修改本人账号状态')
  return { status, expectedVersion: account.version }
}
