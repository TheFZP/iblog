import { computed, ref, watch } from 'vue'

/** 与桌面版共用 key，便于从桌面版迁移持仓数据 */
const STORAGE_KEY = 'stock-analysis-positions'

export interface Position {
  code: string
  name: string
  /** 成本价 */
  buyPrice: number
  /** 持仓数量（股） */
  quantity: number
  /** 买入日期 YYYY-MM-DD */
  buyDate?: string
  addedAt?: string
  /** 以下为行情快照，每次刷新整体覆盖，用户字段永不被行情覆盖 */
  price?: number
  prevClose?: number
  change?: number
  changePct?: number
  quoteAt?: number
}

/** 持仓 + 实时行情算出的派生指标 */
export interface PositionStats extends Position {
  currentPrice: number
  profit: number
  profitPct: number
  totalCost: number
  marketValue: number
  /** 当日盈亏 = (现价 - 昨收) × 数量 */
  dayProfit: number
  dayProfitPct: number
  currency: string
}

function loadPositions(): Position[] {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (raw) {
      const parsed = JSON.parse(raw)
      if (Array.isArray(parsed)) {
        return parsed.filter((p: Position) => p && p.code)
      }
    }
  } catch {
    /* 隐私模式 / 脏数据 */
  }
  return []
}

function today(): string {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

/**
 * 持仓管理（localStorage 持久化）。
 *
 * <p>模块级单例：行情页、详情页的持仓表单共享同一份状态。</p>
 *
 * <p><b>行情字段与用户字段刻意分开存放</b>：行情每次刷新整体覆盖指定字段，
 * 而成本/数量等用户录入字段永不被行情写回。早期桌面版用 {@code {...pos, ...quote}}
 * 合并，一旦行情对象将来带上同名字段（如 name）就会静默改写用户数据，
 * 所以这里坚持白名单拷贝。</p>
 */
const positions = ref<Position[]>(loadPositions())

watch(
  positions,
  (val) => {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(val))
    } catch {
      /* ignore quota errors */
    }
  },
  { deep: true }
)

/** 新增或覆盖一条持仓（同一只股票只保留一条） */
function upsertPosition(input: Omit<Position, 'addedAt'>) {
  if (!input?.code) {
    return
  }
  const entry: Position = {
    ...input,
    buyPrice: Number(input.buyPrice) || 0,
    quantity: Number(input.quantity) || 0,
    buyDate: input.buyDate || today(),
    addedAt: today()
  }
  const idx = positions.value.findIndex((p) => p.code === entry.code)
  if (idx === -1) {
    positions.value = [...positions.value, entry]
  } else {
    // 保留原行情快照，只更新用户字段
    positions.value = positions.value.map((p, i) =>
      i === idx ? { ...p, ...entry, addedAt: p.addedAt ?? entry.addedAt } : p
    )
  }
}

function removePosition(code: string) {
  positions.value = positions.value.filter((p) => p.code !== code)
}

/** 行情回写：只碰行情字段，绝不覆盖 buyPrice / quantity */
function applyPositionQuote(code: string, patch: Partial<Position>) {
  const idx = positions.value.findIndex((p) => p.code === code)
  if (idx === -1) {
    return
  }
  const cur = positions.value[idx]
  positions.value = positions.value.map((p, i) =>
    i === idx
      ? {
          ...p,
          price: patch.price ?? cur.price,
          prevClose: patch.prevClose ?? cur.prevClose,
          change: patch.change ?? cur.change,
          changePct: patch.changePct ?? cur.changePct,
          quoteAt: Date.now()
        }
      : p
  )
}

const positionStats = computed<PositionStats[]>(() =>
  positions.value.map((p) => {
    const hasQuote = (p.price ?? 0) > 0
    // 无行情时用成本价兜底，此时盈亏为 0 而不是 NaN/Infinity
    const currentPrice = hasQuote ? (p.price as number) : p.buyPrice
    const buyPrice = p.buyPrice || 0
    const quantity = p.quantity || 0
    const totalCost = buyPrice * quantity
    const marketValue = currentPrice * quantity
    const profit = buyPrice > 0 ? (currentPrice - buyPrice) * quantity : 0
    const profitPct = buyPrice > 0 ? ((currentPrice - buyPrice) / buyPrice) * 100 : 0
    const prevClose = p.prevClose ?? 0
    const dayProfit = hasQuote && prevClose > 0 ? (currentPrice - prevClose) * quantity : 0
    const dayProfitPct = hasQuote && prevClose > 0 ? ((currentPrice - prevClose) / prevClose) * 100 : 0
    return {
      ...p,
      currentPrice,
      profit,
      profitPct,
      totalCost,
      marketValue,
      dayProfit,
      dayProfitPct,
      currency: p.code.length === 5 ? 'HK$' : '¥'
    }
  })
)

/** 汇总：只统计有成本价的持仓，未填成本的记录不计入收益率 */
const summary = computed(() => {
  const list = positionStats.value
  const totalCost = list.reduce((s, p) => s + p.totalCost, 0)
  const marketValue = list.reduce((s, p) => s + p.marketValue, 0)
  const dayProfit = list.reduce((s, p) => s + p.dayProfit, 0)
  const profit = marketValue - totalCost
  return {
    count: list.length,
    totalCost,
    marketValue,
    profit,
    profitPct: totalCost > 0 ? (profit / totalCost) * 100 : 0,
    dayProfit,
    dayProfitPct: marketValue - dayProfit > 0 ? (dayProfit / (marketValue - dayProfit)) * 100 : 0
  }
})

export function usePositions() {
  return {
    positions,
    positionStats,
    summary,
    upsertPosition,
    removePosition,
    applyPositionQuote
  }
}
