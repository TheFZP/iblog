<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { usePriceAlerts } from '@/composables/usePriceAlerts'
import { useNotify } from '@/composables/useNotify'
import { fmtNum } from '@/utils/stockFormat'

/**
 * 价格提醒配置弹窗。
 *
 * <p>与桌面版的关键差异：桌面版可以弹 Windows 原生通知，网页端只能走浏览器
 * Notification API，且<b>页面关闭后就收不到</b>。这一点在弹窗里必须明说，
 * 否则用户会以为关掉网页仍会被提醒。</p>
 */

const props = defineProps<{
  show: boolean
  code: string
  name: string
  /** 当前价，作为新建提醒的默认值 */
  price?: number
}>()

const emit = defineEmits<{ close: [] }>()

const { alertsForCode, addAlert, removeAlert, rearmAlert, pauseAlert } = usePriceAlerts()
const { permission, requestNotifyPermission } = useNotify()

const direction = ref<'above' | 'below'>('above')
const targetPrice = ref<number | null>(null)
const volumeCondition = ref(false)
const volumeMultiple = ref(2)
const mode = ref<'once' | 'daily'>('once')
const error = ref('')

const list = computed(() => (props.code ? alertsForCode(props.code) : []))

// 打开时用当前价预填目标价，大多数人是「现价上方/下方某个价位」
watch(
  () => props.show,
  (visible) => {
    if (visible) {
      direction.value = 'above'
      targetPrice.value = props.price && props.price > 0 ? Number(props.price.toFixed(2)) : null
      volumeCondition.value = false
      volumeMultiple.value = 2
      mode.value = 'once'
      error.value = ''
    }
  }
)

function submit() {
  const price = Number(targetPrice.value)
  if (!price || price <= 0 || Number.isNaN(price)) {
    error.value = '请填写有效的目标价'
    return
  }
  const created = addAlert({
    code: props.code,
    name: props.name,
    direction: direction.value,
    price,
    volumeCondition: volumeCondition.value,
    volumeMultiple: volumeMultiple.value,
    mode: mode.value
  })
  if (!created) {
    error.value = '创建失败，请检查目标价'
    return
  }
  error.value = ''
  targetPrice.value = null
}

async function askPermission() {
  await requestNotifyPermission()
}

function directionLabel(d: 'above' | 'below'): string {
  return d === 'above' ? '突破' : '跌破'
}
</script>

<template>
  <div v-if="show" class="modal" @click.self="emit('close')">
    <div class="modal__box" role="dialog" aria-modal="true" aria-label="价格提醒">
      <header class="modal__head">
        <h2 class="modal__title">价格提醒 · {{ name || code }}</h2>
        <button class="modal__close" aria-label="关闭" @click="emit('close')">✕</button>
      </header>

      <!-- 通知权限提示 -->
      <div v-if="permission === 'unsupported'" class="notice notice--warn">
        当前浏览器不支持桌面通知，提醒只在页面内提示。
      </div>
      <div v-else-if="permission === 'denied'" class="notice notice--warn">
        通知权限已被拒绝。请点击地址栏左侧的锁形图标，在「通知」里改为允许。
      </div>
      <div v-else-if="permission === 'default'" class="notice notice--info">
        尚未授权通知。授权后才能收到系统弹窗提醒。
        <button class="notice__btn" @click="askPermission">立即授权</button>
      </div>
      <div v-else class="notice notice--ok">通知已开启。注意：关闭本网页后将无法收到提醒。</div>

      <!-- 新建表单 -->
      <div class="form">
        <div class="form__row">
          <span class="form__label">条件</span>
          <div class="seg">
            <button
              class="seg__btn"
              :class="{ 'seg__btn--on': direction === 'above' }"
              @click="direction = 'above'"
            >突破</button>
            <button
              class="seg__btn"
              :class="{ 'seg__btn--on': direction === 'below' }"
              @click="direction = 'below'"
            >跌破</button>
          </div>
          <span class="form__hint">{{ direction === 'above' ? '现价上穿目标价时提醒' : '现价下穿目标价时提醒' }}</span>
        </div>

        <div class="form__row">
          <span class="form__label">目标价</span>
          <input
            v-model.number="targetPrice"
            class="form__input"
            type="number"
            step="0.01"
            min="0"
            :placeholder="price ? `当前 ${fmtNum(price)}` : '输入目标价'"
            @keydown.enter="submit"
          >
          <span class="form__hint">元</span>
        </div>

        <div class="form__row">
          <span class="form__label">放量条件</span>
          <label class="switch">
            <input v-model="volumeCondition" type="checkbox">
            <span>当日成交量 ≥</span>
          </label>
          <input
            v-model.number="volumeMultiple"
            class="form__input form__input--sm"
            type="number"
            step="0.5"
            min="1"
            :disabled="!volumeCondition"
          >
          <span class="form__hint">倍 5 日均量</span>
        </div>

        <div class="form__row">
          <span class="form__label">触发模式</span>
          <div class="seg">
            <button
              class="seg__btn"
              :class="{ 'seg__btn--on': mode === 'once' }"
              @click="mode = 'once'"
            >一次性</button>
            <button
              class="seg__btn"
              :class="{ 'seg__btn--on': mode === 'daily' }"
              @click="mode = 'daily'"
            >每日</button>
          </div>
          <span class="form__hint">{{ mode === 'once' ? '触发后自动暂停' : '每个交易日最多一次' }}</span>
        </div>

        <p v-if="error" class="form__error">{{ error }}</p>

        <button class="btn btn--primary form__submit" @click="submit">添加提醒</button>
      </div>

      <!-- 已有提醒列表 -->
      <section class="list">
        <h3 class="list__title">已有提醒（{{ list.length }}）</h3>
        <p v-if="list.length === 0" class="list__empty">还没有为这只股票设置提醒</p>
        <ul v-else class="list__items">
          <li v-for="a in list" :key="a.id" class="list__item">
            <span class="list__dir" :class="a.direction === 'above' ? 'tone-up' : 'tone-down'">
              {{ directionLabel(a.direction) }}
            </span>
            <span class="list__price">{{ fmtNum(a.price) }}</span>
            <span class="list__meta">
              <template v-if="a.volumeCondition">放量{{ a.volumeMultiple }}× · </template>
              {{ a.mode === 'once' ? '一次性' : '每日' }}
            </span>
            <span class="list__state" :class="a.enabled ? 'list__state--on' : 'list__state--off'">
              {{ a.enabled ? '监控中' : a.triggeredAt ? '已触发' : '已暂停' }}
            </span>
            <span class="list__actions">
              <button v-if="!a.enabled" class="link" @click="rearmAlert(a.id)">重新启用</button>
              <button v-else class="link" @click="pauseAlert(a.id)">暂停</button>
              <button class="link link--danger" @click="removeAlert(a.id)">删除</button>
            </span>
          </li>
        </ul>
      </section>
    </div>
  </div>
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

/* ---------- 通知权限提示 ---------- */
.notice {
  display: flex;
  align-items: center;
  gap: var(--s-2);
  flex-wrap: wrap;
  padding: var(--s-2) var(--s-3);
  margin-bottom: var(--s-4);
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

.notice--ok {
  background: color-mix(in srgb, var(--c-brand) 10%, transparent);
  color: var(--c-brand);
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

/* ---------- 表单 ---------- */
.form {
  display: flex;
  flex-direction: column;
  gap: var(--s-3);
  padding-bottom: var(--s-5);
  border-bottom: 1px solid var(--c-border);
}

.form__row {
  display: flex;
  align-items: center;
  gap: var(--s-3);
  flex-wrap: wrap;
}

.form__label {
  width: 64px;
  flex: none;
  font-size: var(--t-sm);
  color: var(--c-text-secondary);
}

.form__hint {
  font-size: var(--t-xs);
  color: var(--c-text-faint);
}

.form__input {
  width: 140px;
  padding: 6px 10px;
  border: 1px solid var(--c-border);
  border-radius: var(--r-sm);
  background: var(--c-bg);
  color: var(--c-text);
  font-size: var(--t-sm);
  font-variant-numeric: tabular-nums;
}

.form__input--sm {
  width: 74px;
}

.form__input:focus {
  outline: none;
  border-color: var(--c-brand);
  box-shadow: 0 0 0 3px var(--c-brand-ring);
}

.form__input:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.form__error {
  margin: 0;
  font-size: var(--t-sm);
  color: #d93025;
}

.form__submit {
  align-self: flex-start;
}

.seg {
  display: inline-flex;
  padding: 2px;
  border-radius: var(--r-full);
  background: var(--c-surface-sunken);
}

.seg__btn {
  padding: 4px 14px;
  border: 0;
  border-radius: var(--r-full);
  background: none;
  color: var(--c-text-muted);
  font-size: var(--t-sm);
  cursor: pointer;
  transition: all 0.15s var(--ease-out);
}

.seg__btn--on {
  background: var(--c-surface);
  color: var(--c-text);
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.1);
}

.switch {
  display: inline-flex;
  align-items: center;
  gap: var(--s-1);
  font-size: var(--t-sm);
  color: var(--c-text-secondary);
  cursor: pointer;
}

/* ---------- 已有提醒 ---------- */
.list {
  padding-top: var(--s-4);
}

.list__title {
  margin: 0 0 var(--s-3);
  font-size: var(--t-sm);
  font-weight: 600;
  color: var(--c-text-secondary);
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
  grid-template-columns: 44px 68px minmax(0, 1fr) 56px auto;
  align-items: center;
  gap: var(--s-2);
  padding: var(--s-2) var(--s-3);
  border: 1px solid var(--c-border);
  border-radius: var(--r);
  font-size: var(--t-sm);
  font-variant-numeric: tabular-nums;
}

.list__dir {
  font-weight: 600;
}

.list__price {
  font-weight: 600;
}

.list__meta {
  color: var(--c-text-faint);
  font-size: var(--t-xs);
}

.list__state {
  font-size: var(--t-xs);
}

.list__state--on {
  color: var(--c-brand);
}

.list__state--off {
  color: var(--c-text-faint);
}

.list__actions {
  display: flex;
  gap: var(--s-2);
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

/* ---------- 涨跌色 ---------- */
.tone-up {
  color: #d93025;
}

.tone-down {
  color: #14803c;
}

:root[data-theme='dark'] .tone-up {
  color: #ff6b5e;
}

:root[data-theme='dark'] .tone-down {
  color: #45c26f;
}

@media (max-width: 560px) {
  .list__item {
    grid-template-columns: 44px 68px minmax(0, 1fr);
    row-gap: var(--s-1);
  }

  .list__actions {
    grid-column: 1 / -1;
    justify-content: flex-end;
  }
}
</style>
