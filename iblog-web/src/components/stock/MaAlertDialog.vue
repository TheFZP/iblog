<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import {
  MA_PERIODS,
  useMaAlerts,
  type MaAlert,
  type MaAlertHistoryItem,
  type MaDirection,
  type MaPeriod
} from '@/composables/useMaAlerts'
import { useNotify } from '@/composables/useNotify'

/**
 * 均线穿越提醒配置弹窗。
 *
 * <p>交互与视觉照搬 {@code PriceAlertDialog}（同一套 modal/notice/list/switch 类名），
 * 两个入口在行情页并排出现，长得不像一家的会显得像两个人写的。</p>
 *
 * <p>与桌面版的差异同价格提醒：网页端只能走浏览器 Notification，
 * 页面关闭后完全收不到 —— 这一点必须在弹窗顶部明说，否则用户会以为关掉标签页也会被提醒。</p>
 */

const props = defineProps<{
  open: boolean
  code: string
  name?: string
}>()

const emit = defineEmits<{ close: [] }>()

const { maAlerts, history, maAlertsForCode, addMaAlert, removeMaAlert, toggleMaAlert } = useMaAlerts()
const { permission, requestNotifyPermission } = useNotify()

/** 触发历史最多展示条数。store 里最多留 200 条，一次全铺出来太长 */
const HISTORY_LIMIT = 50

const tab = ref<'alerts' | 'history'>('alerts')
const period = ref<MaPeriod>(20)
const direction = ref<MaDirection>('both')
const error = ref('')

/**
 * 只列当前股票的提醒。
 *
 * <p>弹窗是从某只股票的详情页打开的，上下文就是这只票；把全市场的提醒铺进来
 * 会让用户找不到刚添加的那条，也容易误删别的股票的配置。
 * 全市场的总量在底部用一行小字带过，足够让人知道「别处的提醒还在跑」。</p>
 */
const list = computed<MaAlert[]>(() => (props.code ? maAlertsForCode(props.code) : []))

/** 其它股票上的提醒条数：底部小字用，0 时不占位置 */
const otherCount = computed(() => Math.max(0, maAlerts.value.length - list.value.length))

/** 倒序（新的在上）并截断。history 内部按时间升序追加，所以 reverse 前先拷贝一份 */
const historyList = computed<MaAlertHistoryItem[]>(() => [...history.value].reverse().slice(0, HISTORY_LIMIT))

const directionLabel = (d: MaDirection | MaAlertHistoryItem['direction']): string =>
  d === 'up' ? '上穿' : d === 'down' ? '下穿' : '双向'

/**
 * 判定「同周期 + 同方向」是否已存在。
 *
 * <p>store 的 addMaAlert 按「股票 + 周期」唯一：再添加同周期只会覆盖方向，
 * 所以这里把完全相同的组合先拦住，避免用户点一下「添加」看到列表毫无变化，
 * 以为按钮坏了。</p>
 */
const duplicate = computed(() =>
  list.value.some((a) => a.period === period.value && a.direction === direction.value)
)

function pad(n: number): string {
  return String(n).padStart(2, '0')
}

/** 触发时间戳 → `MM-dd HH:mm`；今天之外再带上月份，跨日记录不至于看不出是哪天 */
function fmtTriggeredAt(at: number | null): string {
  if (!at) {
    return '未触发'
  }
  const d = new Date(at)
  if (Number.isNaN(d.getTime())) {
    return '未触发'
  }
  return `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

function add() {
  if (duplicate.value) {
    error.value = `已有 MA${period.value} ${directionLabel(direction.value)} 提醒`
    return
  }
  const created = addMaAlert({
    code: props.code,
    name: props.name,
    period: period.value,
    direction: direction.value
  })
  if (!created) {
    error.value = '添加失败，请检查股票代码与周期'
    return
  }
  error.value = ''
}

function remove(a: MaAlert) {
  // 删配置是不可逆的，二次确认照抄价格提醒的口径
  if (!window.confirm(`删除 MA${a.period} ${directionLabel(a.direction)} 提醒？`)) {
    return
  }
  removeMaAlert(a.id)
}

async function askPermission() {
  await requestNotifyPermission()
}

function onWindowKeydown(event: KeyboardEvent) {
  // 只在打开时响应 Esc，避免弹窗关掉后还抢走页面上其它弹窗的 Esc
  if (event.key === 'Escape' && props.open) {
    emit('close')
  }
}

watch(
  () => props.open,
  (open) => {
    if (!open) {
      return
    }
    tab.value = 'alerts'
    error.value = ''
    direction.value = 'both'
    period.value = 20
  },
  { immediate: true }
)

onMounted(() => {
  window.addEventListener('keydown', onWindowKeydown)
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onWindowKeydown)
})
</script>

<template>
  <Teleport to="body">
    <div v-if="open" class="modal" @click.self="emit('close')">
      <div class="modal__box" role="dialog" aria-modal="true" aria-label="均线提醒">
        <header class="modal__head">
          <h2 class="modal__title">均线提醒 · {{ name || code || '全市场' }}</h2>
          <button class="modal__close" aria-label="关闭" @click="emit('close')">✕</button>
        </header>

        <!-- 通知权限：与价格提醒同一套口径，能力边界说清楚比事后解释便宜 -->
        <div class="notice notice--info">
          均线提醒依赖浏览器通知权限与定时巡查，页面关闭后不会提醒。
          <button v-if="permission === 'default'" class="notice__btn" @click="askPermission">开启通知</button>
        </div>
        <div v-if="permission === 'denied'" class="notice notice--warn">
          通知已被浏览器拒绝，需在浏览器设置里恢复。
        </div>

        <div class="tabs" role="tablist">
          <button
            class="tabs__btn"
            :class="{ 'tabs__btn--on': tab === 'alerts' }"
            role="tab"
            :aria-selected="tab === 'alerts'"
            @click="tab = 'alerts'"
          >
            均线提醒（{{ list.length }}）
          </button>
          <button
            class="tabs__btn"
            :class="{ 'tabs__btn--on': tab === 'history' }"
            role="tab"
            :aria-selected="tab === 'history'"
            @click="tab = 'history'"
          >
            触发历史（{{ historyList.length }}）
          </button>
        </div>

        <!-- ── 均线提醒 ────────────────────────────────────── -->
        <template v-if="tab === 'alerts'">
          <div class="form">
            <div class="form__row">
              <span class="form__label">周期</span>
              <select v-model="period" class="form__select" aria-label="均线周期">
                <option v-for="p in MA_PERIODS" :key="p" :value="p">MA{{ p }}（{{ p }} 日）</option>
              </select>

              <span class="form__label form__label--sm">方向</span>
              <select v-model="direction" class="form__select" aria-label="穿越方向">
                <option value="up">上穿</option>
                <option value="down">下穿</option>
                <option value="both">双向</option>
              </select>

              <!-- .btn 本身就是主按钮样式（品牌底），没有 --primary 修饰类 -->
              <button class="btn form__add" :disabled="duplicate" @click="add">添加</button>
            </div>
            <p v-if="duplicate" class="form__hint">
              已有 MA{{ period }} {{ directionLabel(direction) }} 提醒，无需重复添加。
            </p>
            <p v-if="error" class="form__error">{{ error }}</p>
          </div>

          <section class="list">
            <p v-if="list.length === 0" class="list__empty">还没有为这只股票设置均线提醒</p>
            <ul v-else class="list__items">
              <li v-for="a in list" :key="a.id" class="list__item">
                <span class="list__period" :class="a.direction === 'down' ? 'tone-down' : 'tone-up'">
                  MA{{ a.period }}
                </span>
                <span class="list__dir">{{ directionLabel(a.direction) }}</span>
                <span class="list__meta">最近触发 {{ fmtTriggeredAt(a.triggeredAt) }}</span>
                <span class="list__actions">
                  <label class="switch" :title="a.enabled ? '关闭提醒' : '开启提醒'">
                    <input
                      type="checkbox"
                      class="switch__input"
                      :checked="a.enabled"
                      :aria-label="`MA${a.period} ${directionLabel(a.direction)} 提醒开关`"
                      @change="toggleMaAlert(a.id, ($event.target as HTMLInputElement).checked)"
                    />
                    <span class="switch__track"><span class="switch__knob" /></span>
                    <span class="switch__text">{{ a.enabled ? '开启' : '关闭' }}</span>
                  </label>
                  <button class="link link--danger" @click="remove(a)">删除</button>
                </span>
              </li>
            </ul>
          </section>

          <p v-if="otherCount > 0" class="modal__foot">
            另有 {{ otherCount }} 条其它股票的均线提醒在巡查中
          </p>
        </template>

        <!-- ── 触发历史 ────────────────────────────────────── -->
        <template v-else>
          <p v-if="historyList.length === 0" class="list__empty">还没有触发记录</p>
          <ul v-else class="hist">
            <li v-for="h in historyList" :key="h.id" class="hist__item">
              <span class="hist__name">{{ h.name }}({{ h.code }})</span>
              <span class="hist__body">
                收盘价 {{ h.close.toFixed(2) }}
                <span :class="h.direction === 'up' ? 'tone-up' : 'tone-down'">{{ directionLabel(h.direction) }}</span>
                MA{{ h.period }}({{ h.ma.toFixed(2) }})
              </span>
              <span class="hist__date">· {{ h.date }}</span>
            </li>
          </ul>
          <p v-if="historyList.length >= HISTORY_LIMIT" class="modal__foot">
            仅展示最近 {{ HISTORY_LIMIT }} 条
          </p>
        </template>

        <p class="foot__hint">
          均线用日 K 收盘价计算，盘中每次巡查按当前价重算；同一只股票同一周期每个交易日最多提醒一次。
        </p>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.modal {
  position: fixed;
  inset: 0;
  z-index: 100;
  display: grid;
  place-items: center;
  padding: var(--s-5);
  background: var(--c-overlay);
  backdrop-filter: blur(2px);
}

.modal__box {
  width: min(560px, 100%);
  max-height: 88vh;
  overflow-y: auto;
  padding: var(--s-6);
  border-radius: var(--r-lg);
  background: var(--c-surface);
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.24);
}

.modal__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--s-4);
}

.modal__title {
  margin: 0;
  font-size: var(--t-lg);
  font-weight: 600;
}

.modal__close {
  border: 0;
  background: none;
  color: var(--c-text-faint);
  font-size: var(--t-md);
  cursor: pointer;
  padding: var(--s-1);
}

.modal__close:hover {
  color: var(--c-text);
}

/* ---------- 提示条 ---------- */
.notice {
  display: flex;
  align-items: center;
  gap: var(--s-2);
  flex-wrap: wrap;
  padding: var(--s-2) var(--s-3);
  margin-bottom: var(--s-3);
  border-radius: var(--r);
  font-size: var(--t-sm);
  line-height: 1.6;
}

.notice--info {
  background: var(--c-surface-sunken);
  color: var(--c-text-secondary);
}

.notice--warn {
  background: color-mix(in srgb, #d93025 10%, transparent);
  color: #b3261e;
}

.notice__btn {
  margin-left: auto;
  padding: 3px 10px;
  border: 1px solid currentColor;
  border-radius: var(--r-full);
  background: none;
  color: inherit;
  font-size: var(--t-xs);
  cursor: pointer;
}

/* ---------- Tab ---------- */
.tabs {
  display: inline-flex;
  padding: 2px;
  margin-bottom: var(--s-4);
  border-radius: var(--r-full);
  background: var(--c-surface-sunken);
}

.tabs__btn {
  padding: 5px 14px;
  border: 0;
  border-radius: var(--r-full);
  background: none;
  color: var(--c-text-muted);
  font-size: var(--t-sm);
  cursor: pointer;
  transition: all 0.15s var(--ease);
}

.tabs__btn--on {
  background: var(--c-surface);
  color: var(--c-text);
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.1);
}

/* ---------- 新增表单 ---------- */
.form {
  display: flex;
  flex-direction: column;
  gap: var(--s-2);
  padding-bottom: var(--s-4);
  border-bottom: 1px solid var(--c-border);
}

.form__row {
  display: flex;
  align-items: center;
  gap: var(--s-2);
  flex-wrap: wrap;
}

.form__label {
  font-size: var(--t-sm);
  color: var(--c-text-secondary);
}

.form__label--sm {
  margin-left: var(--s-2);
}

.form__select {
  padding: 6px 10px;
  border: 1px solid var(--c-border);
  border-radius: var(--r-sm);
  background: var(--c-bg);
  color: var(--c-text);
  font-size: var(--t-sm);
  cursor: pointer;
}

.form__select:focus-visible {
  outline: 2px solid var(--c-brand);
  outline-offset: 1px;
}

.form__add {
  margin-left: auto;
  min-height: 34px;
  padding: 0 var(--s-4);
  font-size: var(--t-sm);
}

.form__hint {
  margin: 0;
  font-size: var(--t-xs);
  color: var(--c-text-faint);
}

.form__error {
  margin: 0;
  font-size: var(--t-sm);
  color: #d93025;
}

/* ---------- 列表 ---------- */
.list {
  padding-top: var(--s-4);
}

.list__empty {
  margin: 0;
  font-size: var(--t-sm);
  color: var(--c-text-faint);
}

.list__items {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--s-2);
}

.list__item {
  display: grid;
  grid-template-columns: 58px 44px minmax(0, 1fr) minmax(120px, auto);
  align-items: center;
  gap: var(--s-2);
  padding: var(--s-2) var(--s-3);
  border: 1px solid var(--c-border);
  border-radius: var(--r);
  font-size: var(--t-sm);
  font-variant-numeric: tabular-nums;
}

.list__period {
  font-weight: 600;
}

.list__dir {
  color: var(--c-text-secondary);
}

.list__meta {
  color: var(--c-text-faint);
  font-size: var(--t-xs);
}

.list__actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: var(--s-4);
}

/* ---------- 历史 ---------- */
.hist {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--s-2);
}

.hist__item {
  display: flex;
  align-items: baseline;
  gap: var(--s-2);
  flex-wrap: wrap;
  padding: var(--s-2) var(--s-3);
  border: 1px solid var(--c-border);
  border-radius: var(--r);
  font-size: var(--t-sm);
  font-variant-numeric: tabular-nums;
}

.hist__name {
  font-weight: 600;
}

.hist__body {
  color: var(--c-text-secondary);
}

.hist__date {
  margin-left: auto;
  font-size: var(--t-xs);
  color: var(--c-text-faint);
}

/* ---------- 开关 ---------- */
.switch {
  display: inline-flex;
  align-items: center;
  gap: var(--s-2);
  cursor: pointer;
  user-select: none;
}

/* 视觉上隐藏，但保留键盘焦点与无障碍语义 */
.switch__input {
  position: absolute;
  width: 1px;
  height: 1px;
  margin: -1px;
  padding: 0;
  overflow: hidden;
  clip-path: inset(50%);
  white-space: nowrap;
  border: 0;
}

.switch__track {
  position: relative;
  display: block;
  width: 34px;
  height: 20px;
  border-radius: var(--r-full);
  background: var(--c-border);
  transition: background 0.18s var(--ease-out);
}

.switch__knob {
  position: absolute;
  top: 2px;
  left: 2px;
  width: 16px;
  height: 16px;
  border-radius: 50%;
  background: #fff;
  box-shadow: 0 1px 2px rgb(0 0 0 / 0.2);
  transition: transform 0.18s var(--ease-out);
}

.switch__input:checked + .switch__track {
  background: var(--c-brand);
}

.switch__input:checked + .switch__track .switch__knob {
  transform: translateX(14px);
}

.switch__input:focus-visible + .switch__track {
  outline: 2px solid var(--c-brand);
  outline-offset: 2px;
}

.switch__text {
  font-size: var(--t-xs);
  color: var(--c-text-muted);
  min-width: 24px;
}

.link {
  border: 0;
  background: none;
  padding: 0;
  color: var(--c-brand);
  font-size: var(--t-xs);
  cursor: pointer;
}

.link:hover {
  text-decoration: underline;
}

.link--danger {
  color: #d93025;
}

.link:focus-visible,
.tabs__btn:focus-visible,
.modal__close:focus-visible,
.notice__btn:focus-visible,
.btn:focus-visible {
  outline: 2px solid var(--c-brand);
  outline-offset: 2px;
}

/* ---------- 页面小字 ---------- */
.modal__foot {
  margin: var(--s-3) 0 0;
  font-size: var(--t-xs);
  color: var(--c-text-faint);
}

.foot__hint {
  margin: var(--s-4) 0 0;
  font-size: var(--t-xs);
  line-height: 1.7;
  color: var(--c-text-faint);
}

/* ---------- 涨跌色 ---------- */
.tone-up {
  color: var(--stock-up);
}

.tone-down {
  color: var(--stock-down);
}

@media (max-width: 560px) {
  .modal {
    padding: var(--s-3);
  }

  .modal__box {
    padding: var(--s-4);
  }

  .list__item {
    grid-template-columns: 58px 44px minmax(0, 1fr);
    row-gap: var(--s-1);
  }

  .list__actions {
    grid-column: 1 / -1;
    justify-content: space-between;
  }

  .form__add {
    margin-left: 0;
  }
}
</style>
