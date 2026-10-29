import { test } from 'node:test'
import assert from 'node:assert/strict'
import { registerHooks } from 'node:module'

/**
 * 均线穿越提醒测试。
 *
 * <p>直接导入真实的 TS 源文件（Node 22.6+ 的 `--experimental-strip-types`），
 * 不复制一份逻辑来测 —— 副本永远会通过，却证明不了线上跑的那份是对的。</p>
 *
 * <p>被测模块依赖三样浏览器环境的东西，都在本文件内用最小 stub 兜住，
 * 不为 Node 去改产品代码：</p>
 * <ul>
 *   <li>`localStorage`：内存实现</li>
 *   <li>`Notification`：记录 title / body，用于断言通知文案</li>
 *   <li>`@/api/stock`：用 module hooks 换成一个可由测试注入日 K 的假模块
 *       （真实的那个走 axios + `import.meta.env`，Node 下加载不了）</li>
 * </ul>
 *
 * <p>运行：`node --experimental-strip-types --test scripts/ma-alerts.test.mjs`</p>
 */

// ── 环境 stub（必须在导入被测模块之前就位）────────────────────

class MemoryStorage {
  #map = new Map()

  getItem(key) {
    return this.#map.has(key) ? this.#map.get(key) : null
  }

  setItem(key, value) {
    this.#map.set(String(key), String(value))
  }

  removeItem(key) {
    this.#map.delete(key)
  }

  clear() {
    this.#map.clear()
  }
}

globalThis.localStorage = new MemoryStorage()

/** 记录所有发出的通知 */
globalThis.__notifications = []

class FakeNotification {
  static permission = 'granted'

  static requestPermission() {
    return Promise.resolve('granted')
  }

  constructor(title, options = {}) {
    globalThis.__notifications.push({ title, body: options.body, tag: options.tag })
  }
}

globalThis.Notification = FakeNotification

// ── 模块解析钩子：把 '@/api/stock' 换成可控的假模块 ──────────────

const SRC = new URL('../src/', import.meta.url)
const STOCK_STUB = 'iblog-test:stock-stub'

const STOCK_STUB_SOURCE = `
const bars = new Map()
export function __setBars(code, list) { bars.set(code, list) }
export function __clearBars() { bars.clear() }
export function fetchKline(code) {
  const list = bars.get(code)
  if (!list) return Promise.reject(new Error('测试未注入日K: ' + code))
  return Promise.resolve(list)
}
`

registerHooks({
  resolve(specifier, context, next) {
    if (specifier === STOCK_STUB) {
      return { url: STOCK_STUB, shortCircuit: true }
    }
    if (specifier === '@/api/stock') {
      return { url: STOCK_STUB, shortCircuit: true }
    }
    // 其余 @/ 别名（marketTime / stockIndicators）照常指向 src
    if (specifier.startsWith('@/')) {
      return { url: new URL(`${specifier.slice(2)}.ts`, SRC).href, shortCircuit: true }
    }
    // 产品代码里的相对导入按 Vite 习惯省略扩展名（`./useNotify`），
    // Node 的 ESM 解析要求写全，这里补一次 .ts 即可，无需改动源码。
    // 只对 src/ 下的模块生效：node_modules 里的包有自己的解析规则，不能代劳
    if (
      /^\.{1,2}\//.test(specifier) &&
      !/\.[cm]?[jt]s$/.test(specifier) &&
      context.parentURL?.startsWith(SRC.href)
    ) {
      return { url: new URL(`${specifier}.ts`, context.parentURL).href, shortCircuit: true }
    }
    return next(specifier, context)
  },
  load(url, context, next) {
    if (url === STOCK_STUB) {
      return { format: 'module', source: STOCK_STUB_SOURCE, shortCircuit: true }
    }
    return next(url, context)
  }
})

const stock = await import(STOCK_STUB)
const { useMaAlerts, MA_PERIODS } = await import('../src/composables/useMaAlerts.ts')

const { maAlerts, history, addMaAlert, checkMaAlerts, hasNotifiedToday, countEnabledForCode } = useMaAlerts()

// ── 测试辅助 ────────────────────────────────────────────────

/**
 * 造日 K：最后一根刻意不是「今天」，模拟盘中上游还没补当日那根。
 * 被测实现应当把本轮收盘价补到序列末尾再算 MA。
 */
function makeBars(closes, startDay = 1) {
  return closes.map((close, i) => ({
    date: `2026-10-${String(startDay + i).padStart(2, '0')}`,
    open: close,
    close,
    high: close,
    low: close,
    volume: 1000,
    turnover: 1000
  }))
}

/** 清空 store 与 stub，保证用例互不影响（模块是单例，只能擦状态） */
function resetAll() {
  maAlerts.value = []
  history.value = []
  globalThis.__notifications = []
  stock.__clearBars()
}

const UP_BARS = makeBars([12, 12, 12, 12, 12, 10, 10, 10, 10])
// 上穿场景：MA5 = (10*4 + 11) / 5 = 10.2，昨收 10 → 今收 11 跨过 10.2
const UP_PREV = 10
const UP_CLOSE = 11
const UP_MA = 10.2
// 下穿场景：MA5 = (10*4 + 9.5) / 5 = 9.9，昨收 10 → 今收 9.5 跌破 9.9
const DOWN_PREV = 10
const DOWN_CLOSE = 9.5
const DOWN_MA = 9.9

test('上穿触发：昨收在下、今收在上', async () => {
  resetAll()
  stock.__setBars('600519', UP_BARS)
  const alert = addMaAlert({ code: '600519', name: '贵州茅台', period: 5, direction: 'up' })
  assert.ok(alert)

  const triggered = await checkMaAlerts('600519', UP_PREV, UP_CLOSE, '2026-10-29')

  assert.equal(triggered.length, 1)
  assert.equal(triggered[0].direction, 'up')
  assert.equal(triggered[0].period, 5)
  assert.ok(Math.abs(triggered[0].ma - UP_MA) < 1e-9)
  assert.equal(history.value.length, 1)
  assert.equal(hasNotifiedToday('600519', 5, '2026-10-29'), true)
  // 触发后 store 里的那条要带上「今日已通知」与触发时间，否则下一轮会重复弹
  const stored = maAlerts.value.find((a) => a.id === alert.id)
  assert.equal(stored.lastNotifiedDate, '2026-10-29')
  assert.ok(typeof stored.triggeredAt === 'number')
})

test('通知文案包含代码、名称、方向、周期、均线值与收盘价', async () => {
  resetAll()
  stock.__setBars('600519', UP_BARS)
  addMaAlert({ code: '600519', name: '贵州茅台', period: 5, direction: 'up' })

  await checkMaAlerts('600519', UP_PREV, UP_CLOSE, '2026-10-29')

  assert.equal(globalThis.__notifications.length, 1)
  const { title, body } = globalThis.__notifications[0]
  assert.match(title, /上穿/)
  assert.match(title, /MA5/)
  assert.equal(body, '贵州茅台(600519) 收盘价 11.00 上穿 MA5(10.20)')
})

test('下穿触发：昨收在上、今收在下', async () => {
  resetAll()
  stock.__setBars('000001', UP_BARS)
  addMaAlert({ code: '000001', name: '平安银行', period: 5, direction: 'down' })

  const triggered = await checkMaAlerts('000001', DOWN_PREV, DOWN_CLOSE, '2026-10-29')

  assert.equal(triggered.length, 1)
  assert.equal(triggered[0].direction, 'down')
  assert.ok(Math.abs(triggered[0].ma - DOWN_MA) < 1e-9)
  assert.match(globalThis.__notifications[0].title, /下穿/)
})

test('both 双向：上穿与下穿都能触发（跨日各一次）', async () => {
  resetAll()
  stock.__setBars('300750', UP_BARS)
  addMaAlert({ code: '300750', name: '宁德时代', period: 5, direction: 'both' })

  const up = await checkMaAlerts('300750', UP_PREV, UP_CLOSE, '2026-10-28')
  assert.equal(up.length, 1)
  assert.equal(up[0].direction, 'up')

  const down = await checkMaAlerts('300750', DOWN_PREV, DOWN_CLOSE, '2026-10-29')
  assert.equal(down.length, 1)
  assert.equal(down[0].direction, 'down')
  assert.equal(history.value.length, 2)
})

test('方向不符不触发', async () => {
  resetAll()
  stock.__setBars('600519', UP_BARS)
  addMaAlert({ code: '600519', name: '贵州茅台', period: 5, direction: 'up' })

  // 只配了上穿，却发生下穿 → 不该有任何通知
  const triggered = await checkMaAlerts('600519', DOWN_PREV, DOWN_CLOSE, '2026-10-29')

  assert.deepEqual(triggered, [])
  assert.equal(history.value.length, 0)
  assert.equal(globalThis.__notifications.length, 0)
})

test('同一交易日第二次穿越不再通知（幂等）', async () => {
  resetAll()
  stock.__setBars('600519', UP_BARS)
  addMaAlert({ code: '600519', name: '贵州茅台', period: 5, direction: 'both' })

  const first = await checkMaAlerts('600519', UP_PREV, UP_CLOSE, '2026-10-29')
  assert.equal(first.length, 1)

  // 盘后到另一个上游价位再来一次同样的穿越，也不该再弹
  const second = await checkMaAlerts('600519', UP_PREV, UP_CLOSE + 1, '2026-10-29')
  assert.deepEqual(second, [])
  assert.equal(history.value.length, 1, '同一交易日重复判定不得写入历史')
  assert.equal(globalThis.__notifications.length, 1)
})

test('跨日重置后能再次通知', async () => {
  resetAll()
  stock.__setBars('600519', UP_BARS)
  addMaAlert({ code: '600519', name: '贵州茅台', period: 5, direction: 'up' })

  assert.equal((await checkMaAlerts('600519', UP_PREV, UP_CLOSE, '2026-10-28')).length, 1)
  // 次日同样的穿越，应当重新提醒
  assert.equal((await checkMaAlerts('600519', UP_PREV, UP_CLOSE, '2026-10-29')).length, 1)
  assert.equal(history.value.length, 2)
  // lastNotifiedDate 是「最后一次」，所以只有最新那天为 true；两天都通知过看历史
  assert.deepEqual(
    history.value.map((h) => h.date),
    ['2026-10-28', '2026-10-29']
  )
  assert.equal(hasNotifiedToday('600519', 5, '2026-10-29'), true)
  assert.equal(hasNotifiedToday('600519', 5, '2026-10-30'), false)
})

test('无前值（首轮）不判定，只记基准', async () => {
  resetAll()
  stock.__setBars('600519', UP_BARS)
  addMaAlert({ code: '600519', name: '贵州茅台', period: 5, direction: 'both' })

  const first = await checkMaAlerts('600519', null, UP_CLOSE, '2026-10-29')
  assert.deepEqual(first, [])
  assert.equal(history.value.length, 0)
  assert.equal(globalThis.__notifications.length, 0)

  // 基准建立后，第二轮才判定；这一次 11 → 12 且 MA5 随之上移，仍算一次上穿
  const second = await checkMaAlerts('600519', 10, 12, '2026-10-29')
  assert.equal(second.length, 1)
})

test('停牌（收盘价为 null/0）不判定', async () => {
  resetAll()
  stock.__setBars('600519', UP_BARS)
  addMaAlert({ code: '600519', name: '贵州茅台', period: 5, direction: 'both' })

  assert.deepEqual(await checkMaAlerts('600519', UP_PREV, null, '2026-10-29'), [])
  assert.deepEqual(await checkMaAlerts('600519', UP_PREV, 0, '2026-10-29'), [])
  assert.equal(history.value.length, 0)
})

test('同一股票同一周期重复添加只保留一条（避免一天弹两遍）', async () => {
  resetAll()
  const a = addMaAlert({ code: '600519', name: '贵州茅台', period: 20, direction: 'up' })
  const b = addMaAlert({ code: '600519', name: '贵州茅台', period: 20, direction: 'both' })

  assert.ok(a && b)
  assert.equal(a.id, b.id)
  assert.equal(countEnabledForCode('600519'), 1)
  assert.equal(maAlerts.value[0].direction, 'both')
})

test('历史记录超过 200 条被裁剪，只留最新 200 条', async () => {
  resetAll()
  stock.__setBars('600519', UP_BARS)

  // 先铺满 200 条旧记录
  const seed = Array.from({ length: 200 }, (_, i) => ({
    id: `seed-${i}`,
    code: 'SEED',
    name: '旧记录',
    period: 5,
    direction: 'up',
    ma: 1,
    prevClose: 1,
    close: 1,
    date: '2026-10-01',
    at: i
  }))
  history.value = seed
  assert.equal(history.value.length, 200)

  addMaAlert({ code: '600519', name: '贵州茅台', period: 5, direction: 'up' })
  assert.equal((await checkMaAlerts('600519', UP_PREV, UP_CLOSE, '2026-10-29')).length, 1)

  assert.equal(history.value.length, 200, '超出上限时应裁剪而不是继续增长')
  // 最旧的一条被挤掉，最新的一条在末尾
  assert.equal(history.value[0].id, 'seed-1')
  assert.equal(history.value[history.value.length - 1].code, '600519')
})

test('非法输入不炸：未知周期、空代码', async () => {
  resetAll()
  assert.equal(addMaAlert({ code: '', name: 'x', period: 5, direction: 'up' }), null)
  assert.equal(addMaAlert({ code: '600519', name: '茅台', period: 7, direction: 'up' }), null)
  assert.deepEqual(await checkMaAlerts('600519', 10, 11, '不是日期'), [])
  assert.deepEqual(await checkMaAlerts('', 10, 11, '2026-10-29'), [])
})

test('周期白名单与需求一致：5/10/20/30/60', () => {
  assert.deepEqual([...MA_PERIODS], [5, 10, 20, 30, 60])
})
