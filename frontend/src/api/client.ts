import type { Envelope } from './types'
import type { SessionStore } from '../auth/session'
import { pdfFilename } from './files'

export interface RequestOptions { method?: string; body?: unknown; form?: FormData; auth?: boolean; signal?: AbortSignal; idempotencyKey?: string; response?: 'pdf' }
export interface PdfDownload { blob: Blob; filename: string }

export class ApiError extends Error {
  constructor(public status: number, public code: string, message: string, public traceId = '') { super(message) }
}
export class StaleResponse extends Error { constructor() { super('已忽略上一次会话的响应') } }
export function errorText(error: unknown): string {
  if (error instanceof StaleResponse || (error instanceof DOMException && error.name === 'AbortError')) return ''
  if (error instanceof ApiError) return `${error.message}（${error.code}${error.traceId ? ` · ${error.traceId}` : ''}）`
  return error instanceof Error ? error.message : '请求失败，请稍后重试。'
}

export function createClient(session: SessionStore, transport: typeof fetch = fetch, timeoutMs = 15000) {
  return async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
    if (!path.startsWith('/api/v1/') || path.includes('://')) throw new Error('请求路径无效')
    const epoch = session.generation.value
    const auth = options.auth !== false
    const headers = new Headers({ Accept: 'application/json' })
    if (auth) {
      const s = session.current.value
      if (!s) throw new ApiError(401, 'AUTH_REQUIRED', '请先登录')
      if (Date.parse(s.expiresAt) <= Date.now()) {
        session.clear('登录已过期，请重新登录。')
        throw new ApiError(401, 'SESSION_EXPIRED', '登录已过期，请重新登录')
      }
      headers.set('Authorization', `Bearer ${s.accessToken}`)
    }
    if (options.form && options.body !== undefined) throw new Error('不能同时发送JSON和文件')
    if (options.body !== undefined) headers.set('Content-Type', 'application/json')
    if (options.response === 'pdf') headers.set('Accept', 'application/pdf, application/json')
    if (options.idempotencyKey) {
      if (!/^[A-Za-z0-9_-]{1,64}$/.test(options.idempotencyKey)) throw new Error('创建键格式无效')
      headers.set('Idempotency-Key', options.idempotencyKey)
    }
    const controller = new AbortController()
    let timedOut = false
    const abort = () => controller.abort()
    options.signal?.addEventListener('abort', abort, { once: true })
    if (options.signal?.aborted) controller.abort()
    const timer = setTimeout(() => { timedOut = true; controller.abort() }, timeoutMs)
    try {
      const response = await transport(path, { method: options.method || 'GET', headers, body: options.form ?? (options.body === undefined ? undefined : JSON.stringify(options.body)), signal: controller.signal, cache: 'no-store', credentials: 'omit', redirect: 'error' })
      if (epoch !== session.generation.value) throw new StaleResponse()
      if (options.response === 'pdf' && response.ok) {
        if (response.status !== 200 || response.headers.get('content-type')?.split(';')[0]?.trim().toLowerCase() !== 'application/pdf') throw new ApiError(response.status, 'RESPONSE_INVALID', '下载未返回完整PDF，文件未保存')
        const blob = await response.blob()
        if (epoch !== session.generation.value) throw new StaleResponse()
        if (!blob.size) throw new ApiError(200, 'RESPONSE_INVALID', '下载文件为空，未保存')
        return { blob, filename: pdfFilename(response.headers.get('content-disposition')) } as T
      }
      if (!response.headers.get('content-type')?.includes('application/json')) throw new ApiError(response.status, 'RESPONSE_INVALID', '服务未返回有效数据，请确认后端已启动')
      const result = await response.json() as Envelope<T>
      if (epoch !== session.generation.value) throw new StaleResponse()
      if (!response.ok || result.code !== 'OK') {
        if (auth && (response.status === 401 || result.code === 'ACCOUNT_DISABLED')) session.clear('登录已失效或账号停用，请重新登录。')
        throw new ApiError(response.status, result.code || 'REQUEST_FAILED', result.message || '请求失败', result.traceId || '')
      }
      return result.data
    } catch (error) {
      if (epoch !== session.generation.value && !(error instanceof ApiError)) throw new StaleResponse()
      if (error instanceof ApiError || error instanceof StaleResponse) throw error
      if (options.signal?.aborted) throw new DOMException('已取消读取', 'AbortError')
      throw new ApiError(0, timedOut ? 'REQUEST_TIMEOUT' : 'NETWORK_ERROR', options.method && options.method !== 'GET'
        ? '网络中断或响应超时，操作可能已经提交。请先刷新核对，勿反复提交。'
        : '暂时无法连接服务，请确认后端已启动后重试。')
    } finally { clearTimeout(timer); options.signal?.removeEventListener('abort', abort) }
  }
}
