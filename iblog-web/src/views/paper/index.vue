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
            title="新建一个独立资金的模拟盘"
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
                  advancedOpen ? '收起' : `共 ${ADVANCED_PANEL_FIELD_COUNT} 项`
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
                <div class="advanced__group">
                  <h3 class="advanced__title">质量因子</h3>
                  <p class="quality__hint">
                    以下都是「打分项」，不是硬性过滤：不满足只是不加分，不会直接排除股票。
                  </p>
                  <div class="quality__rows">
                    <div class="quality__row">
                      <span class="quality__name">换手率</span>
                      <label class="field quality__field">
                        <span class="field__label">权重</span>
                        <span class="field__control">
                          <input
                            v-model.number="strategyForm.turnoverScore"
                            class="field__input"
                            type="number"
                            :min="ruleOf('turnoverScore').min"
                            :max="ruleOf('turnoverScore').max"
                            step="1"
                            :disabled="!strategy"
                          />
                          <span class="field__unit">分</span>
                        </span>
                      </label>
                      <label class="field quality__field">
                        <span class="field__label">下限</span>
                        <span class="field__control">
                          <input
                            v-model.number="strategyForm.turnoverMinPct"
                            class="field__input"
                            type="number"
                            :min="ruleOf('turnoverMinPct').min"
                            :max="ruleOf('turnoverMinPct').max"
                            step="0.5"
                            :disabled="!strategy"
                          />
                          <span class="field__unit">%</span>
                        </span>
                      </label>
                      <label class="field quality__field">
                        <span class="field__label">上限</span>
                        <span class="field__control">
                          <input
                            v-model.number="strategyForm.turnoverMaxPct"
                            class="field__input"
                            type="number"
                            :min="ruleOf('turnoverMaxPct').min"
                            :max="ruleOf('turnoverMaxPct').max"
                            step="0.5"
                            :disabled="!strategy"
                          />
                          <span class="field__unit">%</span>
                        </span>
                      </label>
                    </div>

                    <div class="quality__row">
                      <span class="quality__name">流通市值</span>
                      <label class="field quality__field">
                        <span class="field__label">权重</span>
                        <span class="field__control">
                          <input
                            v-model.number="strategyForm.floatCapScore"
                            class="field__input"
                            type="number"
                            :min="ruleOf('floatCapScore').min"
                            :max="ruleOf('floatCapScore').max"
                            step="1"
                            :disabled="!strategy"
                          />
                          <span class="field__unit">分</span>
                        </span>
                      </label>
                      <label class="field quality__field">
                        <span class="field__label">下限</span>
                        <span class="field__control">
                          <input
                            v-model.number="strategyForm.floatCapMinYi"
                            class="field__input"
                            type="number"
                            :min="ruleOf('floatCapMinYi').min"
                            :max="ruleOf('floatCapMinYi').max"
                            step="10"
                            :disabled="!strategy"
                          />
                          <span class="field__unit">亿</span>
                        </span>
                      </label>
                      <label class="field quality__field">
                        <span class="field__label">上限</span>
                        <span class="field__control">
                          <input
                            v-model.number="strategyForm.floatCapMaxYi"
                            class="field__input"
                            type="number"
                            :min="ruleOf('floatCapMaxYi').min"
                            :max="ruleOf('floatCapMaxYi').max"
                            step="10"
                            :disabled="!strategy"
                          />
                          <span class="field__unit">亿</span>
                        </span>
                      </label>
                    </div>

                    <div class="quality__row">
                      <span class="quality__name">分时站上均价线</span>
                      <label class="field quality__field">
                        <span class="field__label">权重</span>
                        <span class="field__control">
                          <input
                            v-model.number="strategyForm.vwapScore"
                            class="field__input"
                            type="number"
                            :min="ruleOf('vwapScore').min"
                            :max="ruleOf('vwapScore').max"
                            step="1"
                            :disabled="!strategy"
                          />
                          <span class="field__unit">分</span>
                        </span>
                      </label>
                      <label class="field quality__field">
                        <span class="field__label">占比阈值</span>
                        <span class="field__control">
                          <input
                            v-model.number="strategyForm.vwapAboveRatio"
                            class="field__input"
                            type="number"
                            :min="ruleOf('vwapAboveRatio').min"
                            :max="ruleOf('vwapAboveRatio').max"
                            step="0.01"
                            :disabled="!strategy"
                          />
                        </span>
                      </label>
                    </div>

                    <div class="quality__row">
                      <span class="quality__name">涨停记忆</span>
                      <label class="field quality__field">
                        <span class="field__label">权重</span>
                        <span class="field__control">
                          <input
                            v-model.number="strategyForm.limitUpScore"
                            class="field__input"
                            type="number"
                            :min="ruleOf('limitUpScore').min"
                            :max="ruleOf('limitUpScore').max"
                            step="1"
                            :disabled="!strategy"
                          />
                          <span class="field__unit">分</span>
                        </span>
                      </label>
                      <label class="field quality__field">
                        <span class="field__label">回看天数</span>
                        <span class="field__control">
                          <input
                            v-model.number="strategyForm.limitUpWithinDays"
                            class="field__input"
                            type="number"
                            :min="ruleOf('limitUpWithinDays').min"
                            :max="ruleOf('limitUpWithinDays').max"
                            step="1"
                            :disabled="!strategy"
                          />
                          <span class="field__unit">天</span>
                        </span>
                      </label>
                    </div>
                  </div>
                  <p class="quality__hint">
                    换手率、流通市值取自行情快照；分时均价线在盘中才会更新；涨停记忆按日 K 判断（主板
                    10%、创业板/科创板 20%、北交所 30%、ST 5%）。
                  </p>
                </div>
                <div class="advanced__group">
                  <h3 class="advanced__title">涨停结构</h3>
                  <div class="advanced__grid">
                    <label class="field">
                      <span class="field__label">首板加分</span>
                      <span class="field__control">
                        <input
                          v-model.number="strategyForm.firstBoardScore"
                          class="field__input"
                          type="number"
                          :min="ruleOf('firstBoardScore').min"
                          :max="ruleOf('firstBoardScore').max"
                          step="1"
                          :disabled="!strategy"
                        />
                        <span class="field__unit">分</span>
                      </span>
                      <span class="field__hint">
                        上一交易日为首板（涨停且前一日未涨停）时加分，不是硬性过滤
                      </span>
                    </label>
                    <label class="field">
                      <span class="field__label">连板高度上限</span>
                      <span class="field__control">
                        <input
                          v-model.number="strategyForm.boardHeightMax"
                          class="field__input"
                          type="number"
                          :min="ruleOf('boardHeightMax').min"
                          :max="ruleOf('boardHeightMax').max"
                          step="1"
                          :disabled="!strategy"
                        />
                        <span class="field__unit">板</span>
                      </span>
                      <span class="field__hint">
                        0=不限；1=只做昨日首板（一进二）；N=昨日涨停且连板数不超过 N，不满足直接不入选
                      </span>
                    </label>
                  </div>
                </div>
                <div class="advanced__group">
                  <h3 class="advanced__title">新高与量能</h3>
                  <div class="advanced__grid">
                    <label class="field">
                      <span class="field__label">两年新高</span>
                      <span class="field__control">
                        <input
                          v-model.number="strategyForm.newHighScore"
                          class="field__input"
                          type="number"
                          :min="ruleOf('newHighScore').min"
                          :max="ruleOf('newHighScore').max"
                          step="1"
                          :disabled="!strategy"
                        />
                        <span class="field__unit">分</span>
                      </span>
                      <span class="field__hint">
                        近 N 个交易日内最高价超过前两年（约 486 个交易日）最高价时加分
                      </span>
                    </label>
                    <label class="field">
                      <span class="field__label">新高回看窗口</span>
                      <span class="field__control">
                        <input
                          v-model.number="strategyForm.newHighWithinDays"
                          class="field__input"
                          type="number"
                          :min="ruleOf('newHighWithinDays').min"
                          :max="ruleOf('newHighWithinDays').max"
                          step="1"
                          :disabled="!strategy"
                        />
                        <span class="field__unit">个交易日</span>
                      </span>
                      <span class="field__hint">
                        「近 N 个交易日」的 N：越小越强调刚创新高，默认 5
                      </span>
                    </label>
                    <label class="field">
                      <span class="field__label">缩量上涨</span>
                      <span class="field__control">
                        <input
                          v-model.number="strategyForm.shrinkVolumeScore"
                          class="field__input"
                          type="number"
                          :min="ruleOf('shrinkVolumeScore').min"
                          :max="ruleOf('shrinkVolumeScore').max"
                          step="1"
                          :disabled="!strategy"
                        />
                        <span class="field__unit">分</span>
                      </span>
                      <span class="field__hint">
                        当日收涨且较前一日缩量时加分，不是硬性过滤
                      </span>
                    </label>
                    <label class="field">
                      <span class="field__label">缩量阈值</span>
                      <span class="field__control">
                        <input
                          v-model.number="strategyForm.shrinkVolumeMaxRatio"
                          class="field__input"
                          type="number"
                          :min="ruleOf('shrinkVolumeMaxRatio').min"
                          :max="ruleOf('shrinkVolumeMaxRatio').max"
                          step="0.01"
                          :disabled="!strategy"
                        />
                      </span>
                      <span class="field__hint">
                        当日成交量 ≤ 前一日成交量 × 该系数（0.1–1，默认 0.9）
                      </span>
                    </label>
                  </div>
                </div>
                <div class="advanced__group">
                  <h3 class="advanced__title">严格过滤（硬条件）</h3>
                  <label class="trade-window__check">
                    <input
                      v-model="strategyForm.strictFilters"
                      type="checkbox"
                      :disabled="!strategy"
                    />
                    <span class="trade-window__label">启用严格过滤</span>
                    <span class="trade-window__hint">
                      总开关：打开后下面的硬条件才生效，不满足的候选直接剔除；关闭时仅打分项参与排序。
                    </span>
                  </label>
                  <div class="advanced__grid">
                    <label v-for="item in STRICT_NUMERIC_FIELDS" :key="item.key" class="field">
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
                  <div class="strict__checks">
                    <label class="trade-window__check">
                      <input
                        v-model="strategyForm.sortByLowRatioAsc"
                        type="checkbox"
                        :disabled="!strategy"
                      />
                      <span class="trade-window__label">候选按「现价/一年低点」升序</span>
                      <span class="trade-window__hint">优先级：越接近一年低点越靠前</span>
                    </label>
                    <label class="trade-window__check">
                      <input
                        v-model="strategyForm.requireAboveOpen"
                        type="checkbox"
                        :disabled="!strategy"
                      />
                      <span class="trade-window__label">现价必须高于今开</span>
                      <span class="trade-window__hint">低于或等于今开的候选直接剔除</span>
                    </label>
                    <label class="trade-window__check">
                      <input
                        v-model="strategyForm.requireAboveVwap"
                        type="checkbox"
                        :disabled="!strategy"
                      />
                      <span class="trade-window__label">现价必须高于分时均价</span>
                      <span class="trade-window__hint">低于或等于均价线的候选直接剔除</span>
                    </label>
                    <label class="trade-window__check">
                      <input
                        v-model="strategyForm.requireHolderShrink"
                        type="checkbox"
                        :disabled="!strategy"
                      />
                      <span class="trade-window__label">股东户数较上一期减少</span>
                      <span class="trade-window__hint">筹码集中度变高的标的优先</span>
                    </label>
                    <label class="trade-window__check">
                      <input
                        v-model="strategyForm.affordableOnly"
                        type="checkbox"
                        :disabled="!strategy"
                      />
                      <span class="trade-window__label">只保留当前资金买得起的标的</span>
                      <span class="trade-window__hint">科创板按 200 股起算，避免选出来却下不了单</span>
                    </label>
                  </div>
                  <div class="trade-window__row">
                    <label class="field trade-window__field">
                      <span class="field__label">盈利要求</span>
                      <select
                        v-model.number="strategyForm.profitRequire"
                        class="field__input"
                        :disabled="!strategy"
                      >
                        <option :value="0">不限</option>
                        <option :value="1">最新报告期盈利</option>
                        <option :value="2">二季报或年报盈利</option>
                        <option :value="3">二季报/二季报预告/年报盈利</option>
                      </select>
                    </label>
                    <label class="field trade-window__field">
                      <span class="field__label">早盘量窗口（开始）</span>
                      <input
                        v-model="strategyForm.openTurnoverStart"
                        class="field__input"
                        type="time"
                        :disabled="!strategy"
                      />
                    </label>
                    <span class="trade-window__dash" aria-hidden="true">至</span>
                    <label class="field trade-window__field">
                      <span class="field__label">早盘量窗口（结束）</span>
                      <input
                        v-model="strategyForm.openTurnoverEnd"
                        class="field__input"
                        type="time"
                        :disabled="!strategy"
                      />
                    </label>
                  </div>
                  <p v-if="openTurnoverInvalid" class="trade-window__warn">
                    早盘量窗口起止非法（需 HH:mm 且开始早于结束），后端会当作不限制该窗口（仍可保存）。
                  </p>
                  <p v-else class="trade-window__hint">
                    早盘换手率只统计该窗口内的成交量；窗口外不影响全天换手率与其它硬条件。
                  </p>
                </div>
                <div class="advanced__group">
                  <h3 class="advanced__title">市场情绪（热度）</h3>
                  <label class="trade-window__check">
                    <input
                      v-model="strategyForm.sentimentEnabled"
                      type="checkbox"
                      :disabled="!strategy"
                    />
                    <span class="trade-window__label">启用情绪维度</span>
                    <span class="trade-window__hint">
                      总开关：打开后下面的情绪分与热度才参与买入选股；关闭时策略行为与以前完全一致。
                    </span>
                  </label>
                  <div v-if="sentiment" class="sentiment-card">
                    <div class="sentiment-card__main">
                      <span class="sentiment-card__score">{{ sentiment.score }}</span>
                      <span class="sentiment-card__level">{{ sentiment.level }}</span>
                      <span v-if="sentiment.stale" class="sentiment-card__tag">沿用上一份快照</span>
                      <span v-else-if="!sentiment.available" class="sentiment-card__tag">
                        上游暂时取不到数据，此时不会拦单
                      </span>
                    </div>
                    <p class="sentiment-card__line">
                      涨 {{ sentiment.upCount }} / 跌 {{ sentiment.downCount }} / 平
                      {{ sentiment.flatCount }}，涨停 {{ sentiment.limitUpCount }} 家、跌停
                      {{ sentiment.limitDownCount }} 家，炸板率 {{ sentiment.brokenRate }}%，成交
                      {{ sentiment.amountYi }} 亿
                    </p>
                    <p class="sentiment-card__hint">
                      情绪分只用于「今天该不该出手、出手该多谨慎」，不判断单只股票好坏。
                    </p>
                  </div>
                  <p v-else class="trade-window__hint">打开实时情绪面板需要先保存策略并保持页面在交易时段内。</p>
                  <div class="advanced__grid">
                    <label v-for="item in SENTIMENT_NUMERIC_FIELDS" :key="item.key" class="field">
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
                  <label class="trade-window__check">
                    <input
                      v-model="strategyForm.requireHotRankUp"
                      type="checkbox"
                      :disabled="!strategy"
                    />
                    <span class="trade-window__label">热榜排名必须比上次上升</span>
                    <span class="trade-window__hint">排名下滑的票说明热度正在退潮，直接剔除</span>
                  </label>
                  <p v-if="sentimentScoreInvalid" class="trade-window__warn">
                    清仓线高于开仓线，保存时后端会把清仓线压到开仓线（否则刚买就会被清）。
                  </p>
                </div>
                <div class="advanced__group">
                  <h3 class="advanced__title">新闻情绪（AI 资讯）</h3>
                  <label class="trade-window__check">
                    <input
                      v-model="strategyForm.newsEnabled"
                      type="checkbox"
                      :disabled="!strategy"
                    />
                    <span class="trade-window__label">启用新闻因子</span>
                    <span class="trade-window__hint">
                      每 30 分钟扫描候选池的新闻/公告/小作文，用 AI（无 Key 时用关键词兜底）打出
                      利好/利空分，作为选股总分的加分项。A 股是情绪市场，一条新闻可能直接改变次日方向。
                    </span>
                  </label>
                  <div class="advanced__grid">
                    <label v-for="item in NEWS_NUMERIC_FIELDS" :key="item.key" class="field">
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
                  <label class="trade-window__check">
                    <input
                      v-model="strategyForm.newsHardFilter"
                      type="checkbox"
                      :disabled="!strategy"
                    />
                    <span class="trade-window__label">重大利空直接剔除（硬过滤）</span>
                    <span class="trade-window__hint">
                      默认关闭。开启后仅当新闻分低于阈值且置信度足够（排除传闻）时才把票淘汰。
                    </span>
                  </label>
                  <p class="trade-window__hint">
                    新闻分只是总分的一部分，仍要和其他因子一起达到「入选最低分」；扫新闻失败也绝不影响选股。
                  </p>
                </div>
                <div class="advanced__group">
                  <h3 class="advanced__title">交易时段</h3>
                  <label class="trade-window__check">
                    <input
                      v-model="strategyForm.preMarketPreselect"
                      type="checkbox"
                      :disabled="!strategy"
                    />
                    <span class="trade-window__label">盘前预选</span>
                    <span class="trade-window__hint">
                      9:25 集合竞价结束后先用日 K 粗筛一批候选（跳过依赖分时的条件），
                      9:30 开盘后再用完整数据复核覆盖（分时未就绪时 9:32 自动补一次）；
                      当天已有候选时不重复预选。
                    </span>
                  </label>
                  <label class="trade-window__check">
                    <input
                      v-model="strategyForm.sellAtOpen"
                      type="checkbox"
                      :disabled="!strategy"
                    />
                    <span class="trade-window__label">次日开盘强制卖出</span>
                    <span class="trade-window__hint">
                      T+1 可卖后的首个 tick 直接市价清仓，不看止损/止盈/MA10，适合隔夜策略。
                    </span>
                  </label>
                  <div class="trade-window__row">
                    <label class="field trade-window__field">
                      <span class="field__label">自动买入时间窗（开始）</span>
                      <input
                        v-model="strategyForm.buyStartTime"
                        class="field__input"
                        type="time"
                        :disabled="!strategy"
                      />
                    </label>
                    <span class="trade-window__dash" aria-hidden="true">至</span>
                    <label class="field trade-window__field">
                      <span class="field__label">自动买入时间窗（结束）</span>
                      <input
                        v-model="strategyForm.buyEndTime"
                        class="field__input"
                        type="time"
                        :disabled="!strategy"
                      />
                    </label>
                  </div>
                  <p v-if="buyWindowInvalid" class="trade-window__warn">
                    开始时间不早于结束时间，后端会忽略该时间窗（仍可保存）。
                  </p>
                  <p v-else class="trade-window__hint">
                    仅约束自动交易买入，手动下单不受影响；未设置时默认覆盖整个交易时段。
                  </p>
                </div>
                <p class="advanced__sum">
                  因子权重合计 <strong>{{ weightSum }}</strong> 分（仅作参考，不影响入选总分上限）
                </p>

                <!-- 规则模板库：把当前全部参数存成命名模板，一键应用到账户 -->
                <div class="rule-bar">
                  <div class="rule-bar__row">
                    <label class="rule-bar__select">
                      <span class="field__label">规则模板</span>
                      <select
                        v-model.number="selectedRuleId"
                        class="field__input"
                        :disabled="(rulesLoading && !rules.length) || applyingRule"
                      >
                        <option v-if="!rules.length" :value="null">暂无模板</option>
                        <option v-for="item in rules" :key="item.id" :value="item.id">
                          {{ item.name }}{{ item.active ? '（当前生效）' : '' }}
                        </option>
                      </select>
                      <span class="field__hint">
                        {{ rules.length }} / {{ MAX_RULES }} 套 · 应用后账户仍可单独微调
                      </span>
                    </label>
                    <div class="rule-bar__actions">
                      <button
                        class="btn btn--ghost"
                        type="button"
                        :disabled="!selectedRule || applyingRule || !strategy"
                        @click="applyRule"
                      >
                        {{ applyingRule ? '应用中…' : '应用模板' }}
                      </button>
                      <button
                        class="btn btn--ghost"
                        type="button"
                        :disabled="!canSaveRule"
                        :title="
                          rules.length >= MAX_RULES
                            ? `最多 ${MAX_RULES} 套模板，请先删除一套`
                            : '把当前全部参数另存为命名模板'
                        "
                        @click="openSaveRule"
                      >
                        另存为模板
                      </button>
                      <button
                        class="btn btn--ghost rule-bar__danger"
                        type="button"
                        :disabled="!selectedRule || ruleDeleting"
                        @click="armDeleteRule"
                      >
                        删除模板
                      </button>
                    </div>
                  </div>
                  <p v-if="rulesError" class="rule-bar__error">{{ rulesError }}</p>
                  <p v-else-if="rulesLoading && !rules.length" class="rule-bar__error">
                    规则模板加载中…
                  </p>
                  <div v-if="ruleDeleteArmed" class="rule-bar__confirm">
                    <span>
                      确定删除模板「{{ selectedRule?.name }}」？删除后无法恢复，删完会自动补一套基础规则。
                    </span>
                    <button
                      class="btn btn--ghost rule-bar__danger"
                      type="button"
                      :disabled="ruleDeleting"
                      @click="confirmDeleteRule"
                    >
                      {{ ruleDeleting ? '删除中…' : '确认删除' }}
                    </button>
                    <button
                      class="btn btn--ghost"
                      type="button"
                      :disabled="ruleDeleting"
                      @click="cancelDeleteRule"
                    >
                      取消
                    </button>
                  </div>
                  <p v-else-if="activeRule" class="rule-bar__active">
                    当前生效：{{ activeRule.name }}
                  </p>
                </div>
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
              <span v-if="selectionsTime" class="panel__meta">选股于 {{ shortTime(selectionsTime) }}</span>
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
                  <th scope="col" class="num">新闻</th>
                  <th scope="col" class="num">操作</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="item in selections" :key="item.id" class="row-link" @click="goStockDetail(item.code, $event)">
                  <td>
                    <router-link
                      class="cell-name cell-name--link"
                      :to="`/stock/${item.code}`"
                      @click.stop
                    >
                      <span class="cell-name__title">{{ item.name }}</span>
                      <span class="cell-name__code">{{ item.code }}</span>
                    </router-link>
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
                  <td class="num" @click.stop>
                    <button
                      v-if="newsOf(item.code)"
                      type="button"
                      class="news-badge"
                      :class="`news-badge--${newsTone(newsScoreOf(item.code))}`"
                      :title="newsSummaryOf(item.code)"
                      @click="openNews(item.code)"
                    >
                      {{ newsTextOf(item.code) }}
                    </button>
                    <span v-else class="cell-sub">--</span>
                  </td>
                  <td class="num" @click.stop>
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
                  <th scope="col" class="num">评分</th>
                  <th scope="col" class="num">新闻</th>
                  <th scope="col" class="num">操作</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="position in positions" :key="position.code" class="row-link" @click="goStockDetail(position.code, $event)">
                  <td>
                    <router-link
                      class="cell-name cell-name--link"
                      :to="`/stock/${position.code}`"
                      @click.stop
                    >
                      <span class="cell-name__title">{{ position.name }}</span>
                      <span class="cell-name__code">{{ position.code }}</span>
                    </router-link>
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
                  <td class="num" @click.stop>
                    <span
                      v-if="position.score != null"
                      class="score-badge"
                      :class="{
                        'score-badge--hot': position.score >= 80,
                        'score-badge--warm': position.score >= 65 && position.score < 80
                      }"
                      :title="position.scoreReason || ''"
                    >
                      {{ position.score }}
                    </span>
                    <span v-else class="cell-sub" title="下次重新选股后生成">--</span>
                  </td>
                  <td class="num" @click.stop>
                    <button
                      v-if="newsOf(position.code)"
                      type="button"
                      class="news-badge"
                      :class="`news-badge--${newsTone(newsScoreOf(position.code))}`"
                      :title="newsSummaryOf(position.code)"
                      @click="openNews(position.code)"
                    >
                      {{ newsTextOf(position.code) }}
                    </button>
                    <span v-else class="cell-sub">--</span>
                  </td>
                  <td class="num" @click.stop>
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

    <!-- 另存为规则模板 -->
    <div v-if="saveRuleOpen" class="stock-modal" @click.self="closeSaveRule">
      <div
        class="stock-modal__box account-modal__box"
        role="dialog"
        aria-modal="true"
        aria-labelledby="rule-save-title"
      >
        <div class="stock-modal__head">
          <h2 id="rule-save-title" class="stock-modal__title">另存为规则模板</h2>
          <button
            class="stock-modal__close"
            type="button"
            aria-label="关闭"
            @click="closeSaveRule"
          >
            ×
          </button>
        </div>
        <form class="account-form" @submit.prevent="submitSaveRule">
          <label class="field">
            <span class="field__label">模板名称</span>
            <input
              v-model="saveRuleName"
              class="field__input"
              type="text"
              :maxlength="RULE_NAME_MAX"
              placeholder="例如：稳健趋势"
              :disabled="ruleSaving"
            />
            <span class="field__hint">
              最多 {{ RULE_NAME_MAX }} 个字；同名模板会被拒绝，当前表单的
              {{ PARAM_FIELD_COUNT }} 项参数会一起保存
            </span>
          </label>
          <p v-if="saveRuleError" class="account-form__error">{{ saveRuleError }}</p>
          <div class="account-form__actions">
            <button class="btn" type="submit" :disabled="ruleSaving">
              {{ ruleSaving ? '保存中…' : '保存模板' }}
            </button>
            <button
              class="btn btn--ghost"
              type="button"
              :disabled="ruleSaving"
              @click="closeSaveRule"
            >
              取消
            </button>
          </div>
        </form>
      </div>
    </div>

    <!-- 新闻情绪面板 -->
    <div v-if="newsOpen" class="stock-modal" @click.self="closeNews">
      <div
        class="stock-modal__box news-modal__box"
        role="dialog"
        aria-modal="true"
        aria-labelledby="news-panel-title"
      >
        <div class="stock-modal__head">
          <h2 id="news-panel-title" class="stock-modal__title">
            新闻情绪{{ newsDetail ? ` · ${newsDetail.name || newsDetail.code}` : '' }}
          </h2>
          <button class="stock-modal__close" type="button" aria-label="关闭" @click="closeNews">
            ×
          </button>
        </div>
        <div class="news-panel">
          <div v-if="newsLoading" class="table-state">加载中…</div>
          <div v-else-if="newsError" class="table-state table-state--error">{{ newsError }}</div>
          <template v-else-if="newsDetail">
            <div class="news-panel__summary">
              <span class="news-badge" :class="`news-badge--${newsDetailTone}`">
                {{ newsDetailText }}
              </span>
              <span class="cell-sub">
                置信度 {{ newsDetailConfidence }}% · {{ newsDetail.updatedAt || '--' }}
                <template v-if="newsDetail.model === 'keyword'"> · 关键词兜底</template>
              </span>
            </div>
            <p v-if="newsDetail.summary" class="news-panel__text">{{ newsDetail.summary }}</p>
            <p v-if="newsAdvice" class="news-panel__advice">{{ newsAdvice }}</p>
            <ul v-if="(newsDetail.headlines ?? []).length" class="news-list">
              <li v-for="(h, i) in newsDetail.headlines" :key="i" class="news-list__item">
                <span class="news-list__scope" :class="{ 'is-sector': h.scope === 'sector' }">
                  {{ h.scope === 'sector' ? '板块' : '个股' }}
                </span>
                <div class="news-list__body">
                  <span class="news-list__title">{{ h.title }}</span>
                  <span class="news-list__meta">
                    {{ h.source || '未知来源' }}<template v-if="h.date"> · {{ h.date }}</template>
                  </span>
                </div>
              </li>
            </ul>
            <p v-else class="trade-window__hint">没有可展示的新闻标题。</p>
            <p class="trade-window__hint">
              新闻分每天盘中约每 30 分钟刷新，只参与选股加分、不会自动交易；
              重大利空可在高级规则里开启硬过滤，持仓预警请结合自身仓位判断。
            </p>
          </template>
          <p v-else class="trade-window__hint">今天还没有这只股票的新闻分析。</p>
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
import { useRouter } from 'vue-router'
import NavChart from '@/components/paper/NavChart.vue'
import TradeDialog from '@/components/paper/TradeDialog.vue'
import {
  applyPaperRule,
  cancelPaperOrder,
  createPaperAccount,
  createPaperRule,
  deletePaperAccount,
  deletePaperRule,
  fetchPaperAccount,
  fetchPaperAccounts,
  fetchPaperEngineStatus,
  fetchPaperNav,
  fetchPaperOrders,
  fetchPaperPositions,
  fetchPaperRules,
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
  PaperRule,
  PaperSide,
  PaperSignal,
  PaperStrategy,
  PaperStrategyParams,
  PaperTrade
} from '@/api/paper'
import { fmtNum, toneClass } from '@/utils/stockFormat'
import { fetchMarketSentiment } from '@/api/stock'
import type { MarketSentiment } from '@/api/stock'
import { getMarketState, marketStateBadge, refreshIntervalMs } from '@/utils/marketTime'
import { useSettings } from '@/composables/useSettings'
import { fetchStockNewsBatch, fetchStockNewsDetail } from '@/api/news'
import type { StockNewsDetail, StockNewsItem } from '@/api/news'

const router = useRouter()

/**
 * 用户在设置里指定的刷新间隔。
 *
 * <p>模拟盘页面拉的是账户/持仓/委托这类「自己的数据」，不是行情报价，
 * 但节奏仍跟行情绑在一起——盘后价格不动，资产也不会变，用同一个间隔最省事。</p>
 */
const { refreshSettings } = useSettings()
const quotesRefreshMs = computed(() => refreshSettings.value.quotesRefreshMs)

const ORDERS_PAGE_SIZE = 20
const TRADES_PAGE_SIZE = 20
const NAV_DAYS = [30, 90, 180]
/** 信号历史最多展示条数（接口上限 200） */
const SIGNALS_LIMIT = 50
/** 引擎/选股/信号属于「慢数据」，最短 60s 刷新一次，避免频繁拉选股与信号 */
const SECONDARY_MIN_INTERVAL_MS = 60_000
/** 规则模板名称长度上限（后端同样限制 32 个字符） */
const RULE_NAME_MAX = 32
/** 每个用户最多可存的规则模板数量（后端同样限制 20 套） */
const MAX_RULES = 20

/** 策略表单：API 里 3 个新增字段可选，表单内部统一补全 */
type StrategyForm = Required<PaperStrategyParams>
/** 数值型参数 key：时间窗、开关与下拉单独渲染，不参与区间钳制 */
type NumericParamKey = Exclude<
  keyof PaperStrategyParams,
  | 'sellAtOpen'
  | 'buyStartTime'
  | 'buyEndTime'
  | 'strictFilters'
  | 'sortByLowRatioAsc'
  | 'openTurnoverStart'
  | 'openTurnoverEnd'
  | 'requireAboveOpen'
  | 'requireAboveVwap'
  | 'requireHolderShrink'
  | 'affordableOnly'
  | 'sentimentEnabled'
  | 'requireHotRankUp'
  | 'preMarketPreselect'
  | 'newsEnabled'
  | 'newsHardFilter'
>

/** 次日开盘强制卖出默认关闭 */
const DEFAULT_SELL_AT_OPEN = false
/** 自动买入时间窗默认覆盖整个交易时段（含） */
const DEFAULT_BUY_START_TIME = '09:30'
const DEFAULT_BUY_END_TIME = '15:00'
/** 早盘量窗口默认 09:25–09:40（与后端 PaperStrategyParams 一致） */
const DEFAULT_OPEN_TURNOVER_START = '09:25'
const DEFAULT_OPEN_TURNOVER_END = '09:40'
/** HH:mm 时间格式（与后端 DateTimeFormatter "HH:mm" 一致） */
const TIME_PATTERN = /^([01]\d|2[0-3]):[0-5]\d$/

/** 后端策略参数的完整默认值，与 PaperStrategyParams 一一对应 */
const DEFAULT_STRATEGY_PARAMS: StrategyForm = {
  minScore: 60,
  stopLossPct: 8,
  takeProfitPct: 15,
  maxHoldDays: 10,
  universeSize: 20,
  maxCandidates: 5,
  selectionRefreshSec: 300,
  preMarketPreselect: true,
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
  changeMaxPct: 9,
  sellAtOpen: DEFAULT_SELL_AT_OPEN,
  buyStartTime: DEFAULT_BUY_START_TIME,
  buyEndTime: DEFAULT_BUY_END_TIME,
  turnoverScore: 5,
  turnoverMinPct: 3,
  turnoverMaxPct: 15,
  floatCapScore: 5,
  floatCapMinYi: 50,
  floatCapMaxYi: 200,
  vwapScore: 10,
  vwapAboveRatio: 0.95,
  limitUpScore: 10,
  limitUpWithinDays: 20,
  firstBoardScore: 0,
  boardHeightMax: 0,
  newHighScore: 0,
  newHighWithinDays: 5,
  shrinkVolumeScore: 0,
  shrinkVolumeMaxRatio: 0.9,
  strictFilters: false,
  lowRatioMax: 0,
  sortByLowRatioAsc: false,
  openTurnoverMinPct: 0,
  auctionTurnoverMinPct: 0,
  openTurnoverStart: DEFAULT_OPEN_TURNOVER_START,
  openTurnoverEnd: DEFAULT_OPEN_TURNOVER_END,
  realTurnoverMinPct: 0,
  requireAboveOpen: false,
  requireAboveVwap: false,
  hardVolumeRatioMin: 0,
  ma5OverMa20Min: 0,
  vol5OverVol20Min: 0,
  totalCapMinYi: 0,
  noLimitUpWithinDays: 0,
  requireHolderShrink: false,
  profitRequire: 0,
  affordableOnly: false,
  sentimentEnabled: false,
  sentimentBuyMinScore: 0,
  sentimentExitScore: 0,
  hotRankTopN: 0,
  requireHotRankUp: false,
  hotnessScore: 0,
  sentimentChangeMaxPct: 0,
  newsEnabled: true,
  newsScoreMax: 15,
  newsPenaltyMax: 10,
  newsHardFilter: false,
  newsMinScore: -50,
  newsMinConfidence: 0.6,
  newsRefreshMin: 30
}

interface NumberRule {
  min: number
  max: number
  /** 是否只接受整数（先取整再钳制） */
  integer: boolean
}

/** 各字段的合法区间：保存前把非法/越界输入夹回区间内（与后端 PaperStrategyParams.parse 一致） */
const STRATEGY_RULES: Record<NumericParamKey, NumberRule> = {
  minScore: { min: 0, max: 200, integer: true },
  stopLossPct: { min: 0.2, max: 50, integer: false },
  takeProfitPct: { min: 0.2, max: 200, integer: false },
  maxHoldDays: { min: 1, max: 250, integer: true },
  universeSize: { min: 1, max: 100, integer: true },
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
  moneyFlowPctMin: { min: 0, max: 100, integer: false },
  rsiMin: { min: 0, max: 99, integer: true },
  rsiMax: { min: 0, max: 100, integer: true },
  changeMinPct: { min: -10, max: 49.9, integer: false },
  changeMaxPct: { min: -10, max: 50, integer: false },
  turnoverScore: { min: 0, max: 100, integer: true },
  turnoverMinPct: { min: 0, max: 100, integer: false },
  turnoverMaxPct: { min: 0, max: 100, integer: false },
  floatCapScore: { min: 0, max: 100, integer: true },
  floatCapMinYi: { min: 0, max: 100000, integer: false },
  floatCapMaxYi: { min: 0, max: 100000, integer: false },
  vwapScore: { min: 0, max: 100, integer: true },
  vwapAboveRatio: { min: 0.5, max: 1, integer: false },
  limitUpScore: { min: 0, max: 100, integer: true },
  limitUpWithinDays: { min: 1, max: 120, integer: true },
  firstBoardScore: { min: 0, max: 100, integer: true },
  boardHeightMax: { min: 0, max: 10, integer: true },
  newHighScore: { min: 0, max: 100, integer: true },
  newHighWithinDays: { min: 1, max: 10, integer: true },
  shrinkVolumeScore: { min: 0, max: 100, integer: true },
  shrinkVolumeMaxRatio: { min: 0.1, max: 1, integer: false },
  lowRatioMax: { min: 0, max: 100, integer: false },
  openTurnoverMinPct: { min: 0, max: 100, integer: false },
  auctionTurnoverMinPct: { min: 0, max: 100, integer: false },
  realTurnoverMinPct: { min: 0, max: 100, integer: false },
  hardVolumeRatioMin: { min: 0, max: 100, integer: false },
  ma5OverMa20Min: { min: 0, max: 100, integer: false },
  vol5OverVol20Min: { min: 0, max: 100, integer: false },
  totalCapMinYi: { min: 0, max: 100000, integer: false },
  noLimitUpWithinDays: { min: 0, max: 120, integer: true },
  profitRequire: { min: 0, max: 3, integer: true },
  sentimentBuyMinScore: { min: 0, max: 100, integer: true },
  sentimentExitScore: { min: 0, max: 100, integer: true },
  hotRankTopN: { min: 0, max: 100, integer: true },
  hotnessScore: { min: 0, max: 100, integer: true },
  sentimentChangeMaxPct: { min: 0, max: 20, integer: false },
  newsScoreMax: { min: 0, max: 100, integer: true },
  newsPenaltyMax: { min: 0, max: 100, integer: true },
  newsMinScore: { min: -100, max: 0, integer: false },
  newsMinConfidence: { min: 0, max: 1, integer: false },
  newsRefreshMin: { min: 10, max: 600, integer: true }
}

const NUMERIC_KEYS = Object.keys(STRATEGY_RULES) as NumericParamKey[]

function ruleOf(key: NumericParamKey): NumberRule {
  return STRATEGY_RULES[key]
}

interface AdvancedField {
  key: NumericParamKey
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

/** 严格过滤区的 9 个数值项：与开关、下拉、时间窗一起单独渲染在「严格过滤（硬条件）」分组内 */
const STRICT_NUMERIC_FIELDS: AdvancedField[] = [
  {
    key: 'lowRatioMax',
    label: '现价/一年低点上限',
    step: 0.1,
    unit: '倍',
    hint: '0=不限；如 1.3 表示现价不超过一年内最低价的 1.3 倍'
  },
  {
    key: 'openTurnoverMinPct',
    label: '早盘换手率下限',
    step: 0.5,
    unit: '%',
    hint: '早盘窗口内成交量 ÷ 自由流通股 × 100'
  },
  {
    key: 'auctionTurnoverMinPct',
    label: '集合竞价换手率(%)',
    step: 0.1,
    unit: '%',
    hint: '9:25 竞价量 ÷ 自由流通股 × 100，0=不限'
  },
  {
    key: 'realTurnoverMinPct',
    label: '全天换手率下限',
    step: 0.5,
    unit: '%',
    hint: '全天成交量 ÷ 自由流通股 × 100'
  },
  { key: 'hardVolumeRatioMin', label: '量比下限（硬）', step: 0.1, unit: '倍' },
  {
    key: 'ma5OverMa20Min',
    label: '5 日/20 日均价下限',
    step: 0.01,
    unit: '倍',
    hint: '5 日均价 ÷ 20 日均价 的下限'
  },
  {
    key: 'vol5OverVol20Min',
    label: '5 日/20 日均量下限',
    step: 0.01,
    unit: '倍',
    hint: '5 日均量 ÷ 20 日均量 的下限'
  },
  { key: 'totalCapMinYi', label: '总市值下限', step: 10, unit: '亿' },
  {
    key: 'noLimitUpWithinDays',
    label: '近期不得涨停',
    step: 1,
    unit: '天',
    hint: '近 N 个交易日内不得涨停，0=不限'
  }
]

/** 情绪维度区固定 7 项：5 个数值 + 2 个开关（总开关、热度须上升） */
const SENTIMENT_NUMERIC_FIELDS: AdvancedField[] = [
  {
    key: 'sentimentBuyMinScore',
    label: '情绪开仓线',
    step: 5,
    unit: '分',
    hint: '市场情绪跌破该分就不开新仓（0=不限）'
  },
  {
    key: 'sentimentExitScore',
    label: '情绪清仓线',
    step: 5,
    unit: '分',
    hint: '跌破该分无条件清仓（0=不限；高于开仓线会被压平）'
  },
  {
    key: 'hotRankTopN',
    label: '只要热榜前 N',
    step: 5,
    unit: '名',
    hint: '候选必须来自热榜前 N 名，0=不限'
  },
  {
    key: 'hotnessScore',
    label: '热榜名次加分',
    step: 1,
    unit: '分',
    hint: '第 1 名拿满分，名次越靠后越少'
  },
  {
    key: 'sentimentChangeMaxPct',
    label: '情绪高涨时涨幅上限放宽到',
    step: 0.5,
    unit: '%',
    hint: '情绪越热越接近该上限，情绪差时按 60% 收紧；0=不自适应'
  }
]

/** 交易时段区固定 3 项：次日开盘强制卖出开关 + 自动买入时间窗起止 */
const TRADE_WINDOW_FIELD_COUNT = 3
/** 质量因子区固定 10 项：4 个权重 + 换手率区间 2 + 流通市值区间 2 + 均价线占比 1 + 涨停回看 1 */
const QUALITY_FIELD_COUNT = 10
/** 涨停结构区固定 2 项：首板加分 + 连板高度上限 */
const BOARD_FIELD_COUNT = 2
/** 新高与量能区固定 4 项：两年新高权重 + 回看窗口 + 缩量上涨权重 + 缩量阈值 */
const NEW_HIGH_FIELD_COUNT = 4
/** 严格过滤区固定 17 项：8 个数值项 + 6 个开关 + profitRequire 下拉 + 早盘窗口起止 2 个时间 */
const STRICT_FILTER_FIELD_COUNT = STRICT_NUMERIC_FIELDS.length + 9
/** 情绪维度区固定 7 项：5 个数值 + 总开关 + 「热度须上升」开关 */
const SENTIMENT_FIELD_COUNT = SENTIMENT_NUMERIC_FIELDS.length + 2

/** 新闻情绪区固定 7 项：5 个数值 + 总开关 + 硬过滤开关 */
const NEWS_NUMERIC_FIELDS: AdvancedField[] = [
  {
    key: 'newsScoreMax',
    label: '利好加分上限',
    step: 1,
    unit: '分',
    hint: '新闻明确利好的最大加分（作用于总分）'
  },
  {
    key: 'newsPenaltyMax',
    label: '利空扣分上限',
    step: 1,
    unit: '分',
    hint: '新闻明确利空的最大扣分'
  },
  {
    key: 'newsMinScore',
    label: '硬过滤阈值',
    step: 5,
    unit: '分',
    hint: '开启硬过滤后，新闻分低于该值且置信度达标就剔除'
  },
  {
    key: 'newsMinConfidence',
    label: '硬过滤最低置信度',
    step: 0.1,
    unit: '',
    hint: '传闻（低置信）不触发硬过滤，避免小作文误杀'
  },
  {
    key: 'newsRefreshMin',
    label: '缓存新鲜度',
    step: 10,
    unit: '分钟',
    hint: '新闻分超过该时长视为过期，选股按无新闻处理'
  }
]
const NEWS_FIELD_COUNT = NEWS_NUMERIC_FIELDS.length + 2

/** 表单顶部固定的 4 项基础参数：最低分、止损、止盈、最长持有天数 */
const BASIC_FIELD_COUNT = 4
/** 高级规则面板内的可编辑参数数（不含顶部 4 项基础参数），用于折叠按钮上的「共 N 项」 */
const ADVANCED_PANEL_FIELD_COUNT =
  ADVANCED_FIELDS.length +
  TRADE_WINDOW_FIELD_COUNT +
  QUALITY_FIELD_COUNT +
  BOARD_FIELD_COUNT +
  NEW_HIGH_FIELD_COUNT +
  STRICT_FILTER_FIELD_COUNT +
  SENTIMENT_FIELD_COUNT +
  NEWS_FIELD_COUNT
/** 策略参数总数（含顶部 4 项基础参数），与后端 PaperStrategyParams 的字段一一对应 */
const PARAM_FIELD_COUNT = BASIC_FIELD_COUNT + ADVANCED_PANEL_FIELD_COUNT

/** 参与「权重合计」提示的因子权重字段（用户借此判断分数上限，需与实际加分项保持一致） */
const WEIGHT_KEYS: NumericParamKey[] = [
  'maTrendScore',
  'ma20Score',
  'macdScore',
  'volumeRatioScore',
  'moneyFlowScore',
  'moneyFlowPctScore',
  'rsiScore',
  'changeScore',
  'hotRankScore',
  'turnoverScore',
  'floatCapScore',
  'vwapScore',
  'limitUpScore',
  'newHighScore',
  'shrinkVolumeScore',
  'firstBoardScore',
  'hotnessScore',
  'newsScoreMax'
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

// —— 新闻情绪（候选/持仓徽标 + 新闻面板） ——
const newsMap = ref<Record<string, StockNewsItem>>({})
const newsOpen = ref(false)
const newsLoading = ref(false)
const newsError = ref('')
const newsDetail = ref<StockNewsDetail | null>(null)
let newsSeq = 0
let newsDetailSeq = 0
/** 新闻分 60 秒内复用上次结果（后端本身 30 分钟才更新一轮）。 */
const NEWS_TTL_MS = 60_000
let newsLoadedAt = 0
let newsLoadedKey = ''
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
const refreshSec = computed(() =>
  Math.round(refreshIntervalMs(marketState.value, false, quotesRefreshMs.value) / 1000)
)

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
/** 市场情绪快照：只在启用情绪维度时拉取 */
const sentiment = ref<MarketSentiment | null>(null)
const strategyForm = ref<StrategyForm>({ ...DEFAULT_STRATEGY_PARAMS })
const strategyBusy = ref(false)
const strategyError = ref('')
const autoTradeConfirm = ref(false)
const autoTradeBusy = ref(false)
const advancedOpen = ref(false)
let strategyLoaded = false

const selections = ref<PaperSignal[]>([])
const selectionsLoading = ref(false)
/**
 * 正在重新选股的账户集合。之前是一个全局布尔，切换账户时不清空，
 * 会导致「一个账户选股中，其他账户的按钮也一直禁用」。按账户记住才会互不影响。
 */
const selectionsRefreshingIds = ref<number[]>([])
const selectionsRefreshing = computed(() => {
  const id = currentAccountId.value
  return id != null && selectionsRefreshingIds.value.includes(id)
})
const selectionsError = ref('')

/** 今日选股时间：同一次选股插入的候选时间相同，取最大的一条即可 */
const selectionsTime = computed(() => {
  let latest = ''
  for (const item of selections.value) {
    if (item.createTime && item.createTime > latest) {
      latest = item.createTime
    }
  }
  return latest
})

const signals = ref<PaperSignal[]>([])
const signalsLoading = ref(false)
const signalsError = ref('')

// —— P4：规则模板库 ——
const rules = ref<PaperRule[]>([])
const rulesLoading = ref(false)
const rulesError = ref('')
const selectedRuleId = ref<number | null>(null)
const applyingRule = ref(false)
const ruleSaving = ref(false)
const ruleDeleting = ref(false)
const ruleDeleteArmed = ref(false)
const saveRuleOpen = ref(false)
const saveRuleName = ref('')
const saveRuleError = ref('')

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
const canDeleteAccount = computed(() => accounts.value.length > 1)
const weightSum = computed(() =>
  WEIGHT_KEYS.reduce((sum, key) => sum + (Number(strategyForm.value[key]) || 0), 0)
)
/** 自动买入时间窗起止非法（start >= end）时仅提示：后端会忽略该限制，不阻止保存 */
const buyWindowInvalid = computed(() => {
  const start = strategyForm.value.buyStartTime
  const end = strategyForm.value.buyEndTime
  return TIME_PATTERN.test(start) && TIME_PATTERN.test(end) && start >= end
})
/** 早盘量窗口非法（格式错误或 start >= end）时仅提示：后端会当作窗口无效不限制 */
const openTurnoverInvalid = computed(() => {
  const start = strategyForm.value.openTurnoverStart
  const end = strategyForm.value.openTurnoverEnd
  if (!TIME_PATTERN.test(start) || !TIME_PATTERN.test(end)) return true
  return start >= end
})
/** 情绪清仓线高于开仓线：后端保存时会把清仓线压平，这里提前告知，免得用户以为配置生效了 */
const sentimentScoreInvalid = computed(() => {
  const buy = Number(strategyForm.value.sentimentBuyMinScore) || 0
  const exit = Number(strategyForm.value.sentimentExitScore) || 0
  return buy > 0 && exit > buy
})
/** 当前下拉选中的规则模板 */
const selectedRule = computed(
  () => rules.value.find((item) => item.id === selectedRuleId.value) ?? null
)
/** 与当前账户生效参数一致的那套模板 */
const activeRule = computed(() => rules.value.find((item) => item.active) ?? null)
const canSaveRule = computed(() => !!strategy.value && rules.value.length < MAX_RULES && !ruleSaving.value)

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
    void loadNews(epoch)
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
  const delay = refreshIntervalMs(marketState.value, false, quotesRefreshMs.value)
  // 设置里选了「不自动刷新」：不排下一次，页面上的手动刷新按钮仍然有效
  if (delay <= 0) return
  refreshTimer = setTimeout(() => {
    void refreshAll(true)
  }, delay)
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

/** params 里的开关字段：兼容 true/false、1/0、"true"/"1" 等写法 */
function toBoolean(value: unknown, fallback = DEFAULT_SELL_AT_OPEN): boolean {
  if (typeof value === 'boolean') return value
  if (typeof value === 'number') return value !== 0
  if (typeof value === 'string') return value.trim() === 'true' || value.trim() === '1'
  return fallback
}

/** params 里的时间字段：不是合法 HH:mm 时回退默认值 */
function toTimeText(value: unknown, fallback: string): string {
  if (typeof value !== 'string') return fallback
  const text = value.trim()
  return TIME_PATTERN.test(text) ? text : fallback
}

/** 后端 params 是 JSON 文本；解析失败或字段缺失时回退默认值 */
function normalizeStrategyParams(text: string | null | undefined): StrategyForm {
  const result: StrategyForm = { ...DEFAULT_STRATEGY_PARAMS }
  if (!text) return result
  try {
    const node = JSON.parse(text) as Record<string, unknown>
    NUMERIC_KEYS.forEach((key) => {
      const value = node[key]
      if (typeof value === 'number' && Number.isFinite(value)) result[key] = value
    })
    result.sellAtOpen = toBoolean(node.sellAtOpen)
    result.buyStartTime = toTimeText(node.buyStartTime, DEFAULT_BUY_START_TIME)
    result.buyEndTime = toTimeText(node.buyEndTime, DEFAULT_BUY_END_TIME)
    result.strictFilters = toBoolean(node.strictFilters)
    result.sortByLowRatioAsc = toBoolean(node.sortByLowRatioAsc)
    result.requireAboveOpen = toBoolean(node.requireAboveOpen)
    result.requireAboveVwap = toBoolean(node.requireAboveVwap)
    result.requireHolderShrink = toBoolean(node.requireHolderShrink)
    result.affordableOnly = toBoolean(node.affordableOnly)
    result.sentimentEnabled = toBoolean(node.sentimentEnabled)
    result.requireHotRankUp = toBoolean(node.requireHotRankUp)
    result.newsEnabled = toBoolean(node.newsEnabled, true)
    result.newsHardFilter = toBoolean(node.newsHardFilter)
    // 与后端默认值一致：旧规则里没有这个字段时视为开启
    result.preMarketPreselect = toBoolean(node.preMarketPreselect, true)
    result.openTurnoverStart = toTimeText(
      node.openTurnoverStart,
      DEFAULT_OPEN_TURNOVER_START
    )
    result.openTurnoverEnd = toTimeText(node.openTurnoverEnd, DEFAULT_OPEN_TURNOVER_END)
  } catch {
    return { ...DEFAULT_STRATEGY_PARAMS }
  }
  return result
}

/** 首次拉到策略时填充表单；之后定时刷新不覆盖用户正在改的输入 */
function applyStrategyForm() {
  strategyForm.value = normalizeStrategyParams(strategy.value?.params)
}

function clampRuleValue(key: NumericParamKey): number {
  const rule = STRATEGY_RULES[key]
  const fallback = DEFAULT_STRATEGY_PARAMS[key]
  let value = Number(strategyForm.value[key])
  if (!Number.isFinite(value)) value = fallback
  value = Math.min(rule.max, Math.max(rule.min, value))
  return rule.integer ? Math.round(value) : Math.round(value * 100) / 100
}

/** 保存前把全部字段钳制到合法区间，并修正区间类参数的上下限关系 */
function clampStrategyForm(): StrategyForm {
  const result: StrategyForm = { ...DEFAULT_STRATEGY_PARAMS }
  NUMERIC_KEYS.forEach((key) => {
    result[key] = clampRuleValue(key)
  })
  if (result.rsiMax < result.rsiMin) result.rsiMax = result.rsiMin
  if (result.changeMaxPct < result.changeMinPct) result.changeMaxPct = result.changeMinPct
  if (result.turnoverMaxPct < result.turnoverMinPct) {
    result.turnoverMaxPct = result.turnoverMinPct
  }
  if (result.floatCapMaxYi < result.floatCapMinYi) result.floatCapMaxYi = result.floatCapMinYi
  result.sellAtOpen = strategyForm.value.sellAtOpen === true
  result.buyStartTime = toTimeText(strategyForm.value.buyStartTime, DEFAULT_BUY_START_TIME)
  result.buyEndTime = toTimeText(strategyForm.value.buyEndTime, DEFAULT_BUY_END_TIME)
  result.strictFilters = strategyForm.value.strictFilters === true
  result.sortByLowRatioAsc = strategyForm.value.sortByLowRatioAsc === true
  result.requireAboveOpen = strategyForm.value.requireAboveOpen === true
  result.requireAboveVwap = strategyForm.value.requireAboveVwap === true
  result.requireHolderShrink = strategyForm.value.requireHolderShrink === true
  result.affordableOnly = strategyForm.value.affordableOnly === true
  result.sentimentEnabled = strategyForm.value.sentimentEnabled === true
  result.requireHotRankUp = strategyForm.value.requireHotRankUp === true
  result.newsEnabled = strategyForm.value.newsEnabled === true
  result.newsHardFilter = strategyForm.value.newsHardFilter === true
  result.preMarketPreselect = strategyForm.value.preMarketPreselect === true
  result.openTurnoverStart = toTimeText(
    strategyForm.value.openTurnoverStart,
    DEFAULT_OPEN_TURNOVER_START
  )
  result.openTurnoverEnd = toTimeText(
    strategyForm.value.openTurnoverEnd,
    DEFAULT_OPEN_TURNOVER_END
  )
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

/**
 * 拉取市场情绪快照。
 *
 * <p>只在用户开了情绪维度时才请求：没开的人不需要这条每分钟一次的外部调用，
 * 而且上游失败时页面上多一个「取不到数据」的提示反而添乱。</p>
 */
async function loadSentiment() {
  if (!strategyForm.value.sentimentEnabled) {
    sentiment.value = null
    return
  }
  try {
    sentiment.value = await fetchMarketSentiment()
  } catch {
    // 情绪取不到不影响模拟盘本身，静默降级：面板显示空态，后端也不会因此拦单
    sentiment.value = null
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
    void loadNews(epoch)
  } catch (err) {
    if (epoch === accountEpoch) selectionsError.value = errText(err)
  } finally {
    if (epoch === accountEpoch) selectionsLoading.value = false
  }
}

/**
 * 拉取候选与持仓的今日新闻分；失败静默降级（新闻只是展示增强）。
 *
 * <p>新闻每 30 分钟才刷新一轮，而行情轮询是秒级，所以同一批代码 60 秒内复用上次结果；
 * 候选/持仓代码变化时会立即重拉。</p>
 */
async function loadNews(epoch = accountEpoch, force = false) {
  const codes = Array.from(
    new Set([
      ...selections.value.map((item) => item.code),
      ...positions.value.map((item) => item.code)
    ].filter(Boolean))
  )
  if (!codes.length) {
    newsMap.value = {}
    newsLoadedKey = ''
    return
  }
  const key = codes.join(',')
  if (!force && key === newsLoadedKey && Date.now() - newsLoadedAt < NEWS_TTL_MS) return
  const seq = ++newsSeq
  try {
    const items = (await fetchStockNewsBatch(codes)) ?? []
    if (seq !== newsSeq || epoch !== accountEpoch) return
    const map: Record<string, StockNewsItem> = {}
    for (const item of items) map[item.code] = item
    newsMap.value = map
    newsLoadedKey = key
    newsLoadedAt = Date.now()
  } catch {
    // 新闻接口失败不影响模拟盘主流程，保留上一次展示
  }
}

function newsOf(code: string): StockNewsItem | undefined {
  return newsMap.value[code]
}

function newsTone(score: number): string {
  return score >= 20 ? 'pos' : score <= -20 ? 'neg' : 'neutral'
}

function newsText(item: StockNewsItem): string {
  return `${item.label} ${item.score > 0 ? '+' : ''}${item.score}`
}

function newsScoreOf(code: string): number {
  return newsMap.value[code]?.score ?? 0
}

function newsTextOf(code: string): string {
  const item = newsMap.value[code]
  return item ? newsText(item) : ''
}

function newsSummaryOf(code: string): string {
  return newsMap.value[code]?.summary || '查看新闻来源'
}

const newsDetailText = computed(() => (newsDetail.value ? newsText(newsDetail.value) : ''))
const newsDetailTone = computed(() => newsTone(newsDetail.value?.score ?? 0))
const newsDetailConfidence = computed(() =>
  Math.round((newsDetail.value?.confidence ?? 0) * 100)
)

/** 强利空只有置信度足够时才给建议，避免一条小作文让人砍仓。 */
const newsAdvice = computed(() => {
  const detail = newsDetail.value
  if (!detail || detail.confidence < 0.6) return ''
  if (detail.score > -40) return ''
  const held = positions.value.some((item) => item.code === detail.code)
  return held
    ? '利空信号较强：已持仓建议评估减仓，必要时清仓。'
    : '利空信号较强：空仓不建议参与。'
})

async function openNews(code: string) {
  const seq = ++newsDetailSeq
  newsOpen.value = true
  newsLoading.value = true
  newsError.value = ''
  newsDetail.value = null
  try {
    const detail = (await fetchStockNewsDetail(code)) ?? null
    if (seq !== newsDetailSeq) return
    newsDetail.value = detail
  } catch (err) {
    if (seq === newsDetailSeq) newsError.value = errText(err)
  } finally {
    if (seq === newsDetailSeq) newsLoading.value = false
  }
}

function closeNews() {
  newsOpen.value = false
  newsDetailSeq += 1
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
  await Promise.all([
    loadEngine(),
    loadStrategies(),
    loadSelections(),
    loadSignals(),
    loadSentiment(),
    // 定时刷新保留用户当前的下拉选择；切账户后该值为空，会自动选中「当前生效」的一套
    loadRules(selectedRuleId.value ?? 'active')
  ])
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
  const base = refreshIntervalMs(state, false, quotesRefreshMs.value)
  // 用户把自动刷新关了：慢数据也没必要偷偷轮询，否则「关了自动刷新」等于没关
  if (base <= 0) return
  const interval = Math.max(SECONDARY_MIN_INTERVAL_MS, base)
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
    await Promise.all([loadEngine(), loadSignals(), loadSentiment()])
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
  const accountId = currentAccountId.value
  if (accountId == null || selectionsRefreshingIds.value.includes(accountId)) return
  selectionsRefreshingIds.value = [...selectionsRefreshingIds.value, accountId]
  selectionsError.value = ''
  const epoch = accountEpoch
  try {
    const data = (await refreshPaperSelections(accountId)) ?? []
    // 请求期间用户可能已经切走账户，旧账户的结果不能覆盖当前账户
    if (epoch !== accountEpoch) return
    selections.value = data
    // 手动选股后强制刷新，避免 60 秒节流挡住刚扫出来的新闻分（同一批代码会命中缓存）
    void loadNews(epoch, true)
    flash(
      selections.value.length
        ? `选股完成，共 ${selections.value.length} 只候选`
        : '选股完成，暂无符合条件的股票'
    )
    // 重选股时后端会顺带给持仓重新打分，这里拉一次持仓把最新评分显示出来
    await Promise.all([loadEngine(), loadSignals(), loadPositions()])
  } catch (err) {
    if (epoch === accountEpoch) {
      selectionsError.value = errText(err)
      flash(selectionsError.value, 'error')
    }
  } finally {
    selectionsRefreshingIds.value = selectionsRefreshingIds.value.filter((id) => id !== accountId)
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

/** 持仓 / 今日选股的股票行：点击跳个股详情页（/stock/:code） */
function goStockDetail(code: string, event?: MouseEvent) {
  // 刚拖选完文本（复制理由/代码）松开鼠标也算 click，这种情况不跳转
  if (window.getSelection()?.toString()) return
  if (event && (event.target as HTMLElement | null)?.closest('button, a, input, select, textarea')) return
  if (code) {
    router.push(`/stock/${code}`)
  }
}

// —— P4：规则模板库 ——
/** 拉取模板列表；prefer 为 id 时优先选中该模板，否则选「当前生效」的一套 */
async function loadRules(prefer: 'active' | number = 'active') {
  const epoch = accountEpoch
  rulesLoading.value = true
  try {
    const list = (await fetchPaperRules(activeAccountId())) ?? []
    if (epoch !== accountEpoch) return
    rules.value = list
    rulesError.value = ''
    syncRuleSelection(prefer)
  } catch (err) {
    if (epoch !== accountEpoch) return
    rulesError.value = errText(err)
    rules.value = []
    selectedRuleId.value = null
  } finally {
    if (epoch === accountEpoch) rulesLoading.value = false
  }
}

function syncRuleSelection(prefer: 'active' | number) {
  if (!rules.value.length) {
    selectedRuleId.value = null
    return
  }
  if (prefer !== 'active') {
    const matched = rules.value.find((item) => item.id === prefer)
    if (matched) {
      selectedRuleId.value = matched.id
      return
    }
  }
  const active = rules.value.find((item) => item.active)
  selectedRuleId.value = (active ?? rules.value[0]).id
}

/** 应用模板：参数复制进当前账户策略，之后账户仍可单独微调 */
async function applyRule() {
  const rule = selectedRule.value
  const accountId = activeAccountId()
  if (!rule || accountId == null || applyingRule.value) return
  applyingRule.value = true
  rulesError.value = ''
  ruleDeleteArmed.value = false
  try {
    const updated = await applyPaperRule(accountId, rule.id)
    replaceStrategy(updated)
    strategyLoaded = true
    strategyForm.value = normalizeStrategyParams(updated.params)
    flash(`已应用模板「${rule.name}」`)
    await Promise.all([loadRules('active'), loadEngine(), loadSignals(), loadStrategies()])
  } catch (err) {
    rulesError.value = errText(err)
    flash(rulesError.value, 'error')
  } finally {
    applyingRule.value = false
  }
}

/** 名称自增，跳过已占用的「规则 N」 */
function suggestRuleName(): string {
  for (let index = rules.value.length + 1; index <= MAX_RULES + 2; index += 1) {
    const name = `规则 ${index}`
    if (!rules.value.some((item) => item.name === name)) return name
  }
  return `规则 ${rules.value.length + 1}`
}

function openSaveRule() {
  if (ruleSaving.value || rules.value.length >= MAX_RULES) return
  saveRuleError.value = ''
  saveRuleName.value = suggestRuleName()
  saveRuleOpen.value = true
}

function closeSaveRule() {
  if (ruleSaving.value) return
  saveRuleOpen.value = false
}

async function submitSaveRule() {
  if (ruleSaving.value) return
  const name = saveRuleName.value.trim()
  if (!name) {
    saveRuleError.value = '请填写模板名称'
    return
  }
  if (name.length > RULE_NAME_MAX) {
    saveRuleError.value = `模板名称最多 ${RULE_NAME_MAX} 个字`
    return
  }
  // 另存前先钳制，保证存下去的参数和表单一致可用
  const params = clampStrategyForm()
  strategyForm.value = params
  ruleSaving.value = true
  saveRuleError.value = ''
  try {
    const created = await createPaperRule({ name, params: JSON.stringify(params) })
    saveRuleOpen.value = false
    flash(`模板「${created.name}」已保存`)
    await loadRules(created.id)
  } catch (err) {
    saveRuleError.value = errText(err)
  } finally {
    ruleSaving.value = false
  }
}

function armDeleteRule() {
  if (!selectedRule.value || ruleDeleting.value) return
  ruleDeleteArmed.value = true
}

function cancelDeleteRule() {
  ruleDeleteArmed.value = false
}

/** 删除模板；后端没有下限，删完再拉列表会自动补一套基础规则 */
async function confirmDeleteRule() {
  const rule = selectedRule.value
  if (!rule || ruleDeleting.value) return
  ruleDeleting.value = true
  rulesError.value = ''
  try {
    await deletePaperRule(rule.id)
    ruleDeleteArmed.value = false
    flash(`模板「${rule.name}」已删除`)
    await loadRules('active')
  } catch (err) {
    rulesError.value = errText(err)
    flash(rulesError.value, 'error')
  } finally {
    ruleDeleting.value = false
  }
}

// —— P3：账户管理 ——
/** 切账户/增删账户后清空上一账户的数据，避免闪现旧账户内容 */
function resetAccountScopedState() {
  accountEpoch += 1
  account.value = null
  positions.value = []
  newsMap.value = {}
  newsLoadedKey = ''
  newsLoadedAt = 0
  newsDetailSeq += 1
  newsOpen.value = false
  newsDetail.value = null
  nav.value = []
  orders.value = []
  ordersTotal.value = 0
  trades.value = []
  tradesTotal.value = 0
  engine.value = null
  strategies.value = []
  selections.value = []
  signals.value = []
  rules.value = []
  error.value = ''
  engineError.value = ''
  strategyError.value = ''
  selectionsError.value = ''
  signalsError.value = ''
  rulesError.value = ''
  rulesLoading.value = false
  applyingRule.value = false
  ruleSaving.value = false
  ruleDeleting.value = false
  selectedRuleId.value = null
  ruleDeleteArmed.value = false
  saveRuleOpen.value = false
  saveRuleName.value = ''
  saveRuleError.value = ''
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
  const used = new Set(accounts.value.map((item) => item.name))
  for (let index = accounts.value.length + 1; index < accounts.value.length + 200; index += 1) {
    const name = `模拟盘 ${index}`
    if (!used.has(name)) return name
  }
  return `模拟盘 ${accounts.value.length + 1}`
}

function openCreateAccount() {
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

/* 持仓 / 今日选股的股票行：整行可点击跳个股详情（router-link 呈现为无下划线文本） */
.row-link {
  cursor: pointer;
}

.cell-name--link {
  text-decoration: none;
  border-radius: var(--r-sm);
  transition: color 0.12s var(--ease);
}

.cell-name--link:hover .cell-name__title {
  color: var(--c-brand);
  text-decoration: underline;
  text-underline-offset: 3px;
}

.cell-name--link:focus-visible {
  outline: 2px solid var(--c-brand);
  outline-offset: 2px;
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
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--s-4);
  margin-bottom: var(--s-6);
  padding-bottom: var(--s-4);
  border-bottom: 1px solid var(--c-border);
}

/* 账户数量不限，多了自动换行，不做横向滚动（隐藏滚动条会让人不知道能翻） */
.account-bar__list {
  display: flex;
  flex: 1;
  flex-wrap: wrap;
  gap: var(--s-2);
  min-width: 0;
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
  margin-left: auto;
  align-self: flex-start;
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

/* ---------- P5：交易时段（隔夜策略） ---------- */
.trade-window__check {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  cursor: pointer;
}

.trade-window__check input {
  width: 16px;
  height: 16px;
  margin: 0;
  accent-color: var(--c-brand);
  cursor: pointer;
}

.trade-window__label {
  font-size: var(--t-sm);
  font-weight: 560;
  color: var(--c-text);
}

.trade-window__row {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  gap: var(--s-3);
}

.trade-window__field {
  flex: 0 1 180px;
  min-width: 0;
}

.trade-window__field .field__input {
  height: 40px;
  font-family: var(--f-mono);
}

.trade-window__dash {
  padding-bottom: 10px;
  font-size: var(--t-xs);
  color: var(--c-text-faint);
}

.trade-window__hint {
  width: 100%;
  font-size: var(--t-xs);
  line-height: 1.7;
  color: var(--c-text-muted);
}

.trade-window__warn {
  margin: 0;
  font-size: var(--t-xs);
  line-height: 1.7;
  color: var(--c-accent);
}

/* ---------- P9：严格过滤（硬条件） ---------- */
.strict__checks {
  display: grid;
  gap: var(--s-2);
  margin-top: var(--s-1);
  padding-top: var(--s-3);
  border-top: 1px dashed var(--c-border);
}

/* ---------- 情绪维度：实时温度计 ---------- */
.sentiment-card {
  display: grid;
  gap: 4px;
  margin: var(--s-1) 0 var(--s-3);
  padding: var(--s-3) var(--s-4);
  background: var(--c-surface-sunken);
  border: 1px solid var(--c-border);
  border-radius: var(--r);
}

.sentiment-card__main {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 8px;
}

.sentiment-card__score {
  font-family: var(--f-mono);
  font-size: var(--t-lg);
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  color: var(--c-text);
}

.sentiment-card__level {
  font-size: var(--t-sm);
  color: var(--c-text-muted);
}

.sentiment-card__tag {
  padding: 1px 6px;
  font-size: var(--t-xs);
  color: var(--c-text-faint);
  background: var(--c-surface);
  border: 1px solid var(--c-border);
  border-radius: var(--r-full);
}

.sentiment-card__line {
  margin: 0;
  font-size: var(--t-xs);
  line-height: 1.7;
  color: var(--c-text-muted);
  font-variant-numeric: tabular-nums;
}

.sentiment-card__hint {
  margin: 0;
  font-size: var(--t-xs);
  line-height: 1.7;
  color: var(--c-text-faint);
}

/* ---------- P6：质量因子 ---------- */
.quality__rows {
  display: flex;
  flex-direction: column;
  gap: var(--s-3);
}

.quality__row {
  display: grid;
  grid-template-columns: 132px repeat(3, minmax(0, 1fr));
  gap: var(--s-3);
  align-items: end;
}

.quality__name {
  padding-bottom: 10px;
  font-size: var(--t-sm);
  font-weight: 560;
  color: var(--c-text);
}

.quality__field {
  min-width: 0;
}

.quality__field .field__input {
  height: 40px;
}

.quality__hint {
  margin: 0;
  font-size: var(--t-xs);
  line-height: 1.7;
  color: var(--c-text-muted);
}

/* ---------- P4：规则模板库 ---------- */
.rule-bar {
  display: flex;
  flex-direction: column;
  gap: var(--s-3);
  margin-top: var(--s-2);
  padding-top: var(--s-4);
  border-top: 1px solid var(--c-border);
}

.rule-bar__row {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  gap: var(--s-4);
}

.rule-bar__select {
  display: flex;
  flex: 1 1 260px;
  flex-direction: column;
  gap: 6px;
  min-width: 0;
}

.rule-bar__select .field__input {
  height: 40px;
  padding-right: 10px;
  font-family: var(--f-sans);
  cursor: pointer;
}

.rule-bar__actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--s-2);
}

.rule-bar__actions .btn {
  min-height: 40px;
  padding: 0 var(--s-4);
  font-size: var(--t-sm);
}

.rule-bar__danger {
  border-color: color-mix(in srgb, var(--stock-up) 45%, var(--c-border));
  color: var(--stock-up);
}

.rule-bar__danger:hover:not(:disabled) {
  border-color: var(--stock-up);
  background: color-mix(in srgb, var(--stock-up) 8%, transparent);
}

.rule-bar__error {
  margin: 0;
  font-size: var(--t-xs);
  color: var(--stock-up);
}

.rule-bar__active {
  margin: 0;
  font-size: var(--t-xs);
  color: var(--c-text-muted);
}

.rule-bar__confirm {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--s-3);
  padding: var(--s-3) var(--s-4);
  border: 1px solid color-mix(in srgb, var(--stock-up) 30%, var(--c-border));
  border-radius: var(--r);
  background: color-mix(in srgb, var(--stock-up) 6%, transparent);
  font-size: var(--t-xs);
  color: var(--c-text-secondary);
}

.rule-bar__confirm .btn {
  min-height: 36px;
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

  .quality__row {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .quality__name {
    padding-bottom: 0;
  }

  .rule-bar__row {
    flex-direction: column;
    align-items: stretch;
  }

  .rule-bar__select {
    flex-basis: auto;
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

  .trade-window__row {
    flex-direction: column;
    align-items: stretch;
  }

  .trade-window__field {
    flex: 1 1 auto;
  }

  .trade-window__dash {
    display: none;
  }

  .quality__row {
    grid-template-columns: minmax(0, 1fr);
    gap: var(--s-2);
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

  .rule-bar__actions {
    justify-content: flex-start;
  }

  .rule-bar__confirm {
    align-items: flex-start;
    flex-direction: column;
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

/* ---------- 新闻情绪 ---------- */
.news-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 64px;
  padding: 2px 8px;
  border: 1px solid var(--c-border);
  border-radius: 999px;
  background: var(--c-surface-sunken);
  color: var(--c-text-muted);
  font-family: var(--f-mono);
  font-size: var(--t-xs);
  font-weight: 600;
  line-height: 1.6;
  white-space: nowrap;
  cursor: pointer;
  transition:
    border-color 0.15s var(--ease-out),
    color 0.15s var(--ease-out);
}

.news-badge:hover {
  border-color: var(--c-border-strong);
  color: var(--c-text);
}

.news-badge--pos {
  border-color: color-mix(in srgb, var(--stock-up) 35%, transparent);
  background: color-mix(in srgb, var(--stock-up) 10%, transparent);
  color: var(--stock-up);
}

.news-badge--neg {
  border-color: color-mix(in srgb, var(--stock-down) 35%, transparent);
  background: color-mix(in srgb, var(--stock-down) 10%, transparent);
  color: var(--stock-down);
}

.news-badge--neutral {
  color: var(--c-text-muted);
}

.news-modal__box {
  width: min(640px, 100%);
}

.news-panel {
  display: grid;
  gap: var(--s-3);
}

.news-panel__summary {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--s-2);
}

.news-panel__text {
  margin: 0;
  font-size: var(--t-sm);
  line-height: 1.75;
  color: var(--c-text);
}

.news-panel__advice {
  margin: 0;
  padding: var(--s-2) var(--s-3);
  border-left: 3px solid var(--c-accent);
  border-radius: 0 var(--r) var(--r) 0;
  background: color-mix(in srgb, var(--c-accent) 8%, transparent);
  font-size: var(--t-sm);
  line-height: 1.7;
  color: var(--c-accent);
}

.news-list {
  display: grid;
  gap: 0;
  margin: 0;
  padding: 0;
  list-style: none;
  border: 1px solid var(--c-border);
  border-radius: var(--r);
  overflow: hidden;
}

.news-list__item {
  display: flex;
  gap: var(--s-2);
  align-items: flex-start;
  padding: var(--s-2) var(--s-3);
  border-bottom: 1px solid var(--c-border);
}

.news-list__item:last-child {
  border-bottom: 0;
}

.news-list__scope {
  flex: none;
  margin-top: 1px;
  padding: 0 4px;
  border-radius: 3px;
  background: var(--c-surface-sunken);
  color: var(--c-text-muted);
  font-size: var(--t-xs);
  line-height: 1.6;
}

.news-list__scope.is-sector {
  background: color-mix(in srgb, var(--c-accent) 12%, transparent);
  color: var(--c-accent);
}

.news-list__body {
  display: grid;
  gap: 2px;
  min-width: 0;
}

.news-list__title {
  font-size: var(--t-sm);
  line-height: 1.5;
  color: var(--c-text);
  word-break: break-word;
}

.news-list__meta {
  font-size: var(--t-xs);
  color: var(--c-text-faint);
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

  .cell-name--link {
    transition: none;
  }
}
</style>
