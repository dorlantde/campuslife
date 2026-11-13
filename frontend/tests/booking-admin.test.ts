import { describe, it, expect } from 'vitest'
import { bookingActions, bookingBody, canWriteReview } from '../src/orders/rules'
import { createPendingBooking, createPendingPrint } from '../src/orders/pending'
import { accountStatusBody, exceptionBody, reasonBody } from '../src/admin/rules'
import type { Account, BookingCreate, BookingSlots, OrderDetail, PrintCreate } from '../src/api/types'
const start = '2026-10-30T02:00:00Z', end = '2026-10-30T02:30:00Z', time = Date.parse(start)
const quote: BookingSlots = { serviceVersion: '9223372036854775806', priceAmount: 3000, durationMinutes: 30, slots: [{ startAt: start, endAt: end }] }
function order(status: OrderDetail['status'], occupancyStatus: 'ACTIVE' | 'HELD' | 'CONSUMED' | 'RELEASED' = 'ACTIVE'): OrderDetail {
  return { orderType: 'BOOKING', status, paymentStatus: status === 'PENDING_PAYMENT' ? 'NOT_PAID' : 'PAID', version: '9223372036854775806', reviewed: false, printing: null, booking: { serviceId: '9007199254740993', serviceName: '理发', barberId: '3', barberName: '理发师', startAt: start, endAt: end, durationMinutes: 30, occupancyStatus } } as OrderDetail
}
describe('预约候选与恢复协议', () => {
  it('保留候选原UTC、对应服务版本/金额，不提交结束时间或人员版本', () => {
    expect(bookingBody('9007199254740993', '4', start, quote, '备注')).toEqual({ serviceId: '9007199254740993', barberId: '4', startAt: start, expectedServiceVersion: quote.serviceVersion, expectedPayableAmount: 3000, note: '备注' })
  })
  it('非候选UTC及超长备注不能创建', () => {
    expect(() => bookingBody('1', '2', end, quote, '')).toThrow('候选')
    expect(() => bookingBody('1', '2', start, quote, '字'.repeat(301))).toThrow('300')
  })
  it('预约和打印各自保存/恢复，同账号同键也不混操作或正文', () => {
    const data = new Map<string, string>(), storage = { getItem: (k: string) => data.get(k) ?? null, setItem: (k: string, v: string) => { data.set(k, v) }, removeItem: (k: string) => { data.delete(k) } }
    const bookings = createPendingBooking(storage, () => 'same-key'), prints = createPendingPrint(storage, () => 'same-key')
    const body = bookingBody('1', '2', start, quote, '')
    bookings.start('9', body); prints.start('9', { fileId: '3' } as PrintCreate); body.startAt = end
    expect(bookings.read('9')!.body.startAt).toBe(start)
    expect(bookings.read('8')).toBeNull()
    expect(bookings.read('9')!.operation).toBe('CREATE_BOOKING')
    expect(prints.read('9')!.operation).toBe('CREATE_PRINT')
    bookings.clear(); expect(prints.read('9')).not.toBeNull()
    expect(() => createPendingBooking().start('9', {} as BookingCreate)).toThrow('不能提交')
  })
})
describe('预约动作的精确时间提示', () => {
  it.each([[-3600001, true], [-3600000, true], [-3599999, false], [0, false]])('学生取消相对开始%s毫秒', (delta, allowed) => {
    expect(bookingActions(order('ACCEPTED'), false, time + delta).includes('cancel')).toBe(allowed)
  })
  it.each([[-1, ['cancel']], [0, ['start']], [1799999, ['start']], [1800000, []]])('商家开始/取消相对开始%s毫秒', (delta, actions) => {
    expect(bookingActions(order('ACCEPTED'), true, time + Number(delta))).toEqual(actions)
  })
  it('服务中仅商家完成，保留期不变更；关闭不恢复', () => {
    expect(bookingActions(order('IN_SERVICE'), true, time)).toEqual(['complete'])
    expect(bookingActions(order('IN_SERVICE'), false, time)).toEqual([])
    expect(bookingActions(order('CLOSED', 'RELEASED'), true, time)).toEqual([])
    expect(bookingActions(order('ACCEPTED', 'RELEASED'), true, time)).toEqual([])
  })
  it('未支付/待接单动作保持，预约不出现打印取件', () => {
    expect(bookingActions(order('PENDING_PAYMENT', 'HELD'), false, time)).toEqual(['payments/mock', 'cancel'])
    expect(bookingActions(order('PENDING_ACCEPTANCE'), true, time)).toEqual(['accept', 'reject'])
    expect(bookingActions(order('PENDING_ACCEPTANCE'), false, time)).toEqual(['cancel'])
  })
  it('合法完成占用可评价；隐藏后reviewed为真仍不能重发', () => {
    const value = order('COMPLETED', 'CONSUMED')
    expect(canWriteReview(value)).toBe(true)
    expect(canWriteReview({ ...value, reviewed: true })).toBe(false)
    expect(canWriteReview(order('COMPLETED', 'RELEASED'))).toBe(false)
  })
})
describe('管理员核实/状态协议', () => {
  it.each(['PENDING_ACCEPTANCE', 'ACCEPTED', 'IN_SERVICE'] as const)('核实%s使用原字符串订单版本', status => {
    expect(exceptionBody(order(status), '已核实')).toEqual({ reason: '已核实', expectedVersion: '9223372036854775806' })
  })
  it.each(['PENDING_PAYMENT', 'COMPLETED', 'CLOSED'] as const)('不能核实退款%s', status => expect(() => exceptionBody(order(status), '原因')).toThrow('不能核实'))
  it('原因按Unicode码点和Java strip验证，正文不改写', () => {
    expect(reasonBody(' \n核实\n ')).toEqual({ reason: ' \n核实\n ' })
    expect(reasonBody('🙂'.repeat(300)).reason).toHaveLength(600)
    expect(() => reasonBody('🙂'.repeat(301))).toThrow()
    expect(() => reasonBody('\u3000\n')).toThrow()
    expect(reasonBody('\u00a0')).toEqual({ reason: '\u00a0' })
  })
  it('账号状态使用账号原版本，禁止本人包括同状态', () => {
    const account = { id: '9007199254740993', version: '9223372036854775806', status: 'ACTIVE' } as Account
    expect(accountStatusBody(account, '9', 'DISABLED')).toEqual({ status: 'DISABLED', expectedVersion: account.version })
    expect(() => accountStatusBody(account, account.id, 'ACTIVE')).toThrow('本人')
  })
  it('评价隐藏仅传原因，不添加版本或误用订单核实字段', () => expect(Object.keys(reasonBody('隐藏原因'))).toEqual(['reason']))
})
