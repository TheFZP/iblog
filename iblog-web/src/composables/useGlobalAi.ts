import { ref } from 'vue'

/**
 * 全局 AI 入口的开关状态。
 *
 * <p>用模块级单例而不是组件内 ref：导航栏按钮、快捷键、问财页跳转过来的预置内容
 * 都要能打开同一个面板。挂在谁身上都会让另外两个入口拿不到状态。</p>
 */
const open = ref(false)
const preset = ref('')

/** 问财页把选股结果拼成文本后写这个键，这里负责读走并清掉 */
export const AI_PRESET_KEY = 'iblog-ai-preset'

export function useGlobalAi() {
  return {
    /** 面板是否展开 */
    open,
    /** 待灌入输入框的预置内容（不自动发送） */
    preset,
    openGlobalAi,
    closeGlobalAi,
    toggleGlobalAi,
    /** 从 sessionStorage 取一次问财预置内容，没有则返回空串 */
    takeStoredPreset,
    /** 读取（不清除）当前预置内容，面板消费后调 clearPreset */
    clearPreset
  }
}

/**
 * 打开全局 AI 面板。
 *
 * <p>{@code nextPreset} 会覆盖当前预置内容——用户点了「AI 分析这批股票」，
 * 期待的一定是刚选出来的这批，而不是上一批残留。</p>
 */
export function openGlobalAi(nextPreset?: string): void {
  if (nextPreset) {
    preset.value = nextPreset
  }
  open.value = true
}

export function closeGlobalAi(): void {
  open.value = false
}

export function toggleGlobalAi(): void {
  open.value = !open.value
}

/**
 * 从 sessionStorage 取出问财页留下的选股结果并清除。
 *
 * <p>必须清除：问财页每次点「AI 分析这批股票」都会跳 `/stock`，
 * 不清的话下次进行情页又会把上一次的结果重新灌进来。</p>
 *
 * <p>隐私模式或配额满时 localStorage 会抛错，这里全部吞掉——没有预置内容顶多让用户
 * 自己打字，不该让页面白屏。</p>
 */
export function takeStoredPreset(): string {
  try {
    const text = sessionStorage.getItem(AI_PRESET_KEY)
    if (text) {
      sessionStorage.removeItem(AI_PRESET_KEY)
    }
    return text ?? ''
  } catch {
    return ''
  }
}

export function clearPreset(): void {
  preset.value = ''
}
