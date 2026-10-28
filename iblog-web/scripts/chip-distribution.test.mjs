/**
 * 筹码分布测试。
 *
 * 筹码是「估算」，但不能是「随便算」：这里钉住几条会让结果彻底失去意义的约定 ——
 * 数据不足返回 null（而不是一张空图）、占比归一化到 1、峰就是最大占比那一档、
 * 现价落在最高/最低档之外时不能崩、窄区间要收紧档数避免价格标签重复。
 */
import { test } from 'node:test'
import assert from 'node:assert/strict'

import {
  CHIP_MAX_BARS,
  calcChipDistribution,
  chipOptionsFromQuote,
} from '../src/utils/chipDistribution.ts'

/** 造 K 线：价格在中枢附近波动，量能稳定 */
function bars(count, center = 20, { volume = 1000 } = {}) {
  const out = []
  for (let i = 0; i < count; i++) {
    const wave = Math.sin(i / 4) * center * 0.03
    const close = center + wave
    out.push({ high: close * 1.01, low: close * 0.99, close, volume })
  }
  return out
}

test('少于 5 根返回 null：数据不够时宁可说「算不出」也不画空图', () => {
  assert.equal(calcChipDistribution(bars(4)), null)
  assert.equal(calcChipDistribution([]), null)
  assert.equal(calcChipDistribution(null), null)
})

test('价格区间退化（全是一字板同一价）时返回 null', () => {
  const flat = Array.from({ length: 30 }, () => ({ high: 10, low: 10, close: 10, volume: 500 }))
  assert.equal(calcChipDistribution(flat), null)
})

test('占比之和归一化为 1，平均成本落在价格区间内', () => {
  const dist = calcChipDistribution(bars(200))
  assert.ok(dist, '正常数据应能算出分布')
  const sum = dist.distribution.reduce((s, p) => s + p.ratio, 0)
  assert.ok(Math.abs(sum - 1) < 1e-6, `占比之和应为 1，实际 ${sum}`)

  const lowest = dist.distribution[0].price
  const highest = dist.distribution[dist.distribution.length - 1].price
  assert.ok(dist.avgCost >= lowest && dist.avgCost <= highest, '平均成本必须在价格区间内')
  assert.ok(dist.currentPrice > 0)
})

test('峰值就是占比最大的那一档，且 peakRatio 与它一致', () => {
  const dist = calcChipDistribution(bars(200))
  const max = Math.max(...dist.distribution.map((p) => p.ratio))
  assert.ok(Math.abs(dist.peakRatio - max) < 1e-9, 'peakRatio 必须等于分布里的最大占比')
  const peak = dist.distribution.find((p) => Math.abs(p.price - dist.peakPrice) < 1e-9)
  assert.ok(peak, 'peakPrice 必须能在分布里找到')
  assert.ok(Math.abs(peak.ratio - dist.peakRatio) < 1e-9)
})

test('窄幅区间自动收紧档数，价格标签不重复', () => {
  // 1 元附近 2% 的振幅，固定 60 档会得到 < 0.01 的步长
  const narrow = bars(60, 1.0)
  const dist = calcChipDistribution(narrow, { steps: 60 })
  assert.ok(dist)
  const prices = dist.distribution.map((p) => p.price)
  for (let i = 1; i < prices.length; i++) {
    assert.ok(prices[i] > prices[i - 1], `相邻档位价格必须严格递增：${prices[i - 1]} → ${prices[i]}`)
  }
})

test('换手率衰减：给了换手率才 decayed，没给就不衰减', () => {
  const without = calcChipDistribution(bars(200))
  assert.equal(without.decayed, false)
  assert.equal(without.decayTurnoverRate, null)

  const withRate = calcChipDistribution(bars(200), { turnoverRate: 3 })
  assert.equal(withRate.decayed, true)
  assert.equal(withRate.decayTurnoverRate, 3)
})

test('衰减系数为 0 时不衰减（等价于关闭）', () => {
  const out = calcChipDistribution(bars(200), { turnoverRate: 3, decayCoefficient: 0 })
  assert.equal(out.decayed, false)
})

test('衰减让历史筹码让位给近期筹码：平均成本更靠近现价', () => {
  // 先低后高：不衰减时平均成本被大量低价筹码拉低；衰减后近期（高价）占比更大
  const rising = []
  for (let i = 0; i < 200; i++) {
    const close = 10 + i * 0.05
    rising.push({ high: close * 1.01, low: close * 0.99, close, volume: 1000 })
  }
  const plain = calcChipDistribution(rising)
  const decay = calcChipDistribution(rising, { turnoverRate: 8 })
  assert.ok(decay.avgCost > plain.avgCost, '衰减后平均成本应更靠近近期高价')
})

test('只取最近 CHIP_MAX_BARS 根，且成本分位单调递增', () => {
  const dist = calcChipDistribution(bars(CHIP_MAX_BARS + 120))
  assert.ok(dist)
  const { COST5, COST15, COST50, COST85, COST95 } = dist.costLevels
  const seq = [COST5, COST15, COST50, COST85, COST95]
  for (let i = 1; i < seq.length; i++) {
    assert.ok(seq[i] >= seq[i - 1], `成本分位必须随分位上升而升高：${seq.join(' / ')}`)
  }
})

test('steps 非法值被钳制，不产生 NaN', () => {
  for (const steps of [0, -5, NaN, 1e9]) {
    const dist = calcChipDistribution(bars(120), { steps })
    assert.ok(dist, `steps=${steps} 应仍能算出结果`)
    assert.ok(dist.distribution.every((p) => Number.isFinite(p.price) && Number.isFinite(p.ratio)))
  }
})

test('chipOptionsFromQuote 只在日 K 且换手率有效时返回参数', () => {
  assert.deepEqual(chipOptionsFromQuote(3.5, 'day'), { turnoverRate: 3.5 })
  assert.deepEqual(chipOptionsFromQuote(0, 'day'), {})
  assert.deepEqual(chipOptionsFromQuote(null, 'day'), {})
  assert.deepEqual(chipOptionsFromQuote(3.5, 'week'), {}, '周 K 的量与日换手率不同口径，不能混用')
})
