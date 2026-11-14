<template>
  <div class="layout">
    <header class="page-head">
      <p class="page-head__eyebrow">小工具</p>
      <h1 class="page-head__title">工具箱</h1>
      <p class="page-head__desc">
        随手要处理点什么的时候，到这里来。每个工具都是独立页面，点击会在新标签页打开，不打断你正在读的内容。
      </p>
    </header>

    <ul class="tools">
      <li v-for="tool in tools" :key="tool.path" class="tools__item">
        <a class="tool" :href="tool.path" target="_blank" rel="noopener">
          <span class="tool__icon" aria-hidden="true">
            <svg viewBox="0 0 24 24">
              <path :d="tool.icon" />
            </svg>
          </span>
          <span class="tool__body">
            <span class="tool__head">
              <span class="tool__name">{{ tool.name }}</span>
              <svg class="tool__arrow" viewBox="0 0 24 24" aria-hidden="true">
                <path d="M7 17 17 7M9 7h8v8" />
              </svg>
            </span>
            <span class="tool__desc">{{ tool.desc }}</span>
            <span class="tool__tags">
              <span v-for="t in tool.tags" :key="t" class="chip">{{ t }}</span>
            </span>
          </span>
        </a>
      </li>
    </ul>
  </div>
</template>

<script setup lang="ts">
interface Tool {
  path: string
  name: string
  desc: string
  icon: string
  tags: string[]
}

const tools: Tool[] = [
  {
    path: '/tools/json',
    name: 'JSON 在线解析',
    desc: '粘贴一段 JSON，格式化、压缩、排序键、校验，出错时定位到具体行和列。',
    icon: 'M9.5 4.5H8.8A2.3 2.3 0 0 0 6.5 6.8v2.6c0 1.1-.6 1.8-1.7 1.9v.4c1.1.1 1.7.8 1.7 1.9v2.6a2.3 2.3 0 0 0 2.3 2.3h.7M14.5 4.5h.7a2.3 2.3 0 0 1 2.3 2.3v2.6c0 1.1.6 1.8 1.7 1.9v.4c-1.1.1-1.7.8-1.7 1.9v2.6a2.3 2.3 0 0 1-2.3 2.3h-.7',
    tags: ['格式化', '压缩', '校验']
  },
  {
    path: '/tools/weather',
    name: 'AI 天气',
    desc: '查看实时天气和未来一周预报，再让 AI 管家结合天气给出穿衣、出行和带伞建议。',
    icon: 'M7 17.5h10.5a3.5 3.5 0 0 0 .4-7 6 6 0 0 0-11.4-1.5A4.3 4.3 0 0 0 7 17.5ZM9 20.5l-1 2M13 20.5l-1 2M17 20.5l-1 2',
    tags: ['实时天气', '7 日预报', 'AI 建议']
  }
]
</script>

<style scoped>
.page-head {
  padding-bottom: var(--s-6);
  margin-bottom: var(--s-8);
  border-bottom: 2px solid var(--c-text);
}
.page-head__eyebrow {
  margin: 0 0 var(--s-3);
  font-size: var(--t-xs);
  letter-spacing: 0.16em;
  text-transform: uppercase;
  color: var(--c-text-faint);
}
.page-head__title {
  font-size: var(--t-3xl);
  letter-spacing: -0.03em;
  line-height: 1.1;
}
.page-head__desc {
  margin-top: var(--s-3);
  max-width: 56ch;
  font-size: var(--t-md);
  color: var(--c-text-muted);
}

.tools {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: var(--s-5);
}
.tool {
  display: flex;
  gap: var(--s-4);
  padding: var(--s-5);
  background: var(--c-surface);
  border: 1px solid var(--c-border);
  border-radius: var(--r-lg);
  color: inherit;
  transition: border-color 0.15s var(--ease), transform 0.15s var(--ease), box-shadow 0.15s var(--ease);
}
.tool:hover {
  border-color: var(--c-border-strong);
  transform: translateY(-2px);
  box-shadow: var(--e);
  color: inherit;
}
.tool__icon {
  flex: 0 0 auto;
  width: 44px;
  height: 44px;
  display: grid;
  place-items: center;
  border-radius: var(--r);
  background: var(--c-brand-soft);
  color: var(--c-brand);
}
.tool__icon svg {
  width: 24px;
  height: 24px;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.7;
  stroke-linecap: round;
  stroke-linejoin: round;
}
.tool__body {
  display: flex;
  flex-direction: column;
  gap: var(--s-2);
  min-width: 0;
}
.tool__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--s-2);
}
.tool__name {
  font-size: var(--t-lg);
  font-weight: 640;
  letter-spacing: -0.01em;
}
.tool__arrow {
  width: 18px;
  height: 18px;
  fill: none;
  stroke: var(--c-text-faint);
  stroke-width: 1.8;
  stroke-linecap: round;
  stroke-linejoin: round;
  transition: transform 0.15s var(--ease), stroke 0.15s var(--ease);
}
.tool:hover .tool__arrow {
  stroke: var(--c-brand);
  transform: translate(2px, -2px);
}
.tool__desc {
  font-size: var(--t-sm);
  color: var(--c-text-secondary);
  line-height: 1.6;
}
.tool__tags {
  display: flex;
  flex-wrap: wrap;
  gap: var(--s-2);
  margin-top: var(--s-1);
}
.chip {
  padding: 3px 10px;
  border-radius: var(--r-full);
  background: var(--c-surface-sunken);
  color: var(--c-text-muted);
  font-size: var(--t-xs);
  white-space: nowrap;
}

@media (max-width: 560px) {
  .tools {
    grid-template-columns: 1fr;
  }
}
</style>
