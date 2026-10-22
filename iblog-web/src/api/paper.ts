import service, { get, post } from './request'

/**
 * 模拟盘接口。
 *
 * <p>后端由 /api/stock/paper/** 提供，账户与后台登录态绑定（一个后台用户最多 5 个模拟账户），
 * 所以这里不需要传 userId —— Cookie 就是身份。金额单位为元，收益率单位是百分数。</p>
 *
 * <p>多账户：账户列表之外的接口都接受可选 accountId 查询参数，不传时作用于主账户；
 * 页面切换账户后，所有读写都必须带上当前 accountId，避免落到主账户。</p>
 */

/** 交易方向 */
export type PaperSide = 'BUY' | 'SELL'

/** 委托类型：市价 / 限价 */
export type PaperOrderType = 'MARKET' | 'LIMIT'

/** 委托状态 */
export type PaperOrderStatus = 'PENDING' | 'FILLED' | 'CANCELLED' | 'REJECTED'

/** 委托来源：手动 / 策略自动交易 */
export type PaperOrderSource = 'MANUAL' | 'STRATEGY'

/** 模拟账户 */
export interface PaperAccount {
  accountId: number
  /** 账户名称 */
  name: string
  /** 初始资金 */
  initialCash: number
  /** 可用现金 */
  cash: number
  /** 持仓市值 */
  marketValue: number
  /** 总资产 = 现金 + 市值 */
  totalAsset: number
  /** 累计盈亏 */
  totalProfit: number
  /** 累计收益率（%） */
  totalProfitPct: number
  /** 今日盈亏 */
  todayProfit: number
  /** 今日收益率（%） */
  todayProfitPct: number
  positionCount: number
  /** 自动交易总开关，后端为 Integer：0 关闭 / 1 开启 */
  autoTrade: number
  /** 当前交易日 yyyy-MM-dd */
  tradeDate: string
}

/** 账户列表项：在账户资产之上多了排序号与「是否主账户」标记 */
export interface PaperAccountSummary extends PaperAccount {
  /** 排序号，越小越靠前 */
  sortNo?: number
  /** 是否主账户（不传 accountId 时默认作用的账户） */
  primary?: boolean
}

/** 新建账户请求体 */
export interface PaperAccountCreateRequest {
  /** 账户名称；不传由后端生成默认名 */
  name?: string
  /** 初始资金（元），正数 */
  initialCash?: number
}

/** 持仓（availableQuantity 是 T+1 的可卖数量） */
export interface PaperPosition {
  code: string
  name: string
  /** 持仓数量（股） */
  quantity: number
  /** 可卖数量（股） */
  availableQuantity: number
  /** 摊薄成本价（含买入费用） */
  costPrice: number
  /** 最新价 */
  lastPrice: number
  marketValue: number
  /** 浮动盈亏 */
  profit: number
  /** 浮动盈亏率（%） */
  profitPct: number
  /** 首次建仓日 yyyy-MM-dd */
  openDate: string
  /** 币种符号：¥ 或 HK$ */
  currency: string
}

/** 委托 */
export interface PaperOrder {
  id: number
  code: string
  name: string
  side: PaperSide
  type: PaperOrderType
  /** 限价；市价单为 null */
  price: number | null
  /** 委托数量（股） */
  quantity: number
  /** 已成交数量（股） */
  filledQuantity: number
  /** 成交均价；未成交为 null */
  avgPrice: number | null
  /** 成交金额；未成交为 null */
  amount: number | null
  status: PaperOrderStatus
  /** 拒单原因（资金不足、涨跌停、非交易时段…），空串表示无 */
  rejectReason: string
  source: PaperOrderSource
  /** 委托时间 */
  createTime: string
  /** 成交时间；未成交为 null */
  filledTime: string | null
}

/** 分页结果（委托 / 成交共用同一形状） */
export interface PaperPage<T> {
  total: number
  records: T[]
}

export type PaperOrderPage = PaperPage<PaperOrder>
export type PaperTradePage = PaperPage<PaperTrade>

/** 成交（交割单） */
export interface PaperTrade {
  id: number
  code: string
  name: string
  side: PaperSide
  /** 成交价 */
  price: number
  /** 成交数量（股） */
  quantity: number
  /** 成交金额（不含费用） */
  amount: number
  /** 佣金 */
  commission: number
  /** 印花税（仅卖出） */
  stampTax: number
  /** 过户费 */
  transferFee: number
  /** 费用合计 */
  totalFee: number
  /** 交易日 yyyy-MM-dd */
  tradeDate: string
  /** 成交时间 */
  tradeTime: string
}

/** 每日净值快照（净值曲线用） */
export interface PaperNavPoint {
  /** 交易日 yyyy-MM-dd */
  date: string
  cash: number
  marketValue: number
  totalAsset: number
  /** 当日盈亏 */
  dayPnl: number
  /** 当日收益率（%） */
  dayPnlPct: number
  /** 累计盈亏 */
  totalPnl: number
  /** 累计收益率（%） */
  totalPnlPct: number
}

/** 下单请求体 */
export interface PaperOrderRequest {
  code: string
  side: PaperSide
  type: PaperOrderType
  /** 限价单必填，市价单不传 */
  price?: number
  /** 委托数量（股，100 的整数倍） */
  quantity: number
}

/** 把 accountId 拼成 query 字符串；不指定时不带参数，后端落到主账户 */
function accountQuery(accountId?: number): string {
  return accountId == null ? '' : `?accountId=${encodeURIComponent(String(accountId))}`
}

/** GET 参数合并 accountId；accountId 为空时原样返回（axios 会忽略 undefined 字段） */
function withAccountId(
  params: Record<string, unknown> | undefined,
  accountId?: number
): Record<string, unknown> | undefined {
  if (accountId == null) {
    return params
  }
  return { ...(params ?? {}), accountId }
}

// ---------- 多账户 ----------

/** 账户列表（最多 5 个，主账户带 primary 标记） */
export function fetchPaperAccounts() {
  return get<PaperAccountSummary[]>('/stock/paper/accounts')
}

/** 新建模拟账户；超过上限时后端返回业务错误，message 可直接展示 */
export function createPaperAccount(body: PaperAccountCreateRequest) {
  return post<PaperAccountSummary>('/stock/paper/accounts', body)
}

/** 账户改名 */
export function renamePaperAccount(accountId: number, name: string) {
  return service.patch(
    `/stock/paper/accounts/${encodeURIComponent(String(accountId))}`,
    { name }
  ) as unknown as Promise<PaperAccountSummary>
}

/** 删除账户；只剩一个时后端返回业务错误「至少保留一个模拟盘」 */
export function deletePaperAccount(accountId: number) {
  return service.delete(
    `/stock/paper/accounts/${encodeURIComponent(String(accountId))}`
  ) as unknown as Promise<null>
}

// ---------- 账户资产 / 持仓 / 委托 / 成交 / 净值 ----------

/** 指定账户（缺省主账户）的资产 */
export function fetchPaperAccount(accountId?: number) {
  return get<PaperAccount>('/stock/paper/account', withAccountId(undefined, accountId))
}

/** 指定账户的持仓 */
export function fetchPaperPositions(accountId?: number) {
  return get<PaperPosition[]>('/stock/paper/positions', withAccountId(undefined, accountId))
}

/** 委托分页列表；status 留空表示全部 */
export function fetchPaperOrders(params: {
  status?: string
  page?: number
  size?: number
  accountId?: number
}) {
  return get<PaperOrderPage>(
    '/stock/paper/orders',
    withAccountId(
      { status: params.status, page: params.page, size: params.size },
      params.accountId
    )
  )
}

/** 下单。业务失败（资金不足 / 非交易时段 / 涨跌停…）时 reject，message 可直接展示 */
export function placePaperOrder(body: PaperOrderRequest, accountId?: number) {
  return service.post(
    `/stock/paper/orders${accountQuery(accountId)}`,
    body
  ) as unknown as Promise<PaperOrder>
}

/**
 * 撤单。
 *
 * <p>request.ts 只导出了 get/post，DELETE 直接用默认实例发；响应拦截器已把 res.data
 * 解包成业务对象，所以这里断言成 Promise&lt;PaperOrder&gt;，与 post 的返回口径一致。</p>
 */
export function cancelPaperOrder(id: number, accountId?: number) {
  return service.delete(
    `/stock/paper/orders/${encodeURIComponent(String(id))}${accountQuery(accountId)}`
  ) as unknown as Promise<PaperOrder>
}

/** 成交分页列表 */
export function fetchPaperTrades(params: {
  page?: number
  size?: number
  accountId?: number
}) {
  return get<PaperTradePage>(
    '/stock/paper/trades',
    withAccountId({ page: params.page, size: params.size }, params.accountId)
  )
}

/** 最近 N 日净值（按日期升序） */
export function fetchPaperNav(days = 90, accountId?: number) {
  return get<PaperNavPoint[]>('/stock/paper/nav', withAccountId({ days }, accountId))
}

/** 重置账户：清空持仓/委托/成交并把资金恢复为初始值 */
export function resetPaperAccount(accountId?: number) {
  return service.post(
    `/stock/paper/account/reset${accountQuery(accountId)}`
  ) as unknown as Promise<PaperAccount>
}

// ---------- P2：策略 / 选股 / 引擎 ----------

/** 信号动作：候选 / 买入 / 卖出 */
export type PaperSignalAction = 'CANDIDATE' | 'BUY' | 'SELL'

/**
 * 引擎状态（GET /engine/status）。
 *
 * <p>这里的 autoTrade 是后端归一后的 boolean；账户对象里的 autoTrade 是 Integer 0/1，
 * 两者口径不同，别混用。</p>
 */
export interface PaperEngineStatus {
  /** 服务端模拟盘总开关（iblog.paper.enabled） */
  enabled: boolean
  /** 当前账户的自动交易开关 */
  autoTrade: boolean
  /** 策略本身是否启用 */
  strategyEnabled: boolean
  /** 当前是否 A 股交易时段 */
  trading: boolean
  /** 是否开启强制交易时段（本地开发用） */
  forceTradingHours: boolean
  /** 今日候选数量 */
  candidates: number
  /** 今日盈亏 */
  todayProfit: number
  /** 今日收益率（%） */
  todayProfitPct: number
  /** 最近一次选股时间；尚未跑过为 null */
  lastRunTime: string | null
}

/** 策略实体：enabled 是 Integer 0/1，params 是 JSON 文本 */
export interface PaperStrategy {
  id: number
  accountId?: number
  name: string
  /** 目前只有 AUTO（趋势选股） */
  type: string
  /** 参数 JSON 文本，包含 PaperStrategyParams 的全部字段 */
  params: string
  /** 0 关闭 / 1 开启 */
  enabled: number
  lastRunTime: string | null
  createTime?: string
  updateTime?: string
}

/** 策略参数（前端解析 params 后用于表单；数值范围见页面上的 min/max 钳制） */
export interface PaperStrategyParams {
  /** 入选最低分 0–100 */
  minScore: number
  /** 止损比例（%） */
  stopLossPct: number
  /** 止盈比例（%） */
  takeProfitPct: number
  /** 最长持有天数 */
  maxHoldDays: number
  /** 候选池大小：取热榜前 N 只 */
  universeSize: number
  /** 最多候选数量 */
  maxCandidates: number
  /** 选股刷新间隔（秒） */
  selectionRefreshSec: number
  /** 均线趋势权重 */
  maTrendScore: number
  /** MA20 权重 */
  ma20Score: number
  /** MACD 权重 */
  macdScore: number
  /** 量比权重 */
  volumeRatioScore: number
  /** 资金流权重 */
  moneyFlowScore: number
  /** 资金流占比权重 */
  moneyFlowPctScore: number
  /** RSI 权重 */
  rsiScore: number
  /** 当日涨幅权重 */
  changeScore: number
  /** 热榜排名权重 */
  hotRankScore: number
  /** 量比下限 */
  volumeRatioMin: number
  /** 资金流占比下限（%） */
  moneyFlowPctMin: number
  /** RSI 下限 */
  rsiMin: number
  /** RSI 上限 */
  rsiMax: number
  /** 涨幅下限（%） */
  changeMinPct: number
  /** 涨幅上限（%） */
  changeMaxPct: number
}

/** 更新策略：enabled 与 params 都可选，只传要改的字段 */
export interface PaperStrategyUpdateRequest {
  enabled?: boolean
  params?: string
}

/** 选股结果 / 交易信号 */
export interface PaperSignal {
  id: number
  accountId?: number
  strategyId?: number
  code: string
  name: string
  action: PaperSignalAction
  /** 中文入选理由 */
  reason: string
  /** 综合评分 */
  score: number
  /** 评分时价格 */
  price: number
  /** 交易日 yyyy-MM-dd */
  tradeDate: string
  createTime: string
}

/** 引擎状态：服务端总开关、自动交易、交易时段、候选数、今日盈亏 */
export function fetchPaperEngineStatus(accountId?: number) {
  return get<PaperEngineStatus>(
    '/stock/paper/engine/status',
    withAccountId(undefined, accountId)
  )
}

/** 指定账户的策略列表（后端保证至少一条默认策略） */
export function fetchPaperStrategies(accountId?: number) {
  return get<PaperStrategy[]>('/stock/paper/strategies', withAccountId(undefined, accountId))
}

/** 更新策略开关或参数；只传要改的字段 */
export function updatePaperStrategy(
  id: number,
  body: PaperStrategyUpdateRequest,
  accountId?: number
) {
  return service.put(
    `/stock/paper/strategies/${encodeURIComponent(String(id))}${accountQuery(accountId)}`,
    body
  ) as unknown as Promise<PaperStrategy>
}

/** 今日候选（按分数从高到低） */
export function fetchPaperSelections(accountId?: number) {
  return get<PaperSignal[]>('/stock/paper/selections', withAccountId(undefined, accountId))
}

/**
 * 立即重新选股并覆盖当日候选。
 *
 * <p>一轮要现场拉热榜 + 日 K，耗时可能超过默认 15s 超时，这里单独放宽到 60s。</p>
 */
export function refreshPaperSelections(accountId?: number) {
  return service.post(
    `/stock/paper/selections/refresh${accountQuery(accountId)}`,
    undefined,
    { timeout: 60000 }
  ) as unknown as Promise<PaperSignal[]>
}

/** 最近信号（含自动买卖记录，按时间倒序） */
export function fetchPaperSignals(limit = 50, accountId?: number) {
  return get<PaperSignal[]>(
    '/stock/paper/signals',
    withAccountId({ limit }, accountId)
  )
}

/** 打开/关闭账户自动交易；服务端总开关关闭时后端返回业务错误 */
export function setPaperAutoTrade(enabled: boolean, accountId?: number) {
  return service.post(
    `/stock/paper/account/auto-trade${accountQuery(accountId)}`,
    { enabled }
  ) as unknown as Promise<PaperAccount>
}
