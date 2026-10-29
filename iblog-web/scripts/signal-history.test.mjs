/**
 * 日内 T+0 信号 + 历史信号胜率的单测。
 *
 * <p>跑法：`node --experimental-strip-types --test scripts/signal-history.test.mjs`</p>
 *
 * <p>为什么要在测试里自造 localStorage：产品代码直接读写 localStorage，
 * 直接依赖 {@code globalThis.localStorage}。Node 里没有这个对象，而**产品代码不该为了
 * 迁就测试环境去加分支**——那样等于把测试环境的怪癖写进运行时代码。所以在这里补一个
 * 最小 stub，让同一份产品代码在原样不动的情况下可测。</p>
 */
import { test, beforeEach } from 'node:test'
import assert from 'node:assert/strict'

import { detectIntradaySignals, detectTrapSignals, detectDailyDivergence } from '../src/utils/t0Signals.ts'

// ───────────────────────────── localStorage 最小 stub ─────────────────────────────

/** 内存版 localStorage：只实现 sessionStorage/localStorage 的 5 个标准方法 + length */
const memory = new Map()
const localStorageStub = {
  get length() {
    return memory.size
  },
  key: (index) => [...memory.keys()][index] ?? null,
  getItem: (key) => (memory.has(key) ? memory.get(key) : null),
  setItem: (key, value) => {
    memory.set(key, String(value))
  },
  removeItem: (key) => {
    memory.delete(key)
  },
  clear: () => {
    memory.clear()
  }
}
globalThis.localStorage = localStorageStub

// ───────────────────────────── 造数工具 ─────────────────────────────

/**
 * 生成从 09:30 开始的连续交易分钟。
 * 11:30 之后直接跳到 13:00——A 股中午不连续，跳过午休才不会造出不存在的分钟。
 */
function minuteLabels(count) {
  const out = []
  let m = 9 * 60 + 30
  for (let i = 0; i < count; i++) {
    if (m > 11 * 60 + 30 && m < 13 * 60) m = 13 * 60
    const hh = String(Math.floor(m / 60)).padStart(2, '0')
    const mm = String(m % 60).padStart(2, '0')
    out.push(`${hh}:${mm}`)
    m += 1
  }
  return out
}

/** 造一根分时 bar；avg 不传就跟着 price 走 */
function bar(time, price, volume, avg = price) {
  return {
    time,
    price,
    avgPrice: avg,
    volume,
    turnover: volume * price * 100,
    vwap: avg
  }
}

/** 按价格 / 成交量数组批量造 bars（时间从 09:30 起，均价固定） */
function makeBars(prices, volumes, avg) {
  const times = minuteLabels(prices.length)
  return prices.map((price, i) => bar(times[i], price, volumes[i], avg ?? price))
}

/** 断言某个 kind 至少出现一次 */
function hasKind(signals, kind) {
  return signals.some((s) => s.kind === kind)
}

// ───────────────────────────── detectIntradaySignals ─────────────────────────────

test('放量急拉：5 分钟拉升 1% 且量能 6 倍基准，判为 surge', () => {
  const prices = new Array(45).fill(10.0)
  const volumes = new Array(45).fill(100)
  prices[20] = 10.1 // 5 分钟 +1.0%
  volumes[20] = 600 // 6 倍基准

  const signals = detectIntradaySignals(makeBars(prices, volumes))
  assert.ok(hasKind(signals, 'surge'), '应识别出放量急拉')
  const surge = signals.find((s) => s.kind === 'surge')
  assert.equal(surge.barIndex, 20)
  assert.equal(surge.level, 'warn')
  assert.equal(surge.price, 10.1)
  assert.equal(surge.time, minuteLabels(45)[20])
})

test('放量急跌：5 分钟下跌 1% 且量能 6 倍基准，判为 dump', () => {
  const prices = new Array(45).fill(10.0)
  const volumes = new Array(45).fill(100)
  prices[20] = 9.9 // 5 分钟 -1.0%
  volumes[20] = 600

  const signals = detectIntradaySignals(makeBars(prices, volumes))
  assert.ok(hasKind(signals, 'dump'), '应识别出放量急跌')
  assert.equal(signals.find((s) => s.kind === 'dump').barIndex, 20)
})

test('无量拉升：5 分钟涨 1% 但量能只有 0.3 倍基准，判为 quietRise', () => {
  const prices = new Array(45).fill(10.0)
  const volumes = new Array(45).fill(100)
  prices[20] = 10.1 // +1.0% >= 0.8%
  volumes[20] = 30 // 0.3 倍 <= 0.4

  const signals = detectIntradaySignals(makeBars(prices, volumes))
  assert.ok(hasKind(signals, 'quietRise'), '应识别出无量拉升')
  assert.equal(signals.find((s) => s.kind === 'quietRise').barIndex, 20)
})

test('突破：放量并越过前 30 分钟高点 0.2% 以上，判为 breakout', () => {
  // 前 36 根从 9.95 缓升到 10.00（chg5 很小），第 41 根放量跳到 10.055：
  // 涨幅 0.55% 落在 [0.5%, 0.6%) 区间——刚好越过「突破动能」门槛，
  // 又不触发「放量急拉」的 0.6%（前者优先，否则永远只会看到 surge）
  const prices = Array.from({ length: 60 }, (_, i) => {
    if (i <= 35) return 9.95 + (0.05 * i) / 35
    if (i <= 39) return 10.0 + 0.005 * (i - 35)
    return 10.055
  })
  const volumes = new Array(60).fill(100)
  volumes[40] = 600

  const signals = detectIntradaySignals(makeBars(prices, volumes))
  assert.ok(hasKind(signals, 'breakout'), '应识别出放量突破')
  const breakout = signals.find((s) => s.kind === 'breakout')
  assert.equal(breakout.barIndex, 40)
  assert.ok(breakout.price > 10.02, '突破价应高于前高 * 1.002')
})

test('破位：放量并跌破前 30 分钟低点 0.2% 以上，判为 breakdown', () => {
  const prices = Array.from({ length: 60 }, (_, i) => {
    if (i <= 35) return 10.15 - (0.1 * i) / 35
    if (i <= 39) return 10.05 - 0.005 * (i - 35)
    return 9.995
  })
  const volumes = new Array(60).fill(100)
  volumes[40] = 600

  const signals = detectIntradaySignals(makeBars(prices, volumes))
  assert.ok(hasKind(signals, 'breakdown'), '应识别出放量破位')
  assert.equal(signals.find((s) => s.kind === 'breakdown').barIndex, 40)
})

test('顶背离：创新高但量能萎缩，判为 topDivergence', () => {
  const prices = [...new Array(16).fill(10.0), ...new Array(4).fill(10.01), 10.1, ...new Array(4).fill(10.1)]
  // 前 15 根 200 手、后段骤降到 100 手：前 5 分钟均量 < 前段均量 * 0.6
  const volumes = [...new Array(15).fill(200), ...new Array(10).fill(100)]

  const signals = detectIntradaySignals(makeBars(prices, volumes))
  assert.ok(hasKind(signals, 'topDivergence'), '应识别出分时顶背离')
  assert.equal(signals.find((s) => s.kind === 'topDivergence').barIndex, 20)
})

test('底背离：创新低但量能萎缩，判为 bottomDivergence', () => {
  const prices = [...new Array(16).fill(10.1), ...new Array(4).fill(10.09), 10.0, ...new Array(4).fill(10.0)]
  const volumes = [...new Array(15).fill(200), ...new Array(10).fill(100)]

  const signals = detectIntradaySignals(makeBars(prices, volumes))
  assert.ok(hasKind(signals, 'bottomDivergence'), '应识别出分时底背离')
  assert.equal(signals.find((s) => s.kind === 'bottomDivergence').barIndex, 20)
})

test('剔除 09:30：集合竞价分钟即便满足放量急拉也不出标记', () => {
  // 造两条 09:30（后端在开盘瞬间可能给出重复的竞价条），第二条本身满足 surge 条件。
  // 若没有「剔除 09:30」这条规则，它会在 barIndex=1 处产出信号。
  const times = ['09:30', '09:30', ...minuteLabels(14).slice(1)]
  const bars = [
    bar(times[0], 10.0, 100),
    bar(times[1], 10.1, 600), // 5 分钟 +1%、量 6 倍 —— 满足 surge
    ...Array.from({ length: 13 }, (_, k) => bar(times[k + 2], 10.1, 100))
  ]

  const strict = detectIntradaySignals(bars)
  assert.equal(strict.filter((s) => s.time === '09:30').length, 0, '默认必须剔除 09:30')
  assert.equal(strict.length, 0, '09:30 被剔除后这里不该剩任何信号')

  // 显式放行时才允许出现，用来证明上面的排除确实来自这条规则
  const relaxed = detectIntradaySignals(bars, { includeAuction: true })
  assert.equal(relaxed.filter((s) => s.time === '09:30').length, 1)
})

test('同一分钟只保留一个标记：同时满足放量急拉与突破时只出优先级高的那条', () => {
  // 第 41 根同时满足 surge（5 分钟 +2%、量 6 倍）与 breakout（越过前高、chg5 >= 0.5%）
  const prices = [...new Array(40).fill(10.0), ...new Array(20).fill(10.2)]
  const volumes = new Array(60).fill(100)
  volumes[40] = 600

  const signals = detectIntradaySignals(makeBars(prices, volumes))
  const atSameMinute = signals.filter((s) => s.barIndex === 40)
  assert.equal(atSameMinute.length, 1, '同一分钟只能留一个标记')
  assert.equal(atSameMinute[0].kind, 'surge', '扫描顺序即优先级：前瞻预警优先于突破')

  // 全局再兜一层：任何 barIndex 都不允许重复
  const indices = signals.map((s) => s.barIndex)
  assert.equal(new Set(indices).size, indices.length)
})

test('数据不足或无成交量时返回空数组，不抛异常', () => {
  assert.deepEqual(detectIntradaySignals([]), [])
  assert.deepEqual(detectIntradaySignals(makeBars([10.0, 10.1], [100, 100])), [])
  const flat = new Array(20).fill(10.0)
  assert.deepEqual(detectIntradaySignals(makeBars(flat, new Array(20).fill(0))), [])
})

// ───────────────────────────── detectTrapSignals ─────────────────────────────

test('诱多嫌疑：高位放量滞涨，未跌破均价时 confirmed 必须为 false', () => {
  // 前 10 根从 9.90 拉到 10.20，之后横在 10.20，最后 5 分钟放量 3 倍却几乎不涨
  const prices = Array.from({ length: 40 }, (_, i) => (i < 10 ? 9.9 + 0.03 * i : 10.2))
  const volumes = Array.from({ length: 40 }, (_, i) => (i >= 35 ? 300 : 100))
  const bars = makeBars(prices, volumes, 10.05) // 均价 10.05，价格高出约 1.5%

  const traps = detectTrapSignals(bars)
  const bull = traps.find((t) => t.side === 'bull' && t.label.includes('高位放量滞涨'))
  assert.ok(bull, '应识别出高位放量滞涨的诱多嫌疑')
  assert.equal(bull.confirmed, false, '还没跌破均价，不能算确认')
  assert.equal(bull.strength, 2)
  assert.ok(bull.reasons.length >= 2, '应列出至少两条客观事实')
  // 文案必须是嫌疑口吻，不能给出确定性结论
  assert.ok(bull.detail.includes('嫌疑'), 'detail 必须保留嫌疑口吻')
})

test('诱空嫌疑：放量急跌后收复启动位，confirmed 为 true 且方向为 bear', () => {
  const prices = []
  const volumes = []
  for (let i = 0; i < 60; i++) {
    if (i <= 10) {
      prices.push(i === 10 ? 10.12 : 10.1)
      volumes.push(100)
    } else if (i <= 15) {
      // 放量急跌到 9.90，跌幅约 -2.2%
      prices.push([10.05, 10.0, 9.95, 9.93, 9.9][i - 11])
      volumes.push(300)
    } else if (i <= 20) {
      prices.push([9.95, 10.02, 10.08, 10.14, 10.2][i - 16])
      volumes.push(100)
    } else if (i <= 30) {
      prices.push(10.2 + 0.01 * (i - 20))
      volumes.push(100)
    } else if (i <= 40) {
      prices.push(10.3 - 0.025 * (i - 30))
      volumes.push(100)
    } else {
      prices.push(10.05)
      volumes.push(100)
    }
  }

  const traps = detectTrapSignals(makeBars(prices, volumes))
  const bear = traps.find((t) => t.side === 'bear' && t.label.includes('放量急跌后收复'))
  assert.ok(bear, '应识别出放量急跌后收复的诱空嫌疑')
  assert.equal(bear.confirmed, true)
  assert.ok(bear.barIndex > 15 && bear.barIndex <= 30, '时间戳应落在谷后收复的那一分钟')
  assert.ok(bear.label.includes('疑似'), '标签必须带「疑似」')
})

test('陷阱信号：同一分钟不重复产出，且数据不足时返回空数组', () => {
  const prices = Array.from({ length: 40 }, (_, i) => (i < 10 ? 9.9 + 0.03 * i : 10.2))
  const volumes = Array.from({ length: 40 }, (_, i) => (i >= 35 ? 300 : 100))
  const traps = detectTrapSignals(makeBars(prices, volumes, 10.05))
  const indices = traps.map((t) => t.barIndex)
  assert.equal(new Set(indices).size, indices.length, '同一分钟只能有一个陷阱标记')

  assert.deepEqual(detectTrapSignals([]), [])
  assert.deepEqual(detectTrapSignals(makeBars([10.0, 10.1], [100, 100])), [])
})

test('日 K MACD 背离：日 K 不足 35 根时不下结论', () => {
  const kline = Array.from({ length: 20 }, (_, i) => ({
    date: `2026-09-${String(i + 1).padStart(2, '0')}`,
    open: 10,
    close: 10 + i * 0.01,
    high: 10.1 + i * 0.01,
    low: 9.9 + i * 0.01,
    volume: 1000,
    turnover: 10000
  }))
  assert.deepEqual(detectDailyDivergence(kline), [])
})

// ═══════════════════════════════════════════════════════════════════════════
// 以下测 `src/utils/signalHistory.ts`：零依赖的纯函数版「历史信号胜率」。
//
//
// 口径上必须钉死三件事，否则这个功能的数字就是错的：
//   1. 样本只来自「用户当时恰好打开了那只股票」，不是策略回测，必须带样本量 + 免责文案；
//   2. 出场价取信号日之后第 horizon 个交易日的收盘价（分时不落库，只能按日 K 估）；
//   3. 后续 K 线不够的样本进 pendingCount，既不能算输也不能算赢。
// ═══════════════════════════════════════════════════════════════════════════

const {
  SIGNAL_EVENTS_KEY,
  MAX_EVENTS,
  SIGNAL_HISTORY_DISCLAIMER,
  DEFAULT_HORIZON,
  HORIZON_CHOICES,
  recordSignalEvent,
  listSignalEvents,
  clearSignalEvents,
  signalEventsForCode,
  computeWinRates,
  summarizeWinRates,
  sampleWarning
} = await import('../src/utils/signalHistory.ts')

/** 造一条合法事件入参（recordSignalEvent 要的是不含 id/createdAt 的版本） */
function ev(overrides = {}) {
  return {
    code: '600519',
    name: '贵州茅台',
    source: 't0',
    label: '放量急拉',
    bias: 'bull',
    price: 10,
    date: '2026-10-20',
    ...overrides
  }
}

/**
 * 造一条已落库形态的事件（computeWinRates 直接吃对象，不经过 localStorage）。
 * 价格类用例大量需要非法值，而 recordSignalEvent 会挡住非法价格，所以这里单独造。
 */
function stored(overrides = {}) {
  return {
    id: `e${storedSeq++}`,
    code: '600519',
    source: 't0',
    label: '放量急拉',
    bias: 'bull',
    price: 10,
    date: '2026-10-20',
    createdAt: storedSeq,
    ...overrides
  }
}
let storedSeq = 1

/** `[['2026-10-21', 10.5], ...]` → 日 K 序列（升序，只看日期与收盘价） */
function bars(rows) {
  return rows.map(([date, close]) => ({ date, close }))
}

// 每个用例都从干净的信号表开始；只清自己这个键，不影响上面的 composable 用例
beforeEach(() => {
  clearSignalEvents()
})

// ───────────────────────────── 记录与去重 ─────────────────────────────

test('recordSignalEvent：同日同股同 label 只写一次', () => {
  assert.equal(recordSignalEvent(ev()), true)
  // 分时信号是轮询刷出来的，同一只票同一天会反复命中同一个标记
  assert.equal(recordSignalEvent(ev()), false)
  assert.equal(listSignalEvents().length, 1)

  // 换 label / 换日期 / 换股票都是不同的信号，各自允许写入
  assert.equal(recordSignalEvent(ev({ label: '突破' })), true)
  assert.equal(recordSignalEvent(ev({ date: '2026-10-21' })), true)
  assert.equal(recordSignalEvent(ev({ code: '000001' })), true)
  assert.equal(listSignalEvents().length, 4)
})

test('recordSignalEvent 拒绝非法入参，不写脏数据', () => {
  assert.equal(recordSignalEvent(ev({ price: 0 })), false)
  assert.equal(recordSignalEvent(ev({ price: -1 })), false)
  assert.equal(recordSignalEvent(ev({ price: Number.NaN })), false)
  assert.equal(recordSignalEvent(ev({ source: 'unknown' })), false)
  assert.equal(recordSignalEvent(ev({ bias: 'long' })), false)
  assert.equal(recordSignalEvent(ev({ label: '   ' })), false)
  assert.equal(recordSignalEvent(ev({ code: '' })), false)
  assert.deepEqual(listSignalEvents(), [])
})

test('listSignalEvents 按 createdAt 降序，signalEventsForCode 只取本股', () => {
  memory.set(
    SIGNAL_EVENTS_KEY,
    JSON.stringify([
      { id: 'a', code: '600519', source: 't0', label: '甲', bias: 'bull', price: 10, date: '2026-10-20', createdAt: 100 },
      { id: 'b', code: '000001', source: 'ma-cross', label: '乙', bias: 'bear', price: 20, date: '2026-10-21', createdAt: 300 },
      { id: 'c', code: '600519', source: 'price-alert', label: '丙', bias: 'bull', price: 30, date: '2026-10-22', createdAt: 200 }
    ])
  )
  assert.deepEqual(listSignalEvents().map((event) => event.id), ['b', 'c', 'a'])
  assert.deepEqual(signalEventsForCode('600519').map((event) => event.id), ['c', 'a'])
  // 页面传进来的代码可能带空白，不该因此查不到
  assert.deepEqual(signalEventsForCode(' 600519 ').map((event) => event.id), ['c', 'a'])
  assert.deepEqual(signalEventsForCode('999999'), [])
  assert.deepEqual(signalEventsForCode(''), [])
})

test('超过 MAX_EVENTS 时按 createdAt 裁掉最旧的，不是按数组顺序', () => {
  const seeded = []
  for (let i = 0; i < MAX_EVENTS; i++) {
    seeded.push({
      id: `s${i}`,
      code: '600519',
      source: 't0',
      label: `旧${i}`,
      bias: 'bull',
      price: 10,
      date: '2026-10-20',
      createdAt: i + 1
    })
  }
  memory.set(SIGNAL_EVENTS_KEY, JSON.stringify(seeded))

  assert.equal(recordSignalEvent(ev({ label: '新来的' })), true)
  const kept = listSignalEvents()
  assert.equal(kept.length, MAX_EVENTS)
  assert.equal(kept[0].label, '新来的')
  assert.equal(kept.some((event) => event.label === '旧0'), false)
  assert.equal(kept.some((event) => event.label === '旧1'), true)
})

test('clearSignalEvents 只删自己的键，不碰别的 localStorage', () => {
  memory.set('other-key', 'keep-me')
  recordSignalEvent(ev())
  assert.equal(listSignalEvents().length, 1)

  clearSignalEvents()
  assert.deepEqual(listSignalEvents(), [])
  assert.equal(memory.get('other-key'), 'keep-me')
})

test('坏 JSON / 非数组 / 脏条目都退回可用的空集，不抛异常', () => {
  memory.set(SIGNAL_EVENTS_KEY, '{ 这不是 JSON')
  assert.deepEqual(listSignalEvents(), [])

  memory.set(SIGNAL_EVENTS_KEY, '"不是数组"')
  assert.deepEqual(listSignalEvents(), [])

  memory.set(
    SIGNAL_EVENTS_KEY,
    JSON.stringify([
      { id: 'x' },
      { id: 'y', code: 'A', source: 't0', label: '甲', bias: 'bull', price: 0, date: '2026-10-20', createdAt: 1 },
      { id: 'ok', code: 'A', source: 't0', label: '甲', bias: 'bull', price: 10, date: '2026-10-20', createdAt: 5 }
    ])
  )
  assert.deepEqual(listSignalEvents().map((event) => event.id), ['ok'])
})

test('写入抛错（配额满 / 隐私模式）时 recordSignalEvent 返回 false 且不抛', () => {
  const original = localStorageStub.setItem
  localStorageStub.setItem = () => {
    throw new Error('QuotaExceededError')
  }
  try {
    assert.doesNotThrow(() => {
      assert.equal(recordSignalEvent(ev()), false)
    })
  } finally {
    localStorageStub.setItem = original
  }
})

test('无 localStorage 环境（Node / 隐私模式）安全降级：读空、写 false、清空不抛', () => {
  const saved = globalThis.localStorage
  delete globalThis.localStorage
  try {
    assert.deepEqual(listSignalEvents(), [])
    assert.deepEqual(signalEventsForCode('600519'), [])
    assert.equal(recordSignalEvent(ev()), false)
    assert.doesNotThrow(() => clearSignalEvents())
    assert.equal(summarizeWinRates(computeWinRates([], {})), '')
  } finally {
    globalThis.localStorage = saved
  }
})

// ───────────────────────────── 胜率计算 ─────────────────────────────

test('bull 涨算赢、bear 跌算赢：收益按方向归一', () => {
  const events = [
    stored({ id: 'bull', code: 'A', bias: 'bull', price: 10, date: '2026-10-20', createdAt: 1 }),
    stored({ id: 'bear', code: 'B', bias: 'bear', price: 10, date: '2026-10-20', createdAt: 2 })
  ]
  const result = computeWinRates(events, {
    // 看多：+15%；看空：-15% → 归一成 +15%
    A: bars([['2026-10-20', 10], ['2026-10-21', 10.5], ['2026-10-22', 11], ['2026-10-23', 11.5]]),
    B: bars([['2026-10-20', 10], ['2026-10-21', 9.5], ['2026-10-22', 9], ['2026-10-23', 8.5]])
  })

  assert.equal(result.buckets.length, 1)
  assert.equal(result.buckets[0].sample, 2)
  assert.equal(result.buckets[0].wins, 2)
  assert.equal(result.buckets[0].winRate, 100)
  assert.equal(result.buckets[0].avgReturn, 15)
})

test('bear 信号在价格上涨时算输，收益归一成负数', () => {
  const result = computeWinRates(
    [stored({ code: 'A', bias: 'bear', price: 10, date: '2026-10-20' })],
    { A: bars([['2026-10-21', 11]]) },
    { horizon: 1 }
  )
  assert.equal(result.buckets[0].wins, 0)
  assert.equal(result.buckets[0].sample, 1)
  assert.equal(result.buckets[0].avgReturn, -10)
})

test('持平（ret === 0）单列进 flats，既不进赢也不进输的分母', () => {
  const result = computeWinRates(
    [stored({ code: 'A', bias: 'bull', price: 10, date: '2026-10-20' })],
    { A: bars([['2026-10-21', 10], ['2026-10-22', 10], ['2026-10-23', 10]]) }
  )
  assert.equal(result.buckets[0].sample, 1)
  assert.equal(result.buckets[0].wins, 0)
  assert.equal(result.buckets[0].losses, 0)
  assert.equal(result.buckets[0].flats, 1)
  // 没有分出胜负 → null（「没结论」），不是 0（「全输」），也不是 100（「没输过」）
  assert.equal(result.buckets[0].winRate, null)
  assert.equal(result.buckets[0].avgReturn, 0)
})

test('持平不稀释胜率：3 赢 0 输 2 持平 → 胜率 100%，不是 60%', () => {
  // 5 条信号，各自 horizon=1 的出场依次是 +10%、+10%、+10%、0、0
  const barList = bars([
    ['2026-10-21', 11],
    ['2026-10-22', 11],
    ['2026-10-23', 11],
    ['2026-10-24', 10],
    ['2026-10-27', 10]
  ])
  const events = ['2026-10-20', '2026-10-21', '2026-10-22', '2026-10-23', '2026-10-24'].map(
    (date, i) => stored({ id: `e${i}`, code: 'A', bias: 'bull', price: 10, date, createdAt: i })
  )
  const result = computeWinRates(events, { A: barList }, { horizon: 1 })

  const bucket = result.buckets[0]
  assert.equal(bucket.sample, 5)
  assert.equal(bucket.wins, 3)
  assert.equal(bucket.flats, 2)
  assert.equal(bucket.winRate, 100)
})

test('后续 K 线不足时计入 pendingCount 且不进分母', () => {
  const events = [
    stored({ id: 'ok', code: 'A', date: '2026-10-20', price: 10, createdAt: 1 }),
    stored({ id: 'short', code: 'A', date: '2026-10-21', price: 10, createdAt: 2 }),
    stored({ id: 'nobars', code: 'B', date: '2026-10-20', price: 10, createdAt: 3 }),
    stored({ id: 'sameday', code: 'A', date: '2026-10-23', price: 10, createdAt: 4 })
  ]
  const result = computeWinRates(events, {
    A: bars([['2026-10-20', 10], ['2026-10-21', 10.2], ['2026-10-22', 10.4], ['2026-10-23', 10.6]])
  })

  assert.equal(result.totalCount, 4)
  assert.equal(result.pendingCount, 3)
  assert.equal(result.ignoredCount, 0)
  // 只有 10-20 那条走完了 3 个交易日；10-21 只剩 2 根、10-23 之后一根没有、B 没有序列
  assert.equal(result.buckets.length, 1)
  assert.equal(result.buckets[0].sample, 1)
  assert.equal(result.buckets[0].wins, 1)
})

test('信号当天的收盘价不能当出场价（date 严格大于）', () => {
  const result = computeWinRates(
    [stored({ code: 'A', date: '2026-10-20', price: 10 })],
    {
      // 信号日自己收盘 20（+100%），但那是未来数据，必须被跳过；出场取之后第 3 根
      A: bars([['2026-10-20', 20], ['2026-10-21', 10], ['2026-10-22', 10], ['2026-10-23', 11]])
    }
  )
  assert.equal(result.buckets[0].sample, 1)
  assert.equal(result.buckets[0].avgReturn, 10)
})

test('horizon 生效：取信号日之后第 horizon 个交易日的收盘价', () => {
  const barsA = bars([['2026-10-20', 10], ['2026-10-21', 11], ['2026-10-22', 12], ['2026-10-23', 13]])
  const events = [stored({ code: 'A', date: '2026-10-20', price: 10 })]

  assert.equal(computeWinRates(events, { A: barsA }, { horizon: 1 }).buckets[0].avgReturn, 10)
  assert.equal(computeWinRates(events, { A: barsA }, { horizon: 2 }).buckets[0].avgReturn, 20)
  assert.equal(computeWinRates(events, { A: barsA }, { horizon: 3 }).buckets[0].avgReturn, 30)
  // horizon 超出可用 K 线 → 待观察，不是失败
  assert.equal(computeWinRates(events, { A: barsA }, { horizon: 4 }).pendingCount, 1)
  assert.deepEqual(computeWinRates(events, { A: barsA }, { horizon: 4 }).buckets, [])
})

test('无效价格计入 ignoredCount：信号价非正、收盘价非正或非有限', () => {
  const events = [
    stored({ id: 'badprice', code: 'A', price: 0, date: '2026-10-20', createdAt: 1 }),
    stored({ id: 'badclose', code: 'B', price: 10, date: '2026-10-20', createdAt: 2 }),
    stored({ id: 'zeroclose', code: 'C', price: 10, date: '2026-10-20', createdAt: 3 })
  ]
  const result = computeWinRates(
    events,
    {
      A: bars([['2026-10-21', 11], ['2026-10-22', 12], ['2026-10-23', 13]]),
      // 停牌日收盘价可能是 0 / NaN，这些不是「没走完」，是「数据不可用」
      B: bars([['2026-10-21', 0], ['2026-10-22', -1], ['2026-10-23', Number.NaN]]),
      C: bars([['2026-10-21', 10], ['2026-10-22', 10], ['2026-10-23', 0]])
    },
    { horizon: 3 }
  )

  assert.equal(result.totalCount, 3)
  assert.equal(result.ignoredCount, 3)
  assert.equal(result.pendingCount, 0)
  assert.deepEqual(result.buckets, [])
})

test('不足 horizon 根时只是因为「还没走完」，不能当成数据不可用', () => {
  const result = computeWinRates(
    [stored({ code: 'A', date: '2026-10-20', price: 10 })],
    // 第 3 根根本不存在；就算中间某些收盘价是坏的，也不该提前判 invalid
    { A: bars([['2026-10-21', 0], ['2026-10-22', 0]]) },
    { horizon: 3 }
  )
  assert.equal(result.pendingCount, 1)
  assert.equal(result.ignoredCount, 0)
})

test('分组键是 label + source：同名标签不同来源不合并', () => {
  const events = [
    stored({ id: '1', code: 'A', label: '突破', source: 't0', date: '2026-10-20', createdAt: 1 }),
    stored({ id: '2', code: 'A', label: '突破', source: 'price-alert', date: '2026-10-20', createdAt: 2 }),
    stored({ id: '3', code: 'A', label: '突破', source: 't0', date: '2026-10-21', createdAt: 3 })
  ]
  const result = computeWinRates(events, {
    A: bars([['2026-10-21', 11], ['2026-10-22', 11], ['2026-10-23', 11], ['2026-10-24', 11]])
  })

  assert.equal(result.buckets.length, 2)
  const t0 = result.buckets.find((bucket) => bucket.source === 't0')
  assert.equal(t0.sample, 2)
  assert.equal(result.buckets.find((bucket) => bucket.source === 'price-alert').sample, 1)
})

test('buckets 按 sample 降序排列：样本多的先看', () => {
  const events = []
  const push = (label, source, count) => {
    for (let i = 0; i < count; i++) {
      events.push(stored({ code: 'A', label, source, date: '2026-10-20' }))
    }
  }
  push('甲', 't0', 1)
  push('乙', 't0', 3)
  push('丙', 'ma-cross', 2)

  const result = computeWinRates(events, { A: bars([['2026-10-21', 11]]) }, { horizon: 1 })
  assert.deepEqual(result.buckets.map((bucket) => bucket.label), ['乙', '丙', '甲'])
  assert.deepEqual(result.buckets.map((bucket) => bucket.sample), [3, 2, 1])
})

test('sample < minSample 时 winRate 照算，但 sampleWarning 给提示', () => {
  const thin = [
    stored({ code: 'A', label: '放量急拉', date: '2026-10-20', createdAt: 1 }),
    stored({ code: 'A', label: '放量急拉', date: '2026-10-21', createdAt: 2 })
  ]
  const thinResult = computeWinRates(
    thin,
    { A: bars([['2026-10-21', 11], ['2026-10-22', 11], ['2026-10-23', 11], ['2026-10-24', 9]]) },
    { horizon: 1 }
  )
  assert.equal(thinResult.buckets[0].sample, 2)
  assert.notEqual(thinResult.buckets[0].winRate, null)
  assert.match(sampleWarning(thinResult), /样本少于 5 条/)
  assert.match(sampleWarning(thinResult), /放量急拉/)
  // 自定义阈值：2 条对阈值 2 来说已经够了
  assert.equal(sampleWarning(thinResult, 2), '')

  const enough = []
  for (let i = 0; i < 5; i++) {
    enough.push(stored({ code: 'A', label: '放量急拉', date: '2026-10-20' }))
  }
  const enoughResult = computeWinRates(enough, { A: bars([['2026-10-21', 11], ['2026-10-22', 11], ['2026-10-23', 11], ['2026-10-24', 11]]) })
  assert.equal(sampleWarning(enoughResult), '')
})

test('sampleWarning：有记录但全都还没走完时给专门的提示', () => {
  const result = computeWinRates(
    [stored({ code: 'A', date: '2026-10-20' })],
    { A: bars([['2026-10-21', 11]]) }
  )
  assert.equal(result.pendingCount, 1)
  const warning = sampleWarning(result)
  assert.match(warning, /还没有足够的后续日 K/)
  assert.match(warning, /1 条记录/)
})

test('summarizeWinRates 输出样本量、已结算/待观察数与免责口径', () => {
  const events = [
    stored({ id: '1', code: 'A', label: '放量急拉', date: '2026-10-20', createdAt: 1 }),
    stored({ id: '2', code: 'A', label: '放量急拉', date: '2026-10-21', createdAt: 2 }),
    stored({ id: '3', code: 'A', label: '放量急拉', date: '2026-10-22', createdAt: 3 })
  ]
  const result = computeWinRates(events, {
    A: bars([['2026-10-21', 11], ['2026-10-22', 11], ['2026-10-23', 11], ['2026-10-24', 11]])
  })

  // horizon 默认 3：10-20 与 10-21 能结算，10-22 之后只剩 2 根 → 待观察
  assert.equal(result.pendingCount, 1)
  const text = summarizeWinRates(result)
  assert.match(text, /^共 3 条记录，2 条已结算、1 条待观察/)
  assert.match(text, /『放量急拉』胜率 100\.0%（n=2）/)
  // 免责口径不许丢：这些数字只描述「用户实际看过的那部分样本」
  assert.ok(text.includes(SIGNAL_HISTORY_DISCLAIMER))
  assert.match(text, /不代表策略胜率/)
  assert.match(text, /按日 K 收盘估算/)
})

test('summarizeWinRates 在有 ignore 时把不可用条数说清楚', () => {
  const result = computeWinRates(
    [stored({ code: 'A', price: 0 })],
    {},
    { horizon: 1 }
  )
  const text = summarizeWinRates(result)
  assert.match(text, /另有 1 条价格不可用/)
})

test('空数据不抛：computeWinRates / summarize / sampleWarning 都安全', () => {
  const empty = computeWinRates([], {})
  assert.deepEqual(empty.buckets, [])
  assert.equal(empty.pendingCount, 0)
  assert.equal(empty.ignoredCount, 0)
  assert.equal(empty.totalCount, 0)
  assert.equal(empty.horizon, DEFAULT_HORIZON)
  assert.equal(summarizeWinRates(empty), '')
  assert.equal(sampleWarning(empty), '')
  // 调用方可能传进来一个只有 buckets 的对象（老缓存 / 半成品），也不能炸
  assert.equal(summarizeWinRates({ buckets: [] }), '')
  assert.equal(sampleWarning({ buckets: [] }), '')
  assert.doesNotThrow(() => computeWinRates([], {}))
})

// ───────────────────────────── 盈亏比 / 多持有期 ─────────────────────────────

test('avgWin / avgLoss / profitFactor：赢的均幅与输的均幅分开给', () => {
  // 3 条：+20%（赢）、+10%（赢）、-15%（输，归一后仍是 -15%）
  const events = [
    stored({ id: '1', code: 'A', price: 10, date: '2026-10-20', createdAt: 1 }),
    stored({ id: '2', code: 'A', price: 10, date: '2026-10-21', createdAt: 2 }),
    stored({ id: '3', code: 'A', price: 10, date: '2026-10-22', createdAt: 3 })
  ]
  const result = computeWinRates(
    events,
    { A: bars([['2026-10-21', 12], ['2026-10-22', 11], ['2026-10-23', 8.5], ['2026-10-24', 8.5]]) },
    { horizon: 1 }
  )
  const bucket = result.buckets[0]
  assert.equal(bucket.wins, 2)
  assert.equal(bucket.losses, 1)
  assert.equal(bucket.avgWin, 15) // (20 + 10) / 2
  assert.equal(bucket.avgLoss, 15) // |−15| / 1，存正数好和 avgWin 直接比
  assert.equal(bucket.profitFactor, 2) // 总盈利 30 / 总亏损 15
  assert.equal(bucket.best, 20)
  assert.equal(bucket.worst, -15)
})

test('全赢时 profitFactor 是 null 而不是 Infinity', () => {
  const result = computeWinRates(
    [stored({ code: 'A', price: 10, date: '2026-10-20' })],
    { A: bars([['2026-10-21', 11]]) },
    { horizon: 1 }
  )
  const bucket = result.buckets[0]
  assert.equal(bucket.avgLoss, null)
  // Infinity 会让界面打印出 "Infinity"，也会让 JSON.stringify 变成 null
  assert.equal(bucket.profitFactor, null)
})

test('全输时 profitFactor 是 null 而不是 0', () => {
  const result = computeWinRates(
    [stored({ code: 'A', price: 10, date: '2026-10-20' })],
    { A: bars([['2026-10-21', 9]]) },
    { horizon: 1 }
  )
  const bucket = result.buckets[0]
  assert.equal(bucket.avgWin, null)
  assert.equal(bucket.avgLoss, 10)
  // 0 会被读成「不赚不亏」，实际是「一次没赢」
  assert.equal(bucket.profitFactor, null)
})

test('最好/最差成绩按方向归一：bear 信号跌得越多 best 越大', () => {
  const events = [
    stored({ id: '1', code: 'A', bias: 'bear', price: 10, date: '2026-10-20', createdAt: 1 }),
    stored({ id: '2', code: 'A', bias: 'bear', price: 10, date: '2026-10-21', createdAt: 2 })
  ]
  const result = computeWinRates(
    events,
    { A: bars([['2026-10-21', 8], ['2026-10-22', 11]]) },
    { horizon: 1 }
  )
  const bucket = result.buckets[0]
  assert.equal(bucket.best, 20) // 10 → 8，看空赚 20%
  assert.equal(bucket.worst, -10) // 10 → 11，看空亏 10%
})

test('多持有期给出不同结果：同一批信号 1/3/5/10 日各算一遍', () => {
  const barList = bars([
    ['2026-10-20', 10],
    ['2026-10-21', 10.2],
    ['2026-10-22', 10.5],
    ['2026-10-23', 11],
    ['2026-10-24', 11.2],
    ['2026-10-27', 11.5],
    ['2026-10-28', 12],
    ['2026-10-29', 12.5],
    ['2026-10-30', 13],
    ['2026-10-31', 13.5],
    ['2026-11-03', 14]
  ])
  const events = [stored({ code: 'A', price: 10, date: '2026-10-20' })]

  const returns = HORIZON_CHOICES.map(
    (horizon) => computeWinRates(events, { A: barList }, { horizon }).buckets[0]?.avgReturn ?? null
  )
  // 1 日 = 10.2、3 日 = 11、5 日 = 11.5、10 日 = 14
  assert.deepEqual(returns, [2, 10, 15, 40])
  // 每次结果都带着自己的 horizon，界面据此显示口径
  assert.deepEqual(
    HORIZON_CHOICES.map(
      (horizon) => computeWinRates(events, { A: barList }, { horizon }).horizon
    ),
    [1, 3, 5, 10]
  )
})

test('持有期越长待观察越多：同批信号 10 日档 pendingCount 更大', () => {
  // 信号日后只有 3 根 K 线：1 日档和 3 日档都能结算，4 日档（更别提 5/10 日档）只能待观察
  const barList = bars([
    ['2026-10-20', 10],
    ['2026-10-21', 10.1],
    ['2026-10-22', 10.2],
    ['2026-10-23', 10.3]
  ])
  const events = [stored({ code: 'A', price: 10, date: '2026-10-20' })]
  assert.equal(computeWinRates(events, { A: barList }, { horizon: 1 }).pendingCount, 0)
  assert.equal(computeWinRates(events, { A: barList }, { horizon: 3 }).pendingCount, 0)
  assert.equal(computeWinRates(events, { A: barList }, { horizon: 5 }).pendingCount, 1)
  assert.equal(computeWinRates(events, { A: barList }, { horizon: 10 }).pendingCount, 1)
})
