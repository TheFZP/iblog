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
