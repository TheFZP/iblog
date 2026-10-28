/**
 * 涨跌停幅度口径测试。
 *
 * 直接 import 真实 TS 源，不复制逻辑：口径错一次，回测里「逼近涨停」规则就会
 * 把 20% 的创业板信号全判成不达标，而这种错误在页面上看不出来。
 */
import { test } from 'node:test'
import assert from 'node:assert/strict'

import { getLimitPct, getLimitThreshold, isBjCode, isHkCode } from '../src/utils/marketLimit.ts'

const A = (code) => assert.equal(getLimitPct(code), 10, `${code} 应为主板 10%`)
const P20 = (code) => assert.equal(getLimitPct(code), 20, `${code} 应为 20%`)

test('涨跌停幅度：主板 10%、创业板/科创板 20%、北交所 30%、港股无限制', () => {
  A('600519')
  A('000001')
  A('002594')
  P20('300750')
  P20('688981')
  assert.equal(getLimitPct('430047'), 30)
  assert.equal(getLimitPct('830799'), 30)
  assert.equal(getLimitPct('00700'), 0, '港股没有涨跌停')
})

test('涨跌停幅度：ST 与所属板块同口径，只看代码不需要名称', () => {
  // ST 股没有单独的 ±5%，改板后与板块一致，所以这里只要保证不因代码前缀误判
  assert.equal(getLimitPct('600123'), 10)
  assert.equal(getLimitPct('300123'), 20)
})

test('涨跌停幅度：空值与非法输入回落到主板 10%', () => {
  assert.equal(getLimitPct(''), 10)
  assert.equal(getLimitPct(null), 10)
  assert.equal(getLimitPct(undefined), 10)
})

test('港股 / 北交所代码判定', () => {
  assert.equal(isHkCode('00700'), true)
  assert.equal(isHkCode('600519'), false)
  assert.equal(isHkCode(''), false)
  assert.equal(isBjCode('430047'), true)
  assert.equal(isBjCode('920002'), true)
  assert.equal(isBjCode('600519'), false)
})

test('涨停阈值留 0.5% 容差，港股为 0 表示不适用', () => {
  assert.equal(getLimitThreshold('600519'), 9.5)
  assert.equal(getLimitThreshold('300750'), 19.5)
  assert.equal(getLimitThreshold('430047'), 29.5)
  assert.equal(getLimitThreshold('00700'), 0)
})
