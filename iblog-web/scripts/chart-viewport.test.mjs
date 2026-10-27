/**
 * K 线视口锁定测试。
 *
 * 直接 import 真实 TS 源文件（node --experimental-strip-types），不复制逻辑。
 *
 * 钉住的口径（改了就红）：
 * - 逻辑索引 i 的 K 线占 [i-0.5, i+0.5]，最后一根索引 dataLength-1，右边缘 = dataLength-0.5
 * - 缩放后保持原来的跨度（barSpacing 不变），只把右端挪到最后一根
 * - 已经贴边就不动（返回 null），避免每帧都 setVisibleLogicalRange 抖动
 * - 跨度已达到全览时交给 fitContent（返回 'fit'），否则 from 会算出小于 -0.5 的越界值
 */
import { test } from 'node:test'
import assert from 'node:assert/strict'

import { latestRightRange } from '../src/utils/chartViewport.ts'

/** 浮点减法会带出 -0.30000000000000004 这种尾差，用近似比较 */
function assertRange(actual, expected, label) {
  assert.ok(actual && actual !== 'fit', `${label}: 应该返回范围对象`)
  assert.ok(
    Math.abs(actual.from - expected.from) < 1e-9,
    `${label}: from 期望 ${expected.from}，实际 ${actual.from}`
  )
  assert.ok(
    Math.abs(actual.to - expected.to) < 1e-9,
    `${label}: to 期望 ${expected.to}，实际 ${actual.to}`
  )
}

test('把偏左的可见范围整体右移到最后一根', () => {
  // 100 根数据，当前只看前 50 根 → 右端要挪到 99.5，跨度保持 50
  assertRange(latestRightRange(100, { from: 0, to: 50 }), { from: 49.5, to: 99.5 }, '右移')
})

test('已经贴住右边缘时不调整', () => {
  assert.equal(latestRightRange(100, { from: 49.5, to: 99.5 }), null)
  // 差 0.005 视为同一位置，避免亚像素抖动反复 setVisibleLogicalRange
  assert.equal(latestRightRange(100, { from: 49.505, to: 99.505 }), null)
})

test('跨度已达到全部数据时交给 fitContent', () => {
  // fitContent 的范围是 [-0.5, dataLength-0.5]，跨度正好等于 dataLength
  assert.equal(latestRightRange(100, { from: -0.5, to: 99.5 }), 'fit')
  // 比全览还宽的越界范围同样按全览处理
  assert.equal(latestRightRange(100, { from: -5, to: 120 }), 'fit')
})

test('只有一根数据时右边缘是 0.5', () => {
  assertRange(latestRightRange(1, { from: 0, to: 0.8 }), { from: -0.3, to: 0.5 }, '单根')
})

test('数据为空或范围缺失时不动', () => {
  assert.equal(latestRightRange(0, { from: 0, to: 10 }), null)
  assert.equal(latestRightRange(-1, { from: 0, to: 10 }), null)
  assert.equal(latestRightRange(100, null), null)
  assert.equal(latestRightRange(100, undefined), null)
})

test('范围非法（倒置或零宽）时不动', () => {
  assert.equal(latestRightRange(100, { from: 50, to: 50 }), null)
  assert.equal(latestRightRange(100, { from: 60, to: 50 }), null)
  assert.equal(latestRightRange(100, { from: Number.NaN, to: 99.5 }), null)
})

test('放大后跨度变小，但右端仍贴最后一根', () => {
  // 放大到只看 10 根：from 从 -0.5 变成 89.5，to 恒为 99.5
  assertRange(latestRightRange(100, { from: 10, to: 20 }), { from: 89.5, to: 99.5 }, '放大')
})
