import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  HK_HOLIDAY_YEARS,
  HK_HOLIDAY_TABLE,
  isHKHoliday,
  getHKHolidayName,
  isHKTradingDay
} from '../src/utils/hkHolidays.ts'

/**
 * 港股公众假期表测试。
 *
 * <p>这张表的价值全在「准」和「完整」两点上：漏一条，那天就会被当成交易日去轮询；
 * 年份漏一整年，则要靠三态语义把「不知道」显式暴露出来，而不能悄悄返回 false。
 * 所以断言分三层——数据本身（条数、关键日期）、三态语义、交易日兜底逻辑。</p>
 *
 * <p>这里反复强调 false 与 null 的区别，是因为它们太容易被顺手合并成同一个值：
 * 一旦合并，未知年份的国庆节就会被判为交易日，而且没有任何报错。</p>
 */

/** 表内每年的实际条数（与官方公告逐条对应，含落在周末的条目） */
const PER_YEAR_COUNT = 17

test('HK_HOLIDAY_YEARS 恰为 [2024, 2025, 2026, 2027] 且升序', () => {
  assert.deepEqual(HK_HOLIDAY_YEARS, [2024, 2025, 2026, 2027])
  // 升序是「调用方按年份区间判断覆盖范围」的前提
  const sorted = [...HK_HOLIDAY_YEARS].sort((a, b) => a - b)
  assert.deepEqual(HK_HOLIDAY_YEARS, sorted)
})

test('四年都非空，且每年条数达到下限（实际 17 条）', () => {
  for (const year of HK_HOLIDAY_YEARS) {
    const list = HK_HOLIDAY_TABLE[year]
    assert.ok(Array.isArray(list), `${year} 年应有假期表`)
    assert.ok(list.length >= 15, `${year} 年至少 15 条，实际 ${list.length} 条`)
    assert.equal(list.length, PER_YEAR_COUNT, `${year} 年应为 ${PER_YEAR_COUNT} 条`)
  }

  // 四年合计 68 条，一条都不能漏
  const total = HK_HOLIDAY_YEARS.reduce((sum, y) => sum + HK_HOLIDAY_TABLE[y].length, 0)
  assert.equal(total, 68)
})

test('表内数据自身一致：日期以年份开头、同一年内不重复、名字非空', () => {
  for (const year of HK_HOLIDAY_YEARS) {
    const seen = new Set()
    for (const [date, name] of HK_HOLIDAY_TABLE[year]) {
      assert.match(date, /^\d{4}-\d{2}-\d{2}$/, `${date} 应为 yyyy-MM-dd`)
      assert.equal(date.slice(0, 4), String(year), `${date} 应归在 ${year} 年`)
      assert.equal(seen.has(date), false, `${date} 在同一年内重复`)
      seen.add(date)
      assert.ok(name.length > 0, `${date} 缺少假期名`)
    }
  }
})

test('2024-01-01 元旦：isHKHoliday 为 true，名字为「元旦」', () => {
  assert.equal(isHKHoliday('2024-01-01'), true)
  assert.equal(getHKHolidayName('2024-01-01'), '元旦')
})

test('2026-10-01 国庆日 → true（年份已收录，不能因为「看起来像普通日子」就漏判）', () => {
  assert.equal(isHKHoliday('2026-10-01'), true)
  assert.equal(getHKHolidayName('2026-10-01'), '国庆日')
})

test('2027 农历新年：初一 02-06 与初三 02-08 都是假期，02-07 周日公告未单列', () => {
  assert.equal(isHKHoliday('2027-02-06'), true)
  assert.equal(getHKHolidayName('2027-02-06'), '农历年初一')
  assert.equal(isHKHoliday('2027-02-08'), true)
  assert.equal(getHKHolidayName('2027-02-08'), '农历年初三')
  // 02-07 是周日，官方公告没有单列这一条 —— 所以表里查不到，
  // 但它本来就不是交易日（周末兜底），这正是表保留原样的意义
  assert.equal(isHKHoliday('2027-02-07'), false, '周日未单列 → 表内不是假期')
  assert.equal(isHKTradingDay('2027-02-07'), false, '但周日依然不是交易日')
})

test('已收录年份的普通工作日 → false，绝不能是 null', () => {
  // 2025-03-05 是周三，不是任何假期
  assert.equal(isHKHoliday('2025-03-05'), false)
  assert.equal(getHKHolidayName('2025-03-05'), '')
  assert.equal(isHKTradingDay('2025-03-05'), true)
})

test('未收录年份 → null（三态里的「不知道」，不是 false）', () => {
  assert.equal(isHKHoliday('2023-10-01'), null, '2023 未收录 → 未知')
  assert.equal(isHKHoliday('2030-01-01'), null, '2030 未收录 → 未知')
  // 未知年份连「叫什么名字」都说不出，只能给空串
  assert.equal(getHKHolidayName('2030-01-01'), '')
})

test('getHKHolidayName：非假期 / 未知年份 / 空参数一律返回空串', () => {
  assert.equal(getHKHolidayName('2025-03-05'), '', '已收录年份的非假期')
  assert.equal(getHKHolidayName('2030-12-25'), '', '未收录年份')
  assert.equal(getHKHolidayName(''), '', '空串')
  assert.equal(getHKHolidayName(null), '', 'null')
  assert.equal(getHKHolidayName(undefined), '', 'undefined')
})

test('isHKTradingDay：周末、假期为 false，周五普通日为 true', () => {
  assert.equal(isHKTradingDay('2026-10-03'), false, '周六')
  assert.equal(isHKTradingDay('2026-10-04'), false, '周日')
  assert.equal(isHKTradingDay('2026-10-01'), false, '国庆日（周四）')
  assert.equal(isHKTradingDay('2026-10-02'), true, '国庆次日是普通周五')
})

test('未收录年份：只按周一至周五兜底，不因「不知道假期」就全判休市', () => {
  // 2030-01-01 是周二、当年未收录 → 假期信息未知，但周中放行（可接受的降级）
  assert.equal(isHKTradingDay('2030-01-01'), true)
  // 2030-01-02 是周三 → true
  assert.equal(isHKTradingDay('2030-01-02'), true)
  // 2030-01-05 是周六 → 周末判定不依赖年份表，仍为 false
  assert.equal(isHKTradingDay('2030-01-05'), false)
  // 2023-10-01 是周日
  assert.equal(isHKTradingDay('2023-10-01'), false, '未收录年份的周日')
})

test('空串 / null / undefined / 非法格式都不抛异常', () => {
  for (const value of ['', null, undefined]) {
    assert.equal(isHKHoliday(value), null, `${String(value)} 应为未知`)
    assert.equal(getHKHolidayName(value), '', `${String(value)} 应返回空串`)
    assert.doesNotThrow(() => isHKTradingDay(value))
  }

  // 格式不对时不装作查过了：isHKHoliday 未知，isHKTradingDay 不敢说交易日
  for (const weird of ['2026/10/01', '20261001', '2026-13-01', '2026-02-30', 'garbage']) {
    assert.equal(isHKHoliday(weird), null, `${weird} 无法解析 → 未知`)
    assert.doesNotThrow(() => isHKTradingDay(weird))
    assert.equal(isHKTradingDay(weird), false, `${weird} 说不清，不给交易日`)
  }
})

test('不用 new Date(dateStr) 解析：跨时区不得把 2026-10-01 判成前一天', () => {
  // 关键回归：若用 new Date('2026-10-01') 取本地星期，东八区会回到 09-30，
  // 那样 10-01 的假期判定就整体错一天。这里直接钉住「字符串口径」的结果。
  assert.equal(isHKHoliday('2026-09-30'), false, '09-30 不是假期，不能与 10-01 混淆')
  assert.equal(isHKHoliday('2026-10-01'), true)
  assert.equal(isHKTradingDay('2026-09-30'), true, '09-30 是周三交易日')
})
