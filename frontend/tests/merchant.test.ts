import { describe, it, expect, vi } from 'vitest'
import { effectScope as vueScope } from 'vue'
import { ApiError } from '../src/api/client'
import { cents, serviceBody, shopPatch, priceBody, priceDrafts, weeklyBody, checkIntervals, campusDates } from '../src/merchant/forms'
import { useLatestRead, useWrite } from '../src/merchant/useRequests'
import type { MerchantShop, MerchantService } from '../src/api/types'
import { createDiscardConfirmation } from '../src/shared/discard'

const version = '9223372036854775806'
const shop: MerchantShop = { id: '9007199254740993', name: '星海理发', category: { id: '1', code: 'HAIRCUT', name: '理发' }, locationText: '校园101', description: '介绍', campusCode: 'XINGHAI_MAIN', approvalStatus: 'APPROVED', approvalReason: null, businessEnabled: false, memberStatus: 'ACTIVE', version, businessHours: [] }

describe('经营表单的接口边界', () => {
  it.each([['0.01', 1], ['1.2', 120], ['1000.00', 100000], ['20', 2000]])('元输入%s精确变为整数分', (text, expected) => expect(cents(text)).toBe(expected))
  it.each(['0', '-1', '1e2', '0.001', '1000.01', '', 'NaN'])('拒绝非法金额%s', text => expect(() => cents(text)).toThrow())
  it('PATCH只带变更字段和原字符串版本；空介绍显式清空', () => {
    expect(shopPatch(shop, { name: shop.name, locationText: shop.locationText, description: '', businessEnabled: true })).toEqual({ expectedVersion: version, description: '', businessEnabled: true })
    expect(shopPatch(shop, { name: shop.name, locationText: shop.locationText, description: shop.description, businessEnabled: false })).toEqual({ expectedVersion: version })
  })
  it('PRINT创建显式null时长，HAIRCUT必须合法时长', () => {
    const draft = { name: '打印', description: '', price: '0.20', duration: '', enabled: false }
    expect(serviceBody('PRINT', draft)).toMatchObject({ serviceType: 'PRINT', durationMinutes: null, priceAmount: 20, enabled: false })
    expect(() => serviceBody('HAIRCUT', { ...draft, duration: '20' })).toThrow('15')
  })
  it('服务修改不能误用店铺版本或发送未改字段', () => {
    const service: MerchantService = { id: '4', serviceType: 'HAIRCUT', name: '剪发', description: '', priceAmount: 3000, durationMinutes: 30, enabled: false, version: '17' }
    expect(serviceBody('HAIRCUT', { name: '剪发', description: '', price: '30.00', duration: '30', enabled: true }, service)).toEqual({ expectedVersion: '17', enabled: true })
  })
  it('整周完整替换及清空，不使用人员版本字段', () => {
    expect(weeklyBody(version, { 1: [{ startTime: '09:00', endTime: '18:00' }], 2: [] })).toEqual({ expectedVersion: version, intervals: [{ weekday: 1, startTime: '09:00', endTime: '18:00' }] })
    expect(weeklyBody(version, {})).toEqual({ expectedVersion: version, intervals: [] })
  })
  it('价目新规格显式null版本，遗漏已配置规格不进入请求', () => {
    const rows = priceDrafts([{ id: '7', paperSize: 'A4', colorMode: 'BW', sideMode: 'SINGLE', pricePerImpression: 20, enabled: true, version }])
    rows[0]!.include = false; rows[1]!.include = true; rows[1]!.price = '0.30'; rows[1]!.enabled = true
    expect(priceBody(rows)).toEqual({ rules: [{ paperSize: 'A4', colorMode: 'BW', sideMode: 'DOUBLE', pricePerImpression: 30, enabled: true, expectedVersion: null }] })
    rows[1]!.include = false; expect(() => priceBody(rows)).toThrow('勾选')
  })
  it('已有价目保留独立字符串版本，停用通过enabled=false而非遗漏', () => {
    const rows = priceDrafts([{ id: '7', paperSize: 'A4', colorMode: 'COLOR', sideMode: 'DOUBLE', pricePerImpression: 150, enabled: true, version }])
    rows[3]!.enabled = false
    expect(priceBody(rows).rules[0]).toMatchObject({ expectedVersion: version, pricePerImpression: 150, enabled: false })
  })
  it('排班输入须15分钟对齐，空数组允许清空，返回独立副本', () => {
    const rows = [{ startTime: '10:00', endTime: '12:00' }]
    expect(checkIntervals(rows, true)).toEqual(rows); expect(checkIntervals(rows, true)).not.toBe(rows)
    expect(checkIntervals([], true)).toEqual([])
    expect(() => checkIntervals([{ startTime: '10:10', endTime: '12:00' }], true)).toThrow()
    expect(() => checkIntervals(Array(5).fill(rows[0]))).toThrow('最多')
  })
  it('七日按上海日期跨月/年，纽约前一日不影响', () => {
    expect(campusDates(new Date('2026-12-31T18:30:00Z'))).toEqual(['2027-01-01', '2027-01-02', '2027-01-03', '2027-01-04', '2027-01-05', '2027-01-06', '2027-01-07'])
  })
})

describe('配置请求的并发与不确定结果', () => {
  it('草稿确认须明确选择，重复请求不会覆盖等待中的选择', async () => {
    const choice = createDiscardConfirmation()
    const first = choice.ask('切换会丢弃草稿')
    expect(choice.pending.value?.message).toBe('切换会丢弃草稿')
    await expect(choice.ask('另一导航')).resolves.toBe(false)
    choice.settle(false); await expect(first).resolves.toBe(false); expect(choice.pending.value).toBeNull()
    const next = choice.ask('明确丢弃'); choice.settle(true); await expect(next).resolves.toBe(true)
  })
  it('后发读取优先；旧响应和组件销毁后的响应不提交', async () => {
    const scope = vueScope(); const reader = scope.run(() => useLatestRead())!
    let first!: (x: string) => void; const commit = vi.fn()
    const pending = reader.run(() => new Promise<string>(resolve => { first = resolve }), commit)
    await reader.run(async () => 'new', commit); first('old'); await pending
    expect(commit.mock.calls).toEqual([['new']])
    let last!: (x: string) => void
    const disposed = reader.run(() => new Promise<string>(resolve => { last = resolve }), commit); scope.stop(); last('disposed'); await disposed
    expect(commit).toHaveBeenCalledTimes(1)
  })
  it.each([new ApiError(409, 'CONFIG_VERSION_CONFLICT', '旧版本'), new ApiError(409, 'SHOP_STATE_CONFLICT', '已审核'), new ApiError(0, 'REQUEST_TIMEOUT', '超时')])('冲突或网络结果不确定后阻止直接重复写入', async failure => {
    const scope = vueScope(), writer = scope.run(() => useWrite())!
    const call = vi.fn().mockRejectedValue(failure)
    await writer.run(call); await writer.run(call)
    expect(call).toHaveBeenCalledTimes(1); expect(writer.blocked.value).toBe(true)
    writer.reset(); await writer.run(async () => ({ saved: true }))
    expect(writer.message.value).toBe('已保存。'); scope.stop()
  })
  it('明确的排班保护失败保留重试能力，不自动重试', async () => {
    const scope = vueScope(), writer = scope.run(() => useWrite())!
    const call = vi.fn().mockRejectedValue(new ApiError(409, 'SCHEDULE_CONFLICT', '已有排班'))
    await writer.run(call); expect(call).toHaveBeenCalledTimes(1); expect(writer.blocked.value).toBe(false)
    expect(writer.error.value).toContain('SCHEDULE_CONFLICT'); scope.stop()
  })
  it('保存进行中再次提交无效，卸载后不产生成功提示', async () => {
    const scope = vueScope(), writer = scope.run(() => useWrite())!
    let complete!: (x: string) => void
    const call = vi.fn(() => new Promise<string>(resolve => { complete = resolve }))
    const pending = writer.run(call); await writer.run(call); expect(call).toHaveBeenCalledTimes(1)
    scope.stop(); complete('ok'); await pending; expect(writer.message.value).toBe('')
  })
})
