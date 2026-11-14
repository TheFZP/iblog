<template>
  <div class="weather-app">
    <header class="top">
      <a class="top__back" href="/tools">
        <svg viewBox="0 0 24 24" aria-hidden="true">
          <path d="M15 5l-7 7 7 7" />
        </svg>
        工具箱
      </a>
      <span class="top__divider" aria-hidden="true" />
      <h1 class="top__title">AI 天气</h1>
      <span class="top__status" :class="statusClass">{{ statusText }}</span>

      <div class="top__actions">
        <button
          class="icon-btn"
          type="button"
          :aria-label="theme === 'dark' ? '切换到浅色模式' : '切换到深色模式'"
          @click="toggleTheme"
        >
          <svg v-if="theme === 'dark'" viewBox="0 0 24 24" aria-hidden="true">
            <circle cx="12" cy="12" r="4.2" />
            <path d="M12 2.6v2M12 19.4v2M2.6 12h2M19.4 12h2M5.4 5.4l1.4 1.4M17.2 17.2l1.4 1.4M18.6 5.4l-1.4 1.4M6.8 17.2l-1.4 1.4" />
          </svg>
          <svg v-else viewBox="0 0 24 24" aria-hidden="true">
            <path d="M20.6 14.4A8.7 8.7 0 0 1 9.6 3.4a8.6 8.6 0 1 0 11 11Z" />
          </svg>
        </button>
      </div>
    </header>

    <main class="stage">
      <section class="search">
        <form class="search__form" @submit.prevent="search()">
          <input
            v-model="cityInput"
            class="search__input"
            type="text"
            maxlength="20"
            autocomplete="off"
            placeholder="输入城市，如 广州、杭州、北京市"
            aria-label="城市名"
          />
          <button class="btn" type="submit" :disabled="loading || !cityInput.trim()">
            {{ loading ? '查询中…' : '查看天气' }}
          </button>
        </form>
        <div class="quick">
          <button
            v-for="item in QUICK"
            :key="item"
            type="button"
            class="quick__btn"
            :class="{ 'quick__btn--on': item === data?.location.city.replace(/市$/, '') }"
            @click="search(item)"
          >
            {{ item }}
          </button>
        </div>
      </section>

      <div v-if="loading && !data" class="placeholder">正在获取天气数据…</div>

      <div v-else-if="error && !data" class="placeholder placeholder--error">
        <p>{{ error }}</p>
        <button class="btn btn--ghost" type="button" @click="search()">重试</button>
      </div>

      <template v-else-if="data">
        <section class="now">
          <div class="now__main">
            <WeatherIcon :type="data.now.weather" :size="104" class="now__icon" />
            <div class="now__temp-wrap">
              <p class="now__temp">{{ tempText }}<span>℃</span></p>
              <p class="now__desc">
                {{ data.location.city }}
                <span v-if="data.location.province">· {{ data.location.province }}</span>
                · {{ data.now.weather }}
              </p>
              <p class="now__range">{{ data.now.low }}° ~ {{ data.now.high }}°</p>
            </div>
          </div>

          <dl class="metrics">
            <div class="metric">
              <dt>湿度</dt>
              <dd>{{ data.now.humidity || '—' }}</dd>
            </div>
            <div class="metric">
              <dt>空气质量</dt>
              <dd>
                {{ data.now.quality || '—' }}
                <span v-if="data.now.aqi">AQI {{ data.now.aqi }}</span>
              </dd>
            </div>
            <div class="metric">
              <dt>PM2.5</dt>
              <dd>{{ data.now.pm25 === null ? '—' : data.now.pm25 }}</dd>
            </div>
            <div class="metric">
              <dt>风向风力</dt>
              <dd>{{ data.now.wind || '—' }} {{ data.now.windLevel }}</dd>
            </div>
            <div class="metric">
              <dt>日出 / 日落</dt>
              <dd>{{ data.now.sunrise || '—' }} / {{ data.now.sunset || '—' }}</dd>
            </div>
            <div class="metric">
              <dt>数据时间</dt>
              <dd>{{ data.updateDate }} {{ data.updateTime }}</dd>
            </div>
          </dl>
        </section>

        <section class="agent">
          <header class="agent__head">
            <span class="agent__badge">AI</span>
            <div class="agent__titles">
              <h2 class="agent__title">天气管家</h2>
              <p class="agent__sub">
                <template v-if="ai?.available">
                  {{ ai.model }} · {{ (ai.elapsedMs / 1000).toFixed(1) }}s
                </template>
                <template v-else>接入了模型的天气助手，会先查数据再给建议</template>
              </p>
            </div>
          </header>

          <form class="ask" @submit.prevent="ask(question)">
            <input
              v-model="question"
              class="ask__input"
              type="text"
              maxlength="200"
              placeholder="想问点什么？例如：明天适合跑步吗"
              :disabled="aiLoading"
            />
            <button class="btn btn--ghost" type="submit" :disabled="aiLoading || !data">
              {{ aiLoading ? '思考中…' : '问 AI' }}
            </button>
          </form>

          <div v-if="aiLoading" class="agent__loading">
            <span class="dot" /><span class="dot" /><span class="dot" />
            正在查天气、组织建议…
          </div>

          <p v-else-if="aiError" class="agent__error">{{ aiError }}</p>

          <template v-else-if="ai">
            <ul v-if="ai.traces.length" class="traces">
              <li v-for="(trace, i) in ai.traces" :key="i" class="trace" :class="{ 'trace--bad': !trace.ok }">
                <span class="trace__dot" />
                <span class="trace__label">{{ trace.label }}</span>
                <span class="trace__result">{{ trace.result }}</span>
              </li>
            </ul>

            <p v-if="ai.answer" class="agent__answer">{{ ai.answer }}</p>
            <p v-else class="agent__note">{{ ai.message || summary }}</p>

            <p v-if="!ai.available" class="agent__hint">
              没有配置模型 Key 时，这里会退回到上面的数据摘要；AI 配置在管理后台。
            </p>
          </template>

          <p v-else class="agent__note">{{ summary }}</p>
        </section>

        <section class="daily">
          <h2 class="daily__title">未来 {{ data.days.length }} 天</h2>
          <div class="days">
            <article v-for="(day, i) in data.days" :key="day.date" class="day" :class="{ 'day--today': i === 0 }">
              <p class="day__week">{{ weekText(day.week) }}</p>
              <p class="day__date">{{ day.date.slice(5) }}</p>
              <WeatherIcon :type="day.weather" :size="44" class="day__icon" />
              <p class="day__type">{{ day.weather }}</p>
              <p class="day__temp">
                <strong>{{ day.high }}°</strong>
                <span>{{ day.low }}°</span>
              </p>
              <p class="day__wind">{{ day.wind }} {{ day.windLevel }}</p>
              <p class="day__notice">{{ day.notice }}</p>
            </article>
          </div>
        </section>

        <footer class="foot">
          <span>{{ summary }}</span>
          <span>天气数据来自中国天气网 · AI 建议由模型生成，仅供参考</span>
        </footer>
      </template>
    </main>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { initTheme, toggleTheme, useTheme } from '@/composables/useSite'
import WeatherIcon from '@/components/tools/WeatherIcon.vue'
import {
  fetchWeatherAdvice,
  fetchWeatherForecast,
  type WeatherAdvice,
  type WeatherForecast
} from '@/api/weather'

const { theme } = useTheme()
onMounted(initTheme)

const QUICK = ['广州', '北京', '上海', '深圳', '杭州', '成都']
const STORAGE_KEY = 'iblog:weather-city'

const cityInput = ref('广州')
const data = ref<WeatherForecast | null>(null)
const summary = ref('')
const loading = ref(false)
const error = ref('')

const ai = ref<WeatherAdvice | null>(null)
const aiLoading = ref(false)
const aiError = ref('')
const question = ref('')

let searchSeq = 0
let aiSeq = 0

const tempText = computed(() => {
  const value = data.value?.now.temp ?? 0
  return Number.isInteger(value) ? String(value) : value.toFixed(1)
})

const statusText = computed(() => {
  if (loading.value) return '查询中'
  if (error.value) return '查询失败'
  if (data.value) return `更新于 ${data.value.updateTime || data.value.updateDate}`
  return '等待查询'
})

const statusClass = computed(() => {
  if (error.value) return 'is-error'
  if (loading.value) return 'is-busy'
  if (data.value) return 'is-ok'
  return 'is-idle'
})

function weekText(week: string): string {
  return week.replace('星期', '周')
}

function messageOf(e: unknown): string {
  return e instanceof Error && e.message ? e.message : '天气数据获取失败，请稍后重试'
}

async function search(city?: string) {
  const target = (city ?? cityInput.value).trim()
  if (!target) return
  cityInput.value = target
  try {
    localStorage.setItem(STORAGE_KEY, target)
  } catch {
    // 隐私模式下 localStorage 可能不可用，不影响查询
  }

  const seq = ++searchSeq
  const nextAi = ++aiSeq
  loading.value = true
  error.value = ''
  ai.value = null
  aiError.value = ''
  aiLoading.value = false

  try {
    const response = await fetchWeatherForecast(target)
    if (seq !== searchSeq) return
    data.value = response.forecast
    summary.value = response.summary
    loading.value = false
    void ask('', nextAi)
  } catch (e) {
    if (seq !== searchSeq) return
    data.value = null
    summary.value = ''
    error.value = messageOf(e)
    loading.value = false
  }
}

async function ask(text = question.value, inheritedSeq?: number) {
  if (!data.value) return
  const city = data.value.location.city || cityInput.value
  const seq = inheritedSeq ?? ++aiSeq
  if (inheritedSeq !== undefined) {
    aiSeq = inheritedSeq
  }
  aiLoading.value = true
  aiError.value = ''
  ai.value = null
  try {
    const result = await fetchWeatherAdvice(city, text.trim())
    if (seq !== aiSeq) return
    ai.value = result
    if (result.available && !result.answer && result.message) {
      aiError.value = result.message
    }
  } catch (e) {
    if (seq !== aiSeq) return
    aiError.value = messageOf(e)
  } finally {
    if (seq === aiSeq) aiLoading.value = false
  }
}

onMounted(() => {
  let saved = ''
  try {
    saved = localStorage.getItem(STORAGE_KEY) || ''
  } catch {
    saved = ''
  }
  if (saved) cityInput.value = saved
  void search()
})
</script>

<style scoped>
.weather-app {
  min-height: 100vh;
  min-height: 100dvh;
  background: var(--c-bg);
  color: var(--c-text);
}

/* 顶部条：与 JSON 工具保持一致 */
.top {
  display: flex;
  align-items: center;
  gap: var(--s-3);
  padding: var(--s-3) var(--s-5);
  border-bottom: 1px solid var(--c-border);
  background: var(--c-bg);
  position: sticky;
  top: 0;
  z-index: 5;
}
.top__back {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: var(--c-text-secondary);
  font-size: var(--t-sm);
}
.top__back svg {
  width: 16px;
  height: 16px;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.8;
  stroke-linecap: round;
  stroke-linejoin: round;
}
.top__back:hover {
  color: var(--c-brand);
}
.top__divider {
  width: 1px;
  height: 18px;
  background: var(--c-border-strong);
}
.top__title {
  font-size: var(--t-md);
  font-weight: 640;
  letter-spacing: -0.01em;
}
.top__status {
  font-size: var(--t-xs);
  padding: 2px 10px;
  border-radius: var(--r-full);
  background: var(--c-surface-sunken);
  color: var(--c-text-muted);
  white-space: nowrap;
}
.top__status.is-ok {
  background: color-mix(in srgb, var(--c-success) 16%, transparent);
  color: var(--c-success);
}
.top__status.is-error {
  background: color-mix(in srgb, var(--stock-up) 16%, transparent);
  color: var(--stock-up);
}
.top__status.is-busy {
  background: color-mix(in srgb, var(--c-brand) 14%, transparent);
  color: var(--c-brand);
}
.top__actions {
  margin-left: auto;
}
.icon-btn {
  display: grid;
  place-items: center;
  width: 38px;
  height: 38px;
  border: 1px solid var(--c-border);
  border-radius: var(--r);
  background: var(--c-surface);
  color: var(--c-text-secondary);
  cursor: pointer;
  transition: border-color 0.15s var(--ease), color 0.15s var(--ease);
}
.icon-btn:hover {
  border-color: var(--c-border-strong);
  color: var(--c-brand);
}
.icon-btn svg {
  width: 20px;
  height: 20px;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.7;
  stroke-linecap: round;
  stroke-linejoin: round;
}

/* 内容 */
.stage {
  width: min(1080px, 100%);
  margin: 0 auto;
  padding: var(--s-6) var(--s-5) var(--s-10);
  display: flex;
  flex-direction: column;
  gap: var(--s-5);
}

.search {
  display: flex;
  flex-direction: column;
  gap: var(--s-3);
}
.search__form {
  display: flex;
  gap: var(--s-3);
}
.search__input {
  flex: 1 1 auto;
  min-width: 0;
  height: 46px;
  padding: 0 var(--s-4);
  border: 1px solid var(--c-border);
  border-radius: var(--r);
  background: var(--c-surface);
  color: var(--c-text);
  font-size: var(--t-md);
  outline: none;
  transition: border-color 0.15s var(--ease);
}
.search__input:focus {
  border-color: var(--c-brand);
}
.quick {
  display: flex;
  flex-wrap: wrap;
  gap: var(--s-2);
}
.quick__btn {
  padding: 4px 12px;
  border: 1px solid var(--c-border);
  border-radius: var(--r-full);
  background: transparent;
  color: var(--c-text-muted);
  font-size: var(--t-xs);
  cursor: pointer;
  transition: color 0.15s var(--ease), border-color 0.15s var(--ease);
}
.quick__btn:hover,
.quick__btn--on {
  color: var(--c-brand);
  border-color: var(--c-brand);
}

.btn {
  height: 46px;
  padding: 0 var(--s-5);
  border: 1px solid transparent;
  border-radius: var(--r);
  background: var(--c-brand);
  color: #fff;
  font-size: var(--t-sm);
  font-weight: 540;
  cursor: pointer;
  transition: background-color 0.15s var(--ease), opacity 0.15s var(--ease);
}
.btn:hover:not(:disabled) {
  background: var(--c-brand-hover);
}
.btn:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}
.btn--ghost {
  height: 40px;
  background: transparent;
  border-color: var(--c-border);
  color: var(--c-text-secondary);
}
.btn--ghost:hover:not(:disabled) {
  background: var(--c-surface-sunken);
  color: var(--c-text);
  border-color: var(--c-border-strong);
}

.placeholder {
  padding: var(--s-10) 0;
  text-align: center;
  color: var(--c-text-faint);
  font-size: var(--t-sm);
}
.placeholder--error {
  color: var(--stock-up);
}
.placeholder .btn {
  margin-top: var(--s-4);
}

/* 当前天气 */
.now {
  display: grid;
  grid-template-columns: minmax(0, 1.2fr) minmax(0, 1.8fr);
  gap: var(--s-6);
  padding: var(--s-6);
  border: 1px solid var(--c-border);
  border-radius: var(--r-lg);
  background:
    radial-gradient(120% 160% at 0% 0%, color-mix(in srgb, var(--c-brand) 10%, transparent), transparent 60%),
    var(--c-surface);
}
.now__main {
  display: flex;
  align-items: center;
  gap: var(--s-5);
}
.now__icon {
  flex: 0 0 auto;
}
.now__temp {
  margin: 0;
  font-size: 64px;
  line-height: 1;
  letter-spacing: -0.04em;
  font-variant-numeric: tabular-nums;
}
.now__temp span {
  font-size: 24px;
  letter-spacing: 0;
  color: var(--c-text-muted);
  margin-left: 2px;
}
.now__desc {
  margin: var(--s-2) 0 0;
  font-size: var(--t-md);
  color: var(--c-text-secondary);
}
.now__range {
  margin: var(--s-1) 0 0;
  font-size: var(--t-sm);
  color: var(--c-text-faint);
  font-variant-numeric: tabular-nums;
}

.metrics {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--s-4);
  margin: 0;
  align-content: center;
}
.metric {
  min-width: 0;
}
.metric dt {
  font-size: var(--t-xs);
  color: var(--c-text-faint);
  margin-bottom: 2px;
}
.metric dd {
  margin: 0;
  font-size: var(--t-sm);
  color: var(--c-text);
  font-variant-numeric: tabular-nums;
}
.metric dd span {
  display: block;
  font-size: var(--t-xs);
  color: var(--c-text-muted);
}

/* AI 卡片 */
.agent {
  padding: var(--s-5);
  border: 1px solid color-mix(in srgb, var(--c-brand) 30%, var(--c-border));
  border-radius: var(--r-lg);
  background: var(--c-surface);
  display: flex;
  flex-direction: column;
  gap: var(--s-4);
}
.agent__head {
  display: flex;
  align-items: center;
  gap: var(--s-3);
}
.agent__badge {
  display: grid;
  place-items: center;
  width: 38px;
  height: 38px;
  border-radius: var(--r);
  background: var(--c-brand);
  color: #fff;
  font-size: var(--t-xs);
  font-weight: 700;
  letter-spacing: 0.04em;
}
.agent__titles {
  min-width: 0;
}
.agent__title {
  margin: 0;
  font-size: var(--t-lg);
  font-weight: 640;
  letter-spacing: -0.01em;
}
.agent__sub {
  margin: 2px 0 0;
  font-size: var(--t-xs);
  color: var(--c-text-faint);
}
.ask {
  display: flex;
  gap: var(--s-2);
}
.ask__input {
  flex: 1 1 auto;
  min-width: 0;
  height: 40px;
  padding: 0 var(--s-3);
  border: 1px solid var(--c-border);
  border-radius: var(--r);
  background: var(--c-bg);
  color: var(--c-text);
  font-size: var(--t-sm);
  outline: none;
}
.ask__input:focus {
  border-color: var(--c-brand);
}
.agent__loading {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: var(--t-sm);
  color: var(--c-text-muted);
}
.dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--c-brand);
  animation: pulse 1.2s infinite ease-in-out;
}
.dot:nth-child(2) {
  animation-delay: 0.15s;
}
.dot:nth-child(3) {
  animation-delay: 0.3s;
  margin-right: var(--s-2);
}
@keyframes pulse {
  0%,
  100% {
    opacity: 0.25;
  }
  50% {
    opacity: 1;
  }
}
.traces {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-wrap: wrap;
  gap: var(--s-2);
}
.trace {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  max-width: 100%;
  padding: 4px 10px;
  border-radius: var(--r-full);
  background: var(--c-surface-sunken);
  font-size: var(--t-xs);
  color: var(--c-text-muted);
}
.trace__dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--c-success);
  flex: 0 0 auto;
}
.trace--bad .trace__dot {
  background: var(--stock-up);
}
.trace__label {
  color: var(--c-text-secondary);
  font-weight: 560;
}
.trace__result {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.agent__answer {
  margin: 0;
  font-size: var(--t-md);
  line-height: 1.75;
  color: var(--c-text);
  white-space: pre-wrap;
}
.agent__note {
  margin: 0;
  font-size: var(--t-sm);
  line-height: 1.7;
  color: var(--c-text-secondary);
}
.agent__error {
  margin: 0;
  font-size: var(--t-sm);
  color: var(--stock-up);
}
.agent__hint {
  margin: 0;
  font-size: var(--t-xs);
  color: var(--c-text-faint);
}

/* 七日预报 */
.daily__title {
  margin: 0 0 var(--s-3);
  font-size: var(--t-sm);
  font-weight: 620;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: var(--c-text-secondary);
}
.days {
  display: grid;
  grid-template-columns: repeat(7, minmax(0, 1fr));
  gap: var(--s-2);
}
.day {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  padding: var(--s-4) var(--s-2);
  border: 1px solid var(--c-border);
  border-radius: var(--r);
  background: var(--c-surface);
  text-align: center;
}
.day--today {
  border-color: color-mix(in srgb, var(--c-brand) 45%, var(--c-border));
  background: color-mix(in srgb, var(--c-brand) 6%, var(--c-surface));
}
.day__week {
  margin: 0;
  font-size: var(--t-xs);
  color: var(--c-text-secondary);
}
.day__date {
  margin: 0;
  font-size: var(--t-xs);
  color: var(--c-text-faint);
  font-variant-numeric: tabular-nums;
}
.day__icon {
  margin: var(--s-1) 0;
}
.day__type {
  margin: 0;
  font-size: var(--t-sm);
  color: var(--c-text);
  min-height: 1.4em;
}
.day__temp {
  margin: 0;
  font-size: var(--t-sm);
  font-variant-numeric: tabular-nums;
  color: var(--c-text-muted);
}
.day__temp strong {
  color: var(--c-text);
  font-weight: 640;
}
.day__temp span {
  margin-left: 4px;
}
.day__wind {
  margin: 0;
  font-size: var(--t-xs);
  color: var(--c-text-faint);
}
.day__notice {
  margin: 2px 0 0;
  font-size: var(--t-xs);
  line-height: 1.4;
  color: var(--c-text-faint);
}

.foot {
  display: flex;
  flex-direction: column;
  gap: var(--s-2);
  padding-top: var(--s-4);
  border-top: 1px solid var(--c-border);
  font-size: var(--t-xs);
  color: var(--c-text-faint);
  line-height: 1.6;
}

@media (max-width: 900px) {
  .now {
    grid-template-columns: 1fr;
  }
  .days {
    grid-template-columns: repeat(auto-fill, minmax(120px, 1fr));
  }
}

@media (max-width: 560px) {
  .stage {
    padding: var(--s-4) var(--s-4) var(--s-8);
  }
  .now {
    padding: var(--s-5);
  }
  .now__temp {
    font-size: 52px;
  }
  .now__icon {
    width: 76px;
    height: 76px;
  }
  .metrics {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .search__form {
    flex-direction: column;
  }
  .search__form .btn {
    width: 100%;
  }
}
</style>
