import { it, expect } from 'vitest'
import { money, campusTime, safeReturn } from '../src/shared/format'
it.each([[1, '0.01'], [100, '1.00'], [5000000000, '50000000.00'], [NaN, '—'], [1.5, '—']])('金额以整数分精确展示 %s', (value, expected) => expect(money(value)).toBe(expected))
it('校园日期不受浏览器本地日期影响', () => expect(campusTime('2026-10-28T18:30:00Z')).toContain('2026/10/29 02:30'))
it.each(['https://evil.example', '//evil.example', '/\\evil.example', '/login', ['/me'], '/session'])('拒绝不安全或循环的返回路径 %s', value => expect(safeReturn(value)).toBe('/workspace'))
it('接受本站受保护路径，交由路由再次验证身份', () => expect(safeReturn('/me')).toBe('/me'))
