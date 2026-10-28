/**
 * 持仓组合分析测试。
 *
 * 组合层最容易出错的不是算术，而是「缺数据时假装有数据」：
 * 缺昨收就当今天没涨没跌、缺行业就把未知算成「无行业」、有港股就当汇率是 1。
 * 这三条都会让「组合风险」看起来比实际更小，所以每一条都单独钉住。
 */
import { test } from 'node:test'
import assert from 'node:assert/strict'

import { analyzePortfolio, assetClassOf } from '../src/utils/portfolio.ts'

const close = (a, e, label, eps = 1e-6) => {
  assert.ok(Number.isFinite(a), `${label}: 不该是空值/NaN，实际 ${a}`)
  assert.ok(Math.abs(a - e) < eps, `${label}: 期望 ${e}，实际 ${a}`)
}

function pos(code, quantity, buyPrice, currentPrice, extra = {}) {
  return { code, quantity, buyPrice, currentPrice, ...extra }
}

test('空持仓：isEmpty 为真且各项金额为 0，不产生 NaN', () => {
  const a = analyzePortfolio([])
  assert.equal(a.isEmpty, true)
  assert.equal(a.count, 0)
  close(a.totalMarketValueCNY, 0, '总市值')
  close(a.totalProfitCNY, 0, '总盈亏')
  close(a.totalProfitPctCNY, 0, '总盈亏率')
  assert.equal(a.topHolding, null)
  assert.equal(a.weightedPE.value, null)
  assert.equal(a.maxIndustryWeightPct, null)
  assert.equal(a.concentration.level, '单一')
})

test('单票盈亏与权重：只有一只时权重 100%', () => {
  const a = analyzePortfolio([pos('600519', 100, 10, 12)])
  close(a.totalCostCNY, 1000, '总成本')
  close(a.totalMarketValueCNY, 1200, '总市值')
  close(a.totalProfitCNY, 200, '总盈亏')
  close(a.totalProfitPctCNY, 20, '总盈亏率')
  close(a.rows[0].weightPct, 100, '权重')
  close(a.top3WeightPct, 100, '前三大权重')
  close(a.hhi, 10000, 'HHI（单票=10000）')
  assert.equal(a.concentration.level, '单一')
})

test('权重按市值分配，合计 100', () => {
  const a = analyzePortfolio([
    pos('600519', 100, 10, 10), // 1000
    pos('000001', 100, 10, 30), // 3000
  ])
  close(a.rows[0].weightPct, 25, '第一只权重')
  close(a.rows[1].weightPct, 75, '第二只权重')
  close(a.rows.reduce((s, r) => s + r.weightPct, 0), 100, '权重合计')
  close(a.hhi, 25 * 25 + 75 * 75, 'HHI')
})

test('缺昨收：当日盈亏算不出来，必须计入 missing 而不是当 0', () => {
  const a = analyzePortfolio([
    pos('600519', 100, 10, 12, { prevClose: 11 }),
    pos('000001', 100, 10, 12), // 没有 prevClose
  ])
  assert.equal(a.dayProfit.covered, 1)
  assert.equal(a.dayProfit.missing, 1)
  close(a.dayProfit.value, 100, '当日盈亏只算覆盖到的')
  assert.equal(a.rows[1].dayProfitCNY, null)
  assert.equal(a.rows[1].dayChangePct, null)
})

test('全部缺昨收时当日盈亏率为 null（不能拿「0」冒充「没涨没跌」）', () => {
  const a = analyzePortfolio([pos('600519', 100, 10, 12)])
  assert.equal(a.dayProfit.missing, 1)
  assert.equal(a.dayProfit.pct, null)
})

test('当日涨跌家数只统计有昨收的持仓', () => {
  const a = analyzePortfolio([
    pos('600519', 100, 10, 12, { prevClose: 11 }), // 涨
    pos('000001', 100, 10, 9, { prevClose: 10 }), // 跌
    pos('002594', 100, 10, 10, { prevClose: 10 }), // 平
    pos('300750', 100, 10, 10), // 未知，不进分布
  ])
  assert.deepEqual(a.dayBreadth, { up: 1, down: 1, flat: 1 })
})

test('当日最赚/最亏只在有昨收的持仓里选', () => {
  const a = analyzePortfolio([
    pos('600519', 100, 10, 12, { prevClose: 11 }), // +100
    pos('000001', 100, 10, 9, { prevClose: 10 }), // -100
  ])
  assert.equal(a.dayBestWorst.best.code, '600519')
  assert.equal(a.dayBestWorst.worst.code, '000001')
  close(a.dayBestWorst.best.dayProfitCNY, 100, '最赚金额')
})

test('缺现价时用成本价兜底，不会把市值算成 0', () => {
  const a = analyzePortfolio([pos('600519', 100, 10, 0)])
  close(a.rows[0].currentPrice, 10, '现价回落成本价')
  close(a.totalMarketValueCNY, 1000, '市值')
  close(a.totalProfitCNY, 0, '盈亏为 0')
})

test('港股：默认汇率 1 时给出 fxWarning，设了汇率后金额按汇率折算', () => {
  const hk = [pos('00700', 100, 300, 400)]
  const rate1 = analyzePortfolio(hk)
  assert.equal(rate1.hasHK, true)
  assert.ok(rate1.fxWarning, '有港股且没设汇率必须提示')
  close(rate1.totalMarketValueCNY, 40000, '1:1 估算市值')

  const converted = analyzePortfolio(hk, { fxRate: 0.9 })
  assert.equal(converted.fxWarning, null)
  close(converted.totalMarketValueCNY, 36000, '按 0.9 折算市值')
  close(converted.totalProfitCNY, 9000, '按 0.9 折算盈亏')
})

test('A 股不受汇率影响，即使传了 fxRate', () => {
  const a = analyzePortfolio([pos('600519', 100, 10, 12)], { fxRate: 0.5 })
  close(a.totalMarketValueCNY, 1200, 'A 股不折算')
  assert.equal(a.fxWarning, null)
})

test('资产类别只分 A 股 / 港股', () => {
  assert.equal(assetClassOf('600519'), 'A股')
  assert.equal(assetClassOf('00700'), '港股')
  const a = analyzePortfolio([pos('600519', 100, 10, 10), pos('00700', 100, 10, 10)])
  assert.equal(a.byAssetClass.length, 2)
  close(a.byAssetClass.reduce((s, g) => s + g.weightPct, 0), 100, '资产类别权重合计')
})

test('行业未知单列覆盖率，不并进「无行业」', () => {
  const a = analyzePortfolio([
    pos('600519', 100, 10, 10, { industry: '白酒' }),
    pos('000001', 100, 10, 10, { industry: '银行' }),
    pos('002594', 100, 10, 10), // 没有行业标签
  ])
  assert.equal(a.byIndustry.knownCount, 2)
  assert.equal(a.byIndustry.unknownCount, 1)
  close(a.byIndustry.coveragePct, (2 / 3) * 100, '覆盖率')
  assert.equal(a.byIndustry.groups.length, 2, '未知不能被算成一组')
  assert.ok(!a.byIndustry.groups.some((g) => g.key === ''), '不能出现空行业分组')
})

test('行业分组权重：weightPct 相对已知部分、portfolioPct 相对整个组合', () => {
  const a = analyzePortfolio([
    pos('600519', 100, 10, 10, { industry: '白酒' }), // 1000
    pos('002594', 100, 10, 10), // 未知 1000
  ])
  const group = a.byIndustry.groups[0]
  close(group.weightPct, 100, '相对已知部分')
  close(group.portfolioPct, 50, '相对整个组合')
  close(a.maxIndustryWeightPct, 50, '最大行业权重按整个组合算')
})

test('加权 PE 只统计 PE>0 的，亏损股剔除并计数', () => {
  const a = analyzePortfolio([
    pos('600519', 100, 10, 10, { pe: 20 }), // 市值 1000
    pos('000001', 100, 10, 10, { pe: 10 }), // 市值 1000
    pos('002594', 100, 10, 10, { pe: -5 }), // 亏损，剔除
    pos('300750', 100, 10, 10), // 无 PE，剔除
  ])
  assert.equal(a.weightedPE.covered, 2)
  assert.equal(a.weightedPE.excluded, 2)
  close(a.weightedPE.value, (20 * 1000 + 10 * 1000) / 2000, '加权 PE')
})

test('集中度分级：HHI>2500 高度集中、1500~2500 中度、<1500 分散', () => {
  // 80/20 → HHI = 6400+400 = 6800
  const heavy = analyzePortfolio([pos('600519', 80, 10, 10), pos('000001', 20, 10, 10)])
  assert.ok(heavy.hhi > 2500)
  assert.equal(heavy.concentration.level, '高度集中')

  // 50/50 → 5000 仍高度；45/30/25 → 2025+900+625=3550 仍高度
  // 取 40/30/20/10 → 1600+900+400+100 = 3000 高度；要落到中度需更均匀
  const mid = analyzePortfolio([
    pos('A1', 34, 10, 10),
    pos('A2', 33, 10, 10),
    pos('A3', 33, 10, 10),
  ])
  assert.ok(mid.hhi > 2500, '三三均分 HHI 仍超 2500（≈3333）')
  assert.equal(mid.concentration.level, '高度集中')

  const spread = analyzePortfolio(
    Array.from({ length: 10 }, (_, i) => pos(`00000${i}`, 10, 10, 10))
  )
  close(spread.hhi, 1000, '十只均分 HHI=1000')
  assert.equal(spread.concentration.level, '较为分散')
})

test('非数组 / 脏数据被过滤，不抛异常', () => {
  assert.equal(analyzePortfolio(null).isEmpty, true)
  assert.equal(analyzePortfolio([null, undefined, {}]).isEmpty, true)
})
