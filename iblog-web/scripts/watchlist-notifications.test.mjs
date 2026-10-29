import { test } from 'node:test'
import assert from 'node:assert/strict'

/**
 * 自选列表通知规则测试。
 *
 * <p>要测的每条规则出错时都不会抛异常，只会在某天悄悄少一条提醒或多一堆误报
 * （港股被整体跳过、隔夜跳空被当成快速拉升、静音后当天再也收不到），
 * 所以这里把阈值边界和去重状态逐个钉死。</p>
 *
 * <p>两个环境缺口需要先补：</p>
 * <ul>
 *   <li>Node 没有 localStorage —— 用内存 Map 顶替，同时验证模块在隐私模式下的静默降级</li>
 *   <li>Node 没有 Notification —— 用模块导出的 __setNotifySenderForTest 注入假发送器，
 *       不去 mock useNotify，避免测试依赖真实浏览器权限状态</li>
 * </ul>
 */

const STORAGE_KEY = 'iblog-stock-watch-notify-history'

// ── 内存版 localStorage（在导入被测模块之前装好，让首次 loadHistory 能读到数据）──

const store = new Map()

globalThis.localStorage = {
  getItem: (k) => (store.has(k) ? store.get(k) : null),
  setItem: (k, v) => {
    store.set(k, String(v))
  },
  removeItem: (k) => {
    store.delete(k)
  },
  clear: () => {
    store.clear()
  },
  key: (i) => [...store.keys()][i] ?? null,
  get length() {
    return store.size
  }
}

/** 相对今天偏移若干天的日期串（本地时区，与模块内部口径一致） */
function dayKey(offsetDays = 0) {
  const d = new Date(Date.now() + offsetDays * 86400000)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

// 预置两天的历史，用来验证 listRecentNotifications 的降序
store.set(
  STORAGE_KEY,
  JSON.stringify({
    [dayKey(-1)]: { '00700': ['fast_rise'] },
    [dayKey(0)]: { '000001': ['+5%'] }
  })
)

const { useWatchlistNotifications, __setNotifySenderForTest, DEFAULT_WATCH_NOTIFY_SETTINGS, WATCH_TRIGGER_LABELS } =
  await import('../src/composables/useWatchlistNotifications.ts')

// ── 测试辅助 ────────────────────────────────────────────────

/** 构造本地时间（沪深交易所用的是东八区本地时间，不能拿 UTC 字符串构造） */
function at(y, mo, d, h, mi = 0) {
  return new Date(y, mo - 1, d, h, mi, 0, 0)
}

// 2026-10-12 是周一（交易日），2026-10-17 是周六
//
// 不用 10-19：那天虽是周一，却是香港「重阳节翌日」休市，港股的用例会因为
// 非交易时段提前返回，看着像规则没生效，其实是日期选错了。
const MON = at(2026, 10, 12, 10, 0)
const SAT = at(2026, 10, 17, 10, 0)

/** 完整的行情对象（缺字段会让 toFixed 之类在实现里抛错，这里补齐） */
function quote(code, price, changePct, prevClose = 10, name = '测试股') {
  return { code, name, price, prevClose, changePct }
}

/** 假发送器：记录每次调用的参数，返回值可控 */
function makeSender(result = true) {
  const calls = []
  const fn = async (title, body, tag) => {
    calls.push({ title, body, tag, result })
    return result
  }
  fn.calls = calls
  return fn
}

const api = useWatchlistNotifications()

/** 每个用例都从干净状态开始：清历史、清快照、换发送器 */
function reset(sender = makeSender(true)) {
  api.clearWatchNotifyHistory()
  api.resetPriceSnapshots()
  __setNotifySenderForTest(sender)
  return sender
}

// ── 历史读取（依赖上面预置的数据，必须第一个跑）──────────────

test('listRecentNotifications：读取预置的两天历史，按日期降序', () => {
  const list = api.listRecentNotifications()
  assert.equal(list.length, 2)
  assert.equal(list[0].date, dayKey(0), '今天的排最前')
  assert.equal(list[0].code, '000001')
  assert.deepEqual(list[0].types, ['+5%'])
  assert.equal(list[1].date, dayKey(-1))
  assert.equal(list[1].code, '00700')
  assert.deepEqual(list[1].types, ['fast_rise'])

  // 只取 1 天时，昨天那条被裁掉
  assert.equal(api.listRecentNotifications(1).length, 1)
})

// ── 静态阈值 ───────────────────────────────────────────────

test('主板涨 9.6%：limit_up 与 +7% 同时命中（两个独立的 if，互不排斥）', async () => {
  // 主板 getLimitThreshold = 10 - 0.5 = 9.5，9.6 >= 9.5 命中涨停；
  // 同时 9.6 >= 7 命中 +7% —— 涨跌停与百分比档是两个独立判定，都会推入
  const sender = reset()
  const out = await api.checkAndNotify(quote('000001', 10.96, 9.6), DEFAULT_WATCH_NOTIFY_SETTINGS, MON)
  assert.ok(out.includes('limit_up'), `应含 limit_up，实际 ${JSON.stringify(out)}`)
  assert.ok(out.includes('+7%'), `应含 +7%，实际 ${JSON.stringify(out)}`)
  assert.equal(sender.calls.length, 2)
  // 不同触发类型必须给不同 tag，否则后一条会顶掉前一条
  assert.notEqual(sender.calls[0].tag, sender.calls[1].tag)
  assert.equal(sender.calls[0].tag, 'iblog-watch-limit_up-000001')
  assert.equal(sender.calls[0].title, `${WATCH_TRIGGER_LABELS.limit_up}: 测试股 (000001)`)
  assert.equal(sender.calls[0].body, '当前价 10.96  涨跌幅 +9.60%')
})

test('创业板 300750 涨 15%：阈值 19.5 未到，不含 limit_up 但含 +7%', async () => {
  reset()
  const out = await api.checkAndNotify(quote('300750', 11.5, 15), DEFAULT_WATCH_NOTIFY_SETTINGS, MON)
  assert.ok(!out.includes('limit_up'), '创业板涨停阈值是 19.5，15% 不该报涨停')
  assert.ok(out.includes('+7%'))
})

test('港股 00700 涨 12%：limit 为 0 不报涨停，但 ±5%/±7% 仍然生效', async () => {
  reset()
  const out = await api.checkAndNotify(quote('00700', 11.2, 12), DEFAULT_WATCH_NOTIFY_SETTINGS, MON)
  assert.ok(!out.includes('limit_up'), '港股无涨跌停，limit=0 不该报涨停')
  assert.ok(out.includes('+7%'), '港股日常波动仍需 ±7% 档')
})

test('跌停 -9.6%：含 limit_down', async () => {
  reset()
  const out = await api.checkAndNotify(quote('000001', 9.04, -9.6), DEFAULT_WATCH_NOTIFY_SETTINGS, MON)
  assert.ok(out.includes('limit_down'))
})

test('涨 6%：只报 +5%，不报 +7%（两者是 else if 互斥关系）', async () => {
  reset()
  const out = await api.checkAndNotify(quote('000001', 10.6, 6), DEFAULT_WATCH_NOTIFY_SETTINGS, MON)
  assert.ok(out.includes('+5%'))
  assert.ok(!out.includes('+7%'))
})

test('跌 6%：只报 -5%', async () => {
  reset()
  const out = await api.checkAndNotify(quote('000001', 9.4, -6), DEFAULT_WATCH_NOTIFY_SETTINGS, MON)
  assert.ok(out.includes('-5%'))
  assert.ok(!out.includes('-7%'))
})

// ── 去重 ───────────────────────────────────────────────────

test('同一只标的同一天第二次调用不再重复通知', async () => {
  const sender = reset()
  const first = await api.checkAndNotify(quote('000001', 10.6, 6), DEFAULT_WATCH_NOTIFY_SETTINGS, MON)
  assert.deepEqual(first, ['+5%'])
  assert.equal(sender.calls.length, 1)
  assert.equal(api.hasTriggeredToday('000001', '+5%'), true)

  const second = await api.checkAndNotify(quote('000001', 10.6, 6), DEFAULT_WATCH_NOTIFY_SETTINGS, MON)
  assert.deepEqual(second, [])
  assert.equal(sender.calls.length, 1, '第二次不该再发通知')
})

// ── 动态阈值 ───────────────────────────────────────────────

test('快速拉升：快照 10 → 现价 10.3（昨收 10）变动 3% ≥ 阈值 2%', async () => {
  reset()
  // 第一次只建立基线，30 秒窗口内没有「上一笔」可比
  const baseline = await api.checkAndNotify(quote('000001', 10, 0), DEFAULT_WATCH_NOTIFY_SETTINGS, MON)
  assert.deepEqual(baseline, [])

  const out = await api.checkAndNotify(quote('000001', 10.3, 3), DEFAULT_WATCH_NOTIFY_SETTINGS, MON)
  assert.ok(out.includes('fast_rise'), `应含 fast_rise，实际 ${JSON.stringify(out)}`)
})

test('快速下跌：快照 10 → 现价 9.7 变动 -3%，含 fast_fall', async () => {
  reset()
  await api.checkAndNotify(quote('000001', 10, 0), DEFAULT_WATCH_NOTIFY_SETTINGS, MON)
  const out = await api.checkAndNotify(quote('000001', 9.7, -3), DEFAULT_WATCH_NOTIFY_SETTINGS, MON)
  assert.ok(out.includes('fast_fall'), `应含 fast_fall，实际 ${JSON.stringify(out)}`)
})

test('跨日清空快照：昨天 10 元、今天 10.3 元不会被当成快速拉升', async () => {
  reset()
  await api.checkAndNotify(quote('000001', 10, 0), DEFAULT_WATCH_NOTIFY_SETTINGS, at(2026, 10, 16, 10, 0))
  // 隔了一个周末的第一笔只建立基线，不该因为缺口报 fast_rise
  const out = await api.checkAndNotify(quote('000001', 10.3, 3), DEFAULT_WATCH_NOTIFY_SETTINGS, MON)
  assert.ok(!out.includes('fast_rise'), `跨日首笔不该报快速拉升，实际 ${JSON.stringify(out)}`)
})

// ── 发送失败 / 开关 ────────────────────────────────────────

test('发送器返回 false：不标记已通知，下次仍会重试', async () => {
  const failing = reset(makeSender(false))
  const first = await api.checkAndNotify(quote('000001', 10.6, 6), DEFAULT_WATCH_NOTIFY_SETTINGS, MON)
  assert.deepEqual(first, [], '没送出去就不算触发')
  assert.equal(failing.calls.length, 1)
  assert.equal(api.hasTriggeredToday('000001', '+5%'), false, '静音不该污染已通知状态')

  const ok = makeSender(true)
  __setNotifySenderForTest(ok)
  const second = await api.checkAndNotify(quote('000001', 10.6, 6), DEFAULT_WATCH_NOTIFY_SETTINGS, MON)
  assert.deepEqual(second, ['+5%'], '恢复后应补发')
  assert.equal(ok.calls.length, 1)
})

test('多条触发时中途失败立即停止，不再请求后续通知', async () => {
  const sender = reset(makeSender(false))
  const out = await api.checkAndNotify(quote('000001', 10.96, 9.6), DEFAULT_WATCH_NOTIFY_SETTINGS, MON)
  assert.deepEqual(out, [])
  assert.equal(sender.calls.length, 1, '第一条失败就该收手，不该继续发第二条')
})

test('非交易时段（周六）直接返回空', async () => {
  const sender = reset()
  const out = await api.checkAndNotify(quote('000001', 10.96, 9.6), DEFAULT_WATCH_NOTIFY_SETTINGS, SAT)
  assert.deepEqual(out, [])
  assert.equal(sender.calls.length, 0)
})

test('notifyEnabled 为 false 时整体不通知', async () => {
  const sender = reset()
  const out = await api.checkAndNotify(quote('000001', 10.96, 9.6), {
    ...DEFAULT_WATCH_NOTIFY_SETTINGS,
    notifyEnabled: false
  }, MON)
  assert.deepEqual(out, [])
  assert.equal(sender.calls.length, 0)
})

test('单独关掉某一类开关：只影响该类', async () => {
  reset()
  const out = await api.checkAndNotify(quote('000001', 10.96, 9.6), {
    ...DEFAULT_WATCH_NOTIFY_SETTINGS,
    notifyLimitUp: false
  }, MON)
  assert.ok(!out.includes('limit_up'), '关掉涨停开关后不该报')
  assert.ok(out.includes('+7%'), '没关的档位照常报')
})

// ── 历史清理 ───────────────────────────────────────────────

test('clearWatchNotifyHistory 之后 hasTriggeredToday 为 false', async () => {
  reset()
  await api.checkAndNotify(quote('000001', 10.6, 6), DEFAULT_WATCH_NOTIFY_SETTINGS, MON)
  assert.equal(api.hasTriggeredToday('000001', '+5%'), true)

  api.clearWatchNotifyHistory()
  assert.equal(api.hasTriggeredToday('000001', '+5%'), false)
  assert.deepEqual(api.listRecentNotifications(), [])
  assert.equal(store.has(STORAGE_KEY), false, 'localStorage 里的键也该被删掉')
})

// ── 脏数据 ─────────────────────────────────────────────────

test('空 quote / 空 code / 价格为 0 时返回空数组，不抛异常', async () => {
  const sender = reset()
  assert.deepEqual(await api.checkAndNotify(null, DEFAULT_WATCH_NOTIFY_SETTINGS, MON), [])
  assert.deepEqual(await api.checkAndNotify(undefined, DEFAULT_WATCH_NOTIFY_SETTINGS, MON), [])
  assert.deepEqual(await api.checkAndNotify(quote('', 10, 5), DEFAULT_WATCH_NOTIFY_SETTINGS, MON), [])
  assert.deepEqual(await api.checkAndNotify(quote('000001', 0, 5), DEFAULT_WATCH_NOTIFY_SETTINGS, MON), [])
  assert.deepEqual(await api.checkAndNotify(quote('000001', -1, 5), DEFAULT_WATCH_NOTIFY_SETTINGS, MON), [])
  assert.equal(sender.calls.length, 0)
})

test('不传设置时退回默认设置（全开、阈值 2）', async () => {
  reset()
  const out = await api.checkAndNotify(quote('000001', 10.6, 6), undefined, MON)
  assert.deepEqual(out, ['+5%'])
})

// 收尾：把注入的发送器撤掉，避免影响同进程里后续可能加载的用例
test.after(() => {
  __setNotifySenderForTest(null)
})
