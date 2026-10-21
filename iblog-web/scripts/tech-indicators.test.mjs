/**
 * 技术指标口径测试。
 *
 * 直接 import 真实 TS 源文件（node --experimental-strip-types），不复制逻辑：
 * 复制一份测试就成了「测试写的那份实现」，永远绿、永远没意义。
 *
 * 数值基准的来历：指标公式本身是公开的，但边界处理各家不同（EMA 用什么种子、
 * 全涨时 RSI 给多少、均线不足时返回 null 还是 0）。这里把本项目选定的口径
 * 一条条钉住，改了就会红。
 */
import { test } from 'node:test'
import assert from 'node:assert/strict'

import { ema, emaFilled, findCross, macd, sma } from '../src/utils/stockIndicators.ts'
import {
  analyzeTech,
  calcKDJ,
  calcMACD,
  calcRSI,
  calcWR,
  judgeAlignment,
  judgeKdj,
  judgeMaTrend,
  judgeRsi,
  judgeWr,
  latestMAs,
} from '../src/utils/techIndicators.ts'

function close(a, e, label, eps = 1e-6) {
  assert.ok(a !== null && a !== undefined, `${label}: 不该是空值`)
  assert.ok(Math.abs(a - e) < eps, `${label}: 期望 ${e}，实际 ${a}`)
}

/** 造一段等长的 OHLC，只关心 high/low/close */
function bars(closes, spread = 1) {
  return closes.map((c) => ({ high: c + spread, low: c - spread, close: c }))
}

// ───────────────────────── 基础序列 ─────────────────────────

test('sma：不足周期为 null，边界那一根开始有值', () => {
  const out = sma([1, 2, 3, 4, 5], 3)
  assert.deepEqual(out.slice(0, 2), [null, null])
  close(out[2], 2, 'sma[2]')
  close(out[3], 3, 'sma[3]')
  close(out[4], 4, 'sma[4]')
})

test('sma：遇到非法值整体放弃，不会悄悄偏移', () => {
  const out = sma([1, 2, Number.NaN, 4, 5], 2)
  assert.ok(out.every((v) => v === null))
})

test('ema：以 SMA 为种子，第 period-1 位为第一个有效值', () => {
  const src = [10, 11, 12, 13, 14, 15, 16]
  const out = ema(src, 3)
  // 种子 = (10+11+12)/3 = 11
  close(out[2], 11, 'ema[2] 种子')
  assert.equal(out[0], null)
  assert.equal(out[1], null)
  // k = 2/4 = 0.5；13*0.5 + 11*0.5 = 12
  close(out[3], 12, 'ema[3]')
  // 14*0.5 + 12*0.5 = 13
  close(out[4], 13, 'ema[4]')
})

test('emaFilled：与输入等长且无 null，预热段为逐段均值', () => {
  const out = emaFilled([10, 11, 12, 13], 3)
  assert.equal(out.length, 4)
  close(out[0], 10, 'fill[0]')
  close(out[1], 10.5, 'fill[1]')
  close(out[2], 11, 'fill[2] 种子')
  assert.ok(out.every((v) => Number.isFinite(v)))
})

// ───────────────────────── MACD ─────────────────────────

test('macd：dif/dea/hist 等长且 hist = 2*(dif-dea)', () => {
  const closes = Array.from({ length: 60 }, (_, i) => 100 + Math.sin(i / 5) * 5)
  const r = macd(closes)
  assert.equal(r.dif.length, 60)
  assert.equal(r.dea.length, 60)
  for (let i = 0; i < 60; i++) {
    close(r.hist[i], 2 * (r.dif[i] - r.dea[i]), `hist[${i}]`)
  }
  assert.equal(r.validFrom, 26 + 9 - 2)
})

test('calcMACD：不足 26 根返回空数组，而不是半真半假的值', () => {
  assert.deepEqual(calcMACD(Array.from({ length: 25 }, () => 10)), [])
  assert.equal(calcMACD(Array.from({ length: 26 }, () => 10)).length, 26)
})

test('calcMACD：横盘时 dif 与 dea 都收在 0 附近', () => {
  const flat = Array.from({ length: 40 }, () => 100)
  const last = calcMACD(flat).at(-1)
  close(last.dif, 0, 'dif')
  close(last.dea, 0, 'dea')
  close(last.macd, 0, 'macd')
})

test('calcMACD：持续上涨时 dif 为正（快线在慢线上方）', () => {
  const rising = Array.from({ length: 40 }, (_, i) => 100 + i)
  assert.ok(calcMACD(rising).at(-1).dif > 0)
})

// ───────────────────────── KDJ ─────────────────────────

test('calcKDJ：与输入等长（前 n-1 根按中性 50 处理）', () => {
  const b = bars(Array.from({ length: 20 }, (_, i) => 100 + i))
  assert.equal(calcKDJ(b).length, 20)
  assert.deepEqual(calcKDJ(bars([1, 2, 3])), [])
})

test('calcKDJ：收盘价持续靠近区间上沿时 K 走高', () => {
  // 每根都收在最高价上：RSV 恒为 100，K 单调逼近 100
  const b = Array.from({ length: 20 }, (_, i) => ({ high: 100 + i + 1, low: 100 + i - 1, close: 100 + i + 1 }))
  const last = calcKDJ(b).at(-1)
  assert.ok(last.k > 90, `K 应逼近 100，实际 ${last.k}`)
  // J = 3K - 2D，K 领先 D，所以 J 还要更高
  assert.ok(last.j > last.k)
})

test('calcKDJ：高低点相同时 RSV 记 50，不出现 NaN', () => {
  const b = Array.from({ length: 12 }, () => ({ high: 10, low: 10, close: 10 }))
  const last = calcKDJ(b).at(-1)
  assert.ok(Number.isFinite(last.k) && Number.isFinite(last.d) && Number.isFinite(last.j))
})

// ───────────────────────── RSI ─────────────────────────

test('calcRSI：全涨给 100，全平给 50（不是 NaN、不是 0）', () => {
  const up = Array.from({ length: 30 }, (_, i) => 100 + i)
  close(calcRSI(up).at(-1), 100, '全涨 RSI')
  const flat = Array.from({ length: 30 }, () => 100)
  close(calcRSI(flat).at(-1), 50, '全平 RSI')
})

test('calcRSI：全跌给 0', () => {
  const down = Array.from({ length: 30 }, (_, i) => 200 - i)
  close(calcRSI(down).at(-1), 0, '全跌 RSI')
})

test('calcRSI：长度不足 period+1 时返回空', () => {
  assert.deepEqual(calcRSI(Array.from({ length: 14 }, () => 10)), [])
  assert.equal(calcRSI(Array.from({ length: 15 }, () => 10)).length, 1)
})

test('calcRSI：落在 0~100 之间', () => {
  const wiggly = Array.from({ length: 80 }, (_, i) => 100 + Math.sin(i / 3) * 8 + Math.cos(i) * 3)
  for (const v of calcRSI(wiggly)) {
    assert.ok(v >= 0 && v <= 100, `RSI 越界: ${v}`)
  }
})

// ───────────────────────── WR ─────────────────────────

test('calcWR：收在区间最高价时为 0，最低价时为 -100', () => {
  // 窗口是「最近 14 根」，所以要让最后一根的收盘落在该窗口的极值上：
  // 价格走平 13 根后，最后一根拉到新高/新低，此时 H-L 的极值都由最后一根决定。
  const flat = Array.from({ length: 13 }, () => ({ high: 100, low: 90 }))
  const up = [...flat, { high: 110, low: 100, close: 110 }]
  close(calcWR(up).at(-1), 0, '收在最高')
  const down = [...flat, { high: 100, low: 80, close: 80 }]
  close(calcWR(down).at(-1), -100, '收在最低')
})

test('calcWR：前 n-1 根为 null', () => {
  const out = calcWR(bars(Array.from({ length: 20 }, (_, i) => 100 + i)), 14)
  assert.ok(out.slice(0, 13).every((v) => v === null))
  assert.notEqual(out[13], null)
})

// ───────────────────────── 判读 ─────────────────────────

test('judgeKdj：超买 / 超卖 / 正常三态', () => {
  assert.equal(judgeKdj(85, 70, 90), 'overbought')
  assert.equal(judgeKdj(50, 50, 110), 'overbought', 'J 超 100 也算超买')
  assert.equal(judgeKdj(15, 50, 40), 'oversold')
  assert.equal(judgeKdj(50, 50, -5), 'oversold', 'J 低于 0 也算超卖')
  assert.equal(judgeKdj(50, 50, 50), 'normal')
})

test('judgeRsi：70/30 为界', () => {
  assert.equal(judgeRsi(70.01), 'overbought')
  assert.equal(judgeRsi(70), 'normal', '边界值不算超买')
  assert.equal(judgeRsi(29.99), 'oversold')
  assert.equal(judgeRsi(30), 'normal')
})

test('judgeWr：越接近 0 越超买（量纲是负数，方向别搞反）', () => {
  assert.equal(judgeWr(-10), 'overbought')
  assert.equal(judgeWr(-20), 'overbought', '边界 -20 算超买')
  assert.equal(judgeWr(-85), 'oversold')
  assert.equal(judgeWr(-80), 'oversold', '边界 -80 算超卖')
  assert.equal(judgeWr(-50), 'normal')
})

test('judgeAlignment：多头 / 空头 / 缠绕', () => {
  const bull = [
    { period: 5, value: 12 },
    { period: 10, value: 11 },
    { period: 20, value: 10 },
  ]
  const bear = [
    { period: 5, value: 10 },
    { period: 10, value: 11 },
    { period: 20, value: 12 },
  ]
  const tangled = [
    { period: 5, value: 11 },
    { period: 10, value: 12 },
    { period: 20, value: 11 },
  ]
  assert.equal(judgeAlignment(bull), 'bullish')
  assert.equal(judgeAlignment(bear), 'bearish')
  assert.equal(judgeAlignment(tangled), 'tangled')
  // 只有两条均线不判排列：永远只能是多或空，看不出「正在收敛」
  assert.equal(judgeAlignment([{ period: 5, value: 1 }, { period: 10, value: 2 }]), null)
})

test('judgeMaTrend：只看首尾，允许中间交叉', () => {
  const mas = [
    { period: 5, value: 11 },
    { period: 10, value: 9 },
    { period: 20, value: 10 },
  ]
  assert.equal(judgeMaTrend(mas), 'bull')
  assert.equal(judgeMaTrend([{ period: 5, value: 1 }]), null)
})

test('latestMAs：数据不够的周期直接不返回', () => {
  const mas = latestMAs(Array.from({ length: 12 }, (_, i) => i + 1))
  assert.deepEqual(mas.map((m) => m.period), [5, 10])
  // 1..12 的最后 5 个是 8,9,10,11,12 → 均值 10
  close(mas[0].value, 10, 'MA5 (8+9+10+11+12)/5')
  // 最后 10 个是 3..12 → 均值 7.5
  close(mas[1].value, 7.5, 'MA10 (3..12)/10')
})

// ───────────────────────── 交叉信号 ─────────────────────────

test('findCross：末根金叉 → barsAgo = 1', () => {
  // 至少 lookback + 1 根才可能看到交叉，所以先垫一根再放两段
  const arr = [
    { a: 0, b: 1 },
    { a: -1, b: 1 },
    { a: -2, b: 1 },
    { a: 1, b: 0 },
  ]
  const s = findCross(arr, 'a', 'b', 3)
  assert.deepEqual(s, { direction: 'golden', barsAgo: 1 })
})

test('findCross：两根前金叉 → barsAgo = 2；超过 lookback 不返回', () => {
  const arr = [
    { a: -1, b: 1 },
    { a: 1, b: 0 },
    { a: 2, b: 0 },
    { a: 3, b: 0 },
  ]
  assert.deepEqual(findCross(arr, 'a', 'b', 3), { direction: 'golden', barsAgo: 3 })
  assert.equal(findCross(arr, 'a', 'b', 2), null, 'lookback 2 看不到 3 根前的交叉')
})

test('findCross：死叉方向', () => {
  const arr = [
    { a: 2, b: 0 },
    { a: 1, b: 0 },
    { a: 0, b: 0 },
    { a: -1, b: 0 },
  ]
  assert.deepEqual(findCross(arr, 'a', 'b', 3), { direction: 'death', barsAgo: 1 })
})

test('findCross：数组里含 null 时跳过而不是崩', () => {
  const arr = [{ a: 3, b: 0 }, null, { a: 1, b: 0 }, { a: 2, b: 0 }]
  assert.equal(findCross(arr, 'a', 'b', 2), null)
})

test('findCross：长度不足 lookback+1 返回 null', () => {
  assert.equal(findCross([{ a: 1, b: 0 }], 'a', 'b', 3), null)
})

// ───────────────────────── 聚合 ─────────────────────────

test('analyzeTech：不足 26 根返回 null', () => {
  assert.equal(analyzeTech(bars(Array.from({ length: 25 }, (_, i) => 100 + i))), null)
})

test('analyzeTech：50 根真实形态下各字段齐全且互相自洽', () => {
  // 前 30 根上涨、后 20 根持续回调：短均线已经压到长均线下方，
  // 所以整体趋势应为空头（回调幅度足够大，不是「涨完横盘」）。
  const closes = [
    ...Array.from({ length: 30 }, (_, i) => 100 + i * 1.5),
    ...Array.from({ length: 20 }, (_, i) => 143.5 - (i + 1) * 2),
  ]
  const r = analyzeTech(bars(closes))
  assert.ok(r)
  assert.equal(r.bars, 50)
  close(r.macd.macd, 2 * (r.macd.dif - r.macd.dea), 'hist 自洽')
  assert.ok(r.rsi !== null && r.rsi >= 0 && r.rsi <= 100)
  assert.ok(r.wr !== null && r.wr >= -100 && r.wr <= 0)
  assert.ok(r.mas.length >= 3)
  assert.equal(r.maTrend, 'bear')
  assert.equal(r.alignment, 'bearish')
})

test('analyzeTech：持续下跌时 maTrend 为空头', () => {
  const closes = Array.from({ length: 60 }, (_, i) => 200 - i)
  const r = analyzeTech(bars(closes))
  assert.equal(r.maTrend, 'bear')
  assert.equal(r.alignment, 'bearish')
  assert.equal(r.rsiStatus, 'oversold')
  assert.equal(r.wrStatus, 'oversold')
  assert.equal(r.kdjStatus, 'oversold')
})
