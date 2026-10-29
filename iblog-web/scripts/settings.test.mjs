import { test } from 'node:test'
import assert from 'node:assert/strict'

/**
 * 全局设置（useSettings）与刷新间隔覆盖值的测试。
 *
 * <p>这一层最容易出「悄悄不生效」的问题：设置面板改了 30 秒，页面还在 15 秒刷；
 * 或者 localStorage 里存了脏数据（`"quotesRefreshMs": "abc"`、`0.1` 这种），
 * 让 setTimeout 拿到非法值变成忙轮询把 CPU 打满。所以这里把 sanitize 的
 * 每个分支、以及 refreshIntervalMs 的三参组合逐个钉死。</p>
 *
 * <p>Node 没有 localStorage —— 先装一个内存版再 import 被测模块，
 * 让模块顶层的 load() 能跑通（它读不到就静默回默认，那样测不出区别）。</p>
 */

// ── 内存版 localStorage（必须在 import 之前装好）────────────────

const store = new Map()

globalThis.localStorage = {
  getItem(key) {
    return store.has(key) ? store.get(key) : null
  },
  setItem(key, value) {
    store.set(key, String(value))
  },
  removeItem(key) {
    store.delete(key)
  },
  clear() {
    store.clear()
  },
  key(index) {
    return [...store.keys()][index] ?? null
  },
  get length() {
    return store.size
  }
}

const { DEFAULT_SETTINGS, REFRESH_INTERVAL_CHOICES, sanitizeSettings, useSettings, hasAnyNotifyEnabled } =
  await import('../src/composables/useSettings.ts')
const { refreshIntervalMs } = await import('../src/utils/marketTime.ts')

// ── sanitizeSettings ─────────────────────────────────────────

test('空值 / null / 非对象一律返回完整默认设置', () => {
  for (const raw of [null, undefined, 0, '', 'abc', [], 42]) {
    const out = sanitizeSettings(raw)
    assert.deepEqual(out, DEFAULT_SETTINGS, `${JSON.stringify(raw)} 应回默认`)
  }
})

test('缺失字段由默认值补齐，而不是变成 undefined', () => {
  const out = sanitizeSettings({ quotesRefreshMs: 30000 })
  assert.equal(out.quotesRefreshMs, 30000)
  assert.equal(out.indicesRefreshMs, DEFAULT_SETTINGS.indicesRefreshMs)
  assert.equal(out.notifyEnabled, DEFAULT_SETTINGS.notifyEnabled)
  assert.equal(out.notifyFastThreshold, DEFAULT_SETTINGS.notifyFastThreshold)
})

test('布尔字段只接受真正的 boolean：字符串 "false" 不能当成 false 用', () => {
  const out = sanitizeSettings({ notifyEnabled: 'false', notifyUp5: 0, notifyLimitUp: false })
  assert.equal(out.notifyEnabled, DEFAULT_SETTINGS.notifyEnabled, '字符串应被忽略')
  assert.equal(out.notifyUp5, DEFAULT_SETTINGS.notifyUp5, '数字 0 应被忽略')
  assert.equal(out.notifyLimitUp, false, '真正的 false 要生效')
})

test('刷新间隔只接受白名单值：任意数字是隐患不是自由', () => {
  // 不在白名单里的一律回默认
  assert.equal(sanitizeSettings({ quotesRefreshMs: 1 }).quotesRefreshMs, DEFAULT_SETTINGS.quotesRefreshMs)
  assert.equal(sanitizeSettings({ quotesRefreshMs: 'abc' }).quotesRefreshMs, DEFAULT_SETTINGS.quotesRefreshMs)
  assert.equal(sanitizeSettings({ quotesRefreshMs: 7777 }).quotesRefreshMs, DEFAULT_SETTINGS.quotesRefreshMs)
  // 白名单内的都接受，包括 0（不自动刷新）
  for (const item of REFRESH_INTERVAL_CHOICES) {
    assert.equal(sanitizeSettings({ quotesRefreshMs: item.value }).quotesRefreshMs, item.value)
  }
})

test('快速变动阈值夹到 1~5 并按 0.5 取整', () => {
  assert.equal(sanitizeSettings({ notifyFastThreshold: 0.1 }).notifyFastThreshold, 1)
  assert.equal(sanitizeSettings({ notifyFastThreshold: 99 }).notifyFastThreshold, 5)
  assert.equal(sanitizeSettings({ notifyFastThreshold: 2.3 }).notifyFastThreshold, 2.5)
  assert.equal(sanitizeSettings({ notifyFastThreshold: 2.2 }).notifyFastThreshold, 2)
  // 非有限数（NaN / Infinity）不接
  assert.equal(
    sanitizeSettings({ notifyFastThreshold: Number.NaN }).notifyFastThreshold,
    DEFAULT_SETTINGS.notifyFastThreshold
  )
  assert.equal(
    sanitizeSettings({ notifyFastThreshold: '3' }).notifyFastThreshold,
    DEFAULT_SETTINGS.notifyFastThreshold
  )
})

// ── hasAnyNotifyEnabled ──────────────────────────────────────

test('hasAnyNotifyEnabled：总开关关了就是 false，哪怕子项全开', () => {
  const base = { ...DEFAULT_SETTINGS }
  assert.equal(hasAnyNotifyEnabled({ ...base, notifyEnabled: false }), true === false)
})

test('hasAnyNotifyEnabled：总开关开但子项全关 → false（巡查不该启动）', () => {
  const current = {
    ...DEFAULT_SETTINGS,
    notifyEnabled: true,
    notifyUp5: false,
    notifyUp7: false,
    notifyLimitUp: false,
    notifyDown5: false,
    notifyDown7: false,
    notifyLimitDown: false,
    notifyFastRise: false,
    notifyFastFall: false
  }
  assert.equal(hasAnyNotifyEnabled(current), false)
})

test('hasAnyNotifyEnabled：任一子项开着就是 true', () => {
  const off = {
    ...DEFAULT_SETTINGS,
    notifyEnabled: true,
    notifyUp5: false,
    notifyUp7: false,
    notifyLimitUp: false,
    notifyDown5: false,
    notifyDown7: false,
    notifyLimitDown: false,
    notifyFastRise: false,
    notifyFastFall: false
  }
  assert.equal(hasAnyNotifyEnabled({ ...off, notifyFastFall: true }), true)
})

// ── useSettings 读写 ─────────────────────────────────────────

test('update 写一个字段会落 localStorage，且不影响其它字段', () => {
  store.clear()
  // 重新取一次模块单例：store 清空了，但模块内的 ref 仍是旧值，
  // 所以这里用 update 覆盖后逐个核对，不假设从默认开始。
  const api = useSettings()
  api.update('quotesRefreshMs', 60000)
  assert.equal(api.settings.value.quotesRefreshMs, 60000)
  const saved = JSON.parse(store.get('iblog-stock-settings'))
  assert.equal(saved.quotesRefreshMs, 60000)
  assert.equal(saved.notifyEnabled, api.settings.value.notifyEnabled)
})

test('update 传入非法值会被 sanitize 拦下，落回默认值而不是脏值', () => {
  store.clear()
  const api = useSettings()
  api.update('quotesRefreshMs', 60000)
  assert.equal(api.settings.value.quotesRefreshMs, 60000)
  // 绕过 TS 类型直接写脏值，模拟调用方传错（比如从别处拿了个毫秒数没校验）
  api.update('quotesRefreshMs', 1)
  // 注意：sanitize 以 DEFAULTS 当 schema，非法值是「回默认」而不是「保留上一版」。
  // 这是有意的——保留上一版会让脏值以「看起来生效了」的方式静默存活。
  assert.equal(api.settings.value.quotesRefreshMs, DEFAULT_SETTINGS.quotesRefreshMs, '非法间隔应回默认')
})

test('reset 恢复全部默认值', () => {
  store.clear()
  const api = useSettings()
  api.update('quotesRefreshMs', 120000)
  api.update('notifyUp5', false)
  api.reset()
  assert.deepEqual(api.settings.value, DEFAULT_SETTINGS)
})

test('notifySettings / refreshSettings 是设置切片的投影，不是拷贝', () => {
  store.clear()
  const api = useSettings()
  api.update('notifyFastThreshold', 3.5)
  assert.equal(api.notifySettings.value.notifyFastThreshold, 3.5)
  api.update('intradayRefreshMs', 30000)
  assert.equal(api.refreshSettings.value.intradayRefreshMs, 30000)
  // 切片里不该混进刷新/通知以外的键
  assert.equal('quotesRefreshMs' in api.notifySettings.value, false)
  assert.equal('notifyEnabled' in api.refreshSettings.value, false)
})

// ── refreshIntervalMs 的 override 语义 ───────────────────────

test('不传 override：完全保持原有档位（15s / 60s / 300s）', () => {
  assert.equal(refreshIntervalMs('open'), 15_000)
  assert.equal(refreshIntervalMs('break'), 60_000)
  assert.equal(refreshIntervalMs('post'), 300_000)
})

test('override=0 表示不自动刷新，任何状态都返回 0', () => {
  for (const state of ['open', 'break', 'pre', 'post', 'holiday', 'stale']) {
    assert.equal(refreshIntervalMs(state, false, 0), 0, `${state} 应返回 0`)
    assert.equal(refreshIntervalMs(state, true, 0), 0, `${state} 后台也应返回 0`)
  }
})

test('override 生效：交易中直接用它，午休 ×4、其它 ×20（各有下限）', () => {
  assert.equal(refreshIntervalMs('open', false, 10_000), 10_000)
  assert.equal(refreshIntervalMs('open', false, 300_000), 300_000)
  // 午休 = override×4，但不低于 30 秒
  assert.equal(refreshIntervalMs('break', false, 60_000), 240_000)
  assert.equal(refreshIntervalMs('break', false, 5_000), 30_000, '午休有 30 秒下限')
  // 非交易时段 = override×20，但不低于 2 分钟
  assert.equal(refreshIntervalMs('post', false, 10_000), 200_000)
  assert.equal(refreshIntervalMs('post', false, 3_000), 120_000, '非交易时段有 2 分钟下限')
})

test('override 与后台降频叠加：后台再 ×4', () => {
  assert.equal(refreshIntervalMs('open', true, 15_000), 60_000)
  assert.equal(refreshIntervalMs('break', true, 60_000), 960_000)
})

test('override 传非法值（负数 / NaN / Infinity）时退回默认档位', () => {
  assert.equal(refreshIntervalMs('open', false, -1), 15_000)
  assert.equal(refreshIntervalMs('open', false, Number.NaN), 15_000)
  assert.equal(refreshIntervalMs('open', false, Number.POSITIVE_INFINITY), 15_000)
})
