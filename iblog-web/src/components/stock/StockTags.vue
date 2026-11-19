<template>
  <span v-if="tags.length" class="stock-tags">
    <span
      v-for="tag in tags"
      :key="tag.key"
      class="stock-tag"
      :class="`stock-tag--${tag.key}`"
      :title="tag.title"
    >
      {{ tag.label }}
    </span>
  </span>
</template>

<script setup lang="ts">
/**
 * 股票代码前的板块 / 两融标签：科（科创板）、创（创业板）、融（融资融券标的）。
 * 组件自己触发清单加载，所以任何页面只要放了它就能用，不必各自记得拉数据。
 */
import { computed, onMounted } from 'vue'
import { useMarginTargets } from '@/composables/useMarginTargets'
import { boardTag } from '@/utils/stockTags'

const props = defineProps<{ code?: string | null }>()

const { ensureMarginTargets, isMargin } = useMarginTargets()
onMounted(ensureMarginTargets)

const tags = computed(() => {
  const list: { key: string; label: string; title: string }[] = []
  const board = boardTag(props.code)
  if (board === '科') list.push({ key: 'star', label: '科', title: '科创板' })
  else if (board === '创') list.push({ key: 'chinext', label: '创', title: '创业板' })
  if (isMargin(props.code)) list.push({ key: 'margin', label: '融', title: '融资融券标的' })
  return list
})
</script>

<style scoped>
.stock-tags {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  margin-right: 4px;
  vertical-align: middle;
}

.stock-tag {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 15px;
  height: 15px;
  padding: 0 3px;
  border: 1px solid transparent;
  border-radius: 3px;
  font-family: var(--f-sans, inherit);
  font-size: 10px;
  font-weight: 700;
  line-height: 1;
}

.stock-tag--star {
  color: #3b82f6;
  background: color-mix(in srgb, #3b82f6 14%, transparent);
  border-color: color-mix(in srgb, #3b82f6 35%, transparent);
}

.stock-tag--chinext {
  color: #f59e0b;
  background: color-mix(in srgb, #f59e0b 14%, transparent);
  border-color: color-mix(in srgb, #f59e0b 35%, transparent);
}

.stock-tag--margin {
  /* 橙色，但比「创」的琥珀色更偏红，避免创业板的票两个标签看起来一样 */
  color: #f97316;
  background: color-mix(in srgb, #f97316 14%, transparent);
  border-color: color-mix(in srgb, #f97316 35%, transparent);
}
</style>