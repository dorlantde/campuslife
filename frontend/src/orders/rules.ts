import type { PrintQuote, PrintCreate, PrintSpec, OrderDetail, BookingSlots, BookingCreate } from '../api/types'
export const statusNames = { PENDING_PAYMENT: '待支付', PENDING_ACCEPTANCE: '待接单', ACCEPTED: '已接单', IN_SERVICE: '履约中', COMPLETED: '已完成', CLOSED: '已关闭' } as const
export const paymentNames = { NOT_PAID: '未支付', PAID: '已支付', REFUNDED: '已退款' } as const
export const progressNames: Record<string, string> = { QUEUED: '等待打印', PRINTING: '打印中', READY_FOR_PICKUP: '待取件', PICKED_UP: '已取件', ABORTED: '已终止' }
export const actionNames: Record<string, string> = { CREATE: '创建订单', PAYMENT: '模拟支付', ACCEPT: '商家接单', START: '开始履约', COMPLETE: '完成服务', READY_FOR_PICKUP: '待取件', PICKUP: '学生取件', USER_CANCEL: '学生取消', PAYMENT_TIMEOUT: '支付超时关闭', ACCEPTANCE_TIMEOUT: '接单超时退款', MERCHANT_REJECT: '商家拒单', MERCHANT_UNAVAILABLE: '商家取消', ADMIN_EXCEPTION: '管理员核实关闭' }
export function positiveId(value: string, label = '文件'): string {
  if (!/^[1-9][0-9]{0,18}$/.test(value) || BigInt(value) > 9223372036854775807n) throw new Error(`请输入有效的${label}编号`)
  return value
}
export function bookingBody(serviceId: string, barberId: string, startAt: string, quote: BookingSlots, note: string): BookingCreate {
  if (!quote.slots.some(s => s.startAt === startAt)) throw new Error('请选择本次查询返回的候选时段')
  if ([...note].length > 300) throw new Error('备注最多300个字符')
  return { serviceId, barberId, startAt, expectedServiceVersion: quote.serviceVersion, expectedPayableAmount: quote.priceAmount, note }
}
export function bookingActions(order: OrderDetail, merchant: boolean, now = Date.now()): string[] {
  const b = order.booking
  if (order.orderType !== 'BOOKING' || !b) return []
  const paid = order.paymentStatus === 'PAID'
  if (merchant) {
    if (order.status === 'PENDING_ACCEPTANCE' && paid && b.occupancyStatus === 'ACTIVE') return ['accept', 'reject']
    if (order.status === 'ACCEPTED' && paid && b.occupancyStatus === 'ACTIVE') {
      if (now < Date.parse(b.startAt)) return ['cancel']
      if (now < Date.parse(b.endAt)) return ['start']
    }
    if (order.status === 'IN_SERVICE' && paid && b.occupancyStatus === 'ACTIVE') return ['complete']
  } else {
    if (order.status === 'PENDING_PAYMENT' && order.paymentStatus === 'NOT_PAID' && b.occupancyStatus === 'HELD') return ['payments/mock', 'cancel']
    if (order.status === 'PENDING_ACCEPTANCE' && paid && b.occupancyStatus === 'ACTIVE') return ['cancel']
    if (order.status === 'ACCEPTED' && paid && b.occupancyStatus === 'ACTIVE' && Date.parse(b.startAt) - now >= 3600000) return ['cancel']
  }
  return []
}
export function canWriteReview(order: OrderDetail): boolean {
  return order.status === 'COMPLETED' && order.paymentStatus === 'PAID' && !order.reviewed &&
    ((order.orderType === 'BOOKING' && order.booking?.occupancyStatus === 'CONSUMED') || (order.orderType === 'PRINT' && order.printing?.progress === 'PICKED_UP'))
}
export function copiesValue(value: string) {
  if (!/^[1-9][0-9]{0,2}$/.test(value) || Number(value) > 100) throw new Error('份数须为1～100的整数')
  return Number(value)
}
export function quoteMatches(quote: PrintQuote, spec: PrintSpec) {
  return (['serviceId', 'fileId', 'paperSize', 'colorMode', 'sideMode', 'copies'] as const).every(k => quote[k] === spec[k])
}
export function quoteRequest(value: PrintSpec): PrintSpec {
  return { serviceId: value.serviceId, fileId: value.fileId, paperSize: value.paperSize, colorMode: value.colorMode, sideMode: value.sideMode, copies: value.copies }
}
export function printBody(quote: PrintQuote, note: string): PrintCreate {
  if ([...note].length > 300) throw new Error('备注最多300个字符')
  return { serviceId: quote.serviceId, fileId: quote.fileId, paperSize: quote.paperSize, colorMode: quote.colorMode, sideMode: quote.sideMode, copies: quote.copies, expectedPriceVersion: quote.priceVersion, expectedPayableAmount: quote.payableAmount, note }
}
export function printActions(order: OrderDetail, merchant: boolean): string[] {
  if (order.orderType !== 'PRINT' || !order.printing) return []
  const paid = order.paymentStatus === 'PAID', queued = order.printing.progress === 'QUEUED'
  if (merchant) {
    if (paid && order.status === 'PENDING_ACCEPTANCE' && queued) return ['accept', 'reject']
    if (paid && order.status === 'ACCEPTED' && queued) return ['start', 'cancel']
    if (paid && order.status === 'IN_SERVICE' && order.printing.progress === 'PRINTING') return ['ready-for-pickup']
  } else {
    if (order.status === 'PENDING_PAYMENT' && order.paymentStatus === 'NOT_PAID') return ['payments/mock', 'cancel']
    if (order.status === 'PENDING_ACCEPTANCE' && paid) return ['cancel']
    if (order.status === 'IN_SERVICE' && paid && order.printing.progress === 'READY_FOR_PICKUP') return ['pickup']
  }
  return []
}
export function merchantCanDownload(order: OrderDetail): boolean {
  return order.orderType === 'PRINT' && order.paymentStatus === 'PAID' && !!order.printing &&
    ((order.status === 'ACCEPTED' && order.printing.progress === 'QUEUED') || (order.status === 'IN_SERVICE' && ['PRINTING', 'READY_FOR_PICKUP'].includes(order.printing.progress)))
}
