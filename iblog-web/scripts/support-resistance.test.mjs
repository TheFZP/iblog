/**
 * 支撑 / 阻力位测试。
 *
 * 这里钉的是「结构性约定」而不是某组具体价格：聚类阈值、±25% 区间、
 * 每侧最多 3 条、支撑降序 / 阻力升序。这些一旦被改，页面上支撑位会跑到现价上方，
 * 属于肉眼很难第一时间发现的错。
 */
import { test } from 'node:test'
import assert from 'node:assert/strict'

import { calcSupportResistance } from '../src/utils/supportResistance.ts'

/** 造一段震荡行情：围绕 base 上下摆动，能稳定产生波段高低点 */
function oscillating(base, count) {
  const out = []
  for (let i = 0; i < count; i++) {
    const wave = Math.sin(i / 3) * base * 0.06
    const close = base + wave
    out.push({ high: close + base * 0.01, low: close - base * 0.01, close })
  }
  return out
}

test('少于 30 根直接返回空：样本太短的波段高低点只是噪声', () => {
  const out = calcSupportResistance(oscillating(10, 29))
  assert.deepEqual(out.support, [])
  assert.deepEqual(out.resistance, [])
})

test('非数组 / 空数组不抛异常', () => {
  assert.deepEqual(calcSupportResistance([]).support, [])
  assert.deepEqual(calcSupportResistance(null).support, [])
})

test('支撑位都在现价下方、阻力位都在现价上方', () => {
  const bars = oscillating(20, 120)
  const latest = bars[bars.length - 1].close
  const { support, resistance } = calcSupportResistance(bars)

  for (const s of support) {
    assert.ok(s.price <= latest, `支撑 ${s.price} 应不高于现价 ${latest}`)
  }
  for (const r of resistance) {
    assert.ok(r.price >= latest, `阻力 ${r.price} 应不低于现价 ${latest}`)
  }
})

test('每侧最多 3 条，支撑按价格降序、阻力按价格升序', () => {
  const { support, resistance } = calcSupportResistance(oscillating(20, 200))
  assert.ok(support.length <= 3)
  assert.ok(resistance.length <= 3)

  for (let i = 1; i < support.length; i++) {
    assert.ok(support[i - 1].price >= support[i].price, '支撑应从近到远（价格降序）')
  }
  for (let i = 1; i < resistance.length; i++) {
    assert.ok(resistance[i - 1].price <= resistance[i].price, '阻力应从近到远（价格升序）')
  }
})

test('只保留现价 ±25% 以内：再远的价位对短线没有意义', () => {
  const bars = oscillating(20, 200)
  const latest = bars[bars.length - 1].close
  const { support, resistance } = calcSupportResistance(bars)

  for (const s of support) {
    assert.ok(latest - s.price <= latest * 0.25 + 1e-9, '支撑超出 -25%')
  }
  for (const r of resistance) {
    assert.ok(r.price - latest <= latest * 0.25 + 1e-9, '阻力超出 +25%')
  }
})

test('波段结构不够时用斐波那契补足，并被标记 fib 比例、强度为 0', () => {
  // 单调上涨不产生波段低点，下方一定会走斐波那契补充
  const bars = []
  for (let i = 0; i < 120; i++) {
    const close = 10 + i * 0.05
    bars.push({ high: close + 0.02, low: close - 0.02, close })
  }
  const { support } = calcSupportResistance(bars)
  assert.ok(support.length > 0, '单调上涨也应能给出斐波那契支撑')
  const fibs = support.filter((s) => typeof s.fib === 'number')
  assert.ok(fibs.length > 0, '补充位必须带 fib 比例，否则界面无法区分它是算出来的还是补的')
  for (const f of fibs) {
    assert.equal(f.strength, 0, '斐波那契补充位没有被触及过，强度必须是 0')
    assert.ok(f.fib > 0 && f.fib < 1)
  }
})
