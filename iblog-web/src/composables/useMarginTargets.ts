import { ref } from 'vue'
import { fetchMarginTargets } from '@/api/stock'

/**
 * 融资融券标的清单（全局共享）。
 *
 * <p>清单约 3900 个代码、每天最多变一次，所以整站只拉一次并缓存在模块级 ref 里：
 * 详情页、行情列表、模拟盘表格共用同一份，不会每个组件各拉一遍。
 * 拉取失败不阻塞页面——只是不显示「融」标签，下次进入页面会再试。</p>
 */
const codes = ref<Set<string>>(new Set())
let pending: Promise<void> | null = null
let loaded = false

function ensureMarginTargets(): Promise<void> {
  if (loaded) return Promise.resolve()
  if (pending) return pending
  pending = fetchMarginTargets()
    .then((list) => {
      codes.value = new Set((list ?? []).map((code) => String(code)))
      loaded = true
    })
    .catch(() => {
      // 吞掉错误：标签缺省不影响行情与交易
    })
    .finally(() => {
      pending = null
    })
  return pending
}

export function useMarginTargets() {
  return {
    ensureMarginTargets,
    isMargin: (code: string | null | undefined) => codes.value.has(String(code ?? '')),
  }
}