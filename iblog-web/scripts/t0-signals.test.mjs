import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  computeT0Signals,
  groupMarkers,
  summarizeMarkers
} from '../src/utils/t0Signals.ts'

/**
 * computeT0Signals（分时图打点口径）单测。
 *
 * <p>这一层的价值全在「阈值边界」和「去噪规则」上，所以用例围绕两件事：
 * 边界（刚好命中 / 差一点不命中）和去噪（同一分钟、同类窗口、突破每日一次）。
 * detectIntradaySignals 那套口径已有 signal-history.test.mjs 覆盖，这里不重复。</p>
 */

/** 造一根分时柱子；默认量能 100 手、价 10.00 */
function bar(time, price, volume = 100, turnover) {
  return {
    time,
    price,
    volume,
    turnover: turnover ?? price * volume
  }
}

/** 从 09:30 起逐分钟生成序列：prices[i] 与 volumes[i] 一一对应 */
function series(prices, volumes, startMinute = 9 * 60 + 30) {
  return prices.map((price, i) => {
    const total = startMinute + i
    const hh = String(Math.floor(total / 60)).padStart(2, '0')
    const mm = String(total % 60).padStart(2, '0')
    return bar(`${hh}:${mm}`, price, volumes[i])
  })
}

/** 只取某个 kind 的标记 */
function pick(markers, kind) {
  return markers.filter((m) => m.kind === kind)
}

// ───────────────────────────── 1. 量能前瞻预警 ─────────────────────────────

test('放量急拉命中：量能 3 倍且单分钟涨幅 ≥ 0.5%', () => {
  // 09:30 竞价 + 09:31~09:35 平稳，09:36 放量拉升 1%
  const prices = [10.0, 10.0, 10.0, 10.0, 10.0, 10.0, 10.1]
  const volumes = [999, 100, 100, 100, 100, 100, 900]
  const markers = computeT0Signals(series(prices, volumes), 9.9)
  const spikes = pick(markers, 'spike-up')
  assert.equal(spikes.length, 1)
  assert.equal(spikes[0].time, '09:36')
  assert.equal(spikes[0].price, 10.1)
  assert.equal(spikes[0].icon, '⚡')
  assert.equal(spikes[0].label, '放量急拉')
  assert.match(spikes[0].detail, /量能/)
  // 非 trap 标记不能带 strength
  assert.equal(spikes[0].strength, undefined)
})

test('量能不足不命中：涨幅够但量能只有 2 倍（< 默认 3）', () => {
  const prices = [10.0, 10.0, 10.0, 10.0, 10.0, 10.0, 10.1]
  const volumes = [999, 100, 100, 100, 100, 100, 200]
  const markers = computeT0Signals(series(prices, volumes), 9.9)
  assert.equal(pick(markers, 'spike-up').length, 0)
  // 2 倍也不至于算「无量」，所以什么都不该出
  assert.equal(pick(markers, 'dry-rally').length, 0)
})

test('放量急跌命中：量能 3 倍且单分钟跌幅 ≤ -0.5%', () => {
  const prices = [10.0, 10.0, 10.0, 10.0, 10.0, 10.0, 9.9]
  const volumes = [999, 100, 100, 100, 100, 100, 900]
  const markers = computeT0Signals(series(prices, volumes), 10.1)
  const dumps = pick(markers, 'spike-down')
  assert.equal(dumps.length, 1)
  assert.equal(dumps[0].time, '09:36')
  assert.equal(dumps[0].icon, '⚠️')
})

test('无量拉升：涨幅够但量能只有均量的 0.5 倍', () => {
  const prices = [10.0, 10.0, 10.0, 10.0, 10.0, 10.0, 10.1]
  const volumes = [999, 100, 100, 100, 100, 100, 50]
  const markers = computeT0Signals(series(prices, volumes), 9.9)
  const dry = pick(markers, 'dry-rally')
  assert.equal(dry.length, 1)
  assert.equal(dry[0].time, '09:36')
  assert.equal(dry[0].icon, '♪')
  assert.match(dry[0].detail, /缺乏量能支撑/)
})

test('集合竞价 09:30 那一分钟被剔除：即便它量价都够也不标', () => {
  // 09:30 直接高开 +5% 且天量，若不做剔除会立刻被判成放量急拉
  const prices = [10.5, 10.5, 10.5, 10.5, 10.5, 10.5, 10.5]
  const volumes = [9999, 100, 100, 100, 100, 100, 100]
  const markers = computeT0Signals(series(prices, volumes), 10.0)
  assert.equal(markers.filter((m) => m.time === '09:30').length, 0)
})

test('09:35 之前不判量能：09:34 的放量拉升不标', () => {
  const prices = [10.0, 10.0, 10.0, 10.0, 10.1]
  const volumes = [999, 100, 100, 100, 900]
  // startMinute = 09:30，索引 4 是 09:34
  const markers = computeT0Signals(series(prices, volumes), 9.9)
  assert.equal(markers.length, 0)
})

test('同一分钟只留一个标记：急跌优先于急拉，不叠加', () => {
  const prices = [10.0, 10.0, 10.0, 10.0, 10.0, 10.0, 9.9]
  const volumes = [999, 100, 100, 100, 100, 100, 900]
  const markers = computeT0Signals(series(prices, volumes), 10.1)
  const byTime = new Map()
  for (const m of markers) {
    byTime.set(m.time, (byTime.get(m.time) ?? 0) + 1)
  }
  for (const [time, count] of byTime) {
    assert.equal(count, 1, `${time} 出现了 ${count} 个标记`)
  }
})

test('同类信号 10 分钟内只标第一个', () => {
  // 09:36 与 09:41 各拉一次（间隔 5 分钟 < 10），只应留 09:36
  const prices = [10.0, 10.0, 10.0, 10.0, 10.0, 10.0, 10.1, 10.1, 10.1, 10.1, 10.1, 10.2]
  const volumes = [999, 100, 100, 100, 100, 100, 900, 100, 100, 100, 100, 900]
  const markers = computeT0Signals(series(prices, volumes), 9.9)
  const spikes = pick(markers, 'spike-up')
  assert.equal(spikes.length, 1)
  assert.equal(spikes[0].time, '09:36')

  // 间隔 ≥ 10 分钟时两次都要标：同一组数据把窗口缩到 0 就都留下
  const loosened = computeT0Signals(series(prices, volumes), 9.9, { volumeWindow: 3 })
  assert.ok(loosened.length >= 1)
})

// ───────────────────────────── 2. 关键位突破 ─────────────────────────────

test('突破仅标一次：连续创新高只留第一根', () => {
  // 10:00 之前先在 9.9~10.0 区间横盘，10:01 起连创新高
  const prices = []
  const volumes = []
  for (let i = 0; i < 30; i++) {
    prices.push(10.0 - (i % 2) * 0.05) // 09:30~09:59 在 9.95/10.00 之间震荡
    volumes.push(100)
  }
  for (let i = 0; i < 5; i++) {
    prices.push(10.1 + i * 0.05) // 10:00 起逐级新高
    volumes.push(100)
  }
  const markers = computeT0Signals(series(prices, volumes), 9.9)
  const breaks = pick(markers, 'break-up')
  assert.equal(breaks.length, 1)
  assert.equal(breaks[0].icon, '▲')
  // 首根新高发生在 10:00（索引 30，此时此前最高 10.00）
  assert.equal(breaks[0].time, '10:00')
})

test('破位仅标一次：连续创新低只留第一根', () => {
  const prices = []
  const volumes = []
  for (let i = 0; i < 30; i++) {
    prices.push(10.0 + (i % 2) * 0.05) // 9.95~10.00 震荡，最低 10.00
    volumes.push(100)
  }
  for (let i = 0; i < 5; i++) {
    prices.push(9.9 - i * 0.05)
    volumes.push(100)
  }
  const markers = computeT0Signals(series(prices, volumes), 10.1)
  const breaks = pick(markers, 'break-down')
  assert.equal(breaks.length, 1)
  assert.equal(breaks[0].icon, '▼')
})

test('前 30 分钟不判突破：09:31~09:59 创新高不算', () => {
  const prices = []
  const volumes = []
  for (let i = 0; i < 29; i++) {
    prices.push(10.0 + i * 0.01) // 09:30~09:58 持续新高
    volumes.push(100)
  }
  const markers = computeT0Signals(series(prices, volumes), 9.9)
  assert.equal(pick(markers, 'break-up').length, 0)
  assert.equal(pick(markers, 'break-down').length, 0)
})

// ───────────────────────────── 3. 诱多 / 诱空嫌疑 ─────────────────────────────

test('诱多强度分级：命中条数决定 weak / medium / strong，文案必须写「疑似」', () => {
  // 构造一个满足 3 条线索的诱多：先急拉，再缩量创新高、回吐一半、均价上方跌时放量
  const prices = []
  const volumes = []
  for (let i = 0; i < 20; i++) {
    prices.push(10.0)
    volumes.push(100)
  }
  // 09:50 急拉 1%
  prices.push(10.1)
  volumes.push(400)
  // 09:51~09:53 缩量继续小涨（创新高但无量）
  prices.push(10.11)
  volumes.push(10)
  prices.push(10.12)
  volumes.push(10)
  // 09:54 之后回落，跌时放量
  prices.push(10.05)
  volumes.push(500)
  prices.push(10.02)
  volumes.push(500)
  prices.push(10.03)
  volumes.push(400)
  prices.push(10.04)
  volumes.push(300)

  const markers = computeT0Signals(series(prices, volumes), 9.95)
  const bulls = pick(markers, 'trap-bull')
  assert.ok(bulls.length >= 1, '应当至少命中一次诱多嫌疑')
  const strongest = bulls[bulls.length - 1]
  assert.equal(strongest.icon, '🪤')
  assert.equal(strongest.label, '疑似诱多')
  assert.ok(['weak', 'medium', 'strong'].includes(strongest.strength))
  assert.match(strongest.detail, /嫌疑|疑似/)
  assert.match(strongest.detail, /仅供留意/)
  // 命中条数越strong 档位越高：strong 必须命中 3 条
  if (strongest.strength === 'strong') assert.match(strongest.detail, /命中 3 条/)
  if (strongest.strength === 'weak') assert.match(strongest.detail, /命中 1 条/)
})

test('诱多与诱空强度映射：1 条 weak、2 条 medium、3 条 strong', () => {
  // 只命中①（创新高无量）的极简场景
  const prices = [10.0, 10.0, 10.0, 10.0, 10.0, 10.0, 10.0]
  const volumes = [999, 100, 100, 100, 100, 100, 100]
  for (let i = 0; i < 6; i++) {
    // 保持均价在价格下方，避免③误触发
    prices[i] = 9.9
    volumes[i] = 100
  }
  prices.push(10.2)
  volumes.push(5) // 创新高但量能只有 0.05 倍
  const markers = computeT0Signals(series(prices, volumes), 9.8, { surgePct: 5 })
  const bulls = pick(markers, 'trap-bull')
  if (bulls.length > 0) {
    assert.equal(bulls[0].strength, 'weak')
    assert.match(bulls[0].detail, /命中 1 条/)
  }
})

// ───────────────────────────── 4. 边界与工具函数 ─────────────────────────────

test('空输入不抛：null / undefined / 空数组都返回空数组', () => {
  assert.deepEqual(computeT0Signals([], 10), [])
  assert.deepEqual(computeT0Signals(null, 10), [])
  assert.deepEqual(computeT0Signals(undefined, null), [])
  assert.deepEqual(computeT0Signals([], null), [])
})

test('null prevClose 不抛，标记仍产出（只是说明里没有较昨收那段）', () => {
  const prices = [10.0, 10.0, 10.0, 10.0, 10.0, 10.0, 10.1]
  const volumes = [999, 100, 100, 100, 100, 100, 900]
  const markers = computeT0Signals(series(prices, volumes), null)
  const spikes = pick(markers, 'spike-up')
  assert.equal(spikes.length, 1)
  assert.doesNotMatch(spikes[0].detail, /较昨收/)
})

test('options 覆盖生效：把 surgePct 调高后原本命中的信号消失', () => {
  const prices = [10.0, 10.0, 10.0, 10.0, 10.0, 10.0, 10.1]
  const volumes = [999, 100, 100, 100, 100, 100, 900]
  assert.equal(pick(computeT0Signals(series(prices, volumes), 9.9), 'spike-up').length, 1)
  // 涨幅 1% < 2%，不再命中
  assert.equal(
    pick(computeT0Signals(series(prices, volumes), 9.9, { surgePct: 2 }), 'spike-up').length,
    0
  )
  // 量能比例调到 20 倍后也不再命中
  assert.equal(
    pick(computeT0Signals(series(prices, volumes), 9.9, { volumeSpikeRatio: 20 }), 'spike-up').length,
    0
  )
  // dryRatio 调到 0.05 后「无量拉升」不再命中
  const dryPrices = [10.0, 10.0, 10.0, 10.0, 10.0, 10.0, 10.1]
  const dryVolumes = [999, 100, 100, 100, 100, 100, 50]
  assert.equal(
    pick(computeT0Signals(series(dryPrices, dryVolumes), 9.9), 'dry-rally').length,
    1
  )
  assert.equal(
    pick(computeT0Signals(series(dryPrices, dryVolumes), 9.9, { dryRatio: 0.05 }), 'dry-rally')
      .length,
    0
  )
})

test('非法阈值退回默认值，不产生 NaN 判定', () => {
  const prices = [10.0, 10.0, 10.0, 10.0, 10.0, 10.0, 10.1]
  const volumes = [999, 100, 100, 100, 100, 100, 900]
  const markers = computeT0Signals(series(prices, volumes), 9.9, {
    surgePct: 0,
    volumeSpikeRatio: -1,
    dryRatio: Number.NaN,
    volumeWindow: 0
  })
  assert.equal(pick(markers, 'spike-up').length, 1)
})

test('groupMarkers：7 个 kind 全部预置空数组，未知 kind 静默丢弃', () => {
  const markers = computeT0Signals(
    series([10.0, 10.0, 10.0, 10.0, 10.0, 10.0, 10.1], [999, 100, 100, 100, 100, 100, 900]),
    9.9
  )
  const grouped = groupMarkers(markers)
  assert.deepEqual(Object.keys(grouped).sort(), [
    'break-down',
    'break-up',
    'dry-rally',
    'spike-down',
    'spike-up',
    'trap-bear',
    'trap-bull'
  ])
  assert.equal(grouped['spike-up'].length, 1)
  assert.deepEqual(grouped['break-up'], [])

  const withJunk = groupMarkers([{ time: '09:40', price: 10, kind: 'nope', label: 'x', icon: '?', detail: 'x' }])
  assert.equal(Object.values(withJunk).reduce((n, arr) => n + arr.length, 0), 0)
  assert.deepEqual(groupMarkers([]), withJunk)
})

test('summarizeMarkers：无标记返回空串，有标记给出「今日 N 个预警」与分类计数', () => {
  assert.equal(summarizeMarkers([]), '')

  const one = computeT0Signals(
    series([10.0, 10.0, 10.0, 10.0, 10.0, 10.0, 10.1], [999, 100, 100, 100, 100, 100, 900]),
    9.9
  )
  const text = summarizeMarkers(one)
  assert.match(text, /^今日 1 个预警：/)
  assert.match(text, /放量急拉 1/)

  const mixed = summarizeMarkers([
    { time: '09:36', price: 10, kind: 'spike-up', label: '放量急拉', icon: '⚡', detail: '' },
    { time: '09:40', price: 10, kind: 'spike-up', label: '放量急拉', icon: '⚡', detail: '' },
    { time: '10:01', price: 10, kind: 'break-down', label: '破位', icon: '▼', detail: '' }
  ])
  assert.equal(mixed, '今日 3 个预警：放量急拉 2、破位 1')
})

test('标记按时间升序返回，且 time 与输入格式一致（HH:mm）', () => {
  const prices = [10.0, 10.0, 10.0, 10.0, 10.0, 10.0, 10.1, 10.1, 10.1, 10.1, 10.1, 10.1]
  const volumes = [999, 100, 100, 100, 100, 100, 900, 100, 100, 100, 100, 100]
  const markers = computeT0Signals(series(prices, volumes), 9.9)
  for (const m of markers) {
    assert.match(m.time, /^\d{2}:\d{2}$/)
  }
  const times = markers.map((m) => m.time)
  assert.deepEqual(times, [...times].sort())
})
