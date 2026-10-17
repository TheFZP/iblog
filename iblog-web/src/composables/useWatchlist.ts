import { computed, ref, watch } from 'vue'
import type { StockQuote } from '@/api/stock'

/** 与桌面版共用同一个 key，便于将来从桌面版迁移数据 */
const STORAGE_KEY = 'stock-analysis-watchlist'

export interface WatchStock {
  code: string
  name: string
  market?: string
  price?: number
  change?: number
  changePct?: number
  /** 加入自选的日期 YYYY-MM-DD */
  addedAt?: string
  /** 加入时的价格，用于计算加入以来收益 */
  addedPrice?: number
  [key: string]: unknown
}

/** 默认股票池：仅作首次打开的种子数据 */
const DEFAULT_STOCKS: WatchStock[] = [
  { code: '300750', name: '宁德时代', market: 'SZ' }
]

function loadWatchlist(): WatchStock[] {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (raw) {
      const parsed = JSON.parse(raw)
      // 空数组是用户的真实意图（已清空自选），不能当作“无数据”重新塞回默认股
      if (Array.isArray(parsed)) {
        return parsed.filter((s: WatchStock) => s && s.code)
      }
    }
  } catch {
    /* 隐私模式 / 脏数据：回落到默认池 */
  }
  // 深拷贝，避免行情回写污染模块级常量
  return DEFAULT_STOCKS.map((s) => ({ ...s }))
}

function today(): string {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

/**
 * 自选股状态（localStorage 持久化）。
 *
 * <p>模块级单例：多个组件共享同一份状态，无需引入 pinia。</p>
 */
const watchlist = ref<WatchStock[]>(loadWatchlist())

// 持久化：隐私模式 / quota 满时静默忽略
watch(
  watchlist,
  (val) => {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(val))
    } catch {
      /* ignore quota / security errors */
    }
  },
  { deep: true }
)

function isInWatchlist(code: string): boolean {
  return watchlist.value.some((s) => s.code === code)
}

function addToWatchlist(stock: WatchStock) {
  if (!stock?.code || isInWatchlist(stock.code)) {
    return
  }
  watchlist.value = [
    ...watchlist.value,
    {
      ...stock,
      addedAt: today(),
      // 搜索添加时没有价格，先留空，首次行情回填
      addedPrice: (stock.price ?? 0) > 0 ? stock.price : undefined
    }
  ]
}

function removeFromWatchlist(code: string) {
  watchlist.value = watchlist.value.filter((s) => s.code !== code)
}

function toggleWatchlist(stock: WatchStock) {
  if (isInWatchlist(stock.code)) {
    removeFromWatchlist(stock.code)
  } else {
    addToWatchlist(stock)
  }
}

/** 用实时行情回写某只股票的缓存数据（名称、价格等） */
function applyQuote(code: string, quote: StockQuote) {
  const idx = watchlist.value.findIndex((s) => s.code === code)
  if (idx === -1) {
    return
  }
  const cur = watchlist.value[idx]
  watchlist.value[idx] = {
    ...cur,
    name: quote.name || cur.name,
    market: quote.market || cur.market,
    price: quote.price,
    change: quote.change,
    changePct: quote.changePct,
    addedPrice: cur.addedPrice ?? (quote.price > 0 ? quote.price : undefined)
  }
  // 触发 watch 深度监听需要换引用
  watchlist.value = [...watchlist.value]
}

/** 批量回写行情，避免 N 次数组重建 */
function applyQuotes(list: StockQuote[]) {
  const map = new Map(list.map((q) => [q.code, q]))
  let changed = false
  const next = watchlist.value.map((s) => {
    const q = map.get(s.code)
    if (!q) {
      return s
    }
    changed = true
    return {
      ...s,
      name: q.name || s.name,
      market: q.market || s.market,
      price: q.price,
      change: q.change,
      changePct: q.changePct,
      addedPrice: s.addedPrice ?? (q.price > 0 ? q.price : undefined)
    }
  })
  if (changed) {
    watchlist.value = next
  }
}

export function useWatchlist() {
  return {
    watchlist,
    count: computed(() => watchlist.value.length),
    isInWatchlist,
    addToWatchlist,
    removeFromWatchlist,
    toggleWatchlist,
    applyQuote,
    applyQuotes
  }
}
