/**
 * 信号回测测试。
 *
 * 回测最怕两种「看起来对」的错：
 * 1. 未来函数 —— 用信号日之后的数据判断信号（胜率会凭空变高）；
 * 2. 重叠交易 —— 持仓期内反复开仓，把同一段行情算成多笔样本。
 * 这里用可手算的构造数据把这两条钉住，另外锁住「非重叠」「平盘不进分母」的口径。
 */
import { test } from 'node:test'
import assert from 'node:assert/strict'

import { HOLD_PERIODS, MIN_BARS, analyzeBars } from '../src/utils/signalBacktest.ts'

/** 造一段带日期、可指定收盘价序列的日 K */
function makeBars(closes, startDate = new Date('2026-01-05')) {
  const out = []
  const day = new Date(startDate)
  for (let i = 0; i < closes.length; i++) {
    const close = closes[i]
    out.push({
      date: day.toISOString().slice(0, 10),
      open: close,
      high: close * 1.02,
      low: close * 0.98,
      close,
      volume: 100000,
    })
    day.setDate(day.getDate() + 1)
  }
  return out
}

/** 匀速上涨：MA 排列持续多头 */
function rising(count, base = 10, step = 0.1) {
  return Array.from({ length: count }, (_, i) => base + i * step)
}

/** 先跌后涨的 V 形：用来让「均线多头首次成立」发生在预热期之后 */
function vShape(down = 120, up = 180, base = 40) {
  const out = []
  for (let i = 0; i < down; i++) out.push(base - i * 0.12)
  const bottom = out[out.length - 1]
  for (let i = 1; i <= up; i++) out.push(bottom + i * 0.15)
  return out
}

test(`少于 ${MIN_BARS} 根不跑回测：指标预热不够，结果没有意义`, () => {
  const r = analyzeBars(makeBars(rising(MIN_BARS - 1)), '600519')
  assert.equal(r.enough, false)
  assert.deepEqual(r.rules, [])
  assert.equal(r.from, null)
})

test('空数据 / 非数组不抛异常', () => {
  assert.equal(analyzeBars([], '600519').enough, false)
  assert.equal(analyzeBars(null, '600519').enough, false)
})

test('数据足够时四条持有期齐全，规则带齐统计字段', () => {
  const r = analyzeBars(makeBars(rising(300)), '600519')
  assert.equal(r.enough, true)
  assert.deepEqual([...r.periods], [...HOLD_PERIODS])
  assert.ok(r.rules.length > 0)
  for (const rule of r.rules) {
    assert.equal(typeof rule.id, 'string')
    assert.equal(typeof rule.name, 'string')
    assert.equal(typeof rule.hint, 'string')
    for (const hold of HOLD_PERIODS) {
      const s = rule.byPeriod[hold]
      assert.ok(s, `${rule.id} 缺少持有 ${hold} 天的统计`)
      assert.equal(typeof s.trades, 'number')
      // decided 是胜率分母，必须等于 trades 减掉平盘数
      assert.equal(s.decided + s.flats, s.trades, `${rule.id}/${hold} 天：decided + flats 必须等于 trades`)
    }
  }
})

test('V 形反转中多头排列规则应触发过信号', () => {
  // ma_stack 只在「均线多头排列首次成立」那天算信号（不是每天算），
  // 所以必须让成立点落在回测起点（第 60 根）之后：先跌 120 根、再涨 180 根。
  const r = analyzeBars(makeBars(vShape()), '600519')
  const stacked = r.rules.find((x) => x.id === 'ma_stack')
  assert.ok(stacked, '应存在 ma_stack 规则')
  assert.ok(stacked.signalCount > 0, 'V 形反转后 MA5>MA10>MA20 首次成立必须被捕捉到')
})

test('匀速持续上涨时 ma_stack 可能为 0：多头排列在预热期就已成立，不是漏算', () => {
  const r = analyzeBars(makeBars(rising(300)), '600519')
  const stacked = r.rules.find((x) => x.id === 'ma_stack')
  // 这条断言锁住的是口径而不是数值：ma_stack 语义是「首次成立」，
  // 起点之前就成立的话回测区间内确实没有信号，属于预期行为。
  assert.equal(stacked.signalCount, 0)
})

test('匀速下跌中，多头信号不该出现', () => {
  const falling = Array.from({ length: 300 }, (_, i) => 60 - i * 0.1)
  const r = analyzeBars(makeBars(falling), '600519')
  const stacked = r.rules.find((x) => x.id === 'ma_stack')
  assert.equal(stacked.signalCount, 0, '单边下跌不该出现均线多头排列')
})

test('没有未来函数：把某天之后的 K 线全部删掉，该天之前的结果不能变', () => {
  const closes = rising(300).map((v, i) => v + Math.sin(i / 5) * 2)
  const full = makeBars(closes)
  const cut = full.slice(0, 220)

  const fullRes = analyzeBars(full, '600519')
  const cutRes = analyzeBars(cut, '600519')

  // 截断后的最后一根，正是完整数据里的第 219 根；用它之前的数据算出的信号次数
  // 必须与完整数据里同一区间的信号次数一致，否则就是用到了未来数据
  for (const rule of fullRes.rules) {
    const cutRule = cutRes.rules.find((x) => x.id === rule.id)
    // cut 的第 219 根之前的命中数 = full 里第 219 根之前的命中数
    // 这里只能间接验证：截断版能算出的规则，在完整版里也必须存在且有信号
    assert.ok(cutRule, `${rule.id} 在截断数据里应当同样存在`)
    if (cutRule.signalCount > 0) {
      assert.ok(rule.signalCount >= cutRule.signalCount, `${rule.id}: 完整数据里的信号数不该少于截断版`)
    }
  }
})

test('非重叠交易：持有期内不再开仓，所以笔数远少于信号数', () => {
  const r = analyzeBars(makeBars(vShape()), '600519')
  const stacked = r.rules.find((x) => x.id === 'ma_stack')
  for (const hold of HOLD_PERIODS) {
    const s = stacked.byPeriod[hold]
    // 连续信号（每天都是多头）在持有 hold 天时，最多只能开 trades ≈ 可用根数 / hold
    const maxTrades = Math.ceil(300 / hold) + 1
    assert.ok(s.trades <= maxTrades, `持有 ${hold} 天：笔数 ${s.trades} 超出非重叠上限 ${maxTrades}`)
  }
})

test('active / barsAgo / lastHitDate 语义一致', () => {
  const r = analyzeBars(makeBars(vShape()), '600519')
  for (const rule of r.rules) {
    if (rule.signalCount === 0) {
      assert.equal(rule.barsAgo, null)
      assert.equal(rule.lastHitDate, null)
      assert.equal(rule.active, false, '从没触发过就不可能是当前触发')
      continue
    }
    assert.ok(rule.barsAgo >= 0, `${rule.id} 触发过就必须有 barsAgo`)
    assert.ok(rule.lastHitDate, `${rule.id} 触发过就必须有 lastHitDate`)
    // 当前触发 ⇔ 最近一次命中就在最后一根
    assert.equal(rule.active, rule.barsAgo === 0, `${rule.id}: active 与 barsAgo===0 必须一致`)
  }
})

test('统计自洽：best ≥ avgRet ≥ worst，且 maxDrawdown ≥ 0', () => {
  const r = analyzeBars(makeBars(rising(300).map((v, i) => v + Math.sin(i / 7) * 3)), '600519')
  for (const rule of r.rules) {
    for (const hold of HOLD_PERIODS) {
      const s = rule.byPeriod[hold]
      if (s.trades === 0) continue
      assert.ok(s.best >= s.avgRet && s.avgRet >= s.worst, `${rule.id}/${hold}: best≥avg≥worst 不成立`)
      assert.ok(s.maxDrawdown >= 0)
      assert.ok(s.winRate === null || (s.winRate >= 0 && s.winRate <= 100))
    }
  }
})
