<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted } from 'vue'
import AiPanel from '@/components/stock/AiPanel.vue'
import { closeGlobalAi, takeStoredPreset, useGlobalAi } from '@/composables/useGlobalAi'

/**
 * 全局 AI 入口的挂载点。
 *
 * <p>放在 {@code AppLayout} 里而不是某个页面里：AI 助手要在官网任何页面都能唤起
 * （看文章时想问「这只票怎么样」、在问财页想让它分析选出来的这批）。
 * 挂在布局层意味着切路由时组件不重建，对话也不会因为跳页面被清空。</p>
 *
 * <p>面板本身复用详情页的 {@code AiPanel}，只是不打个股 code——
 * 后端 {@code AiContextBuilder} 见到空 code 会走「全局对话」分支，不预加载个股行情。</p>
 */
const { open, preset } = useGlobalAi()

/** 问财页跳过来时带着结果：第一次打开面板就把它读进来 */
const pendingPreset = computed(() => preset.value)

function onPresetConsumed() {
  // 面板已经把它灌进输入框了，这里清掉，免得关掉再打开又灌一遍
  preset.value = ''
}

function show(stored?: string) {
  if (stored) {
    preset.value = stored
  }
  open.value = true
}

/**
 * Ctrl/⌘ + N 唤起。
 *
 * <p>桌面版用的是系统级全局热键（Tauri 能力），网页端只能做到「页面在前台时」响应。</p>
 *
 * <p>{@code event.preventDefault()} 的必要性：Chrome 的 Ctrl+N 是「新建窗口」，
 * 不拦下来用户会同时弹出新窗口。⌘+N 在 Mac 上同样如此。</p>
 *
 * <p>不问焦点在哪：Ctrl+N 不是文字输入组合键，不该因为光标在搜索框里就失效。</p>
 */
function onWindowKeydown(event: KeyboardEvent) {
  const isMod = event.ctrlKey || event.metaKey
  if (!isMod || event.key.toLowerCase() !== 'n' || event.shiftKey || event.altKey) {
    return
  }
  event.preventDefault()
  if (open.value) {
    closeGlobalAi()
    return
  }
  show(takeStoredPreset())
}

onMounted(() => {
  window.addEventListener('keydown', onWindowKeydown)
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onWindowKeydown)
})
</script>

<template>
  <AiPanel
    :open="open"
    code=""
    name=""
    :preset="pendingPreset"
    @close="closeGlobalAi"
    @preset-consumed="onPresetConsumed"
  />
</template>
