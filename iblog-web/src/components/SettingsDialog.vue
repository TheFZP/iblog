<script setup lang="ts">
/**
 * 设置中心（Ctrl+, 或顶栏齿轮唤起）。
 *
 * <p>为什么集中到一处：原来「通知开关」在板块里没有入口、「刷新间隔」写死在代码里、
 * 「图表均线」藏在 K 线图上方的按钮里，用户在三个地方找同一件事。这里把通知、刷新、
 * 图表三类偏好放到同一屏，各自的数据层不变（通知+刷新走 `useSettings`，
 * 图表走 `useChartSettings`），只是把入口收到一处。</p>
 *
 * <p>AI 配置不在这里改：API Key 是服务端密钥，必须走后台的「AI 配置」页，
 * 网页端只显示当前状态并给一个跳转说明。</p>
 */
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useNotify } from '@/composables/useNotify'
import { fetchAiConfig, type AiConfig } from '@/api/ai'
import {
  MA_PERIOD_CHOICES,
  MAX_MA_PERIODS,
  useChartSettings
} from '@/composables/useChartSettings'
import {
  DEFAULT_SETTINGS,
  REFRESH_INTERVAL_CHOICES,
  useSettings
} from '@/composables/useSettings'
import {
  WATCH_TRIGGER_LABELS,
  useWatchlistNotifications,
  type WatchTriggerType
} from '@/composables/useWatchlistNotifications'

const props = defineProps<{ open: boolean }>()
const emit = defineEmits<{ close: [] }>()

const { settings, update, reset } = useSettings()
const { maPeriods, show30DayHL, toggleMaPeriod, setShow30DayHL, resetChartSettings } =
  useChartSettings()
const { permission, requestNotifyPermission } = useNotify()
const { listRecentNotifications, clearWatchNotifyHistory } = useWatchlistNotifications()

type TabKey = 'notify' | 'refresh' | 'chart' | 'about'
const TABS: Array<{ key: TabKey; label: string }> = [
  { key: 'notify', label: '通知' },
  { key: 'refresh', label: '刷新' },
  { key: 'chart', label: '图表' },
  { key: 'about', label: '关于' }
]
const tab = ref<TabKey>('notify')

/**
 * 刷新设置项的元数据。
 *
 * <p>写死在这而不是从 `useSettings` 导出：它只是「哪几个字段要显示成下拉框」的
 * 展示顺序，属于 UI 的排版决定，数据层不该知道面板怎么分组。</p>
 */
const REFRESH_ITEMS = [
  { key: 'indicesRefreshMs', label: '大盘指数', hint: '首页顶部的指数条' },
  { key: 'quotesRefreshMs', label: '个股行情', hint: '自选、持仓、详情页的价格' },
  { key: 'klineRefreshMs', label: '日 K 线', hint: '日线数据变化慢，可以放很长' },
  { key: 'intradayRefreshMs', label: '分时图', hint: '分时点位，太快没有意义' }
] as const

/**
 * 触发类型 → 设置字段名。
 *
 * <p>UI 用 `v-for` 遍历 `WATCH_TRIGGER_LABELS` 渲染八个开关，靠这张表把
 * `'+7%'` 这种带符号的键映射到 `notifyUp7` 这种合法字段名。</p>
 */
const NOTIFY_KEY_MAP = {
  limit_up: 'notifyLimitUp',
  limit_down: 'notifyLimitDown',
  '+7%': 'notifyUp7',
  '+5%': 'notifyUp5',
  '-7%': 'notifyDown7',
  '-5%': 'notifyDown5',
  fast_rise: 'notifyFastRise',
  fast_fall: 'notifyFastFall'
} as const

type NotifyKey = (typeof NOTIFY_KEY_MAP)[keyof typeof NOTIFY_KEY_MAP]

/** 把模板里的触发类型换成设置字段名 */
function notifyKey(type: string): NotifyKey {
  return NOTIFY_KEY_MAP[type as keyof typeof NOTIFY_KEY_MAP]
}

const aiConfig = ref<AiConfig | null>(null)
const aiLoading = ref(false)
const aiError = ref('')

/** 通知权限的文案与可操作性 */
const permissionText = computed(() => {
  switch (permission.value) {
    case 'granted':
      return '已开启'
    case 'denied':
      return '已被浏览器拒绝（需到站点设置里手动允许）'
    case 'unsupported':
      return '当前浏览器不支持网页通知'
    default:
      return '未开启'
  }
})

/** 最近通知记录，展示成「日期 代码 触发类型」 */
const recent = computed(() => {
  return listRecentNotifications(7).map((item) => ({
    ...item,
    text: item.types.map((t) => WATCH_TRIGGER_LABELS[t as WatchTriggerType]).join('，')
  }))
})

async function askPermission() {
  await requestNotifyPermission()
}

function loadAi() {
  if (aiConfig.value || aiLoading.value) {
    return
  }
  aiLoading.value = true
  aiError.value = ''
  fetchAiConfig()
    .then((config) => {
      aiConfig.value = config
    })
    .catch((e: unknown) => {
      aiError.value = e instanceof Error ? e.message : '读取 AI 配置失败'
    })
    .finally(() => {
      aiLoading.value = false
    })
}

function onResetRefresh() {
  update('indicesRefreshMs', DEFAULT_SETTINGS.indicesRefreshMs)
  update('quotesRefreshMs', DEFAULT_SETTINGS.quotesRefreshMs)
  update('klineRefreshMs', DEFAULT_SETTINGS.klineRefreshMs)
  update('intradayRefreshMs', DEFAULT_SETTINGS.intradayRefreshMs)
}

function onResetNotify() {
  reset()
}

function onResetChart() {
  resetChartSettings()
}

function onKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape' && props.open) {
    emit('close')
  }
}

// 切换到「关于」时才去读 AI 配置：绝大多数用户只改通知/刷新，没必要一开面板就打接口
function switchTab(next: TabKey) {
  tab.value = next
  if (next === 'about') {
    loadAi()
  }
}

onMounted(() => {
  window.addEventListener('keydown', onKeydown)
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKeydown)
})
</script>

<template>
  <Teleport to="body">
    <Transition name="st-fade">
      <div v-if="open" class="st-mask" @click.self="emit('close')">
        <div class="st-box" role="dialog" aria-modal="true" aria-label="设置">
          <header class="st-head">
            <h2 class="st-title">设置</h2>
            <button type="button" class="st-close" aria-label="关闭设置" @click="emit('close')">
              ✕
            </button>
          </header>

          <nav class="st-tabs" role="tablist" aria-label="设置分组">
            <button
              v-for="item in TABS"
              :key="item.key"
              type="button"
              role="tab"
              class="st-tab"
              :class="{ 'is-active': tab === item.key }"
              :aria-selected="tab === item.key"
              @click="switchTab(item.key)"
            >
              {{ item.label }}
            </button>
          </nav>

          <div class="st-body">
            <!-- ── 通知 ── -->
            <section v-if="tab === 'notify'" class="st-section">
              <div class="st-row">
                <div class="st-row__label">
                  <span>浏览器通知</span>
                  <span class="st-row__hint">{{ permissionText }}</span>
                </div>
                <button
                  v-if="permission !== 'granted' && permission !== 'unsupported'"
                  type="button"
                  class="btn btn--ghost st-btn"
                  @click="askPermission"
                >
                  申请权限
                </button>
              </div>

              <div class="st-row">
                <div class="st-row__label">
                  <span>自选股异动提醒</span>
                  <span class="st-row__hint">
                    涨停、跌停、涨跌超 5%/7%、短时快速拉升下跌，每个标的每类每天只提醒一次
                  </span>
                </div>
                <label class="st-switch">
                  <input
                    type="checkbox"
                    :checked="settings.notifyEnabled"
                    @change="update('notifyEnabled', ($event.target as HTMLInputElement).checked)"
                  />
                  <span />
                </label>
              </div>

              <fieldset class="st-fieldset" :disabled="!settings.notifyEnabled">
                <legend class="st-legend">触发条件</legend>
                <div class="st-checks">
                  <label v-for="(label, key) in WATCH_TRIGGER_LABELS" :key="key" class="st-check">
                    <input
                      type="checkbox"
                      :checked="settings[notifyKey(key)]"
                      @change="
                        update(
                          notifyKey(key),
                          ($event.target as HTMLInputElement).checked
                        )
                      "
                    />
                    <span>{{ label }}</span>
                  </label>
                </div>

                <div class="st-row st-row--tight">
                  <div class="st-row__label">
                    <span>快速变动阈值</span>
                    <span class="st-row__hint">一个巡检周期内相对昨收变动超过该比例就提醒</span>
                  </div>
                  <div class="st-threshold">
                    <input
                      v-model.number="settings.notifyFastThreshold"
                      type="range"
                      min="1"
                      max="5"
                      step="0.5"
                      aria-label="快速变动阈值"
                      @change="update('notifyFastThreshold', settings.notifyFastThreshold)"
                    />
                    <span class="st-threshold__value">{{ settings.notifyFastThreshold }}%</span>
                  </div>
                </div>
              </fieldset>

              <div class="st-row">
                <div class="st-row__label">
                  <span>价格提醒</span>
                  <span class="st-row__hint">你在个股页设置的价位线</span>
                </div>
                <label class="st-switch">
                  <input
                    type="checkbox"
                    :checked="settings.priceAlertsEnabled"
                    @change="update('priceAlertsEnabled', ($event.target as HTMLInputElement).checked)"
                  />
                  <span />
                </label>
              </div>

              <div class="st-sub">
                <div class="st-sub__head">
                  <h3>最近 7 天提醒</h3>
                  <button
                    v-if="recent.length > 0"
                    type="button"
                    class="st-link"
                    @click="clearWatchNotifyHistory"
                  >
                    清空记录
                  </button>
                </div>
                <p v-if="recent.length === 0" class="st-empty">还没有触发过提醒</p>
                <ul v-else class="st-history">
                  <li v-for="item in recent" :key="`${item.date}-${item.code}`">
                    <span class="st-history__date">{{ item.date }}</span>
                    <span class="st-history__code">{{ item.code }}</span>
                    <span class="st-history__types">{{ item.text }}</span>
                  </li>
                </ul>
              </div>

              <div class="st-actions">
                <button type="button" class="btn btn--ghost st-btn" @click="onResetNotify">
                  恢复通知默认
                </button>
              </div>
            </section>

            <!-- ── 刷新 ── -->
            <section v-else-if="tab === 'refresh'" class="st-section">
              <p class="st-note">
                交易时段按这里设置的间隔刷新；午休会放慢到 1/4，休市放慢到 1/20，
                页面在后台时再降一档。选「不自动刷新」就只在你手动点击时更新。
              </p>

              <div
                v-for="item in REFRESH_ITEMS"
                :key="item.key"
                class="st-row"
              >
                <div class="st-row__label">
                  <span>{{ item.label }}</span>
                  <span class="st-row__hint">{{ item.hint }}</span>
                </div>
                <select
                  class="st-select"
                  :value="settings[item.key]"
                  :aria-label="item.label"
                  @change="
                    update(item.key, Number(($event.target as HTMLSelectElement).value))
                  "
                >
                  <option v-for="opt in REFRESH_INTERVAL_CHOICES" :key="opt.value" :value="opt.value">
                    {{ opt.label }}
                  </option>
                </select>
              </div>

              <div class="st-actions">
                <button type="button" class="btn btn--ghost st-btn" @click="onResetRefresh">
                  恢复刷新默认
                </button>
              </div>
            </section>

            <!-- ── 图表 ── -->
            <section v-else-if="tab === 'chart'" class="st-section">
              <div class="st-row st-row--block">
                <div class="st-row__label">
                  <span>显示均线</span>
                  <span class="st-row__hint">最多同时 {{ MAX_MA_PERIODS }} 条，再多会糊成一片</span>
                </div>
                <div class="st-chips">
                  <button
                    v-for="p in MA_PERIOD_CHOICES"
                    :key="p"
                    type="button"
                    class="st-chip"
                    :class="{ 'is-on': maPeriods.includes(p) }"
                    :aria-pressed="maPeriods.includes(p)"
                    @click="toggleMaPeriod(p)"
                  >
                    {{ p }} 日
                  </button>
                </div>
              </div>

              <div class="st-row">
                <div class="st-row__label">
                  <span>近 30 日最高/最低参考线</span>
                  <span class="st-row__hint">在 K 线图上画两条虚线，方便看是否到关键位</span>
                </div>
                <label class="st-switch">
                  <input
                    type="checkbox"
                    :checked="show30DayHL"
                    @change="setShow30DayHL(($event.target as HTMLInputElement).checked)"
                  />
                  <span />
                </label>
              </div>

              <div class="st-actions">
                <button type="button" class="btn btn--ghost st-btn" @click="onResetChart">
                  恢复图表默认
                </button>
              </div>
            </section>

            <!-- ── 关于 ── -->
            <section v-else class="st-section">
              <div class="st-row st-row--block">
                <div class="st-row__label">
                  <span>AI 分析</span>
                  <span class="st-row__hint">
                    API Key 存在服务端，网页端无法修改，需到管理后台「AI 配置」里填写
                  </span>
                </div>
                <p v-if="aiLoading" class="st-empty">读取中…</p>
                <p v-else-if="aiError" class="st-empty">{{ aiError }}</p>
                <p v-else-if="aiConfig" class="st-empty">
                  <template v-if="aiConfig.available">
                    已配置 · 模型 {{ aiConfig.model }} · 联网
                    {{ aiConfig.webSearchEnabled ? '开' : '关' }} · 深度思考
                    {{ aiConfig.thinkingEnabled ? '开' : '关' }}
                  </template>
                  <template v-else>未配置（需在后台填写 DeepSeek API Key）</template>
                </p>
              </div>

              <div class="st-row st-row--block">
                <div class="st-row__label">
                  <span>快捷键</span>
                </div>
                <ul class="st-shortcuts">
                  <li><kbd>Ctrl</kbd> + <kbd>K</kbd> 全局搜索</li>
                  <li><kbd>Ctrl</kbd> + <kbd>N</kbd> AI 助手</li>
                  <li><kbd>Ctrl</kbd> + <kbd>,</kbd> 打开设置</li>
                  <li><kbd>Esc</kbd> 关闭弹窗</li>
                </ul>
              </div>

              <div class="st-row st-row--block">
                <div class="st-row__label">
                  <span>提醒能力的边界</span>
                  <span class="st-row__hint">
                    提醒依赖页面开着（后台标签页也算）。浏览器关闭后网页无法推送通知，
                    真正的离线推送需要 Service Worker 与推送服务，暂未实现。
                  </span>
                </div>
              </div>
            </section>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<script lang="ts">
/**
 * 组件名。`<script setup>` 里没法直接写 name，而 KeepAlive/DevTools 需要它；
 * 这里只补一个名字，不要把状态搬进来（会和 setup 里的 ref 打架）。
 */
export default { name: 'SettingsDialog' }
</script>

<style scoped>
.st-mask {
  position: fixed;
  inset: 0;
  z-index: 205;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: var(--s-4);
  background: rgb(0 0 0 / 45%);
  backdrop-filter: blur(2px);
}

.st-box {
  display: flex;
  flex-direction: column;
  width: min(600px, 100%);
  max-height: 78vh;
  border: 1px solid var(--c-border);
  border-radius: var(--r);
  background: var(--c-surface);
  box-shadow: 0 24px 60px rgb(0 0 0 / 28%);
}

.st-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--s-4) var(--s-5);
  border-bottom: 1px solid var(--c-border);
}

.st-title {
  margin: 0;
  font-size: var(--t-base);
  font-weight: 600;
  color: var(--c-text);
}

.st-close {
  width: 28px;
  height: 28px;
  border: 0;
  border-radius: var(--r-sm);
  background: transparent;
  color: var(--c-text-muted);
  cursor: pointer;
  font-size: var(--t-sm);
}

.st-close:hover {
  background: var(--c-surface-sunken);
  color: var(--c-text);
}

.st-tabs {
  display: flex;
  gap: var(--s-1);
  padding: var(--s-3) var(--s-5) 0;
  border-bottom: 1px solid var(--c-border);
}

.st-tab {
  padding: var(--s-2) var(--s-3);
  border: 0;
  border-bottom: 2px solid transparent;
  background: transparent;
  color: var(--c-text-muted);
  cursor: pointer;
  font-size: var(--t-sm);
  font-family: inherit;
}

.st-tab:hover {
  color: var(--c-text);
}

.st-tab.is-active {
  border-bottom-color: var(--c-brand);
  color: var(--c-text);
  font-weight: 600;
}

.st-body {
  flex: 1 1 auto;
  overflow-y: auto;
  padding: var(--s-4) var(--s-5) var(--s-5);
}

.st-section {
  display: flex;
  flex-direction: column;
  gap: var(--s-3);
}

.st-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--s-4);
  padding: var(--s-2) 0;
}

.st-row--tight {
  padding-top: var(--s-3);
}

.st-row--block {
  align-items: flex-start;
  flex-direction: column;
  gap: var(--s-2);
}

.st-row__label {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
  font-size: var(--t-sm);
  color: var(--c-text);
}

.st-row__hint {
  color: var(--c-text-faint);
  font-size: var(--t-xs);
  line-height: 1.5;
}

.st-btn {
  flex: 0 0 auto;
  min-height: 32px;
  font-size: var(--t-sm);
}

.st-switch {
  position: relative;
  flex: 0 0 auto;
  width: 40px;
  height: 22px;
  cursor: pointer;
}

.st-switch input {
  position: absolute;
  opacity: 0;
  width: 100%;
  height: 100%;
  margin: 0;
  cursor: pointer;
}

.st-switch span {
  position: absolute;
  inset: 0;
  border-radius: var(--r-full);
  background: var(--c-border);
  transition: background 0.15s var(--ease);
  pointer-events: none;
}

.st-switch span::after {
  content: '';
  position: absolute;
  top: 3px;
  left: 3px;
  width: 16px;
  height: 16px;
  border-radius: 50%;
  background: var(--c-surface);
  transition: transform 0.15s var(--ease);
}

.st-switch input:checked + span {
  background: var(--c-brand);
}

.st-switch input:checked + span::after {
  transform: translateX(18px);
}

.st-fieldset {
  margin: 0;
  padding: var(--s-3);
  border: 1px solid var(--c-border);
  border-radius: var(--r);
  min-width: 0;
}

.st-fieldset:disabled {
  opacity: 0.5;
}

.st-legend {
  padding: 0 var(--s-2);
  color: var(--c-text-muted);
  font-size: var(--t-xs);
}

.st-checks {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
  gap: var(--s-2);
}

.st-check {
  display: flex;
  align-items: center;
  gap: var(--s-2);
  padding: var(--s-1) 0;
  color: var(--c-text);
  font-size: var(--t-sm);
  cursor: pointer;
}

.st-threshold {
  display: flex;
  align-items: center;
  gap: var(--s-2);
  flex: 0 0 auto;
}

.st-threshold input[type='range'] {
  width: 110px;
  accent-color: var(--c-brand);
}

.st-threshold__value {
  min-width: 34px;
  color: var(--c-text-muted);
  font-size: var(--t-xs);
  font-variant-numeric: tabular-nums;
}

.st-select {
  flex: 0 0 auto;
  height: 32px;
  padding: 0 var(--s-2);
  border: 1px solid var(--c-border);
  border-radius: var(--r-sm);
  background: var(--c-surface);
  color: var(--c-text);
  font-size: var(--t-sm);
  font-family: inherit;
}

.st-chips {
  display: flex;
  flex-wrap: wrap;
  gap: var(--s-2);
}

.st-chip {
  padding: var(--s-1) var(--s-3);
  border: 1px solid var(--c-border);
  border-radius: var(--r-full);
  background: var(--c-surface);
  color: var(--c-text-muted);
  cursor: pointer;
  font-size: var(--t-xs);
  font-family: inherit;
  transition:
    background 0.15s var(--ease),
    color 0.15s var(--ease),
    border-color 0.15s var(--ease);
}

.st-chip.is-on {
  border-color: var(--c-brand);
  background: var(--c-brand);
  color: #fff;
}

.st-sub {
  padding-top: var(--s-3);
  border-top: 1px solid var(--c-border);
}

.st-sub__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--s-2);
}

.st-sub__head h3 {
  margin: 0;
  color: var(--c-text-muted);
  font-size: var(--t-xs);
  font-weight: 500;
}

.st-link {
  border: 0;
  background: transparent;
  color: var(--c-brand);
  cursor: pointer;
  font-size: var(--t-xs);
  font-family: inherit;
}

.st-empty {
  margin: 0;
  color: var(--c-text-faint);
  font-size: var(--t-xs);
  line-height: 1.6;
}

.st-history {
  display: flex;
  flex-direction: column;
  gap: var(--s-1);
  margin: 0;
  padding: 0;
  list-style: none;
  max-height: 160px;
  overflow-y: auto;
}

.st-history li {
  display: flex;
  gap: var(--s-3);
  padding: var(--s-1) 0;
  font-size: var(--t-xs);
}

.st-history__date {
  flex: 0 0 auto;
  color: var(--c-text-faint);
  font-variant-numeric: tabular-nums;
}

.st-history__code {
  flex: 0 0 auto;
  color: var(--c-text);
  font-variant-numeric: tabular-nums;
}

.st-history__types {
  flex: 1 1 auto;
  min-width: 0;
  color: var(--c-text-muted);
}

.st-note {
  margin: 0;
  padding: var(--s-3);
  border-radius: var(--r);
  background: var(--c-surface-sunken);
  color: var(--c-text-muted);
  font-size: var(--t-xs);
  line-height: 1.6;
}

.st-actions {
  display: flex;
  justify-content: flex-end;
  padding-top: var(--s-2);
}

.st-shortcuts {
  display: flex;
  flex-direction: column;
  gap: var(--s-1);
  margin: 0;
  padding: 0;
  list-style: none;
  color: var(--c-text-muted);
  font-size: var(--t-xs);
}

.st-shortcuts kbd {
  padding: 1px 5px;
  border: 1px solid var(--c-border);
  border-radius: 4px;
  background: var(--c-surface-sunken);
  font-family: inherit;
}

.st-fade-enter-active,
.st-fade-leave-active {
  transition: opacity 0.15s var(--ease);
}

.st-fade-enter-from,
.st-fade-leave-to {
  opacity: 0;
}

@media (max-width: 720px) {
  .st-box {
    max-height: 86vh;
  }

  .st-body {
    padding: var(--s-3) var(--s-4) var(--s-4);
  }

  .st-row {
    flex-wrap: wrap;
  }
}
</style>
