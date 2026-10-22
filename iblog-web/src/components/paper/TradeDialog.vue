<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import type { PaperOrder, PaperOrderType, PaperPosition, PaperSide } from '@/api/paper'
import { placePaperOrder } from '@/api/paper'
import { fetchQuote, searchStock, type SearchResult, type StockQuote } from '@/api/stock'
import { fmtNum, toneClass } from '@/utils/stockFormat'

/**
 * 手动买卖弹窗。
 *
 * <p>股票搜索复用行情页的 searchStock，「从持仓卖出」时由父组件传 preset 预填。
 * 金额与费用都是**估算**：实际以成交后的交割单（接口返回的 trade）为准。</p>
 */
const props = defineProps<{
  show: boolean
  /** 从持仓行点「卖出」时带入的预填参数 */
  preset?: {
    code: string
    name: string
    side: PaperSide
    price?: number | null
    availableQuantity?: number
    currency?: string
  } | null
  /** 可用资金，用于买入前的余额校验 */
  cash?: number
  /** 当前持仓，用于卖出前的可卖数量校验（含 T+1 可用数量） */
  positions?: PaperPosition[]
  /** 当前模拟盘账户 id：多账户下所有读写都必须带上它 */
  accountId?: number | null
}>()

const emit = defineEmits<{
  close: []
  submitted: [order: PaperOrder]
}>()

/** 佣金费率（万分之 2.5，最低 5 元） */
const COMMISSION_RATE = 0.00025
const COMMISSION_MIN = 5
/** 印花税：仅卖出，万分之 5 */
const STAMP_TAX_RATE = 0.0005
/** 过户费：十万分之一 */
const TRANSFER_FEE_RATE = 0.00001

const keyword = ref('')
const results = ref<SearchResult[]>([])
const searching = ref(false)
const searchError = ref('')
const dropdownOpen = ref(false)

const selected = ref<{ code: string; name: string } | null>(null)
const quote = ref<StockQuote | null>(null)
const quoteLoading = ref(false)

const side = ref<PaperSide>('BUY')
const orderType = ref<PaperOrderType>('LIMIT')
const priceInput = ref<number | null>(null)
const quantity = ref<number | null>(100)

const submitting = ref(false)
const error = ref('')

let searchTimer: ReturnType<typeof setTimeout> | undefined
let searchSeq = 0

const currency = computed(() => props.preset?.currency || quote.value?.currency || '¥')
const availableQuantity = computed(() => props.preset?.availableQuantity ?? 0)
const latestPrice = computed(() => quote.value?.price ?? null)

/** 当前选中股票的可卖数量：优先取预设，其次从持仓里找（T+1 可用） */
const sellableQuantity = computed(() => {
  if (props.preset && props.preset.code === selected.value?.code) return availableQuantity.value
  const held = props.positions?.find((item) => item.code === selected.value?.code)
  return held?.availableQuantity ?? 0
})

/** 用于估算的价格：限价用输入价，市价用最新价 */
const refPrice = computed(() => (orderType.value === 'MARKET' ? latestPrice.value : priceInput.value))

const estAmount = computed(() => {
  const price = refPrice.value
  const qty = quantity.value
  if (price == null || price <= 0 || qty == null || qty <= 0) return null
  return price * qty
})

interface FeeEstimate {
  commission: number
  stampTax: number
  transferFee: number
  total: number
}

const fees = computed<FeeEstimate | null>(() => {
  const amount = estAmount.value
  if (amount == null) return null
  const commission = Math.max(amount * COMMISSION_RATE, COMMISSION_MIN)
  const stampTax = side.value === 'SELL' ? amount * STAMP_TAX_RATE : 0
  const transferFee = amount * TRANSFER_FEE_RATE
  return { commission, stampTax, transferFee, total: commission + stampTax + transferFee }
})

/** 买入预估扣款 / 卖出预估到账 */
const settleAmount = computed(() => {
  if (estAmount.value == null || !fees.value) return null
  return side.value === 'BUY' ? estAmount.value + fees.value.total : estAmount.value - fees.value.total
})

const title = computed(() => {
  if (selected.value) return `${side.value === 'BUY' ? '买入' : '卖出'} ${selected.value.name}`
  return '手动买卖'
})

const quickQuantities = computed(() => {
  const list = [
    { label: '100 股', value: 100 },
    { label: '500 股', value: 500 },
    { label: '1000 股', value: 1000 }
  ]
  if (side.value !== 'SELL') return list
  const maxQty = Math.floor(sellableQuantity.value / 100) * 100
  const sellable = list.filter((item) => item.value <= maxQty)
  if (maxQty >= 100) sellable.push({ label: '全部可卖', value: maxQty })
  return sellable
})

function round2(value: number): number {
  return Math.round(value * 100) / 100
}

function money(value: number | null): string {
  return value == null ? '--' : `${currency.value}${fmtNum(value)}`
}

function priceText(value: number | null): string {
  return value == null ? '--' : value.toFixed(2)
}

function signedNum(value: number | null | undefined): string {
  if (value == null || Number.isNaN(value)) return '--'
  return `${value >= 0 ? '+' : ''}${value.toFixed(2)}`
}

function clearSearchTimer() {
  if (searchTimer) {
    clearTimeout(searchTimer)
    searchTimer = undefined
  }
}

function onKeywordInput() {
  clearSearchTimer()
  const kw = keyword.value.trim()
  if (!kw) {
    results.value = []
    searching.value = false
    searchError.value = ''
    return
  }
  searching.value = true
  searchTimer = setTimeout(() => {
    void runSearch(kw)
  }, 320)
}

async function runSearch(kw: string) {
  const seq = ++searchSeq
  try {
    const list = await searchStock(kw)
    if (seq !== searchSeq) return
    results.value = list ?? []
    dropdownOpen.value = results.value.length > 0
    searchError.value = ''
  } catch (err) {
    if (seq !== searchSeq) return
    results.value = []
    dropdownOpen.value = false
    searchError.value = err instanceof Error ? err.message : '搜索失败，请稍后重试'
  } finally {
    if (seq === searchSeq) searching.value = false
  }
}

function pick(result: SearchResult) {
  selected.value = { code: result.code, name: result.name }
  keyword.value = ''
  results.value = []
  dropdownOpen.value = false
  searchError.value = ''
  void loadQuote(result.code)
}

function clearStock() {
  selected.value = null
  quote.value = null
  priceInput.value = null
}

async function loadQuote(code: string) {
  quoteLoading.value = true
  quote.value = null
  try {
    const data = await fetchQuote(code)
    quote.value = data
    // 限价单还没填价时，带出最新价省得手敲
    if (orderType.value === 'LIMIT' && !priceInput.value && data.price > 0) {
      priceInput.value = round2(data.price)
    }
  } catch {
    quote.value = null
  } finally {
    quoteLoading.value = false
  }
}

watch(orderType, (type) => {
  if (type === 'LIMIT' && !priceInput.value && latestPrice.value) {
    priceInput.value = round2(latestPrice.value)
  }
})

function validate(): string {
  if (!selected.value) return '请先搜索并选择股票'
  if (orderType.value === 'MARKET' && latestPrice.value == null) {
    return '暂时取不到最新价，市价单无法估算，请改用限价单或稍后重试'
  }
  if (orderType.value === 'LIMIT' && (!priceInput.value || priceInput.value <= 0)) {
    return '限价单请填写大于 0 的委托价'
  }
  const qty = quantity.value
  if (qty == null || !Number.isFinite(qty) || qty <= 0 || !Number.isInteger(qty)) {
    return '委托数量需为正整数'
  }
  if (qty % 100 !== 0) {
    return '委托数量必须是 100 的整数倍（1 手 = 100 股）'
  }
  if (side.value === 'SELL') {
    if (sellableQuantity.value <= 0) {
      return `「${selected.value.name}」当前无可卖股份：未持有，或今日买入的股份 T+1 才可卖`
    }
    if (qty > sellableQuantity.value) {
      return `可卖数量不足，当前可卖 ${fmtNum(sellableQuantity.value, 0)} 股`
    }
  }
  if (side.value === 'BUY' && props.cash != null && estAmount.value != null && fees.value) {
    const need = estAmount.value + fees.value.total
    if (need > props.cash + 1e-6) {
      return `可用资金不足，本次约需 ${money(need)}，当前可用 ${money(props.cash)}`
    }
  }
  return ''
}

async function submit() {
  if (submitting.value) return
  const message = validate()
  if (message) {
    error.value = message
    return
  }
  error.value = ''
  submitting.value = true
  try {
    const order = await placePaperOrder(
      {
        code: selected.value!.code,
        side: side.value,
        type: orderType.value,
        price: orderType.value === 'LIMIT' ? priceInput.value ?? undefined : undefined,
        quantity: quantity.value as number
      },
      props.accountId ?? undefined
    )
    emit('submitted', order)
    emit('close')
  } catch (err) {
    error.value = err instanceof Error ? err.message : '委托提交失败，请稍后重试'
  } finally {
    submitting.value = false
  }
}

function resetForm() {
  error.value = ''
  submitting.value = false
  searchError.value = ''
  searching.value = false
  quote.value = null
  keyword.value = ''
  results.value = []
  dropdownOpen.value = false
  quantity.value = 100
  clearSearchTimer()
  searchSeq++

  const preset = props.preset
  if (preset) {
    selected.value = { code: preset.code, name: preset.name }
    side.value = preset.side
    orderType.value = 'LIMIT'
    priceInput.value = preset.price != null && preset.price > 0 ? round2(preset.price) : null
    void loadQuote(preset.code)
    return
  }
  selected.value = null
  side.value = 'BUY'
  orderType.value = 'LIMIT'
  priceInput.value = null
}

watch(
  () => props.show,
  (visible) => {
    if (visible) {
      resetForm()
    } else {
      clearSearchTimer()
      submitting.value = false
    }
  },
  { immediate: true }
)

function onKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape' && props.show) {
    emit('close')
  }
}

onMounted(() => document.addEventListener('keydown', onKeydown))
onUnmounted(() => {
  document.removeEventListener('keydown', onKeydown)
  clearSearchTimer()
})
</script>

<template>
  <div v-if="show" class="stock-modal" @click.self="emit('close')">
    <div class="stock-modal__box trade-box" role="dialog" aria-modal="true" aria-labelledby="trade-dialog-title">
      <header class="stock-modal__head">
        <h2 id="trade-dialog-title" class="stock-modal__title">{{ title }}</h2>
        <button type="button" class="stock-modal__close" aria-label="关闭" @click="emit('close')">✕</button>
      </header>

      <!-- 选股 -->
      <div class="trade-field">
        <span class="trade-field__label">股票</span>
        <div class="trade-field__main">
          <template v-if="!selected">
            <div class="trade-search">
              <input
                v-model="keyword"
                class="trade-input trade-input--search"
                type="search"
                placeholder="输入代码或名称搜索，如 600519 / 茅台"
                autocomplete="off"
                spellcheck="false"
                aria-label="搜索股票"
                @input="onKeywordInput"
                @focus="dropdownOpen = results.length > 0"
              />
              <span v-if="searching" class="trade-search__hint">搜索中…</span>
              <ul v-if="dropdownOpen && results.length" class="trade-dropdown">
                <li v-for="item in results" :key="item.code">
                  <button type="button" class="trade-dropdown__item" @click="pick(item)">
                    <span class="trade-dropdown__name">{{ item.name }}</span>
                    <span class="trade-dropdown__code">{{ item.displayCode }}</span>
                  </button>
                </li>
              </ul>
            </div>
            <p v-if="searchError" class="trade-error-text">{{ searchError }}</p>
          </template>

          <div v-else class="trade-picked">
            <span class="trade-picked__name">{{ selected.name }}</span>
            <span class="trade-picked__code">{{ selected.code }}</span>
            <span v-if="quote" class="trade-picked__quote" :class="toneClass(quote.change)">
              现价 {{ priceText(quote.price) }}
              <em>{{ signedNum(quote.change) }}（{{ signedNum(quote.changePct) }}%）</em>
            </span>
            <span v-else-if="quoteLoading" class="trade-picked__quote is-muted">现价加载中…</span>
            <span v-else class="trade-picked__quote is-muted">现价暂不可用</span>
            <button v-if="!preset" type="button" class="trade-link" @click="clearStock">更换</button>
          </div>
        </div>
      </div>

      <!-- 方向 -->
      <div class="trade-field">
        <span class="trade-field__label">方向</span>
        <div class="trade-field__main">
          <div class="seg" role="group" aria-label="交易方向">
            <button
              type="button"
              class="seg__btn"
              :class="{ 'is-active': side === 'BUY' }"
              :aria-pressed="side === 'BUY'"
              @click="side = 'BUY'"
            >
              买入
            </button>
            <button
              type="button"
              class="seg__btn"
              :class="{ 'is-active': side === 'SELL' }"
              :aria-pressed="side === 'SELL'"
              @click="side = 'SELL'"
            >
              卖出
            </button>
          </div>
          <span v-if="side === 'SELL' && selected" class="trade-hint">可卖 {{ fmtNum(sellableQuantity, 0) }} 股</span>
          <span v-else-if="side === 'BUY' && cash != null" class="trade-hint">可用 {{ money(cash) }}</span>
        </div>
      </div>

      <!-- 价格类型 -->
      <div class="trade-field">
        <span class="trade-field__label">价格</span>
        <div class="trade-field__main">
          <div class="seg" role="group" aria-label="价格类型">
            <button
              type="button"
              class="seg__btn"
              :class="{ 'is-active': orderType === 'MARKET' }"
              :aria-pressed="orderType === 'MARKET'"
              @click="orderType = 'MARKET'"
            >
              市价
            </button>
            <button
              type="button"
              class="seg__btn"
              :class="{ 'is-active': orderType === 'LIMIT' }"
              :aria-pressed="orderType === 'LIMIT'"
              @click="orderType = 'LIMIT'"
            >
              限价
            </button>
          </div>
          <template v-if="orderType === 'LIMIT'">
            <input
              v-model.number="priceInput"
              class="trade-input trade-input--num"
              type="number"
              min="0"
              step="0.01"
              inputmode="decimal"
              placeholder="委托价"
              aria-label="委托价"
            />
            <span class="trade-hint">元</span>
          </template>
          <span v-else class="trade-hint">按最新价 {{ priceText(latestPrice) }} 即时成交</span>
        </div>
      </div>

      <!-- 数量 -->
      <div class="trade-field">
        <span class="trade-field__label">数量</span>
        <div class="trade-field__main">
          <input
            v-model.number="quantity"
            class="trade-input trade-input--num"
            type="number"
            min="0"
            step="100"
            inputmode="numeric"
            aria-label="委托数量（股）"
          />
          <span class="trade-hint">股，100 的整数倍</span>
        </div>
      </div>

      <div class="trade-quick">
        <button
          v-for="item in quickQuantities"
          :key="item.label"
          type="button"
          class="trade-quick__btn"
          @click="quantity = item.value"
        >
          {{ item.label }}
        </button>
      </div>

      <!-- 预估 -->
      <dl class="trade-est">
        <div class="trade-est__row">
          <dt>预估金额</dt>
          <dd>{{ money(estAmount) }}</dd>
        </div>
        <div class="trade-est__row">
          <dt>预估费用</dt>
          <dd>{{ money(fees ? fees.total : null) }}</dd>
        </div>
        <div class="trade-est__detail">
          佣金 {{ money(fees ? fees.commission : null) }}
          <template v-if="side === 'SELL'">
            · 印花税 {{ money(fees ? fees.stampTax : null) }}
          </template>
          · 过户费 {{ money(fees ? fees.transferFee : null) }}
        </div>
        <div class="trade-est__row trade-est__row--total">
          <dt>{{ side === 'BUY' ? '预估扣款' : '预估到账' }}</dt>
          <dd>{{ money(settleAmount) }}</dd>
        </div>
      </dl>

      <p class="trade-note">
        费用为估算（佣金万 2.5、最低 5 元；卖出印花税万 5；过户费十万分之一），
        实际以成交后的交割单为准。市价单按最新价估算，成交价可能略有差异。
        委托仅在 A 股交易时段（9:30–11:30、13:00–15:00）受理，停牌、涨跌停或非交易日会被拒单。
      </p>

      <p v-if="error" class="trade-error" role="alert">{{ error }}</p>

      <div class="trade-actions">
        <button type="button" class="btn btn--ghost" @click="emit('close')">取消</button>
        <button
          type="button"
          class="btn trade-submit"
          :class="side === 'BUY' ? 'is-buy' : 'is-sell'"
          :disabled="submitting"
          @click="submit"
        >
          {{ submitting ? '提交中…' : side === 'BUY' ? '确认买入' : '确认卖出' }}
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.trade-box {
  width: min(560px, 100%);
}

.trade-field {
  display: flex;
  align-items: flex-start;
  gap: var(--s-3);
  margin-bottom: var(--s-3);
}

.trade-field__label {
  flex: none;
  width: 44px;
  padding-top: 11px;
  font-size: var(--t-sm);
  color: var(--c-text-muted);
}

.trade-field__main {
  display: flex;
  align-items: center;
  gap: var(--s-2);
  flex-wrap: wrap;
  flex: 1;
  min-width: 0;
}

.trade-input {
  min-width: 0;
  height: 42px;
  padding: 0 var(--s-3);
  border: 1px solid var(--c-border-strong);
  border-radius: var(--r);
  background: var(--c-surface);
  color: var(--c-text);
  font-size: var(--t-base);
  font-family: inherit;
  transition: border-color 0.15s var(--ease), box-shadow 0.15s var(--ease);
}

.trade-input:focus {
  outline: none;
  border-color: var(--c-brand);
  box-shadow: 0 0 0 3px var(--c-brand-ring);
}

.trade-input--search {
  flex: 1;
  min-width: 180px;
}

.trade-input--num {
  width: 132px;
  font-family: var(--f-mono);
  font-variant-numeric: tabular-nums;
}

.trade-search {
  position: relative;
  flex: 1;
  min-width: 180px;
}

.trade-search__hint {
  position: absolute;
  right: 10px;
  top: 50%;
  transform: translateY(-50%);
  font-size: var(--t-xs);
  color: var(--c-text-faint);
  pointer-events: none;
}

.trade-dropdown {
  position: absolute;
  z-index: 30;
  top: calc(100% + 4px);
  left: 0;
  right: 0;
  max-height: 264px;
  overflow-y: auto;
  margin: 0;
  padding: var(--s-1);
  list-style: none;
  border: 1px solid var(--c-border);
  border-radius: var(--r);
  background: var(--c-surface);
  box-shadow: var(--e-lg);
}

.trade-dropdown__item {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--s-3);
  width: 100%;
  padding: 9px var(--s-3);
  border: 0;
  border-radius: var(--r-sm);
  background: none;
  color: var(--c-text-secondary);
  font-size: var(--t-sm);
  font-family: inherit;
  text-align: left;
  cursor: pointer;
}

.trade-dropdown__item:hover {
  background: var(--c-brand-soft);
  color: var(--c-brand);
}

.trade-dropdown__name {
  font-weight: 560;
  color: var(--c-text);
}

.trade-dropdown__item:hover .trade-dropdown__name {
  color: var(--c-brand);
}

.trade-dropdown__code {
  font-family: var(--f-mono);
  font-size: var(--t-xs);
  color: var(--c-text-faint);
}

.trade-picked {
  display: flex;
  align-items: center;
  gap: var(--s-2);
  flex-wrap: wrap;
  min-height: 42px;
}

.trade-picked > * {
  line-height: 1.5;
}

.trade-picked__name {
  font-size: var(--t-md);
  font-weight: 620;
}

.trade-picked__code {
  font-family: var(--f-mono);
  font-size: var(--t-sm);
  color: var(--c-text-faint);
}

.trade-picked__quote {
  font-family: var(--f-mono);
  font-size: var(--t-sm);
  font-variant-numeric: tabular-nums;
}

.trade-picked__quote em {
  font-style: normal;
  font-size: 0.94em;
}

.trade-picked__quote.is-muted {
  color: var(--c-text-faint);
}

.trade-link {
  padding: 0;
  border: 0;
  background: none;
  color: var(--c-brand);
  font-size: var(--t-xs);
  font-family: inherit;
  cursor: pointer;
  text-decoration: underline;
  text-underline-offset: 3px;
}

.seg {
  display: inline-flex;
  padding: 3px;
  border: 1px solid var(--c-border);
  border-radius: var(--r);
  background: var(--c-surface-sunken);
}

.seg__btn {
  min-height: 34px;
  padding: 0 var(--s-4);
  border: 0;
  border-radius: var(--r-sm);
  background: none;
  color: var(--c-text-muted);
  font-size: var(--t-sm);
  font-weight: 560;
  font-family: inherit;
  cursor: pointer;
  transition: background-color 0.15s var(--ease), color 0.15s var(--ease);
}

.seg__btn.is-active {
  background: var(--c-surface);
  color: var(--c-text);
  box-shadow: var(--e-shadow-sm, 0 1px 2px rgba(0, 0, 0, 0.08));
}

.trade-hint {
  font-size: var(--t-xs);
  color: var(--c-text-muted);
}

.trade-error-text {
  margin: var(--s-2) 0 0;
  font-size: var(--t-xs);
  color: var(--stock-up);
}

.trade-quick {
  display: flex;
  flex-wrap: wrap;
  gap: var(--s-2);
  margin: 0 0 var(--s-4) 56px;
}

.trade-quick__btn {
  min-height: 30px;
  padding: 0 var(--s-3);
  border: 1px solid var(--c-border);
  border-radius: var(--r-full);
  background: var(--c-surface);
  color: var(--c-text-muted);
  font-size: var(--t-xs);
  font-family: inherit;
  cursor: pointer;
  transition: border-color 0.15s var(--ease), color 0.15s var(--ease);
}

.trade-quick__btn:hover {
  border-color: var(--c-brand);
  color: var(--c-brand);
}

.trade-est {
  display: flex;
  flex-direction: column;
  gap: var(--s-2);
  margin: 0 0 var(--s-3);
  padding: var(--s-3) var(--s-4);
  border: 1px solid var(--c-border);
  border-radius: var(--r);
  background: var(--c-surface-sunken);
}

.trade-est__row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--s-3);
}

.trade-est__row dt {
  font-size: var(--t-sm);
  color: var(--c-text-muted);
}

.trade-est__row dd {
  margin: 0;
  font-family: var(--f-mono);
  font-size: var(--t-base);
  font-variant-numeric: tabular-nums;
  color: var(--c-text);
}

.trade-est__row--total {
  padding-top: var(--s-2);
  border-top: 1px dashed var(--c-border);
}

.trade-est__row--total dd {
  font-weight: 640;
}

.trade-est__detail {
  font-size: var(--t-xs);
  color: var(--c-text-faint);
  font-family: var(--f-mono);
  font-variant-numeric: tabular-nums;
}

.trade-note {
  margin: 0 0 var(--s-4);
  font-size: var(--t-xs);
  line-height: 1.7;
  color: var(--c-text-muted);
}

.trade-error {
  margin: 0 0 var(--s-3);
  padding: var(--s-2) var(--s-3);
  border-radius: var(--r-sm);
  background: color-mix(in srgb, var(--stock-up) 10%, transparent);
  color: var(--stock-up);
  font-size: var(--t-sm);
}

.trade-actions {
  display: flex;
  justify-content: flex-end;
  gap: var(--s-3);
  padding-top: var(--s-3);
  border-top: 1px solid var(--c-border);
}

.trade-submit.is-buy {
  background: var(--stock-up);
}

.trade-submit.is-buy:hover:not(:disabled) {
  background: color-mix(in srgb, var(--stock-up) 86%, #000);
}

.trade-submit.is-sell {
  background: var(--stock-down);
}

.trade-submit.is-sell:hover:not(:disabled) {
  background: color-mix(in srgb, var(--stock-down) 86%, #000);
}

@media (max-width: 600px) {
  .trade-field {
    flex-direction: column;
    gap: var(--s-2);
  }

  .trade-field__label {
    width: auto;
    padding-top: 0;
  }

  .trade-quick {
    margin-left: 0;
  }

  .trade-input--num {
    width: 112px;
  }

  .trade-actions .btn {
    flex: 1;
  }
}
</style>
