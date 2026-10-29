import { test } from 'node:test'
import assert from 'node:assert/strict'

import {
  buildPresetText,
  clampPage,
  pageCount,
  sliceRows,
} from '../src/components/stock/iwencai/rows.ts'

test('sliceRows 按页切片', () => {
  const rows = ['a', 'b', 'c', 'd', 'e']
  assert.deepEqual(sliceRows(rows, 1, 2), ['a', 'b'])
  assert.deepEqual(sliceRows(rows, 2, 2), ['c', 'd'])
  assert.deepEqual(sliceRows(rows, 3, 2), ['e'])
  assert.deepEqual(sliceRows(rows, 4, 2), [])
})

test('sliceRows 页码越界与非法值都退回第一页', () => {
  const rows = ['a', 'b', 'c']
  assert.deepEqual(sliceRows(rows, 0, 2), ['a', 'b'])
  assert.deepEqual(sliceRows(rows, -5, 2), ['a', 'b'])
  assert.deepEqual(sliceRows(rows, Number.NaN, 2), ['a', 'b'])
  assert.deepEqual(sliceRows(rows, 1, 0), [])
  assert.deepEqual(sliceRows([], 1, 20), [])
})

test('pageCount 不满一页也算一页', () => {
  assert.equal(pageCount(0, 20), 1)
  assert.equal(pageCount(1, 20), 1)
  assert.equal(pageCount(20, 20), 1)
  assert.equal(pageCount(21, 20), 2)
  assert.equal(pageCount(100, 20), 5)
  assert.equal(pageCount(100, 0), 1)
})

test('clampPage 把页码夹在有效范围内', () => {
  assert.equal(clampPage(0, 3), 1)
  assert.equal(clampPage(2, 3), 2)
  assert.equal(clampPage(9, 3), 3)
  assert.equal(clampPage(5, 0), 1)
  assert.equal(clampPage(Number.NaN, 3), 1)
})

test('buildPresetText 首行列名、每行一条、用 | 分隔', () => {
  const text = buildPresetText(
    ['股票代码', '股票简称', '涨跌幅'],
    [
      ['600519', '贵州茅台', '2.13%'],
      ['000001', '平安银行', '-0.55%'],
    ],
  )
  assert.equal(
    text,
    '股票代码 | 股票简称 | 涨跌幅\n600519 | 贵州茅台 | 2.13%\n000001 | 平安银行 | -0.55%',
  )
})

test('buildPresetText 只取前 30 行', () => {
  const rows = Array.from({ length: 50 }, (_, i) => [`${i}`, `名${i}`])
  const lines = buildPresetText(['代码', '名称'], rows).split('\n')
  assert.equal(lines.length, 31)
  assert.equal(lines[1], '0 | 名0')
  assert.equal(lines[30], '29 | 名29')
})

test('buildPresetText 行比列短时补空串、去首尾空白', () => {
  const text = buildPresetText(
    ['代码', '名称', '备注'],
    [['600519 ', ' 贵州茅台'], ['  ']],
  )
  assert.equal(text, '代码 | 名称 | 备注\n600519 | 贵州茅台 | \n |  | ')
})

test('buildPresetText 空输入返回空串', () => {
  assert.equal(buildPresetText([], []), '')
  assert.equal(buildPresetText(['代码'], []), '代码')
})
