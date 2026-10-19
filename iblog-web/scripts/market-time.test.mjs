import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  getMarketState,
  isTradingHours,
  isQuoteFresh,
  marketStateBadge,
  parseQuoteTime,
  refreshIntervalMs
} from '../src/utils/marketTime.ts'

/**
 * 交易时段判断与刷新间隔测试。
 *
 * <p>这两块逻辑出错的方式都很隐蔽：时段算错，只会在某个小时段里莫名不刷新；
 * 间隔算错，只会让流量和上游配额悄悄多烧几倍。两者都不会报错，
 * 所以只能靠断言把每个边界钉住。</p>
 *
 * <p>时钟用固定 Date 构造，不依赖运行测试时的真实时间 ——
 * 否则这套测试在周末和交易日会给出不同结果。</p>
 */

/** 构造本地时间（不能用 UTC 字符串：沪深交易所用的是东八区本地时间） */
function at(y, mo, d, h, mi = 0) {
  return new Date(y, mo - 1, d, h, mi, 0, 0)
}

// 2026-10-19 是周一，2026-10-17 是周六
const MON = 19
const SAT = 17

test('A 股时段边界：09:30 开盘、11:30 收盘、13:00 复牌、15:00 收盘（均含端点）', () => {
  assert.equal(getMarketState(undefined, null, at(2026, 10, MON, 9, 29)), 'pre', '09:29 未开盘')
  assert.equal(getMarketState(undefined, null, at(2026, 10, MON, 9, 30)), 'open', '09:30 已开盘')
  assert.equal(getMarketState(undefined, null, at(2026, 10, MON, 11, 30)), 'open', '11:30 仍算交易')
  assert.equal(getMarketState(undefined, null, at(2026, 10, MON, 11, 31)), 'break', '11:31 进入午休')
  assert.equal(getMarketState(undefined, null, at(2026, 10, MON, 12, 59)), 'break', '12:59 仍午休')
  assert.equal(getMarketState(undefined, null, at(2026, 10, MON, 13, 0)), 'open', '13:00 复牌')
  assert.equal(getMarketState(undefined, null, at(2026, 10, MON, 15, 0)), 'open', '15:00 仍算交易')
  assert.equal(getMarketState(undefined, null, at(2026, 10, MON, 15, 1)), 'post', '15:01 已收盘')
})

test('港股时段比 A 股长：上午到 12:00、下午到 16:00', () => {
  // 00700 是 5 位 → 按港股处理
  assert.equal(getMarketState('00700', null, at(2026, 10, MON, 11, 45)), 'open', '港股上午 11:45 仍交易')
  assert.equal(getMarketState(undefined, null, at(2026, 10, MON, 11, 45)), 'break', '同刻 A 股已午休')
  assert.equal(getMarketState('00700', null, at(2026, 10, MON, 15, 30)), 'open', '港股 15:30 仍交易')
  assert.equal(getMarketState(undefined, null, at(2026, 10, MON, 15, 30)), 'post', '同刻 A 股已收盘')
  assert.equal(getMarketState('00700', null, at(2026, 10, MON, 16, 1)), 'post', '港股 16:01 收盘')
})

test('周末一律休市，与时刻无关', () => {
  for (const h of [0, 10, 12, 14, 23]) {
    assert.equal(getMarketState(undefined, null, at(2026, 10, SAT, h)), 'holiday', `周六 ${h} 点应为休市`)
  }
})

test('上游时间戳反证：时钟说在交易但数据明显没动 → stale', () => {
  const now = at(2026, 10, MON, 10, 0)
  // 上游时间停在 09:00，滞后 60 分钟，远超 10 分钟容忍度 → 其实是休市（法定节假日）
  assert.equal(getMarketState(undefined, '2026-10-19 09:00:00', now), 'stale')
  // 上游时间 09:58，只滞后 2 分钟 → 正常交易中
  assert.equal(getMarketState(undefined, '2026-10-19 09:58:00', now), 'open')
  // 不传时间戳就只能是纯时钟判断
  assert.equal(getMarketState(undefined, null, now), 'open')
})

test('非交易时段不拿时间戳做反证（盘前盘后本来就该不动）', () => {
  // 周一 08:00，上游给的是上周五 15:00 的收盘数据，滞后很久但这是正常的
  assert.equal(getMarketState(undefined, '2026-10-16 15:00:00', at(2026, 10, MON, 8, 0)), 'pre')
  // 周一 20:00 同理
  assert.equal(getMarketState(undefined, '2026-10-19 15:00:00', at(2026, 10, MON, 20, 0)), 'post')
})

test('isQuoteFresh：容忍度内为新鲜，超时为不新鲜，缺失视为新鲜', () => {
  const now = at(2026, 10, MON, 10, 0)
  assert.equal(isQuoteFresh('2026-10-19 09:59:00', now), true, '滞后 1 分钟 → 新鲜')
  assert.equal(isQuoteFresh('2026-10-19 09:50:00', now), true, '滞后 10 分钟 → 边界内')
  assert.equal(isQuoteFresh('2026-10-19 09:49:00', now), false, '滞后 11 分钟 → 不新鲜')
  // 拿不到时间戳时不报「过期」：宁可不报，也别把字段缺失说成数据过期
  assert.equal(isQuoteFresh(null, now), true)
  assert.equal(isQuoteFresh(undefined, now), true)
  assert.equal(isQuoteFresh('', now), true)
  assert.equal(isQuoteFresh('不是时间', now), true)
})

test('parseQuoteTime 支持 yyyy-MM-dd HH:mm:ss 与带 T 的 ISO 形式', () => {
  const a = parseQuoteTime('2026-10-19 09:27:30')
  const b = parseQuoteTime('2026-10-19T09:27:30')
  assert.ok(a !== null && b !== null)
  assert.equal(a, b, '两种写法应解析成同一时刻')
  assert.equal(parseQuoteTime('garbage'), null)
  assert.equal(parseQuoteTime(null), null)
})

test('refreshIntervalMs：交易 15 秒 / 午休 60 秒 / 其余 5 分钟', () => {
  assert.equal(refreshIntervalMs('open'), 15_000)
  assert.equal(refreshIntervalMs('break'), 60_000)
  for (const s of ['pre', 'post', 'holiday', 'stale']) {
    assert.equal(refreshIntervalMs(s), 300_000, `${s} 应为 5 分钟`)
  }
})

test('refreshIntervalMs：页面在后台时一律降频 4 倍', () => {
  assert.equal(refreshIntervalMs('open', true), 60_000)
  assert.equal(refreshIntervalMs('break', true), 240_000)
  assert.equal(refreshIntervalMs('holiday', true), 1_200_000)
})

test('marketStateBadge：只有 open 是 live（决定胶囊是否显示绿点）', () => {
  assert.deepEqual(marketStateBadge('open'), { text: '交易中', live: true })
  for (const s of ['break', 'pre', 'post', 'holiday', 'stale']) {
    assert.equal(marketStateBadge(s).live, false, `${s} 不应显示为实时`)
  }
  // stale 与 holiday 对用户是同一件事：今天不开市
  assert.equal(marketStateBadge('stale').text, '休市')
})

test('isTradingHours：午休算在内（价格提醒的轮询口径与此一致）', () => {
  assert.equal(isTradingHours(undefined, at(2026, 10, MON, 10, 0)), true)
  assert.equal(isTradingHours(undefined, at(2026, 10, MON, 12, 0)), true, '午休仍算交易时段')
  assert.equal(isTradingHours(undefined, at(2026, 10, MON, 8, 0)), false)
  assert.equal(isTradingHours(undefined, at(2026, 10, SAT, 10, 0)), false)
})
