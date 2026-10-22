<template>
  <div class="paper-page">
    <div class="layout">
      <!-- 页头 -->
      <header class="page-head">
        <div class="page-head__main">
          <h1 class="page-title">模拟盘</h1>
          <p class="page-desc">
            用虚拟资金练手，成交与费用按真实规则模拟。{{
              stateBadge.live ? `交易时段每 ${refreshSec} 秒自动刷新` : '休市期间降频刷新'
            }}，页面切到后台时暂停，回到前台自动补刷。
          </p>
        </div>
        <div class="page-head__side">
          <span class="refresh-state" :class="{ 'is-live': stateBadge.live }">
            <span class="dot" aria-hidden="true" />
            {{ stateBadge.text }}
          </span>
          <button
            class="btn btn--ghost head-btn"
            type="button"
            :disabled="refreshing"
            @click="manualRefresh"
          >
            <svg viewBox="0 0 24 24" class="head-btn__icon" :class="{ spin: refreshing }" aria-hidden="true">
              <path d="M20 11a8 8 0 1 0-.6 4M20 5v6h-6" />
            </svg>
            {{ refreshing ? '刷新中' : '刷新' }}
          </button>
          <button
            class="btn btn--ghost head-btn reset-btn"
            :class="{ 'is-armed': resetArmed }"
            type="button"
            :disabled="resetting"
            :title="resetArmed ? '再点一次确认重置' : '清空持仓、委托与成交，资金恢复到初始值'"
            @click="doReset"
          >
            {{ resetting ? '重置中…' : resetArmed ? '确认重置' : '重置账户' }}
          </button>
          <button class="btn head-btn" type="button" @click="openTrade">手动买卖</button>
        </div>
      </header>

      <!-- 账户栏：多开模拟盘，切换后整页数据都跟着换 -->
      <section class="account-bar" aria-label="模拟盘账户">
        <div class="account-bar__list" role="group" aria-label="选择模拟盘">
          <button
            v-for="item in accounts"
            :key="item.accountId"
            type="button"
            class="account-chip"
            :class="{ 'is-active': item.accountId === currentAccountId }"
            :aria-pressed="item.accountId === currentAccountId"
            :disabled="accountSwitching"
            @click="switchAccount(item.accountId)"
          >
            <span class="account-chip__name">
              {{ item.name }}
              <span v-if="item.primary" class="account-chip__flag">主</span>
            </span>
            <span class="account-chip__asset">{{ money(item.totalAsset) }}</span>
            <span class="account-chip__pct" :class="toneClass(item.totalProfitPct)">
              {{ pct(item.totalProfitPct) }}
            </span>
          </button>
          <span v-if="accountsLoading && !accounts.length" class="account-bar__state">
            账户加载中…
          </span>
          <span
            v-else-if="accountsError"
            class="account-bar__state account-bar__state--error"
          >
            {{ accountsError }}
          </span>
        </div>
        <div class="account-bar__actions">
          <button
            class="mini-btn"
            type="button"
            :disabled="!canCreateAccount"
            :title="canCreateAccount ? '新建一个独立资金的模拟盘' : `最多 ${MAX_ACCOUNTS} 个模拟盘`"
            @click="openCreateAccount"
          >
            新建模拟盘
          </button>
          <button
            class="mini-btn"
            type="button"
            :disabled="!currentAccount"
            title="给当前模拟盘改名"
            @click="openRenameAccount"
          >
            改名
          </button>
          <button
            class="mini-btn mini-btn--danger"
            type="button"
            :disabled="!canDeleteAccount"
            :title="canDeleteAccount ? '删除当前模拟盘（需二次确认）' : '至少保留一个模拟盘'"
            @click="openDeleteAccount"
          >
            删除
          </button>
        </div>
      </section>

      <div class="stack">
        <!-- 资产卡 -->
        <section class="asset-card" aria-label="账户资产">
          <template v-if="account">
            <div class="asset-card__hero">
              <span class="asset-card__label">总资产</span>
              <strong class="asset-card__total">{{ money(account.totalAsset) }}</strong>
              <span class="asset-card__profit" :class="toneClass(account.todayProfit)">
                今日 {{ signedMoney(account.todayProfit) }}（{{ pct(account.todayProfitPct) }}）
              </span>
              <span class="asset-card__profit asset-card__profit--total" :class="toneClass(account.totalProfit)">
                累计 {{ signedMoney(account.totalProfit) }}（{{ pct(account.totalProfitPct) }}）
              </span>
              <div class="asset-card__badges">
                <span class="chip" :class="{ 'chip--brand': account.autoTrade === 1 }">
                  策略自动交易{{ account.autoTrade === 1 ? '开启' : '关闭' }}
                </span>
                <span v-if="account.tradeDate" class="chip">{{ account.tradeDate }}</span>
              </div>
            </div>
            <dl class="asset-card__metrics">
              <div class="metric">
                <dt>可用资金</dt>
                <dd>{{ money(account.cash) }}</dd>
              </div>
              <div class="metric">
                <dt>持仓市值</dt>
                <dd>{{ money(account.marketValue) }}</dd>
              </div>
              <div class="metric">
                <dt>持仓数</dt>
                <dd>{{ integer(account.positionCount) }}<small> 只</small></dd>
              </div>
              <div class="metric">
                <dt>初始资金</dt>
                <dd>{{ money(account.initialCash) }}</dd>
              </div>
            </dl>
          </template>

          <template v-else-if="loading">
            <div class="asset-card__hero">
              <span class="skeleton" style="width: 64px; height: 11px" />
              <span class="skeleton" style="width: 220px; height: 40px; margin-top: 12px" />
              <span class="skeleton" style="width: 190px; height: 13px; margin-top: 14px" />
            </div>
            <dl class="asset-card__metrics">
              <div v-for="n in 4" :key="n" class="metric">
                <dt><span class="skeleton" style="width: 52px; height: 10px" /></dt>
                <dd><span class="skeleton" style="width: 92px; height: 18px" /></dd>
              </div>
            </dl>
          </template>

          <div v-else class="asset-card__error">
            <p>{{ error || '账户数据暂时加载失败' }}</p>
            <button class="btn btn--ghost" type="button" @click="manualRefresh">重新加载</button>
          </div>
        </section>

        <!-- 引擎与策略 -->
        <section class="panel engine" aria-label="自动交易引擎与策略">
          <div class="panel__head panel__head--wrap">
            <h2 class="panel__title">
              引擎与策略
              <span v-if="engine" class="panel__count">{{ engine.candidates }} 只候选</span>
            </h2>
            <div class="engine__status">
              <span class="chip" :class="{ 'chip--on': engine?.enabled }">
                {{ engine ? (engine.enabled ? '服务端已开启' : '服务端未开启') : '—' }}
              </span>
              <span class="chip" :class="{ 'chip--on': engine?.trading }">
                {{
                  engine
                    ? engine.trading
                      ? '交易时段'
                      : engine.forceTradingHours
                        ? '强制交易时段'
                        : '非交易时段'
                    : '—'
                }}
              </span>
              <span class="chip" :class="{ 'chip--brand': engine?.autoTrade }">
                自动交易{{ engine?.autoTrade ? '运行中' : '已关闭' }}
              </span>
            </div>
          </div>

          <div v-if="engineLoading && !engine" class="engine__body">
            <dl class="engine__metrics">
              <div v-for="n in 4" :key="n" class="metric">
                <dt><span class="skeleton" style="width: 52px; height: 10px" /></dt>
                <dd><span class="skeleton" style="width: 92px; height: 18px" /></dd>
              </div>
            </dl>
          </div>

          <div v-else-if="engineError && !engine" class="table-state table-state--error">
            {{ engineError }}
          </div>

          <template v-else-if="engine">
            <div class="engine__body">
              <dl class="engine__metrics">
                <div class="metric">
                  <dt>今日盈亏</dt>
                  <dd :class="toneClass(engine.todayProfit)">{{ signedMoney(engine.todayProfit) }}</dd>
                </div>
                <div class="metric">
                  <dt>今日收益率</dt>
                  <dd :class="toneClass(engine.todayProfitPct)">{{ pct(engine.todayProfitPct) }}</dd>
                </div>
                <div class="metric">
                  <dt>今日候选</dt>
                  <dd>{{ integer(engine.candidates) }}<small> 只</small></dd>
                </div>
                <div class="metric">
                  <dt>最近选股</dt>
                  <dd class="engine__time">
                    {{ engine.lastRunTime ? shortTime(engine.lastRunTime) : '--' }}
                  </dd>
                </div>
              </dl>

              <div class="engine__switch">
                <p v-if="!engine.enabled" class="engine__warn">
                  服务端未开启模拟盘（iblog.paper.enabled=false），自动交易不可用。
                </p>
                <template v-if="!autoTradeConfirm">
                  <button
                    class="btn"
                    :class="{ 'btn--ghost': engine.autoTrade }"
                    type="button"
                    :disabled="!engine.enabled || autoTradeBusy"
                    :title="
                      engine.enabled
                        ? undefined
                        : '服务端未开启模拟盘（iblog.paper.enabled=false），自动交易不可用'
                    "
                    @click="askAutoTrade"
                  >
                    {{ autoTradeBusy ? '处理中…' : engine.autoTrade ? '关闭自动交易' : '开启自动交易' }}
                  </button>
                  <p v-if="engine.enabled" class="engine__hint">
                    {{
                      engine.autoTrade
                        ? '策略会按参数自动买卖，可随时关闭。'
                        : '开启后策略会按参数自动模拟下单。'
                    }}
                  </p>
                </template>
                <div v-else class="engine__confirm">
                  <p>自动交易会按策略真实模拟下单，确认开启？</p>
                  <div class="engine__confirm-row">
                    <button
                      class="btn"
                      type="button"
                      :disabled="autoTradeBusy"
                      @click="applyAutoTrade(true)"
                    >
                      {{ autoTradeBusy ? '处理中…' : '确认开启' }}
                    </button>
                    <button
                      class="btn btn--ghost"
                      type="button"
                      :disabled="autoTradeBusy"
                      @click="autoTradeConfirm = false"
                    >
                      取消
                    </button>
                  </div>
                </div>
              </div>
            </div>

            <form class="strategy" @submit.prevent="saveStrategy">
              <div class="strategy__head">
                <span class="strategy__name">{{ strategy?.name || '趋势选股' }}</span>
                <label class="strategy__toggle">
                  <input
                    type="checkbox"
                    :checked="strategy?.enabled === 1"
                    :disabled="!strategy || strategyBusy"
                    @change="onStrategyToggle"
                  />
                  <span>策略启用</span>
                </label>
              </div>
              <div class="strategy__grid">
                <label class="field">
                  <span class="field__label">最低分</span>
                  <input
                    v-model.number="strategyForm.minScore"
                    class="field__input"
                    type="number"
                    min="0"
                    max="100"
                    step="1"
                    :disabled="!strategy"
                  />
                  <span class="field__hint">0–100 分才入选</span>
                </label>
                <label class="field">
                  <span class="field__label">止损比例</span>
                  <span class="field__control">
                    <input
                      v-model.number="strategyForm.stopLossPct"
                      class="field__input"
                      type="number"
                      min="0.5"
                      max="50"
                      step="0.5"
                      :disabled="!strategy"
                    />
                    <span class="field__unit">%</span>
                  </span>
                  <span class="field__hint">跌破成本价卖出</span>
                </label>
                <label class="field">
                  <span class="field__label">止盈比例</span>
                  <span class="field__control">
                    <input
                      v-model.number="strategyForm.takeProfitPct"
                      class="field__input"
                      type="number"
                      min="0.5"
                      max="200"
                      step="0.5"
                      :disabled="!strategy"
                    />
                    <span class="field__unit">%</span>
                  </span>
                  <span class="field__hint">达到目标卖出</span>
                </label>
                <label class="field">
                  <span class="field__label">最长持有</span>
                  <span class="field__control">
                    <input
                      v-model.number="strategyForm.maxHoldDays"
                      class="field__input"
                      type="number"
                      min="1"
                      max="120"
                      step="1"
                      :disabled="!strategy"
                    />
                    <span class="field__unit">天</span>
                  </span>
                  <span class="field__hint">超期自动卖出</span>
                </label>
              </div>

              <button
                class="strategy__advanced-toggle"
                type="button"
                :aria-expanded="advancedOpen"
                @click="advancedOpen = !advancedOpen"
              >
                <svg viewBox="0 0 24 24" aria-hidden="true" :class="{ 'is-open': advancedOpen }">
                  <path d="m9 6 6 6-6 6" />
                </svg>
                高级规则（选股打分）
                <span class="strategy__advanced-hint">{{
                  advancedOpen ? '收起' : `共 ${ADVANCED_FIELDS.length} 项`
                }}</span>
              </button>

              <div v-if="advancedOpen" class="strategy__advanced">
                <div v-for="group in ADVANCED_GROUPS" :key="group.title" class="advanced__group">
                  <h3 class="advanced__title">{{ group.title }}</h3>
                  <div class="advanced__grid">
                    <label
                      v-for="item in group.fields"
                      :key="item.key"
                      class="field"
                    >
                      <span class="field__label">{{ item.label }}</span>
                      <span class="field__control">
                        <input
                          v-model.number="strategyForm[item.key]"
                          class="field__input"
                          type="number"
                          :min="ruleOf(item.key).min"
                          :max="ruleOf(item.key).max"
                          :step="item.step"
                          :disabled="!strategy"
                        />
                        <span v-if="item.unit" class="field__unit">{{ item.unit }}</span>
                      </span>
                      <span v-if="item.hint" class="field__hint">{{ item.hint }}</span>
                    </label>
                  </div>
                </div>
                <p class="advanced__sum">
                  因子权重合计 <strong>{{ weightSum }}</strong> 分（仅作参考，不影响入选总分上限）
                </p>
              </div>

              <p v-if="strategyError" class="strategy__error">{{ strategyError }}</p>
              <div class="strategy__actions">
                <button class="btn" type="submit" :disabled="strategyBusy || !strategy">
                  {{ strategyBusy ? '保存中…' : '保存规则' }}
                </button>
                <span v-if="strategy?.lastRunTime" class="strategy__meta">
                  最近运行 {{ shortTime(strategy.lastRunTime) }}
                </span>
              </div>
            </form>
          </template>
        </section>

        <!-- 今日选股 -->
        <section class="panel" aria-label="今日选股">
          <div class="panel__head">
            <h2 class="panel__title">
              今日选股
              <span class="panel__count">{{ selections.length }}</span>
            </h2>
            <div class="panel__actions">
              <button
                class="btn btn--ghost"
                type="button"
                :disabled="selectionsRefreshing"
                @click="refreshSelections"
              >
                {{ selectionsRefreshing ? '选股中…' : '重新选股' }}
              </button>
            </div>
          </div>

          <div class="table-wrap">
            <table v-if="selections.length" class="table">
              <thead>
                <tr>
                  <th scope="col">股票</th>
                  <th scope="col" class="num">分数</th>
                  <th scope="col" class="num">现价</th>
                  <th scope="col">入选理由</th>
                  <th scope="col" class="num">操作</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="item in selections" :key="item.id">
                  <td>
                    <div class="cell-name">
                      <span class="cell-name__title">{{ item.name }}</span>
                      <span class="cell-name__code">{{ item.code }}</span>
                    </div>
                  </td>
                  <td class="num">
                    <span
                      class="score-badge"
                      :class="{
                        'score-badge--hot': item.score >= 80,
                        'score-badge--warm': item.score >= 65 && item.score < 80
                      }"
                    >
                      {{ item.score }}
                    </span>
                  </td>
                  <td class="num">{{ priceText(item.price) }}</td>
                  <td class="cell-reason">{{ item.reason || '--' }}</td>
                  <td class="num">
                    <button class="mini-btn" type="button" @click="buySelection(item)">下单</button>
                  </td>
                </tr>
              </tbody>
            </table>

            <div v-else-if="selectionsLoading" class="table-state">加载中…</div>

            <div v-else-if="selectionsError" class="table-state table-state--error">
              {{ selectionsError }}
            </div>

            <div v-else class="empty-state">
              <svg class="empty-state__icon" viewBox="0 0 24 24" aria-hidden="true">
                <circle cx="11" cy="11" r="7" />
                <path d="m20 20-3.6-3.6" />
              </svg>
              <p class="empty-title">今天还没有候选股票</p>
              <p class="empty-desc">点右上角「重新选股」，从热榜里筛一批趋势标的。</p>
            </div>
          </div>
        </section>

        <!-- 净值曲线 -->
        <section class="panel" aria-label="净值曲线">
          <div class="panel__head">
            <h2 class="panel__title">净值曲线</h2>
            <div class="seg" role="group" aria-label="时间范围">
              <button
                v-for="days in NAV_DAYS"
                :key="days"
                type="button"
                class="seg__btn"
                :class="{ 'is-active': navDays === days }"
                :aria-pressed="navDays === days"
                @click="setNavDays(days)"
              >
                {{ days }} 天
              </button>
            </div>
          </div>
          <NavChart :items="nav" :loading="navLoading" />
        </section>

        <!-- 持仓 -->
        <section class="panel" aria-label="持仓">
          <div class="panel__head">
            <h2 class="panel__title">
              持仓
              <span class="panel__count">{{ positions.length }}</span>
            </h2>
            <span class="panel__meta">市值合计 {{ money(positionMarketValue) }}</span>
          </div>

          <div class="table-wrap">
            <table v-if="positions.length" class="table">
              <thead>
                <tr>
                  <th scope="col">股票</th>
                  <th scope="col" class="num">持仓 / 可用</th>
                  <th scope="col" class="num">成本价</th>
                  <th scope="col" class="num">现价</th>
                  <th scope="col" class="num">市值</th>
                  <th scope="col" class="num">浮动盈亏</th>
                  <th scope="col" class="num">操作</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="position in positions" :key="position.code">
                  <td>
                    <div class="cell-name">
                      <span class="cell-name__title">{{ position.name }}</span>
                      <span class="cell-name__code">{{ position.code }}</span>
                    </div>
                  </td>
                  <td class="num">
                    {{ integer(position.quantity) }}<span class="cell-sub"> / {{ integer(position.availableQuantity) }}</span>
                  </td>
                  <td class="num">{{ priceText(position.costPrice) }}</td>
                  <td class="num">{{ priceText(position.lastPrice) }}</td>
                  <td class="num">{{ money(position.marketValue, position.currency) }}</td>
                  <td class="num">
                    <div class="cell-pnl" :class="toneClass(position.profit)">
                      <span>{{ signedMoney(position.profit, position.currency) }}</span>
                      <span class="cell-sub">{{ pct(position.profitPct) }}</span>
                    </div>
                  </td>
                  <td class="num">
                    <button
                      class="mini-btn"
                      type="button"
                      :disabled="position.availableQuantity < 100"
                      :title="sellDisabledTip(position)"
                      @click="openSell(position)"
                    >
                      卖出
                    </button>
                  </td>
                </tr>
              </tbody>
            </table>

            <div v-else-if="loading" class="table-state">加载中…</div>

            <div v-else-if="error" class="table-state table-state--error">{{ error }}</div>

            <div v-else class="empty-state">
              <svg class="empty-state__icon" viewBox="0 0 24 24" aria-hidden="true">
                <rect x="3" y="7" width="18" height="13" rx="2" />
                <path d="M3 11h18M8 7V5.5A1.5 1.5 0 0 1 9.5 4h5A1.5 1.5 0 0 1 16 5.5V7" />
              </svg>
              <p class="empty-title">暂无持仓</p>
              <p class="empty-desc">点右上角「手动买卖」买入第一只股票，或等待策略信号触发。</p>
            </div>
          </div>
        </section>

        <!-- 委托 -->
        <section class="panel" aria-label="委托">
          <div class="panel__head panel__head--wrap">
            <h2 class="panel__title">
              委托
              <span class="panel__count">{{ ordersTotal }}</span>
            </h2>
            <div class="seg" role="group" aria-label="委托状态筛选">
              <button
                v-for="filter in ORDER_FILTERS"
                :key="filter.value || 'all'"
                type="button"
                class="seg__btn"
                :class="{ 'is-active': orderStatus === filter.value }"
                :aria-pressed="orderStatus === filter.value"
                @click="selectOrderStatus(filter.value)"
              >
                {{ filter.label }}
              </button>
            </div>
          </div>

          <div class="table-wrap">
            <table v-if="orders.length" class="table table--orders">
              <thead>
                <tr>
                  <th scope="col">委托时间</th>
                  <th scope="col">股票</th>
                  <th scope="col">方向</th>
                  <th scope="col">类型</th>
                  <th scope="col" class="num">委托价</th>
                  <th scope="col" class="num">数量</th>
                  <th scope="col" class="num">已成交</th>
                  <th scope="col" class="num">成交均价</th>
                  <th scope="col">状态</th>
                  <th scope="col" class="num">操作</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="order in orders" :key="order.id">
                  <td class="cell-time">{{ shortTime(order.createTime) }}</td>
                  <td>
                    <div class="cell-name">
                      <span class="cell-name__title">{{ order.name }}</span>
                      <span class="cell-name__code">{{ order.code }}</span>
                    </div>
                  </td>
                  <td>
                    <span :class="order.side === 'BUY' ? 'tone-up' : 'tone-down'">{{ sideLabel(order.side) }}</span>
                  </td>
                  <td>{{ typeLabel(order.type) }}</td>
                  <td class="num">{{ order.price != null ? priceText(order.price) : '市价' }}</td>
                  <td class="num">{{ integer(order.quantity) }}</td>
                  <td class="num">{{ integer(order.filledQuantity) }}</td>
                  <td class="num">{{ order.avgPrice != null ? priceText(order.avgPrice) : '--' }}</td>
                  <td>
                    <span class="status" :class="`status--${order.status.toLowerCase()}`">
                      {{ orderStatusLabel(order.status) }}
                    </span>
                    <span v-if="order.rejectReason" class="reject" :title="order.rejectReason">
                      {{ order.rejectReason }}
                    </span>
                  </td>
                  <td class="num">
                    <template v-if="order.status === 'PENDING'">
                      <template v-if="confirmCancelId === order.id">
                        <button class="mini-btn mini-btn--danger" type="button" @click="cancelOrder(order)">
                          确认撤单
                        </button>
                        <button class="link-btn" type="button" @click="confirmCancelId = null">返回</button>
                      </template>
                      <button
                        v-else
                        class="mini-btn"
                        type="button"
                        :disabled="cancelingId === order.id"
                        @click="confirmCancelId = order.id"
                      >
                        {{ cancelingId === order.id ? '撤单中' : '撤单' }}
                      </button>
                    </template>
                    <span v-else class="cell-sub">--</span>
                  </td>
                </tr>
              </tbody>
            </table>

            <div v-else-if="ordersLoading" class="table-state">加载中…</div>

            <div v-else-if="error" class="table-state table-state--error">{{ error }}</div>

            <div v-else class="empty-state">
              <svg class="empty-state__icon" viewBox="0 0 24 24" aria-hidden="true">
                <path d="M6 3h9l4 4v14H6z" />
                <path d="M14 3v5h5M9 12h6M9 16h4" />
              </svg>
              <p class="empty-title">{{ orderStatus ? '没有该状态的委托' : '暂无委托记录' }}</p>
              <p class="empty-desc">
                {{ orderStatus ? '换个状态标签看看，或点右上角「手动买卖」下一笔委托。' : '手动买卖与策略信号产生的委托都会出现在这里。' }}
              </p>
            </div>
          </div>

          <div v-if="ordersTotal > 0" class="pager pager--inline">
            <button class="pager__btn" type="button" :disabled="orderPage <= 1" @click="goOrderPage(orderPage - 1)">
              上一页
            </button>
            <span class="pager__status">
              第 <strong>{{ orderPage }}</strong> / {{ orderPageCount }} 页 · 共 {{ ordersTotal }} 笔
            </span>
            <button
              class="pager__btn"
              type="button"
              :disabled="orderPage >= orderPageCount"
              @click="goOrderPage(orderPage + 1)"
            >
              下一页
            </button>
          </div>
        </section>

        <!-- 成交 -->
        <section class="panel" aria-label="成交">
          <div class="panel__head">
            <h2 class="panel__title">
              成交
              <span class="panel__count">{{ tradesTotal }}</span>
            </h2>
            <span class="panel__meta">含佣金、印花税、过户费</span>
          </div>

          <div class="table-wrap">
            <table v-if="trades.length" class="table table--trades">
              <thead>
                <tr>
                  <th scope="col">成交时间</th>
                  <th scope="col">股票</th>
                  <th scope="col">方向</th>
                  <th scope="col" class="num">成交价</th>
                  <th scope="col" class="num">数量</th>
                  <th scope="col" class="num">成交额</th>
                  <th scope="col" class="num">费用</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="trade in trades" :key="trade.id">
                  <td class="cell-time">{{ tradeDateTime(trade) }}</td>
                  <td>
                    <div class="cell-name">
                      <span class="cell-name__title">{{ trade.name }}</span>
                      <span class="cell-name__code">{{ trade.code }}</span>
                    </div>
                  </td>
                  <td>
                    <span :class="trade.side === 'BUY' ? 'tone-up' : 'tone-down'">{{ sideLabel(trade.side) }}</span>
                  </td>
                  <td class="num">{{ priceText(trade.price) }}</td>
                  <td class="num">{{ integer(trade.quantity) }}</td>
                  <td class="num">{{ money(trade.amount) }}</td>
                  <td class="num">
                    {{ money(trade.totalFee) }}
                    <span class="fee-detail" :title="feeDetail(trade)">
                      <span class="sr-only">费用明细</span>
                      ⓘ
                    </span>
                  </td>
                </tr>
              </tbody>
            </table>

            <div v-else-if="tradesLoading" class="table-state">加载中…</div>

            <div v-else-if="error" class="table-state table-state--error">{{ error }}</div>

            <div v-else class="empty-state">
              <svg class="empty-state__icon" viewBox="0 0 24 24" aria-hidden="true">
                <rect x="4" y="2.5" width="16" height="19" rx="2" />
                <path d="M8 7h8M8 11h8M8 15h5" />
              </svg>
              <p class="empty-title">暂无成交记录</p>
              <p class="empty-desc">市价单提交即成交；限价单要等价格碰到委托价才会成交。</p>
            </div>
          </div>

          <div v-if="tradesTotal > 0" class="pager pager--inline">
            <button class="pager__btn" type="button" :disabled="tradePage <= 1" @click="goTradePage(tradePage - 1)">
              上一页
            </button>
            <span class="pager__status">
              第 <strong>{{ tradePage }}</strong> / {{ tradePageCount }} 页 · 共 {{ tradesTotal }} 笔
            </span>
            <button
              class="pager__btn"
              type="button"
              :disabled="tradePage >= tradePageCount"
              @click="goTradePage(tradePage + 1)"
            >
              下一页
            </button>
          </div>
        </section>

        <!-- 信号历史 -->
        <section class="panel" aria-label="信号历史">
          <div class="panel__head">
            <h2 class="panel__title">
              信号历史
              <span class="panel__count">{{ signals.length }}</span>
            </h2>
            <span class="panel__meta">候选与自动买卖信号 · 最近 {{ SIGNALS_LIMIT }} 条</span>
          </div>

          <div class="table-wrap">
            <table v-if="signals.length" class="table">
              <thead>
                <tr>
                  <th scope="col">时间</th>
                  <th scope="col">股票</th>
                  <th scope="col">信号</th>
                  <th scope="col" class="num">分数</th>
                  <th scope="col" class="num">价格</th>
                  <th scope="col">理由</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="item in signals" :key="item.id">
                  <td class="cell-time">{{ shortTime(item.createTime) }}</td>
                  <td>
                    <div class="cell-name">
                      <span class="cell-name__title">{{ item.name }}</span>
                      <span class="cell-name__code">{{ item.code }}</span>
                    </div>
                  </td>
                  <td>
                    <span class="signal-badge" :class="`signal-badge--${item.action.toLowerCase()}`">
                      {{ signalActionLabel(item.action) }}
                    </span>
                  </td>
                  <td class="num">{{ item.score }}</td>
                  <td class="num">{{ priceText(item.price) }}</td>
                  <td class="cell-reason">{{ item.reason || '--' }}</td>
                </tr>
              </tbody>
            </table>

            <div v-else-if="signalsLoading" class="table-state">加载中…</div>

            <div v-else-if="signalsError" class="table-state table-state--error">
              {{ signalsError }}
            </div>

            <div v-else class="empty-state">
              <svg class="empty-state__icon" viewBox="0 0 24 24" aria-hidden="true">
                <path d="M3 17l5-6 4 3 5-7 4 4" />
                <path d="M3 21h18" />
              </svg>
              <p class="empty-title">暂无信号记录</p>
              <p class="empty-desc">选股或自动交易产生信号后，会在这里留下可复盘的理由。</p>
            </div>
          </div>
        </section>
      </div>
    </div>

    <!-- 新建模拟盘 -->
    <div v-if="createOpen" class="stock-modal" @click.self="closeCreateAccount">
      <div
        class="stock-modal__box account-modal__box"
        role="dialog"
        aria-modal="true"
        aria-labelledby="account-create-title"
      >
        <div class="stock-modal__head">
          <h2 id="account-create-title" class="stock-modal__title">新建模拟盘</h2>
          <button
            class="stock-modal__close"
            type="button"
            aria-label="关闭"
            @click="closeCreateAccount"
          >
            ×
          </button>
        </div>
        <form class="account-form" @submit.prevent="submitCreateAccount">
          <label class="field">
            <span class="field__label">账户名称</span>
            <input
              v-model="createName"
              class="field__input"
              type="text"
              maxlength="20"
              placeholder="例如：模拟盘 2"
              :disabled="createBusy"
            />
            <span class="field__hint">最多 20 个字，创建后可随时改名</span>
          </label>
          <label class="field">
            <span class="field__label">初始资金</span>
            <span class="field__control">
              <input
                v-model.number="createCash"
                class="field__input"
                type="number"
                min="1000"
                max="100000000"
                step="1000"
                :disabled="createBusy"
              />
              <span class="field__unit">元</span>
            </span>
            <span class="field__hint">默认 30,000 元；创建后想恢复只能点「重置账户」</span>
          </label>
          <p v-if="createError" class="account-form__error">{{ createError }}</p>
          <div class="account-form__actions">
            <button class="btn" type="submit" :disabled="createBusy">
              {{ createBusy ? '创建中…' : '创建并切换' }}
            </button>
            <button
              class="btn btn--ghost"
              type="button"
              :disabled="createBusy"
              @click="closeCreateAccount"
            >
              取消
            </button>
          </div>
        </form>
      </div>
    </div>

    <!-- 账户改名 -->
    <div v-if="renameOpen" class="stock-modal" @click.self="closeRenameAccount">
      <div
        class="stock-modal__box account-modal__box"
        role="dialog"
        aria-modal="true"
        aria-labelledby="account-rename-title"
      >
        <div class="stock-modal__head">
          <h2 id="account-rename-title" class="stock-modal__title">给模拟盘改名</h2>
          <button
            class="stock-modal__close"
            type="button"
            aria-label="关闭"
            @click="closeRenameAccount"
          >
            ×
          </button>
        </div>
        <form class="account-form" @submit.prevent="submitRenameAccount">
          <label class="field">
            <span class="field__label">账户名称</span>
            <input
              v-model="renameName"
              class="field__input"
              type="text"
              maxlength="20"
              :disabled="renameBusy"
            />
            <span class="field__hint">只改显示名，资金与持仓不受影响</span>
          </label>
          <p v-if="renameError" class="account-form__error">{{ renameError }}</p>
          <div class="account-form__actions">
            <button class="btn" type="submit" :disabled="renameBusy">
              {{ renameBusy ? '保存中…' : '保存名称' }}
            </button>
            <button
              class="btn btn--ghost"
              type="button"
              :disabled="renameBusy"
              @click="closeRenameAccount"
            >
              取消
            </button>
          </div>
        </form>
      </div>
    </div>

    <!-- 删除模拟盘（二次确认） -->
    <div v-if="deleteOpen" class="stock-modal" @click.self="closeDeleteAccount">
      <div
        class="stock-modal__box account-modal__box"
        role="dialog"
        aria-modal="true"
        aria-labelledby="account-delete-title"
      >
        <div class="stock-modal__head">
          <h2 id="account-delete-title" class="stock-modal__title">删除模拟盘</h2>
          <button
            class="stock-modal__close"
            type="button"
            aria-label="关闭"
            @click="closeDeleteAccount"
          >
            ×
          </button>
        </div>
        <div class="account-form">
          <p class="account-form__warn">
            确定删除「{{ currentAccount?.name }}」？该账户的资金、持仓、委托、成交与信号记录都会一并清空，且无法恢复。
          </p>
          <div class="account-form__actions">
            <button
              class="btn account-form__danger"
              type="button"
              :disabled="deleteBusy"
              @click="submitDeleteAccount"
            >
              {{ deleteBusy ? '删除中…' : '确认删除' }}
            </button>
            <button
              class="btn btn--ghost"
              type="button"
              :disabled="deleteBusy"
              @click="closeDeleteAccount"
            >
              取消
            </button>
          </div>
        </div>
      </div>
    </div>

    <!-- 手动买卖弹窗 -->
    <TradeDialog
      :show="tradeOpen"
      :preset="tradePreset"
      :cash="account?.cash"
      :positions="positions"
      :account-id="currentAccountId"
      @close="tradeOpen = false"
      @submitted="onTradeSubmitted"
    />

    <!-- 轻提示 -->
    <Transition name="toast">
      <p v-if="notice" class="toast" :class="`is-${notice.tone}`" role="status">
        {{ notice.text }}
      </p>
    </Transition>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import NavChart from '@/components/paper/NavChart.vue'
import TradeDialog from '@/components/paper/TradeDialog.vue'
import {
  cancelPaperOrder,
  createPaperAccount,
  deletePaperAccount,
  fetchPaperAccount,
  fetchPaperAccounts,
  fetchPaperEngineStatus,
  fetchPaperNav,
  fetchPaperOrders,
  fetchPaperPositions,
  fetchPaperSelections,
  fetchPaperSignals,
  fetchPaperStrategies,
  fetchPaperTrades,
  refreshPaperSelections,
  renamePaperAccount,
  resetPaperAccount,
  setPaperAutoTrade,
  updatePaperStrategy
} from '@/api/paper'
import type {
  PaperAccount,
  PaperAccountSummary,
  PaperEngineStatus,
  PaperNavPoint,
  PaperOrder,
  PaperOrderStatus,
  PaperPosition,
  PaperSide,
  PaperSignal,
  PaperStrategy,
  PaperStrategyParams,
  PaperTrade
} from '@/api/paper'
import { fmtNum, toneClass } from '@/utils/stockFormat'
import { getMarketState, marketStateBadge, refreshIntervalMs } from '@/utils/marketTime'

const ORDERS_PAGE_SIZE = 20
const TRADES_PAGE_SIZE = 20
const NAV_DAYS = [30, 90, 180]
/** 信号历史最多展示条数（接口上限 200） */
const SIGNALS_LIMIT = 50
/** 引擎/选股/信号属于「慢数据」，最短 60s 刷新一次，避免频繁拉选股与信号 */
const SECONDARY_MIN_INTERVAL_MS = 60_000
/** 每个用户最多可开的模拟盘数量（后端同样限制 5 个） */
const MAX_ACCOUNTS = 5

/** 后端策略参数的完整默认值，与 PaperStrategyParams 一一对应 */
const DEFAULT_STRATEGY_PARAMS: PaperStrategyParams = {
  minScore: 60,
  stopLossPct: 8,
  takeProfitPct: 15,
  maxHoldDays: 10,
  universeSize: 20,
  maxCandidates: 5,
  selectionRefreshSec: 300,
  maTrendScore: 25,
  ma20Score: 10,
  macdScore: 20,
  volumeRatioScore: 15,
  moneyFlowScore: 20,
  moneyFlowPctScore: 5,
  rsiScore: 10,
  changeScore: 5,
  hotRankScore: 5,
  volumeRatioMin: 1.2,
  moneyFlowPctMin: 5,
  rsiMin: 40,
  rsiMax: 70,
  changeMinPct: 0,
  changeMaxPct: 9
}

interface NumberRule {
  min: number
  max: number
  /** 是否只接受整数（先取整再钳制） */
  integer: boolean
}

/** 各字段的合法区间：保存前把非法/越界输入夹回区间内 */
const STRATEGY_RULES: Record<keyof PaperStrategyParams, NumberRule> = {
  minScore: { min: 0, max: 100, integer: true },
  stopLossPct: { min: 0.5, max: 50, integer: false },
  takeProfitPct: { min: 0.5, max: 200, integer: false },
  maxHoldDays: { min: 1, max: 120, integer: true },
  universeSize: { min: 5, max: 50, integer: true },
  maxCandidates: { min: 1, max: 20, integer: true },
  selectionRefreshSec: { min: 30, max: 3600, integer: true },
  maTrendScore: { min: 0, max: 100, integer: true },
  ma20Score: { min: 0, max: 100, integer: true },
  macdScore: { min: 0, max: 100, integer: true },
  volumeRatioScore: { min: 0, max: 100, integer: true },
  moneyFlowScore: { min: 0, max: 100, integer: true },
  moneyFlowPctScore: { min: 0, max: 100, integer: true },
  rsiScore: { min: 0, max: 100, integer: true },
  changeScore: { min: 0, max: 100, integer: true },
  hotRankScore: { min: 0, max: 100, integer: true },
  volumeRatioMin: { min: 0.1, max: 10, integer: false },
  moneyFlowPctMin: { min: 0, max: 50, integer: false },
  rsiMin: { min: 0, max: 100, integer: true },
  rsiMax: { min: 0, max: 100, integer: true },
  changeMinPct: { min: -10, max: 20, integer: false },
  changeMaxPct: { min: -10, max: 20, integer: false }
}

function ruleOf(key: keyof PaperStrategyParams): NumberRule {
  return STRATEGY_RULES[key]
}

interface AdvancedField {
  key: keyof PaperStrategyParams
  label: string
  step: number
  unit?: string
  hint?: string
}

interface AdvancedGroup {
  title: string
  fields: AdvancedField[]
}

/** 「高级规则」表单分组：除最低分/止损/止盈/持有天数外的全部选股参数 */
const ADVANCED_GROUPS: AdvancedGroup[] = [
  {
    title: '选股范围与节奏',
    fields: [
      { key: 'universeSize', label: '候选池大小', step: 1, unit: '只', hint: '从热榜取前 N 只' },
      { key: 'maxCandidates', label: '最多候选', step: 1, unit: '只', hint: '每轮最多留几只' },
      { key: 'selectionRefreshSec', label: '选股刷新间隔', step: 30, unit: '秒', hint: '自动选股的间隔' }
    ]
  },
  {
    title: '因子权重',
    fields: [
      { key: 'maTrendScore', label: '均线趋势', step: 1, unit: '分' },
      { key: 'ma20Score', label: 'MA20 上方', step: 1, unit: '分' },
      { key: 'macdScore', label: 'MACD 金叉', step: 1, unit: '分' },
      { key: 'volumeRatioScore', label: '量比放大', step: 1, unit: '分' },
      { key: 'moneyFlowScore', label: '资金流入', step: 1, unit: '分' },
      { key: 'moneyFlowPctScore', label: '资金流占比', step: 1, unit: '分' },
      { key: 'rsiScore', label: 'RSI 区间', step: 1, unit: '分' },
      { key: 'changeScore', label: '当日涨幅', step: 1, unit: '分' },
      { key: 'hotRankScore', label: '热榜排名', step: 1, unit: '分' }
    ]
  },
  {
    title: '入选阈值',
    fields: [
      { key: 'volumeRatioMin', label: '量比下限', step: 0.1, hint: '低于该量比不入选' },
      { key: 'moneyFlowPctMin', label: '资金流占比下限', step: 0.5, unit: '%' },
      { key: 'rsiMin', label: 'RSI 下限', step: 1 },
      { key: 'rsiMax', label: 'RSI 上限', step: 1 },
      { key: 'changeMinPct', label: '涨幅下限', step: 0.5, unit: '%' },
      { key: 'changeMaxPct', label: '涨幅上限', step: 0.5, unit: '%' }
    ]
  }
]

const ADVANCED_FIELDS: AdvancedField[] = ADVANCED_GROUPS.reduce<AdvancedField[]>(
  (all, group) => all.concat(group.fields),
  []
)

/** 参与「权重合计」提示的因子权重字段 */
const WEIGHT_KEYS: (keyof PaperStrategyParams)[] = [
  'maTrendScore',
  'ma20Score',
  'macdScore',
  'volumeRatioScore',
  'moneyFlowScore',
  'moneyFlowPctScore',
  'rsiScore',
  'changeScore',
  'hotRankScore'
]

const ORDER_FILTERS: { value: PaperOrderStatus | ''; label: string }[] = [
  { value: '', label: '全部' },
  { value: 'PENDING', label: '待成交' },
  { value: 'FILLED', label: '已成交' },
  { value: 'CANCELLED', label: '已撤单' },
  { value: 'REJECTED', label: '已拒单' }
]

const ORDER_STATUS_TEXT: Record<PaperOrderStatus, string> = {
  PENDING: '待成交',
  FILLED: '已成交',
  CANCELLED: '已撤单',
  REJECTED: '已拒单'
}

// —— 页面数据 ——
const loading = ref(true)
const refreshing = ref(false)
const error = ref('')

// —— P3：多账户 ——
const accounts = ref<PaperAccountSummary[]>([])
const accountsLoading = ref(false)
const accountsError = ref('')
const currentAccountId = ref<number | null>(null)
const accountSwitching = ref(false)
/** 切换代数：切账户后忽略上一账户在途请求的返回，避免串数据 */
let accountEpoch = 0

const createOpen = ref(false)
const createName = ref('')
const createCash = ref(30000)
const createBusy = ref(false)
const createError = ref('')

const renameOpen = ref(false)
const renameName = ref('')
const renameBusy = ref(false)
const renameError = ref('')

const deleteOpen = ref(false)
const deleteBusy = ref(false)

const account = ref<PaperAccount | null>(null)
const positions = ref<PaperPosition[]>([])
const nav = ref<PaperNavPoint[]>([])
const navDays = ref(90)
const navLoading = ref(false)

const orders = ref<PaperOrder[]>([])
const ordersTotal = ref(0)
const ordersLoading = ref(false)
const orderStatus = ref<PaperOrderStatus | ''>('')
const orderPage = ref(1)
const cancelingId = ref<number | null>(null)
const confirmCancelId = ref<number | null>(null)

const trades = ref<PaperTrade[]>([])
const tradesTotal = ref(0)
const tradesLoading = ref(false)
const tradePage = ref(1)

// —— 交互状态 ——
interface TradePreset {
  code: string
  name: string
  side: PaperSide
  price: number
  /** 卖出时带上可卖数量；从选股直接买入时不需要 */
  availableQuantity?: number
  currency: string
}

const tradeOpen = ref(false)
const tradePreset = ref<TradePreset | null>(null)
const resetArmed = ref(false)
const resetting = ref(false)
const notice = ref<{ text: string; tone: 'ok' | 'error' } | null>(null)

// —— 刷新节奏 ——
const marketState = ref(getMarketState())
const stateBadge = computed(() => marketStateBadge(marketState.value))
const refreshSec = computed(() => Math.round(refreshIntervalMs(marketState.value) / 1000))

let refreshTimer: ReturnType<typeof setTimeout> | undefined
let noticeTimer: ReturnType<typeof setTimeout> | undefined
let resetArmTimer: ReturnType<typeof setTimeout> | undefined
let secondaryTimer: ReturnType<typeof setTimeout> | undefined
let disposed = false

// —— P2：引擎 / 策略 / 选股 / 信号 ——
const engine = ref<PaperEngineStatus | null>(null)
const engineLoading = ref(false)
const engineError = ref('')

const strategies = ref<PaperStrategy[]>([])
const strategyForm = ref<PaperStrategyParams>({ ...DEFAULT_STRATEGY_PARAMS })
const strategyBusy = ref(false)
const strategyError = ref('')
const autoTradeConfirm = ref(false)
const autoTradeBusy = ref(false)
const advancedOpen = ref(false)
let strategyLoaded = false

const selections = ref<PaperSignal[]>([])
const selectionsLoading = ref(false)
const selectionsRefreshing = ref(false)
const selectionsError = ref('')

const signals = ref<PaperSignal[]>([])
const signalsLoading = ref(false)
const signalsError = ref('')

// —— 派生数据 ——
const positionMarketValue = computed(() =>
  positions.value.reduce((sum, item) => sum + (item.marketValue ?? 0), 0)
)
const orderPageCount = computed(() => Math.max(1, Math.ceil(ordersTotal.value / ORDERS_PAGE_SIZE)))
const tradePageCount = computed(() => Math.max(1, Math.ceil(tradesTotal.value / TRADES_PAGE_SIZE)))
/** 当前账户只有一条策略（趋势选股） */
const strategy = computed(() => strategies.value[0] ?? null)
const currentAccount = computed(
  () => accounts.value.find((item) => item.accountId === currentAccountId.value) ?? null
)
const canCreateAccount = computed(() => accounts.value.length < MAX_ACCOUNTS)
const canDeleteAccount = computed(() => accounts.value.length > 1)
const weightSum = computed(() =>
  WEIGHT_KEYS.reduce((sum, key) => sum + (Number(strategyForm.value[key]) || 0), 0)
)

// —— 格式化（金额/百分比/时间） ——
function money(value: number | null | undefined, symbol = '¥'): string {
  if (value == null || Number.isNaN(value)) return '--'
  return `${symbol}${fmtNum(value)}`
}

function signedMoney(value: number | null | undefined, symbol = '¥'): string {
  if (value == null || Number.isNaN(value)) return '--'
  return `${value >= 0 ? '+' : '-'}${symbol}${fmtNum(Math.abs(value))}`
}

function pct(value: number | null | undefined): string {
  if (value == null || Number.isNaN(value)) return '--'
  return `${value > 0 ? '+' : ''}${value.toFixed(2)}%`
}

function priceText(value: number | null | undefined): string {
  if (value == null || Number.isNaN(value)) return '--'
  return value.toFixed(2)
}

function integer(value: number | null | undefined): string {
  if (value == null || Number.isNaN(value)) return '--'
  return fmtNum(value, 0)
}

/** 兼容 `yyyy-MM-dd HH:mm:ss` 与 ISO `yyyy-MM-ddTHH:mm:ss` */
function normalizeTime(value: string | null | undefined): string {
  return value ? value.replace('T', ' ') : ''
}

function shortTime(value: string | null | undefined): string {
  const text = normalizeTime(value)
  return text.length >= 16 ? text.slice(5, 16) : text || '--'
}

function tradeDateTime(trade: PaperTrade): string {
  const date = normalizeTime(trade.tradeTime) || normalizeTime(trade.tradeDate)
  if (!date) return '--'
  return date.length >= 16 ? `${date.slice(5, 10)} ${date.slice(11, 16)}` : date
}

function sideLabel(side: PaperSide): string {
  return side === 'BUY' ? '买入' : '卖出'
}

function typeLabel(type: PaperOrder['type']): string {
  return type === 'MARKET' ? '市价' : '限价'
}

const SIGNAL_ACTION_TEXT: Record<PaperSignal['action'], string> = {
  CANDIDATE: '候选',
  BUY: '买入',
  SELL: '卖出'
}

function signalActionLabel(action: PaperSignal['action']): string {
  return SIGNAL_ACTION_TEXT[action] ?? action
}

function orderStatusLabel(status: PaperOrderStatus): string {
  return ORDER_STATUS_TEXT[status] ?? status
}

function feeDetail(trade: PaperTrade): string {
  return `佣金 ${money(trade.commission)} · 印花税 ${money(trade.stampTax)} · 过户费 ${money(trade.transferFee)}`
}

// —— 数据加载 ——
function captureError(err: unknown, silent: boolean) {
  if (silent) return
  if (!error.value) {
    error.value = err instanceof Error ? err.message : '接口请求失败，请稍后重试'
  }
}

/** 当前账户 id；不传时后端按主账户处理 */
function activeAccountId(): number | undefined {
  return currentAccountId.value ?? undefined
}

async function loadAccounts(): Promise<void> {
  accountsLoading.value = true
  try {
    const list = await fetchPaperAccounts()
    accounts.value = list ?? []
    accountsError.value = ''
    // 当前账户被删、或首次加载时，回落到主账户 / 第一个账户
    if (accounts.value.length) {
      const exists = accounts.value.some((item) => item.accountId === currentAccountId.value)
      if (!exists) {
        const preferred = accounts.value.find((item) => item.primary) ?? accounts.value[0]
        currentAccountId.value = preferred.accountId
      }
    } else {
      currentAccountId.value = null
    }
  } catch (err) {
    accountsError.value = errText(err)
  } finally {
    accountsLoading.value = false
  }
}

async function loadAccount(silent = false) {
  const epoch = accountEpoch
  try {
    const data = await fetchPaperAccount(activeAccountId())
    if (epoch !== accountEpoch) return
    account.value = data
  } catch (err) {
    if (epoch === accountEpoch) captureError(err, silent)
  }
}

async function loadPositions(silent = false) {
  const epoch = accountEpoch
  try {
    const data = (await fetchPaperPositions(activeAccountId())) ?? []
    if (epoch !== accountEpoch) return
    positions.value = data
  } catch (err) {
    if (epoch === accountEpoch) captureError(err, silent)
  }
}

async function loadNav(silent = false) {
  const epoch = accountEpoch
  navLoading.value = true
  try {
    const data = (await fetchPaperNav(navDays.value, activeAccountId())) ?? []
    if (epoch !== accountEpoch) return
    nav.value = data
  } catch (err) {
    if (epoch === accountEpoch) captureError(err, silent)
  } finally {
    if (epoch === accountEpoch) navLoading.value = false
  }
}

async function loadOrders(silent = false) {
  const epoch = accountEpoch
  ordersLoading.value = true
  try {
    const page = await fetchPaperOrders({
      status: orderStatus.value || undefined,
      page: orderPage.value,
      size: ORDERS_PAGE_SIZE,
      accountId: activeAccountId()
    })
    if (epoch !== accountEpoch) return
    orders.value = page.records ?? []
    ordersTotal.value = page.total ?? 0
  } catch (err) {
    if (epoch === accountEpoch) captureError(err, silent)
  } finally {
    if (epoch === accountEpoch) ordersLoading.value = false
  }
}

async function loadTrades(silent = false) {
  const epoch = accountEpoch
  tradesLoading.value = true
  try {
    const page = await fetchPaperTrades({
      page: tradePage.value,
      size: TRADES_PAGE_SIZE,
      accountId: activeAccountId()
    })
    if (epoch !== accountEpoch) return
    trades.value = page.records ?? []
    tradesTotal.value = page.total ?? 0
  } catch (err) {
    if (epoch === accountEpoch) captureError(err, silent)
  } finally {
    if (epoch === accountEpoch) tradesLoading.value = false
  }
}

async function refreshAll(silent = false, force = false) {
  if (refreshing.value && !force) return
  refreshing.value = true
  const epoch = accountEpoch
  if (!silent) error.value = ''
  try {
    await Promise.all([
      loadAccounts(),
      loadAccount(silent),
      loadPositions(silent),
      loadNav(silent),
      loadOrders(silent),
      loadTrades(silent)
    ])
  } finally {
    refreshing.value = false
    if (epoch !== accountEpoch) return
    marketState.value = getMarketState()
    scheduleRefresh()
    // 交易时段切换时补上慢数据轮询（已在跑就不打断它的节奏）
    if (!secondaryTimer) scheduleSecondary()
  }
}

function scheduleRefresh() {
  if (refreshTimer) {
    clearTimeout(refreshTimer)
    refreshTimer = undefined
  }
  if (disposed || document.visibilityState !== 'visible') return
  refreshTimer = setTimeout(() => {
    void refreshAll(true)
  }, refreshIntervalMs(marketState.value))
}

function onVisibilityChange() {
  if (document.visibilityState === 'visible') {
    void refreshAll(true)
    void loadSecondary()
  } else {
    if (refreshTimer) {
      clearTimeout(refreshTimer)
      refreshTimer = undefined
    }
    if (secondaryTimer) {
      clearTimeout(secondaryTimer)
      secondaryTimer = undefined
    }
  }
}

async function manualRefresh() {
  if (refreshing.value) return
  await refreshAll(false)
  void loadSecondary()
  if (!error.value) flash('已刷新')
}

// —— P2：引擎与策略 ——
function errText(err: unknown): string {
  return err instanceof Error ? err.message : '接口请求失败，请稍后重试'
}

/** 后端 params 是 JSON 文本；解析失败或字段缺失时回退默认值 */
function normalizeStrategyParams(text: string | null | undefined): PaperStrategyParams {
  const result: PaperStrategyParams = { ...DEFAULT_STRATEGY_PARAMS }
  if (!text) return result
  try {
    const node = JSON.parse(text) as Record<string, unknown>
    ;(Object.keys(DEFAULT_STRATEGY_PARAMS) as (keyof PaperStrategyParams)[]).forEach((key) => {
      const value = node[key]
      if (typeof value === 'number' && Number.isFinite(value)) result[key] = value
    })
  } catch {
    return { ...DEFAULT_STRATEGY_PARAMS }
  }
  return result
}

/** 首次拉到策略时填充表单；之后定时刷新不覆盖用户正在改的输入 */
function applyStrategyForm() {
  strategyForm.value = normalizeStrategyParams(strategy.value?.params)
}

function clampRuleValue(key: keyof PaperStrategyParams): number {
  const rule = STRATEGY_RULES[key]
  const fallback = DEFAULT_STRATEGY_PARAMS[key]
  let value = Number(strategyForm.value[key])
  if (!Number.isFinite(value)) value = fallback
  value = Math.min(rule.max, Math.max(rule.min, value))
  return rule.integer ? Math.round(value) : Math.round(value * 100) / 100
}

/** 保存前把全部字段钳制到合法区间，并修正区间类参数的上下限关系 */
function clampStrategyForm(): PaperStrategyParams {
  const result = { ...DEFAULT_STRATEGY_PARAMS }
  ;(Object.keys(DEFAULT_STRATEGY_PARAMS) as (keyof PaperStrategyParams)[]).forEach((key) => {
    result[key] = clampRuleValue(key)
  })
  if (result.rsiMax < result.rsiMin) result.rsiMax = result.rsiMin
  if (result.changeMaxPct < result.changeMinPct) result.changeMaxPct = result.changeMinPct
  return result
}

async function loadEngine() {
  const epoch = accountEpoch
  engineLoading.value = true
  try {
    const data = await fetchPaperEngineStatus(activeAccountId())
    if (epoch !== accountEpoch) return
    engine.value = data
    engineError.value = ''
  } catch (err) {
    if (epoch === accountEpoch) engineError.value = errText(err)
  } finally {
    if (epoch === accountEpoch) engineLoading.value = false
  }
}

async function loadStrategies() {
  const epoch = accountEpoch
  const hadData = strategies.value.length > 0
  try {
    const data = (await fetchPaperStrategies(activeAccountId())) ?? []
    if (epoch !== accountEpoch) return
    strategies.value = data
    // 首轮加载失败后的恢复：清掉加载错误；已拿到数据时不动，避免覆盖保存参数的报错
    if (!hadData) strategyError.value = ''
    if (!strategyLoaded) {
      strategyLoaded = true
      applyStrategyForm()
    }
  } catch (err) {
    if (epoch === accountEpoch) strategyError.value = errText(err)
  }
}

async function loadSelections() {
  const epoch = accountEpoch
  selectionsLoading.value = true
  try {
    const data = (await fetchPaperSelections(activeAccountId())) ?? []
    if (epoch !== accountEpoch) return
    selections.value = data
    selectionsError.value = ''
  } catch (err) {
    if (epoch === accountEpoch) selectionsError.value = errText(err)
  } finally {
    if (epoch === accountEpoch) selectionsLoading.value = false
  }
}

async function loadSignals() {
  const epoch = accountEpoch
  signalsLoading.value = true
  try {
    const data = (await fetchPaperSignals(SIGNALS_LIMIT, activeAccountId())) ?? []
    if (epoch !== accountEpoch) return
    signals.value = data
    signalsError.value = ''
  } catch (err) {
    if (epoch === accountEpoch) signalsError.value = errText(err)
  } finally {
    if (epoch === accountEpoch) signalsLoading.value = false
  }
}

async function loadSecondary() {
  await Promise.all([loadEngine(), loadStrategies(), loadSelections(), loadSignals()])
  scheduleSecondary()
}

function scheduleSecondary() {
  if (secondaryTimer) {
    clearTimeout(secondaryTimer)
    secondaryTimer = undefined
  }
  if (disposed || document.visibilityState !== 'visible') return
  // 选股/引擎属于慢数据：只在交易时段（含午间休市）定时轮询，
  // 盘前/盘后/休市日靠手动刷新与回到前台时的补刷，避免无意义请求。
  const state = marketState.value
  if (state !== 'open' && state !== 'break') return
  const interval = Math.max(SECONDARY_MIN_INTERVAL_MS, refreshIntervalMs(state))
  secondaryTimer = setTimeout(() => {
    void loadSecondary()
  }, interval)
}

function replaceStrategy(updated: PaperStrategy) {
  strategies.value = strategies.value.map((item) => (item.id === updated.id ? updated : item))
}

async function saveStrategy() {
  const target = strategy.value
  if (!target || strategyBusy.value) return
  // 非法/越界输入不报错，直接钳制到合法区间后再提交
  const params = clampStrategyForm()
  strategyForm.value = params
  strategyBusy.value = true
  strategyError.value = ''
  try {
    const updated = await updatePaperStrategy(
      target.id,
      { params: JSON.stringify(params) },
      activeAccountId()
    )
    replaceStrategy(updated)
    applyStrategyForm()
    flash('规则已保存')
    await Promise.all([loadEngine(), loadSignals()])
  } catch (err) {
    strategyError.value = errText(err)
    flash(strategyError.value, 'error')
  } finally {
    strategyBusy.value = false
  }
}

async function onStrategyToggle(event: Event) {
  const input = event.target as HTMLInputElement
  const next = input.checked
  input.checked = strategy.value?.enabled === 1
  const target = strategy.value
  if (!target || strategyBusy.value) return
  strategyBusy.value = true
  strategyError.value = ''
  try {
    const updated = await updatePaperStrategy(target.id, { enabled: next }, activeAccountId())
    replaceStrategy(updated)
    flash(next ? '策略已启用' : '策略已停用')
    await loadEngine()
  } catch (err) {
    strategyError.value = errText(err)
    flash(strategyError.value, 'error')
  } finally {
    strategyBusy.value = false
  }
}

/** 打开自动交易前先二次确认；关闭直接执行 */
function askAutoTrade() {
  if (!engine.value || autoTradeBusy.value) return
  if (engine.value.autoTrade) {
    void applyAutoTrade(false)
  } else {
    autoTradeConfirm.value = true
  }
}

async function applyAutoTrade(enabled: boolean) {
  if (autoTradeBusy.value) return
  autoTradeBusy.value = true
  try {
    const updated = await setPaperAutoTrade(enabled, activeAccountId())
    account.value = updated
    autoTradeConfirm.value = false
    flash(enabled ? '自动交易已开启' : '自动交易已关闭')
    await Promise.all([loadEngine(), loadAccounts()])
  } catch (err) {
    autoTradeConfirm.value = false
    flash(errText(err), 'error')
  } finally {
    autoTradeBusy.value = false
  }
}

async function refreshSelections() {
  if (selectionsRefreshing.value) return
  selectionsRefreshing.value = true
  selectionsError.value = ''
  try {
    selections.value = (await refreshPaperSelections(activeAccountId())) ?? []
    flash(
      selections.value.length
        ? `选股完成，共 ${selections.value.length} 只候选`
        : '选股完成，暂无符合条件的股票'
    )
    await Promise.all([loadEngine(), loadSignals()])
  } catch (err) {
    selectionsError.value = errText(err)
    flash(selectionsError.value, 'error')
  } finally {
    selectionsRefreshing.value = false
  }
}

/** 今日选股「下单」：打开买卖弹窗并带入股票 */
function buySelection(item: PaperSignal) {
  tradePreset.value = {
    code: item.code,
    name: item.name,
    side: 'BUY',
    price: item.price,
    currency: '¥'
  }
  tradeOpen.value = true
}

// —— P3：账户管理 ——
/** 切账户/增删账户后清空上一账户的数据，避免闪现旧账户内容 */
function resetAccountScopedState() {
  accountEpoch += 1
  account.value = null
  positions.value = []
  nav.value = []
  orders.value = []
  ordersTotal.value = 0
  trades.value = []
  tradesTotal.value = 0
  engine.value = null
  strategies.value = []
  selections.value = []
  signals.value = []
  error.value = ''
  engineError.value = ''
  strategyError.value = ''
  selectionsError.value = ''
  signalsError.value = ''
  orderStatus.value = ''
  orderPage.value = 1
  tradePage.value = 1
  confirmCancelId.value = null
  tradeOpen.value = false
  resetArmed.value = false
  autoTradeConfirm.value = false
  advancedOpen.value = false
  strategyLoaded = false
  strategyForm.value = { ...DEFAULT_STRATEGY_PARAMS }
}

async function switchAccount(accountId: number) {
  if (accountId === currentAccountId.value || accountSwitching.value) return
  accountSwitching.value = true
  try {
    currentAccountId.value = accountId
    resetAccountScopedState()
    await refreshAll(false, true)
    void loadSecondary()
    const target = accounts.value.find((item) => item.accountId === accountId)
    flash(`已切换到「${target?.name ?? '模拟盘'}」`)
  } finally {
    accountSwitching.value = false
  }
}

/** 名称自增，跳过已占用的「模拟盘 N」 */
function suggestAccountName(): string {
  for (let index = accounts.value.length + 1; index <= MAX_ACCOUNTS + 2; index += 1) {
    const name = `模拟盘 ${index}`
    if (!accounts.value.some((item) => item.name === name)) return name
  }
  return `模拟盘 ${accounts.value.length + 1}`
}

function openCreateAccount() {
  if (!canCreateAccount.value) return
  createName.value = suggestAccountName()
  createCash.value = 30000
  createError.value = ''
  createOpen.value = true
}

function closeCreateAccount() {
  if (createBusy.value) return
  createOpen.value = false
}

async function submitCreateAccount() {
  if (createBusy.value) return
  const name = createName.value.trim()
  const cash = Number(createCash.value)
  if (!name) {
    createError.value = '请填写账户名称'
    return
  }
  if (name.length > 20) {
    createError.value = '账户名称最多 20 个字'
    return
  }
  if (!Number.isFinite(cash) || cash < 1000 || cash > 100_000_000) {
    createError.value = '初始资金需在 1,000 – 100,000,000 元之间'
    return
  }
  createBusy.value = true
  createError.value = ''
  try {
    const created = await createPaperAccount({ name, initialCash: Math.round(cash) })
    createOpen.value = false
    currentAccountId.value = created.accountId
    resetAccountScopedState()
    await refreshAll(false, true)
    void loadSecondary()
    flash(`已新建「${created.name}」，初始资金 ${money(created.initialCash)}`)
  } catch (err) {
    createError.value = errText(err)
  } finally {
    createBusy.value = false
  }
}

function openRenameAccount() {
  const target = currentAccount.value
  if (!target) return
  renameName.value = target.name
  renameError.value = ''
  renameOpen.value = true
}

function closeRenameAccount() {
  if (renameBusy.value) return
  renameOpen.value = false
}

async function submitRenameAccount() {
  const target = currentAccount.value
  if (!target || renameBusy.value) return
  const name = renameName.value.trim()
  if (!name) {
    renameError.value = '请填写账户名称'
    return
  }
  if (name.length > 20) {
    renameError.value = '账户名称最多 20 个字'
    return
  }
  renameBusy.value = true
  renameError.value = ''
  try {
    const updated = await renamePaperAccount(target.accountId, name)
    renameOpen.value = false
    accounts.value = accounts.value.map((item) =>
      item.accountId === updated.accountId ? { ...item, name: updated.name } : item
    )
    if (account.value && account.value.accountId === updated.accountId) {
      account.value = { ...account.value, name: updated.name }
    }
    flash('账户已改名')
  } catch (err) {
    renameError.value = errText(err)
  } finally {
    renameBusy.value = false
  }
}

function openDeleteAccount() {
  if (!canDeleteAccount.value || !currentAccount.value) return
  deleteOpen.value = true
}

function closeDeleteAccount() {
  if (deleteBusy.value) return
  deleteOpen.value = false
}

async function submitDeleteAccount() {
  const target = currentAccount.value
  if (!target || deleteBusy.value) return
  deleteBusy.value = true
  try {
    await deletePaperAccount(target.accountId)
    deleteOpen.value = false
    flash(`已删除「${target.name}」`)
    // loadAccounts 会在当前账户消失后自动回落到主账户 / 第一个账户
    await loadAccounts()
    resetAccountScopedState()
    await refreshAll(false, true)
    void loadSecondary()
  } catch (err) {
    deleteOpen.value = false
    flash(errText(err), 'error')
  } finally {
    deleteBusy.value = false
  }
}

// —— 提示 ——
function flash(text: string, tone: 'ok' | 'error' = 'ok') {
  notice.value = { text, tone }
  if (noticeTimer) clearTimeout(noticeTimer)
  noticeTimer = setTimeout(() => {
    notice.value = null
  }, 4000)
}

// —— 分页 / 筛选 ——
function selectOrderStatus(value: PaperOrderStatus | '') {
  if (orderStatus.value === value) return
  orderStatus.value = value
  orderPage.value = 1
  confirmCancelId.value = null
  void loadOrders()
}

function goOrderPage(page: number) {
  if (page < 1 || page > orderPageCount.value || page === orderPage.value) return
  orderPage.value = page
  confirmCancelId.value = null
  void loadOrders()
}

function goTradePage(page: number) {
  if (page < 1 || page > tradePageCount.value || page === tradePage.value) return
  tradePage.value = page
  void loadTrades()
}

function setNavDays(days: number) {
  if (navDays.value === days) return
  navDays.value = days
  void loadNav()
}

// —— 买卖 ——
function openTrade() {
  tradePreset.value = null
  tradeOpen.value = true
}

function sellDisabledTip(position: PaperPosition) {
  if (position.availableQuantity <= 0) return 'T+1：今日买入的股份下一交易日才可卖'
  if (position.availableQuantity < 100) return '可卖股份不足 1 手（100 股），A 股委托需为 100 股的整数倍'
  return `卖出 ${position.name}`
}

function openSell(position: PaperPosition) {
  if (position.availableQuantity < 100) return
  tradePreset.value = {
    code: position.code,
    name: position.name,
    side: 'SELL',
    price: position.lastPrice,
    availableQuantity: position.availableQuantity,
    currency: position.currency
  }
  tradeOpen.value = true
}

function onTradeSubmitted(order: PaperOrder) {
  if (order.status === 'FILLED') {
    flash(`${order.name} ${sideLabel(order.side)}已成交`)
  } else if (order.status === 'PENDING') {
    flash(`${order.name} 委托已提交，等待撮合`)
  } else if (order.status === 'REJECTED') {
    flash(order.rejectReason || `${order.name} 委托被拒绝`, 'error')
  } else {
    flash('委托已提交')
  }
  void refreshAll(true)
}

// —— 撤单 ——
async function cancelOrder(order: PaperOrder) {
  if (cancelingId.value != null) return
  confirmCancelId.value = null
  cancelingId.value = order.id
  try {
    await cancelPaperOrder(order.id, activeAccountId())
    flash(`${order.name} 已撤单`)
    await refreshAll(true)
  } catch (err) {
    flash(err instanceof Error ? err.message : '撤单失败，请稍后重试', 'error')
  } finally {
    cancelingId.value = null
  }
}

// —— 重置账户（二次确认：点两下） ——
async function doReset() {
  if (resetting.value) return
  if (!resetArmed.value) {
    resetArmed.value = true
    if (resetArmTimer) clearTimeout(resetArmTimer)
    resetArmTimer = setTimeout(() => {
      resetArmed.value = false
    }, 6000)
    return
  }
  if (resetArmTimer) clearTimeout(resetArmTimer)
  resetArmed.value = false
  resetting.value = true
  tradeOpen.value = false
  try {
    await resetPaperAccount(activeAccountId())
    flash('账户已重置：资金恢复初始值，持仓 / 委托 / 成交已清空')
    orderPage.value = 1
    tradePage.value = 1
    orderStatus.value = ''
    await refreshAll(false)
    void loadSecondary()
  } catch (err) {
    flash(err instanceof Error ? err.message : '重置失败，请稍后重试', 'error')
  } finally {
    resetting.value = false
  }
}

onMounted(async () => {
  document.addEventListener('visibilitychange', onVisibilityChange)
  await loadAccounts()
  await refreshAll(false)
  loading.value = false
  void loadSecondary()
})

onBeforeUnmount(() => {
  disposed = true
  document.removeEventListener('visibilitychange', onVisibilityChange)
  if (refreshTimer) clearTimeout(refreshTimer)
  if (noticeTimer) clearTimeout(noticeTimer)
  if (resetArmTimer) clearTimeout(resetArmTimer)
  if (secondaryTimer) clearTimeout(secondaryTimer)
})
</script>

<style scoped>
.paper-page {
  padding-bottom: var(--s-8);
}

/* ---------- 页头 ---------- */
.page-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: var(--s-6);
  margin-bottom: var(--s-8);
}

.page-head__main {
  min-width: 0;
}

.page-title {
  font-size: var(--t-3xl);
  letter-spacing: -0.03em;
}

.page-desc {
  margin-top: 8px;
  max-width: 52ch;
  color: var(--c-text-muted);
  font-size: var(--t-sm);
}

.page-head__side {
  display: flex;
  align-items: center;
  gap: var(--s-2);
  flex-shrink: 0;
  flex-wrap: wrap;
  justify-content: flex-end;
}

.refresh-state {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 4px 10px;
  border-radius: var(--r-full);
  background: var(--c-surface-sunken);
  color: var(--c-text-muted);
  font-size: var(--t-xs);
  font-variant-numeric: tabular-nums;
}

.refresh-state .dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--c-text-faint);
}

.refresh-state.is-live {
  background: color-mix(in srgb, var(--c-success) 12%, transparent);
  color: var(--c-success);
}

.refresh-state.is-live .dot {
  background: var(--c-success);
  animation: pulse 2s var(--ease) infinite;
}

@keyframes pulse {
  50% {
    opacity: 0.35;
  }
}

.head-btn {
  min-height: 38px;
  padding: 0 var(--s-4);
  font-size: var(--t-sm);
}

.head-btn__icon {
  width: 15px;
  height: 15px;
  fill: none;
  stroke: currentColor;
  stroke-width: 2;
  stroke-linecap: round;
  stroke-linejoin: round;
}

.head-btn__icon.spin {
  animation: spin 0.9s linear infinite;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

.reset-btn.is-armed {
  border-color: var(--stock-up);
  color: var(--stock-up);
}

/* ---------- 布局 ---------- */
.stack {
  display: flex;
  flex-direction: column;
  gap: var(--s-6);
}

/* ---------- 资产卡 ---------- */
.asset-card {
  display: grid;
  grid-template-columns: minmax(0, 1.1fr) minmax(0, 1fr);
  gap: var(--s-8);
  padding: var(--s-6);
  border: 1px solid var(--c-border);
  border-radius: var(--r-lg);
  background:
    radial-gradient(120% 150% at 0% 0%, var(--c-brand-soft), transparent 62%),
    var(--c-surface);
}

.asset-card__hero {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: var(--s-2);
  min-width: 0;
}

.asset-card__label {
  font-size: var(--t-xs);
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: var(--c-text-faint);
}

.asset-card__total {
  font-family: var(--f-mono);
  font-size: clamp(30px, 4vw, 40px);
  font-weight: 640;
  line-height: 1.1;
  letter-spacing: -0.03em;
  font-variant-numeric: tabular-nums;
  color: var(--c-text);
  word-break: break-all;
}

.asset-card__profit {
  font-size: var(--t-sm);
  font-variant-numeric: tabular-nums;
}

.asset-card__profit--total {
  font-size: var(--t-xs);
  color: var(--c-text-muted);
}

.asset-card__profit--total.tone-up {
  color: var(--stock-up);
}

.asset-card__profit--total.tone-down {
  color: var(--stock-down);
}

.asset-card__badges {
  display: flex;
  flex-wrap: wrap;
  gap: var(--s-2);
  margin-top: var(--s-2);
}

.asset-card__metrics {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  align-content: center;
  gap: var(--s-4) var(--s-5);
  margin: 0;
  padding-left: var(--s-8);
  border-left: 1px solid var(--c-border);
}

.metric {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}

.metric dt {
  font-size: var(--t-xs);
  color: var(--c-text-muted);
}

.metric dd {
  margin: 0;
  font-family: var(--f-mono);
  font-size: var(--t-lg);
  font-weight: 600;
  letter-spacing: -0.01em;
  font-variant-numeric: tabular-nums;
  color: var(--c-text);
  word-break: break-all;
}

.metric dd small {
  font-size: var(--t-xs);
  font-weight: 400;
  color: var(--c-text-faint);
}

.asset-card__error {
  grid-column: 1 / -1;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: var(--s-3);
  padding: var(--s-4) 0;
  color: var(--c-text-muted);
  font-size: var(--t-sm);
}

.asset-card__error p {
  margin: 0;
}

/* ---------- 面板 ---------- */
.panel {
  border: 1px solid var(--c-border);
  border-radius: var(--r-lg);
  background: var(--c-surface);
  overflow: hidden;
}

.panel__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--s-3);
  padding: var(--s-4) var(--s-5);
  border-bottom: 1px solid var(--c-border);
}

.panel__head--wrap {
  flex-wrap: wrap;
  row-gap: var(--s-2);
}

.panel__title {
  display: flex;
  align-items: center;
  gap: var(--s-2);
  font-size: var(--t-md);
  font-weight: 620;
  white-space: nowrap;
}

.panel__count {
  display: inline-grid;
  place-items: center;
  min-width: 20px;
  height: 20px;
  padding: 0 6px;
  border-radius: var(--r-full);
  background: var(--c-surface-sunken);
  color: var(--c-text-muted);
  font-size: var(--t-xs);
  font-weight: 600;
}

.panel__meta {
  font-size: var(--t-xs);
  color: var(--c-text-faint);
  font-variant-numeric: tabular-nums;
}

/* ---------- 分段控件（时间范围 / 状态筛选） ---------- */
.seg {
  display: inline-flex;
  gap: 2px;
  padding: 3px;
  border-radius: var(--r-full);
  background: var(--c-surface-sunken);
  max-width: 100%;
  overflow-x: auto;
  scrollbar-width: none;
}

.seg::-webkit-scrollbar {
  display: none;
}

.seg__btn {
  min-height: 28px;
  padding: 0 12px;
  border: 0;
  border-radius: var(--r-full);
  background: none;
  color: var(--c-text-muted);
  font-size: var(--t-xs);
  font-weight: 560;
  font-family: inherit;
  white-space: nowrap;
  cursor: pointer;
  transition: background-color 0.15s var(--ease), color 0.15s var(--ease);
}

.seg__btn:hover:not(.is-active) {
  color: var(--c-text);
}

.seg__btn.is-active {
  background: var(--c-surface);
  color: var(--c-text);
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.08);
}

/* ---------- 表格 ---------- */
.table-wrap {
  overflow-x: auto;
}

.table {
  width: 100%;
  min-width: 720px;
  border-collapse: collapse;
  font-size: var(--t-sm);
}

.table--orders {
  min-width: 940px;
}

.table--trades {
  min-width: 780px;
}

.table thead th {
  padding: 10px var(--s-4);
  border-bottom: 1px solid var(--c-border);
  background: color-mix(in srgb, var(--c-surface-sunken) 60%, transparent);
  color: var(--c-text-faint);
  font-size: var(--t-xs);
  font-weight: 560;
  text-align: left;
  white-space: nowrap;
}

.table thead th.num {
  text-align: right;
}

.table tbody td {
  padding: 12px var(--s-4);
  border-bottom: 1px solid var(--c-border);
  color: var(--c-text-secondary);
  vertical-align: middle;
}

.table tbody tr:last-child td {
  border-bottom: 0;
}

.table tbody tr {
  transition: background-color 0.12s var(--ease);
}

.table tbody tr:hover {
  background: var(--c-surface-sunken);
}

.table td.num {
  text-align: right;
  font-family: var(--f-mono);
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
  color: var(--c-text);
}

.cell-time {
  font-family: var(--f-mono);
  font-size: var(--t-xs);
  color: var(--c-text-muted);
  white-space: nowrap;
}

.cell-name {
  display: flex;
  flex-direction: column;
  gap: 1px;
  min-width: 0;
}

.cell-name__title {
  font-weight: 560;
  color: var(--c-text);
  white-space: nowrap;
}

.cell-name__code {
  font-family: var(--f-mono);
  font-size: var(--t-xs);
  color: var(--c-text-faint);
}

.cell-sub {
  font-size: var(--t-xs);
  color: var(--c-text-faint);
}

.cell-pnl {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 1px;
  font-variant-numeric: tabular-nums;
}

.table td.num .cell-pnl {
  font-family: inherit;
}

.status {
  display: inline-block;
  padding: 2px 8px;
  border-radius: var(--r-full);
  font-size: var(--t-xs);
  white-space: nowrap;
}

.status--pending {
  background: color-mix(in srgb, var(--c-accent) 12%, transparent);
  color: var(--c-accent);
}

.status--filled {
  background: color-mix(in srgb, var(--c-success) 12%, transparent);
  color: var(--c-success);
}

.status--cancelled {
  background: var(--c-surface-sunken);
  color: var(--c-text-muted);
}

.status--rejected {
  background: color-mix(in srgb, var(--stock-up) 12%, transparent);
  color: var(--stock-up);
}

.reject {
  display: block;
  max-width: 16ch;
  margin-top: 2px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 10.5px;
  color: var(--stock-up);
}

.mini-btn {
  min-height: 28px;
  padding: 0 12px;
  border: 1px solid var(--c-border-strong);
  border-radius: var(--r-full);
  background: var(--c-surface);
  color: var(--c-text-secondary);
  font-size: var(--t-xs);
  font-family: inherit;
  cursor: pointer;
  white-space: nowrap;
  transition: border-color 0.15s var(--ease), color 0.15s var(--ease);
}

.mini-btn:hover:not(:disabled) {
  border-color: var(--c-brand);
  color: var(--c-brand);
}

.mini-btn:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.mini-btn--danger {
  border-color: var(--stock-up);
  color: var(--stock-up);
}

.fee-detail {
  display: inline-block;
  margin-left: 2px;
  color: var(--c-text-faint);
  font-size: 11px;
  cursor: help;
}

.link-btn {
  padding: 0;
  border: 0;
  background: none;
  color: var(--c-text-muted);
  font-size: var(--t-xs);
  font-family: inherit;
  cursor: pointer;
}

.link-btn:hover {
  color: var(--c-brand);
}

.table-state {
  padding: var(--s-10) var(--s-5);
  text-align: center;
  color: var(--c-text-muted);
  font-size: var(--t-sm);
}

.table-state--error {
  color: var(--stock-up);
}

/* ---------- 空状态 ---------- */
.empty-state {
  padding: var(--s-12) var(--s-6);
  text-align: center;
}

.empty-title {
  margin: 0;
  font-size: var(--t-md);
  font-weight: 600;
  color: var(--c-text-secondary);
}

.empty-desc {
  max-width: 42ch;
  margin: var(--s-2) auto 0;
  font-size: var(--t-sm);
  line-height: 1.6;
  color: var(--c-text-muted);
}

/* ---------- 分页（全局 .pager 的页内变体） ---------- */
.pager--inline {
  margin-top: 0;
  padding: var(--s-4) var(--s-5);
  border-top: 1px solid var(--c-border);
}

/* ---------- 轻提示 ---------- */
.toast {
  position: fixed;
  right: var(--s-6);
  bottom: var(--s-6);
  z-index: 110;
  margin: 0;
  max-width: min(360px, calc(100vw - 32px));
  padding: 10px var(--s-4);
  border: 1px solid var(--c-border);
  border-radius: var(--r);
  background: var(--c-surface);
  box-shadow: var(--e-lg);
  color: var(--c-text);
  font-size: var(--t-sm);
  line-height: 1.5;
}

.toast.is-error {
  border-color: color-mix(in srgb, var(--stock-up) 40%, transparent);
  color: var(--stock-up);
}

.toast-enter-active,
.toast-leave-active {
  transition: opacity 0.2s var(--ease), transform 0.2s var(--ease);
}

.toast-enter-from,
.toast-leave-to {
  opacity: 0;
  transform: translateY(8px);
}

/* ---------- P2：引擎与策略 ---------- */
.engine__status {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--s-2);
}

.chip--on {
  background: color-mix(in srgb, var(--c-success) 12%, transparent);
  color: var(--c-success);
}

.engine__body {
  display: grid;
  grid-template-columns: minmax(0, 1.15fr) minmax(0, 1fr);
  gap: var(--s-5);
  padding: var(--s-5);
}

.engine__metrics {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  align-content: start;
  gap: var(--s-4) var(--s-5);
  margin: 0;
}

.engine__metrics .engine__time {
  font-size: var(--t-md);
}

.engine__switch {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: var(--s-2);
  margin-top: var(--s-5);
  padding-top: var(--s-4);
  border-top: 1px dashed var(--c-border);
}

.engine__hint,
.engine__warn {
  margin: 0;
  font-size: var(--t-xs);
  line-height: 1.6;
  color: var(--c-text-muted);
}

.engine__warn {
  color: var(--c-accent);
}

.engine__confirm {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: var(--s-3);
}

.engine__confirm p {
  margin: 0;
  font-size: var(--t-sm);
  color: var(--c-text-secondary);
}

.engine__confirm-row {
  display: flex;
  flex-wrap: wrap;
  gap: var(--s-2);
}

/* 策略参数表单 */
.strategy {
  border-top: 1px solid var(--c-border);
  padding: var(--s-5);
}

.strategy__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--s-3);
  margin-bottom: var(--s-4);
}

.strategy__name {
  font-size: var(--t-sm);
  font-weight: 600;
  color: var(--c-text-secondary);
}

.strategy__toggle {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  font-size: var(--t-sm);
  color: var(--c-text-secondary);
  cursor: pointer;
}

.strategy__toggle input {
  width: 16px;
  height: 16px;
  accent-color: var(--c-brand);
  cursor: pointer;
}

.strategy__toggle input:disabled {
  cursor: not-allowed;
}

.strategy__grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--s-4);
}

.field {
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-width: 0;
}

.field__label {
  font-size: var(--t-xs);
  color: var(--c-text-muted);
}

.field__control {
  position: relative;
  display: block;
}

.field__input {
  width: 100%;
  height: 40px;
  padding: 0 10px;
  border: 1px solid var(--c-border);
  border-radius: var(--r);
  background: var(--c-bg);
  color: var(--c-text);
  font-family: var(--f-mono);
  font-size: var(--t-sm);
  font-variant-numeric: tabular-nums;
}

.field__input:focus-visible {
  outline: 2px solid var(--c-brand);
  outline-offset: 1px;
  border-color: transparent;
}

.field__input:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

.field__control .field__input {
  padding-right: 26px;
}

.field__unit {
  position: absolute;
  top: 50%;
  right: 10px;
  transform: translateY(-50%);
  font-size: var(--t-xs);
  color: var(--c-text-faint);
  pointer-events: none;
}

.field__hint {
  font-size: var(--t-xs);
  color: var(--c-text-faint);
}

.strategy__error {
  margin: var(--s-3) 0 0;
  font-size: var(--t-xs);
  color: var(--stock-up);
}

.strategy__actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--s-3);
  margin-top: var(--s-4);
}

.strategy__meta {
  font-size: var(--t-xs);
  color: var(--c-text-faint);
}

/* ---------- P2：选股与信号 ---------- */
.panel__actions {
  display: flex;
  align-items: center;
  gap: var(--s-3);
}

.score-badge {
  display: inline-grid;
  place-items: center;
  min-width: 32px;
  height: 22px;
  padding: 0 7px;
  border-radius: var(--r-full);
  background: var(--c-surface-sunken);
  color: var(--c-text-secondary);
  font-family: var(--f-mono);
  font-size: var(--t-xs);
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}

.score-badge--warm {
  background: color-mix(in srgb, var(--c-accent) 14%, transparent);
  color: var(--c-accent);
}

.score-badge--hot {
  background: color-mix(in srgb, var(--stock-up) 14%, transparent);
  color: var(--stock-up);
}

.cell-reason {
  max-width: 320px;
  font-size: var(--t-xs);
  line-height: 1.6;
  color: var(--c-text-muted);
}

.signal-badge {
  display: inline-flex;
  align-items: center;
  padding: 2px 8px;
  border-radius: var(--r-full);
  background: var(--c-surface-sunken);
  color: var(--c-text-secondary);
  font-size: var(--t-xs);
  white-space: nowrap;
}

.signal-badge--buy {
  background: color-mix(in srgb, var(--stock-up) 12%, transparent);
  color: var(--stock-up);
}

.signal-badge--sell {
  background: color-mix(in srgb, var(--stock-down) 12%, transparent);
  color: var(--stock-down);
}

/* ---------- P3：多账户 ---------- */
.account-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--s-4);
  margin-bottom: var(--s-6);
  padding-bottom: var(--s-4);
  border-bottom: 1px solid var(--c-border);
}

.account-bar__list {
  display: flex;
  flex: 1;
  gap: var(--s-2);
  min-width: 0;
  overflow-x: auto;
  scrollbar-width: none;
}

.account-bar__list::-webkit-scrollbar {
  display: none;
}

.account-chip {
  display: inline-flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 2px;
  min-width: 150px;
  padding: 10px var(--s-4);
  border: 1px solid var(--c-border);
  border-radius: var(--r-lg);
  background: var(--c-surface);
  color: inherit;
  font: inherit;
  text-align: left;
  cursor: pointer;
  transition:
    border-color 0.15s var(--ease, ease),
    background-color 0.15s var(--ease, ease);
}

.account-chip:hover:not(:disabled) {
  border-color: var(--c-border-strong);
}

.account-chip.is-active {
  border-color: var(--c-brand);
  background: var(--c-brand-soft);
}

.account-chip:disabled {
  cursor: progress;
  opacity: 0.7;
}

.account-chip__name {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: var(--t-sm);
  font-weight: 560;
  color: var(--c-text-secondary);
}

.account-chip__flag {
  padding: 0 5px;
  border-radius: var(--r-full);
  background: color-mix(in srgb, var(--c-brand) 14%, transparent);
  color: var(--c-brand);
  font-size: var(--t-xs);
  font-weight: 500;
}

.account-chip__asset {
  font-family: var(--f-mono);
  font-size: var(--t-md);
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  color: var(--c-text);
}

.account-chip__pct {
  font-size: var(--t-xs);
}

.account-bar__actions {
  display: flex;
  flex-shrink: 0;
  gap: var(--s-2);
}

.account-bar__state {
  padding: var(--s-2) 0;
  font-size: var(--t-xs);
  color: var(--c-text-muted);
}

.account-bar__state--error {
  color: var(--stock-up);
}

.account-modal__box {
  width: min(460px, 100%);
}

.account-form {
  display: flex;
  flex-direction: column;
  gap: var(--s-4);
}

.account-form__error {
  margin: 0;
  font-size: var(--t-xs);
  color: var(--stock-up);
}

.account-form__warn {
  margin: 0;
  font-size: var(--t-sm);
  line-height: 1.7;
  color: var(--c-text-secondary);
}

.account-form__actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--s-2);
  margin-top: var(--s-2);
}

.account-form__danger {
  border-color: var(--stock-up);
  color: var(--stock-up);
}

.account-form__danger:hover:not(:disabled) {
  border-color: var(--stock-up);
  background: color-mix(in srgb, var(--stock-up) 12%, transparent);
}

/* ---------- P3：高级规则 ---------- */
.strategy__advanced-toggle {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  margin-top: var(--s-4);
  padding: 0;
  border: 0;
  background: none;
  color: var(--c-text-secondary);
  font: inherit;
  font-size: var(--t-sm);
  font-weight: 560;
  cursor: pointer;
}

.strategy__advanced-toggle:hover {
  color: var(--c-brand);
}

.strategy__advanced-toggle svg {
  width: 14px;
  height: 14px;
  stroke: currentColor;
  stroke-width: 2;
  fill: none;
  transition: transform 0.15s var(--ease, ease);
}

.strategy__advanced-toggle svg.is-open {
  transform: rotate(90deg);
}

.strategy__advanced-hint {
  font-size: var(--t-xs);
  font-weight: 400;
  color: var(--c-text-faint);
}

.strategy__advanced {
  display: flex;
  flex-direction: column;
  gap: var(--s-5);
  margin-top: var(--s-4);
  padding-top: var(--s-4);
  border-top: 1px dashed var(--c-border);
}

.advanced__group {
  display: flex;
  flex-direction: column;
  gap: var(--s-3);
}

.advanced__title {
  margin: 0;
  font-size: var(--t-xs);
  font-weight: 600;
  letter-spacing: 0.04em;
  color: var(--c-text-faint);
}

.advanced__grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--s-4);
}

.advanced__sum {
  margin: 0;
  font-size: var(--t-xs);
  color: var(--c-text-muted);
}

.advanced__sum strong {
  color: var(--c-text);
  font-family: var(--f-mono);
}

/* ---------- 响应式 ---------- */
@media (max-width: 900px) {
  .asset-card {
    grid-template-columns: minmax(0, 1fr);
    gap: var(--s-5);
  }

  .asset-card__metrics {
    padding-left: 0;
    padding-top: var(--s-5);
    border-left: 0;
    border-top: 1px solid var(--c-border);
  }

  .engine__body {
    grid-template-columns: minmax(0, 1fr);
  }

  .strategy__grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .advanced__grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .account-bar {
    flex-wrap: wrap;
  }
}

@media (max-width: 640px) {
  .page-head {
    flex-direction: column;
    align-items: flex-start;
    gap: var(--s-4);
  }

  .page-head__side {
    width: 100%;
    justify-content: flex-start;
  }

  .page-head__side .head-btn {
    min-height: 40px;
  }

  .asset-card {
    padding: var(--s-4);
  }

  .asset-card__total {
    font-size: 28px;
  }

  .metric dd {
    font-size: var(--t-md);
  }

  .panel__head {
    padding: var(--s-3) var(--s-4);
  }

  .table thead th,
  .table tbody td {
    padding: 10px var(--s-3);
  }

  .empty-state {
    padding: var(--s-8) var(--s-4);
  }

  .engine__body,
  .strategy {
    padding: var(--s-4);
  }

  .strategy__grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .advanced__grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .account-bar {
    flex-direction: column;
    align-items: stretch;
  }

  .account-bar__actions {
    justify-content: flex-start;
  }

  .account-chip {
    min-width: 132px;
  }

  .panel__actions {
    width: 100%;
    justify-content: flex-start;
  }

  .cell-reason {
    max-width: none;
    white-space: normal;
  }

  .toast {
    left: var(--s-4);
    right: var(--s-4);
    bottom: var(--s-4);
    max-width: none;
    text-align: center;
  }
}

@media (prefers-reduced-motion: reduce) {
  .refresh-state.is-live .dot,
  .head-btn__icon.spin {
    animation: none;
  }

  .toast-enter-active,
  .toast-leave-active {
    transition: none;
  }
}
</style>
