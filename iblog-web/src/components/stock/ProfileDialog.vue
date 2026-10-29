<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { fetchAiProfile, saveAiProfile } from '@/api/aiProfile'
import { renderMarkdown } from '@/utils/markdown'
import { formatDate } from '@/utils/format'

/**
 * 用户投资画像编辑弹窗。
 *
 * <p>画像由后端拼进 prompt 的 `{{USER_PROFILE}}` 占位符，是 AI 每次对话的长期背景，
 * 所以这里既要有编辑区（写给机器看），也要有预览区（写给用户自己看）。
 * 布局照搬 PriceAlertDialog 的骨架类名，与行情侧其它弹窗保持一致。</p>
 */

const props = defineProps<{ open: boolean }>()

const emit = defineEmits<{ close: []; saved: [content: string] }>()

/** 与后端 `AiChatController.PROFILE_MAX_LENGTH` 对齐；超限由后端也拦，但前端先拦能省一次往返 */
const MAX_LENGTH = 20000

/**
 * 模板提示里的占位符，单独放变量。
 *
 * <p>直接写进模板会变成 `{{ {{USER_PROFILE}} }}`，Vue 的插值解析会把这串花括号
 * 当成表达式吃坏，所以用一段普通字符串插值输出。</p>
 */
const profileToken = '{{USER_PROFILE}}'

/** 预置模板：五段覆盖 AI 最需要的维度，空着让用户按自己情况改，比一段空白强 */
const TEMPLATE = `## 投资风格

- 偏好中线波段（2 周 ~ 3 个月），不做打板短线
- 以基本面选股、技术面择时，两者冲突时以基本面为先

## 风险偏好

- 可承受单笔最大回撤 15%，账户整体回撤 20% 时主动降仓
- 单只个股仓位不超过总资产的 20%

## 持仓周期

- 主升段持有，跌破 20 日线且基本面无变化时减半
- 不做日内交易，不打新

## 关注行业

- 新能源车产业链、半导体设备、创新药
- 回避强周期资源股与纯概念炒作

## 禁忌

- 不碰 ST 与退市风险股
- 不碰上市不满一年的次新股
- 不加杠杆、不融资
`

const loading = ref(false)
const loadError = ref('')
const error = ref('')
const saving = ref(false)
const content = ref('')
/** 上一次成功读/写的正文，用来判断「是否有未保存修改」 */
const baseline = ref('')
const updatedAt = ref<string | null>(null)
const savedVisible = ref(false)

let loadSeq = 0
let savedTimer: number | null = null

const tooLong = computed(() => content.value.length > MAX_LENGTH)
const dirty = computed(() => content.value !== baseline.value)
const updatedText = computed(() => (updatedAt.value ? formatDate(updatedAt.value, true) : ''))

/**
 * 明显的注入片段粗筛。
 *
 * <p>项目没有引入 DOMPurify，`renderMarkdown` 的结果直接 `v-html` 就是一处 XSS 面。
 * 这里接受该风险的前提是：画像只有登录后的本人能编辑，后端从不渲染它，
 * 唯一的外部写入方是 AI 自己的 `save_user_profile` 工具 —— 风险等价于「你自己写的笔记」。</p>
 *
 * <p>但纵深防御仍要有：命中时降级成 `<pre>` 纯文本。必须说清楚的是，
 * 这<b>不是</b>一个可靠的过滤器 —— 它只做模式匹配，绕过写法无穷多，
 * 充其量提高随手粘贴恶意片段的门槛，不承担安全边界。</p>
 */
const SUSPICIOUS_RE =
  /<\s*(?:script|iframe|object|embed|svg|link|meta)\b|on(?:error|load|click|mouseover|focus|animationstart)\s*=|(?:java|vb)script\s*:|data\s*:\s*text\/html/i

function looksSuspicious(raw: string): boolean {
  return SUSPICIOUS_RE.test(raw)
}

const suspected = computed(() => looksSuspicious(content.value))

// 命中可疑片段时干脆不产出 HTML：不给 v-html 任何可乘之机，比先渲染再改走纯文本更直接
const previewHtml = computed(() => (suspected.value ? '' : renderMarkdown(content.value)))

function errText(err: unknown): string {
  return err instanceof Error ? err.message : String(err)
}

/** 本地时间戳，格式对齐后端的 `yyyy-MM-ddTHH:mm:ss` */
function nowStamp(): string {
  const d = new Date()
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

async function load() {
  const seq = ++loadSeq
  loading.value = true
  loadError.value = ''
  try {
    const data = await fetchAiProfile()
    // 快速关闭再打开会并发两次请求，只认最后一次，否则旧响应可能覆盖新内容
    if (seq !== loadSeq) {
      return
    }
    content.value = data.content ?? ''
    baseline.value = content.value
    updatedAt.value = data.updatedAt ?? null
  } catch (err) {
    if (seq !== loadSeq) {
      return
    }
    loadError.value = `读取失败：${errText(err)}`
  } finally {
    if (seq === loadSeq) {
      loading.value = false
    }
  }
}

function flashSaved() {
  savedVisible.value = true
  if (savedTimer !== null) {
    window.clearTimeout(savedTimer)
  }
  savedTimer = window.setTimeout(() => {
    savedVisible.value = false
    savedTimer = null
  }, 2000)
}

async function save() {
  if (saving.value || tooLong.value) {
    return
  }
  saving.value = true
  error.value = ''
  try {
    await saveAiProfile(content.value)
    baseline.value = content.value
    // PUT 只回空响应体，没有新时间戳：本地补一个，比为一个时间戳再发一次 GET 划算
    updatedAt.value = nowStamp()
    flashSaved()
    emit('saved', content.value)
  } catch (err) {
    // 失败时保留编辑区，用户改一改就能重试；清空等于把人写的东西弄丢了
    error.value = `保存失败：${errText(err)}`
  } finally {
    saving.value = false
  }
}

/** 清空只清编辑区：需要再点「保存」才真正落库，误点一下不至于把画像删了 */
function clearAll() {
  if (!content.value) {
    return
  }
  if (!window.confirm('清空编辑区？清空后需要点「保存」才会生效。')) {
    return
  }
  content.value = ''
  error.value = ''
}

function applyTemplate() {
  if (content.value.trim() && !window.confirm('编辑区已有内容，用清爽模板覆盖？')) {
    return
  }
  content.value = TEMPLATE
  error.value = ''
}

function requestClose() {
  // 保存进行中仍允许关闭（请求已经在飞），但修改未保存时必须拦一下
  if (dirty.value && !window.confirm('有未保存的修改，确定关闭？')) {
    return
  }
  emit('close')
}

function onWindowKeydown(event: KeyboardEvent) {
  // 只在打开时响应 Esc，避免关掉时抢走页面上其它弹窗的 Esc
  if (event.key === 'Escape' && props.open) {
    requestClose()
  }
}

watch(
  () => props.open,
  (open) => {
    if (!open) {
      return
    }
    savedVisible.value = false
    error.value = ''
    loadError.value = ''
    void load()
  },
  { immediate: true }
)

onMounted(() => {
  window.addEventListener('keydown', onWindowKeydown)
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onWindowKeydown)
  if (savedTimer !== null) {
    window.clearTimeout(savedTimer)
    savedTimer = null
  }
})
</script>

<template>
  <Teleport to="body">
    <div v-if="open" class="modal" @click.self="requestClose">
      <div class="modal__box" role="dialog" aria-modal="true" aria-label="投资画像">
        <header class="modal__head">
          <h2 class="modal__title">
            我的投资画像
            <span v-if="savedVisible" class="modal__saved">✓ 已保存</span>
          </h2>
          <button class="modal__close" aria-label="关闭" @click="requestClose">✕</button>
        </header>

        <div v-if="loading" class="loading">
          <span class="loading__row" />
          <span class="loading__row" />
          <span class="loading__row loading__row--short" />
          <span class="loading__text">读取中…</span>
        </div>

        <div v-else-if="loadError" class="notice notice--warn">
          {{ loadError }}
          <button class="notice__btn" @click="load">重试</button>
        </div>

        <template v-else>
          <div class="cols">
            <section class="col">
              <div class="col__head">
                <span class="col__label">编辑（支持 Markdown）</span>
              </div>
              <textarea
                v-model="content"
                class="editor"
                spellcheck="false"
                placeholder="例如：偏好中线波段，单笔回撤超过 15% 就减仓，关注半导体设备与创新药，不碰 ST…"
              />
            </section>

            <section class="col">
              <div class="col__head">
                <span class="col__label">预览</span>
              </div>
              <div class="preview">
                <p v-if="suspected" class="notice notice--warn preview__guard">
                  内容含可疑标记，已按纯文本显示
                </p>
                <pre v-if="suspected" class="preview__plain">{{ content }}</pre>
                <p v-else-if="!content.trim()" class="preview__empty">
                  还没有内容。写点什么，AI 每次分析都会带上它。
                </p>
                <!-- eslint-disable-next-line vue/no-v-html -->
                <div v-else class="markdown-body preview__body" v-html="previewHtml" />
              </div>
            </section>
          </div>

          <footer class="foot">
            <div class="foot__status">
              <span class="foot__count" :class="{ 'foot__count--over': tooLong }">
                {{ content.length }} / {{ MAX_LENGTH }}
              </span>
              <span v-if="updatedText" class="foot__meta">上次保存 {{ updatedText }}</span>
            </div>
            <p v-if="error" class="foot__error">{{ error }}</p>
            <div class="foot__actions">
              <button class="link foot__template" :disabled="saving" @click="applyTemplate">清爽模板</button>
              <button class="btn btn--ghost" :disabled="saving" @click="clearAll">清空</button>
              <button class="btn" :disabled="saving || tooLong || !dirty" @click="save">
                {{ saving ? '保存中…' : '保存' }}
              </button>
            </div>
          </footer>

          <p class="foot__hint">
            画像会作为 AI 分析的背景信息（<code>{{ profileToken }}</code>）注入每次对话，写得越具体越有用。
          </p>
        </template>
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
  width: min(1100px, 100%);
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
  gap: var(--s-3);
  margin-bottom: var(--s-4);
}

.modal__title {
  display: flex;
  align-items: center;
  gap: var(--s-3);
  margin: 0;
  font-size: var(--t-lg);
  font-weight: 600;
}

.modal__saved {
  font-size: var(--t-xs);
  font-weight: 500;
  color: var(--c-success);
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

/* ---------- 读取中 ---------- */
.loading {
  display: flex;
  flex-direction: column;
  gap: var(--s-2);
  padding: var(--s-4) 0 var(--s-6);
}

.loading__row {
  height: 14px;
  border-radius: var(--r-sm);
  background: var(--c-surface-sunken);
  animation: pulse 1.2s var(--ease) infinite;
}

.loading__row--short {
  width: 55%;
}

.loading__text {
  font-size: var(--t-sm);
  color: var(--c-text-faint);
}

@keyframes pulse {
  0%,
  100% {
    opacity: 0.55;
  }
  50% {
    opacity: 1;
  }
}

/* ---------- 提示条 ---------- */
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

/* ---------- 双栏 ---------- */
.cols {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: var(--s-4);
  padding-bottom: var(--s-5);
  border-bottom: 1px solid var(--c-border);
}

.col {
  display: flex;
  flex-direction: column;
  gap: var(--s-2);
  min-width: 0;
}

.col__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--s-2);
  min-height: 22px;
}

.col__label {
  font-size: var(--t-xs);
  color: var(--c-text-muted);
}

.editor {
  width: 100%;
  min-height: 320px;
  max-height: 46vh;
  padding: var(--s-3);
  border: 1px solid var(--c-border);
  border-radius: var(--r);
  background: var(--c-bg);
  color: var(--c-text);
  font-family: var(--f-mono);
  font-size: var(--t-sm);
  line-height: 1.7;
  resize: vertical;
}

.editor:focus {
  outline: none;
  border-color: var(--c-brand);
  box-shadow: 0 0 0 3px var(--c-brand-ring);
}

.preview {
  flex: 1;
  min-height: 320px;
  max-height: 46vh;
  overflow-y: auto;
  padding: var(--s-3);
  border: 1px solid var(--c-border);
  border-radius: var(--r);
  background: var(--c-surface-sunken);
}

.preview__guard {
  margin-bottom: var(--s-3);
}

.preview__plain {
  margin: 0;
  font-family: var(--f-mono);
  font-size: var(--t-sm);
  line-height: 1.7;
  color: var(--c-text-secondary);
  white-space: pre-wrap;
  word-break: break-word;
}

.preview__empty {
  margin: 0;
  font-size: var(--t-sm);
  color: var(--c-text-faint);
}

/* 全局 .markdown-body 是给博客正文用的 17px 衬线；预览框里偏大，压回正文字号 */
.preview__body {
  font-size: var(--t-base);
  line-height: 1.75;
}

/* v-html 生成的子节点没有 scoped 属性，标题尺寸只能走 :deep 才能命中 */
.preview__body :deep(h1),
.preview__body :deep(h2),
.preview__body :deep(h3) {
  margin: 1.1em 0 0.5em;
  font-size: var(--t-md);
}

/* ---------- 底栏 ---------- */
.foot {
  display: flex;
  align-items: center;
  gap: var(--s-3);
  flex-wrap: wrap;
  padding-top: var(--s-4);
}

.foot__status {
  display: flex;
  align-items: baseline;
  gap: var(--s-3);
}

.foot__count {
  font-size: var(--t-sm);
  color: var(--c-text-muted);
  font-variant-numeric: tabular-nums;
}

.foot__count--over {
  color: var(--stock-up);
  font-weight: 600;
}

.foot__meta {
  font-size: var(--t-xs);
  color: var(--c-text-faint);
}

.foot__error {
  flex: 1 1 100%;
  margin: 0;
  font-size: var(--t-sm);
  color: var(--stock-up);
}

.foot__actions {
  display: flex;
  align-items: center;
  gap: var(--s-2);
  margin-left: auto;
}

/* 模板按钮是次要动作：用链接样式与两个实心按钮拉开层级，避免三个按钮抢焦点 */
.foot__template {
  margin-right: var(--s-2);
  font-size: var(--t-sm);
  padding: 4px 2px;
}

.foot__template:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.foot__hint {
  margin: var(--s-3) 0 0;
  font-size: var(--t-xs);
  line-height: 1.7;
  color: var(--c-text-faint);
}

.foot__hint code {
  padding: 1px 4px;
  border-radius: var(--r-sm);
  background: var(--c-surface-sunken);
  font-family: var(--f-mono);
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

/* 文本域的焦点态已由 .editor:focus 的描边环表达，不重复加 outline */
.modal__close:focus-visible,
.link:focus-visible,
.notice__btn:focus-visible,
.btn:focus-visible {
  outline: 2px solid var(--c-brand);
  outline-offset: 2px;
}

@media (max-width: 760px) {
  .modal {
    padding: var(--s-3);
  }

  .modal__box {
    padding: var(--s-4);
  }

  .cols {
    grid-template-columns: minmax(0, 1fr);
  }

  .editor,
  .preview {
    max-height: 32vh;
  }
}
</style>
