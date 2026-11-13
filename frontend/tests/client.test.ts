import { describe, it, expect, vi, afterEach } from 'vitest'
import { createClient, ApiError, StaleResponse } from '../src/api/client'
import { createSession, SESSION_KEY } from '../src/auth/session'
import type { Session } from '../src/api/types'

const stored = (): Session => ({ accessToken: 'isolated-test-only', expiresAt: '2099-01-01T00:00:00Z', account: { id: '9007199254740993', username: 'test_student', nickname: '星海同学', role: 'STUDENT', status: 'ACTIVE', version: '9223372036854775806' } })
function storage(initial?: string) { let value = initial ?? null; return { getItem: vi.fn(() => value), setItem: vi.fn((key: string, next: string) => { value = next }), removeItem: vi.fn(() => { value = null }) } }
const response = (data: unknown, code = 'OK', status = 200) => new Response(JSON.stringify({ code, data, message: code === 'OK' ? '成功' : '拒绝', traceId: 'trace-test' }), { status, headers: { 'Content-Type': 'application/json' } })
afterEach(() => vi.useRealTimers())

describe('标签页会话与响应归属', () => {
  it('恢复大整数字符串但不标为已验证', () => {
    const session = createSession(storage(JSON.stringify(stored())))
    expect(session.current.value?.account.id).toBe('9007199254740993')
    expect(session.current.value?.account.version).toBe('9223372036854775806')
    expect(session.verified.value).toBe(false)
  })
  it.each(['not-json', JSON.stringify({ ...stored(), expiresAt: '2020-01-01' }), JSON.stringify({ ...stored(), account: { ...stored().account, status: 'DISABLED' } })])('丢弃无效或失效的本地会话 %s', raw => {
    const local = storage(raw), session = createSession(local)
    expect(session.current.value).toBeNull(); expect(local.removeItem).toHaveBeenCalledWith(SESSION_KEY)
  })
  it('存储被拒绝仍可内存登录，提示刷新限制', () => {
    const local = storage(); local.setItem.mockImplementation(() => { throw new Error('denied') })
    const session = createSession(local); session.set(stored())
    expect(session.verified.value).toBe(true); expect(session.persistent.value).toBe(false); expect(session.notice.value).toContain('刷新')
  })
  it('旧账号更新无法覆盖新会话', () => {
    const session = createSession(storage()); session.set(stored()); const epoch = session.generation.value
    session.set({ ...stored(), account: { ...stored().account, id: '2', nickname: '新账号' } })
    expect(session.update({ ...stored().account, nickname: '旧响应' }, epoch)).toBe(false)
    expect(session.current.value?.account.nickname).toBe('新账号')
  })
  it('公开请求不携带Bearer，即使本地存在失效令牌', async () => {
    const session = createSession(storage()); session.set(stored())
    const transport = vi.fn<typeof fetch>().mockResolvedValue(response([])); const request = createClient(session, transport)
    await request('/api/v1/shops', { auth: false })
    expect(new Headers(transport.mock.calls[0]![1]!.headers).has('Authorization')).toBe(false)
    expect(transport.mock.calls[0]![1]!.credentials).toBe('omit')
    expect(transport.mock.calls[0]![1]!.redirect).toBe('error')
  })
  it('带当前Bearer读取，并保留错误code和traceId', async () => {
    const session = createSession(storage()); session.set(stored())
    const transport = vi.fn<typeof fetch>().mockResolvedValue(response(null, 'CONFIG_VERSION_CONFLICT', 409))
    await expect(createClient(session, transport)('/api/v1/me', { method: 'PATCH', body: { nickname: '新昵称', expectedVersion: '3' } })).rejects.toMatchObject({ code: 'CONFIG_VERSION_CONFLICT', traceId: 'trace-test' })
    expect(new Headers(transport.mock.calls[0]![1]!.headers).get('Authorization')).toBe('Bearer isolated-test-only')
    expect(session.current.value).not.toBeNull()
  })
  it.each([[401, 'SESSION_REVOKED'], [403, 'ACCOUNT_DISABLED']] as const)('失效身份清除会话 %s/%s', async (status, code) => {
    const session = createSession(storage()); session.set(stored())
    await expect(createClient(session, vi.fn<typeof fetch>().mockResolvedValue(response(null, code, status)))('/api/v1/me')).rejects.toBeInstanceOf(ApiError)
    expect(session.current.value).toBeNull()
  })
  it('普通角色不足403保留会话，登录错误不清理另一请求上下文', async () => {
    const session = createSession(storage()); session.set(stored())
    const request = createClient(session, vi.fn<typeof fetch>().mockResolvedValue(response(null, 'ROLE_FORBIDDEN', 403)))
    await expect(request('/api/v1/admin/accounts')).rejects.toMatchObject({ code: 'ROLE_FORBIDDEN' })
    expect(session.current.value).not.toBeNull()
  })
  it.each([200, 401])('切换账号后旧响应%s不能写入或退出新账号', async status => {
    const session = createSession(storage()); session.set(stored())
    let resolve!: (value: Response) => void
    const transport = vi.fn<typeof fetch>().mockReturnValue(new Promise(r => { resolve = r }))
    const pending = createClient(session, transport)('/api/v1/me')
    session.set({ ...stored(), account: { ...stored().account, id: '2' } })
    resolve(response(stored().account, status === 401 ? 'SESSION_EXPIRED' : 'OK', status))
    await expect(pending).rejects.toBeInstanceOf(StaleResponse)
    expect(session.current.value?.account.id).toBe('2')
  })
  it('响应头已返回但JSON尚未完成时也检查会话归属', async () => {
    const session = createSession(storage()); session.set(stored())
    let resolve!: (value: unknown) => void
    const raw = response({}); vi.spyOn(raw, 'json').mockReturnValue(new Promise(r => { resolve = r }))
    const pending = createClient(session, vi.fn<typeof fetch>().mockResolvedValue(raw))('/api/v1/me')
    await Promise.resolve(); session.clear(); resolve({ code: 'OK', data: stored().account })
    await expect(pending).rejects.toBeInstanceOf(StaleResponse)
  })
  it('已过期会话不再发网络请求', async () => {
    const session = createSession(storage()); session.set(stored()); vi.useFakeTimers(); vi.setSystemTime(new Date('2100-01-01'))
    const transport = vi.fn<typeof fetch>()
    await expect(createClient(session, transport)('/api/v1/me')).rejects.toMatchObject({ code: 'SESSION_EXPIRED' })
    expect(transport).not.toHaveBeenCalled(); expect(session.current.value).toBeNull()
  })
  it('HTML代理故障不被当作业务成功', async () => {
    const request = createClient(createSession(), vi.fn<typeof fetch>().mockResolvedValue(new Response('<html>Error</html>', { status: 502 })))
    await expect(request('/api/v1/shops', { auth: false })).rejects.toMatchObject({ code: 'RESPONSE_INVALID' })
  })
  it('写请求超时不自动重试，并说明结果不确定', async () => {
    const session = createSession(); session.set(stored()); vi.useFakeTimers()
    const transport = vi.fn<typeof fetch>().mockImplementation((_url, init) => new Promise((_r, reject) => { init?.signal?.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError'))) }))
    const pending = createClient(session, transport, 50)('/api/v1/me', { method: 'PATCH', body: {} })
    const assertion = expect(pending).rejects.toMatchObject({ code: 'REQUEST_TIMEOUT', message: expect.stringContaining('可能已经提交') })
    await vi.advanceTimersByTimeAsync(51); await assertion; expect(transport).toHaveBeenCalledTimes(1)
  })
  it('页面取消读取不会变成网络报错', async () => {
    const controller = new AbortController()
    const transport = vi.fn<typeof fetch>().mockImplementation((_url, init) => new Promise((_r, reject) => init?.signal?.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')))))
    const pending = createClient(createSession(), transport)('/api/v1/shops', { auth: false, signal: controller.signal }); controller.abort()
    await expect(pending).rejects.toMatchObject({ name: 'AbortError' })
  })
})
