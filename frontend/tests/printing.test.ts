import { describe, it, expect, vi } from 'vitest'
import { createSession } from '../src/auth/session'
import { createClient, StaleResponse, type PdfDownload } from '../src/api/client'
import { pdfFilename } from '../src/api/files'
import { createPendingPrint, PENDING_KEY } from '../src/orders/pending'
import { copiesValue, merchantCanDownload, positiveId, printActions, printBody, quoteMatches, quoteRequest } from '../src/orders/rules'
import type { OrderDetail, PrintQuote, Session } from '../src/api/types'
const login = (): Session => ({ accessToken: 'synthetic-only', expiresAt: '2099-01-01T00:00:00Z', account: { id: '9007199254740993', role: 'STUDENT', nickname: '测试', username: 'test', status: 'ACTIVE', version: '0' } })
const quote: PrintQuote = { shopId: '1', serviceId: '2', fileId: '3', pageCount: 500, copies: 100, paperSize: 'A4', colorMode: 'COLOR', sideMode: 'DOUBLE', sheetCount: 25000, priceRuleId: '4', priceVersion: '9223372036854775806', unitPriceAmount: 100000, originalAmount: 5000000000, discountAmount: 0, payableAmount: 5000000000, currency: 'CNY' }
function store() { const values = new Map<string, string>(); return { getItem: (k: string) => values.get(k) ?? null, setItem: (k: string, v: string) => { values.set(k, v) }, removeItem: (k: string) => { values.delete(k) } } }
const json = (data: unknown, status = 200, code = 'OK') => new Response(JSON.stringify({ code, data, message: code, traceId: 'test-trace' }), { status, headers: { 'Content-Type': 'application/json' } })
describe('打印协议和恢复记录', () => {
  it.each(['1', '9007199254740993', '9223372036854775807'])('ID保持字符串%s', id => expect(positiveId(id)).toBe(id))
  it.each(['0', '01', '1e3', '9223372036854775808', '-1'])('拒绝非法文件编号%s', id => expect(() => positiveId(id)).toThrow())
  it.each(['0', '01', '1.5', '1e2', '101', ''])('拒绝非法份数%s', n => expect(() => copiesValue(n)).toThrow())
  it('份数边界、50亿分与原规格版本精确传输，不携带客户端页数', () => {
    expect(copiesValue('100')).toBe(100)
    expect(printBody(quote, '打印')).toEqual({ serviceId: '2', fileId: '3', copies: 100, paperSize: 'A4', colorMode: 'COLOR', sideMode: 'DOUBLE', expectedPriceVersion: '9223372036854775806', expectedPayableAmount: 5000000000, note: '打印' })
    expect(() => printBody(quote, '字'.repeat(301))).toThrow()
    expect(quoteMatches(quote, { ...quote, fileId: '5' })).toBe(false)
    expect(quoteMatches(quote, { ...quote, copies: 1 })).toBe(false)
  })
  it('发送前持久化冻结原键/正文，恢复不会生成新键', () => {
    const local = store(), key = vi.fn(() => 'fixed-original-key'), pending = createPendingPrint(local, key)
    const body = printBody(quote, '原备注'), saved = pending.start('9', body)
    body.note = '改过的草稿'
    expect(saved.body.note).toBe('原备注')
    expect(JSON.parse(local.getItem(PENDING_KEY)!)).toMatchObject({ accountId: '9', operation: 'CREATE_PRINT', key: 'fixed-original-key', state: 'pending' })
    const restored = createPendingPrint(local, () => 'different-key')
    expect(restored.read('9')!.key).toBe('fixed-original-key')
    expect(restored.read('8')).toBeNull()
    expect(() => restored.start('9', body)).toThrow('原提交')
    expect(key).toHaveBeenCalledTimes(1)
  })
  it('报价变化后只取原提交的六个规格字段重新报价，不混入创建专用字段', () => {
    expect(quoteRequest(printBody(quote, '原备注'))).toEqual({ serviceId: '2', fileId: '3', paperSize: 'A4', colorMode: 'COLOR', sideMode: 'DOUBLE', copies: 100 })
  })
  it('缺少/拒绝存储时不允许开始下单；明确清除后才生成下一键', () => {
    expect(() => createPendingPrint().start('9', printBody(quote, ''))).toThrow('不能提交')
    const local = store(), pending = createPendingPrint(local, () => 'test-key')
    pending.start('9', printBody(quote, '')); pending.clear(); expect(pending.read('9')).toBeNull()
    const denied = { ...local, setItem: () => { throw new Error('denied') } }
    expect(() => createPendingPrint(denied).start('9', printBody(quote, ''))).toThrow('保存失败')
  })
  it('确认或明确拒绝只更新状态，不改变原键正文；无自动重放', () => {
    const local = store(), pending = createPendingPrint(local, () => 'same')
    const item = pending.start('9', printBody(quote, ''))
    pending.save({ ...item, state: 'confirmed', orderId: '9223372036854775807' })
    expect(pending.read('9')).toMatchObject({ key: 'same', orderId: '9223372036854775807', body: item.body })
  })
})
describe('上传和受控PDF下载', () => {
  it('FormData不手写Content-Type或边界，保留Bearer', async () => {
    const session = createSession(); session.set(login())
    const transport = vi.fn<typeof fetch>().mockResolvedValue(json({ id: '3' })), form = new FormData()
    form.append('file', new Blob(['%PDF-test'], { type: 'application/pdf' }), '测试.pdf')
    await createClient(session, transport)('/api/v1/files', { method: 'POST', form })
    const init = transport.mock.calls[0]![1]!, headers = new Headers(init.headers)
    expect(init.body).toBe(form); expect(headers.has('Content-Type')).toBe(false); expect(headers.get('Authorization')).toBe('Bearer synthetic-only')
  })
  it('原创建键作为header传输，不放URL或正文', async () => {
    const session = createSession(); session.set(login()); const transport = vi.fn<typeof fetch>().mockResolvedValue(json({ id: '8' }))
    await createClient(session, transport)('/api/v1/orders/prints', { method: 'POST', body: printBody(quote, ''), idempotencyKey: 'original-key' })
    expect(transport.mock.calls[0]![0]).toBe('/api/v1/orders/prints')
    expect(new Headers(transport.mock.calls[0]![1]!.headers).get('Idempotency-Key')).toBe('original-key')
  })
  it('成功下载只接受完整PDF，安全解码UTF-8附件名', async () => {
    const session = createSession(); session.set(login())
    const raw = new Response('%PDF-real-test', { headers: { 'Content-Type': 'application/pdf', 'Content-Disposition': "attachment; filename*=UTF-8''%E8%B5%84%E6%96%99.pdf" } })
    const result = await createClient(session, vi.fn<typeof fetch>().mockResolvedValue(raw))<PdfDownload>('/api/v1/files/3/content', { response: 'pdf' })
    expect(result.filename).toBe('资料.pdf'); expect(await result.blob.text()).toBe('%PDF-real-test')
  })
  it.each([['application/json', '{}', 200], ['application/pdf', '', 200], ['application/pdf', '%PDF-partial', 206]])('拒绝错误类型/空/局部PDF %s %s %s', async (type, body, status) => {
    const session = createSession(); session.set(login())
    await expect(createClient(session, vi.fn<typeof fetch>().mockResolvedValue(new Response(body, { status, headers: { 'Content-Type': type } })))('/api/v1/files/3/content', { response: 'pdf' })).rejects.toMatchObject({ code: 'RESPONSE_INVALID' })
  })
  it('下载JSON错误保留code/trace，不保存为PDF；认证失效清会话', async () => {
    const session = createSession(); session.set(login())
    await expect(createClient(session, vi.fn<typeof fetch>().mockResolvedValue(json(null, 404, 'FILE_NOT_FOUND')))('/api/v1/files/3/content', { response: 'pdf' })).rejects.toMatchObject({ status: 404, code: 'FILE_NOT_FOUND', traceId: 'test-trace' })
    await expect(createClient(session, vi.fn<typeof fetch>().mockResolvedValue(json(null, 401, 'SESSION_REVOKED')))('/api/v1/files/3/content', { response: 'pdf' })).rejects.toMatchObject({ status: 401 })
    expect(session.current.value).toBeNull()
  })
  it('Blob尚未读取完成时切换账号，旧文件不得下载', async () => {
    const session = createSession(); session.set(login()); let resolve!: (v: Blob) => void
    const raw = new Response('%PDF-test', { headers: { 'Content-Type': 'application/pdf' } })
    vi.spyOn(raw, 'blob').mockReturnValue(new Promise(r => { resolve = r }))
    const request = createClient(session, vi.fn<typeof fetch>().mockResolvedValue(raw))('/api/v1/files/3/content', { response: 'pdf' })
    await Promise.resolve(); session.clear(); resolve(new Blob(['old']))
    await expect(request).rejects.toBeInstanceOf(StaleResponse)
  })
  it.each([null, "attachment; filename*=UTF-8''%ZZ", 'attachment; filename="../../evil.html"', 'attachment; filename=""'])('异常附件名安全回退%s', header => {
    const name = pdfFilename(header); expect(name).toMatch(/\.pdf$/i); expect(name).not.toMatch(/[\/\\\u0000-\u001f]/)
  })
})
describe('打印动作和商家内容权限提示', () => {
  const order = (status: OrderDetail['status'], progress: string, paymentStatus: OrderDetail['paymentStatus'] = 'PAID') => ({ orderType: 'PRINT', status, paymentStatus, printing: { progress } } as OrderDetail)
  it('学生不可取消已接单，只能在待取件确认；未付可付款/取消', () => {
    expect(printActions(order('PENDING_PAYMENT', 'QUEUED', 'NOT_PAID'), false)).toEqual(['payments/mock', 'cancel'])
    expect(printActions(order('ACCEPTED', 'QUEUED'), false)).toEqual([])
    expect(printActions(order('IN_SERVICE', 'READY_FOR_PICKUP'), false)).toEqual(['pickup'])
  })
  it('商家接单后才有下载，开始后无普通取消，待取件无商家完成按钮', () => {
    expect(merchantCanDownload(order('PENDING_ACCEPTANCE', 'QUEUED'))).toBe(false)
    expect(printActions(order('PENDING_ACCEPTANCE', 'QUEUED'), true)).toEqual(['accept', 'reject'])
    expect(merchantCanDownload(order('ACCEPTED', 'QUEUED'))).toBe(true)
    expect(printActions(order('IN_SERVICE', 'PRINTING'), true)).toEqual(['ready-for-pickup'])
    expect(printActions(order('IN_SERVICE', 'READY_FOR_PICKUP'), true)).toEqual([])
    expect(merchantCanDownload(order('COMPLETED', 'PICKED_UP'))).toBe(false)
    expect(merchantCanDownload(order('CLOSED', 'ABORTED', 'REFUNDED'))).toBe(false)
  })
  it('错配付款状态和预约不开放打印动作', () => {
    expect(printActions(order('ACCEPTED', 'QUEUED', 'REFUNDED'), true)).toEqual([])
    expect(printActions({ ...order('ACCEPTED', 'QUEUED'), orderType: 'BOOKING' }, true)).toEqual([])
  })
})
