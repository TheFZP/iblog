import { ref } from 'vue'
import { getSiteConfig } from '@/api/portal'

const site = ref<Record<string, string>>({})
let loading: Promise<void> | null = null

/**
 * 站点配置全局共享，只请求一次。
 */
export function useSite() {
  function loadSite(): Promise<void> {
    if (Object.keys(site.value).length > 0) {
      return Promise.resolve()
    }
    if (!loading) {
      loading = getSiteConfig()
        .then((data) => {
          site.value = data || {}
        })
        .catch(() => {
          loading = null
        })
    }
    return loading
  }

  return { site, loadSite }
}

/* -------------------------------------------------------------
 *  深色模式
 *    默认跟随系统；用户手动切过就记住选择（localStorage）。
 *    在 index.html 里已有一小段内联脚本提前设好 data-theme，
 *    避免首屏闪白 —— 这里只负责切换和持久化。
 * ------------------------------------------------------------- */
const THEME_KEY = 'iblog-theme'
const theme = ref<'light' | 'dark'>('light')

function systemPrefersDark() {
  return typeof window !== 'undefined' && window.matchMedia('(prefers-color-scheme: dark)').matches
}

export function initTheme() {
  const saved = localStorage.getItem(THEME_KEY)
  theme.value = saved === 'dark' || saved === 'light' ? saved : systemPrefersDark() ? 'dark' : 'light'
  apply()
}

function apply() {
  document.documentElement.dataset.theme = theme.value
}

export function toggleTheme() {
  theme.value = theme.value === 'dark' ? 'light' : 'dark'
  localStorage.setItem(THEME_KEY, theme.value)
  apply()
}

export function useTheme() {
  return { theme, toggleTheme, initTheme }
}
