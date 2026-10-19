import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  judgePositionAlert,
  profitPercent,
  normalizePct
} from '../src/utils/positionAlertRules.ts'

/**
 * 持仓止损止盈判定测试。
 *
 * <p>直接导入真实的 TS 源文件（Node 22.6+ 的 `--experimental-strip-types`），
 * 不复制一份逻辑来测 —— 复制出来的副本永远会通过，却证明不了线上跑的那份是对的。</p>
 *
 * <p>被测模块刻意做成零依赖：不 import Vue、不碰 localStorage，
 * 所以不需要 jsdom 或任何前端测试框架就能直接跑。</p>
 *
 * <p>运行：`node --experimental-strip-types --test scripts/`</p>
 */

test('亏损达到阈值触发止损', () => {
  // 成本 100，现价 92，亏 8%
  assert.deepEqual(judgePositionAlert({ buyPrice: 100, price: 92, stopLossPct: 8 }), ['stop_loss'])
})

test('边界取闭区间：刚好亏到 8% 就提醒', () => {
  // 这是最容易被改成开区间的点，行为差别是「要不要等到 8.0001%」
  assert.deepEqual(judgePositionAlert({ buyPrice: 100, price: 92, stopLossPct: 8 }), ['stop_loss'])
  // 差一点点不报
  assert.deepEqual(judgePositionAlert({ buyPrice: 100, price: 92.01, stopLossPct: 8 }), [])
})

test('盈利达到阈值触发止盈', () => {
  assert.deepEqual(judgePositionAlert({ buyPrice: 100, price: 120, takeProfitPct: 20 }), ['take_profit'])
  // 边界闭区间
  assert.deepEqual(judgePositionAlert({ buyPrice: 100, price: 119.99, takeProfitPct: 20 }), [])
})

test('未设置阈值时不触发', () => {
  assert.deepEqual(judgePositionAlert({ buyPrice: 100, price: 50 }), [])
})

test('非正数阈值视为未启用', () => {
  // 用户清空输入框时 v-model 给 ''，Number('') === 0，
  // 如果不拦就会变成「亏 0% 就提醒」，每轮都触发
  assert.equal(normalizePct(0), null)
  assert.equal(normalizePct(-5), null)
  assert.equal(normalizePct(''), null)
  assert.equal(normalizePct(null), null)
  assert.equal(normalizePct(undefined), null)
  assert.equal(normalizePct(NaN), null)
  assert.equal(normalizePct(8), 8)
  assert.equal(normalizePct('8'), 8)

  assert.deepEqual(judgePositionAlert({ buyPrice: 100, price: 10, stopLossPct: 0 }), [])
  assert.deepEqual(judgePositionAlert({ buyPrice: 100, price: 10, stopLossPct: -8 }), [])
})

test('没有成本价或现价时不判定', () => {
  // 未填成本的持仓不该被当成「亏 100%」
  assert.deepEqual(judgePositionAlert({ buyPrice: 0, price: 100, stopLossPct: 8 }), [])
  assert.deepEqual(judgePositionAlert({ buyPrice: 100, price: 0, stopLossPct: 8 }), [])
  assert.deepEqual(judgePositionAlert({ buyPrice: -1, price: 100, stopLossPct: 8 }), [])
})

test('今天已报过的类型不再重复报', () => {
  // 股价在阈值附近抖动时会连发几十条，比不提醒还烦
  assert.deepEqual(
    judgePositionAlert({ buyPrice: 100, price: 90, stopLossPct: 8, done: ['stop_loss'] }),
    []
  )
  // 但另一种原因仍然可以报
  assert.deepEqual(
    judgePositionAlert({ buyPrice: 100, price: 90, stopLossPct: 8, takeProfitPct: 20, done: ['take_profit'] }),
    ['stop_loss']
  )
})

test('同时设置止损止盈时可以同时触发（理论上不会，但逻辑要正确）', () => {
  // 正常情况下盈亏不可能同时 <= -8% 且 >= +20%，这里只验证两者互不干扰
  assert.deepEqual(judgePositionAlert({ buyPrice: 100, price: 125, stopLossPct: 8, takeProfitPct: 20 }), [
    'take_profit'
  ])
  assert.deepEqual(judgePositionAlert({ buyPrice: 100, price: 90, stopLossPct: 8, takeProfitPct: 20 }), [
    'stop_loss'
  ])
})

test('盈亏百分比用成本价计算', () => {
  assert.equal(profitPercent(100, 110), 10)
  assert.equal(profitPercent(100, 90), -10)
  assert.equal(profitPercent(0, 100), 0)
  // 浮点误差可接受 —— 判定用的是同一个函数，口径一致就不会不一致
  assert.ok(Math.abs(profitPercent(3.33, 3.5) - 5.105105105105109) < 1e-9)
})
