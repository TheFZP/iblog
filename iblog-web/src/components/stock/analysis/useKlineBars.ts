import { onMounted, ref, watch, type Ref } from 'vue'
import { fetchKline, type KlineItem } from '@/api/stock'

/**
 * 四个 Tab 共用的日 K 加载逻辑。
 *
 * <p>每个 Tab 各自持有一个实例（组件级闭包），所以竞态守卫、加载态、错误态互不干扰：
 * 切 Tab 时前一个 Tab 未完成的请求回来，也不会污染新 Tab 的数据。</p>
 *
 * <p>这里只拉日 K：技术指标/支撑阻力/回测的口径都以「日 K 收盘」为准，
 * 混入周/月 K 会让 MA5 的语义悄悄变成「5 周均线」。</p>
 */
export function useKlineBars(code: Ref<string>, limit: number) {
  const bars = ref<KlineItem[]>([])
  const loading = ref(true)
  const error = ref('')
  let seq = 0

  async function load() {
    const value = code.value
    if (!value) {
      bars.value = []
      loading.value = false
      return
    }
    const my = ++seq
    loading.value = true
    error.value = ''
    try {
      const list = await fetchKline(value, 'day', limit)
      if (my !== seq) {
        return
      }
      bars.value = Array.isArray(list) ? list : []
    } catch (err) {
      if (my !== seq) {
        return
      }
      // 失败时保留上一次的 bars：K 线偶发超时不该把已算好的指标整片清空
      error.value = err instanceof Error ? err.message : String(err)
    } finally {
      if (my === seq) {
        loading.value = false
      }
    }
  }

  onMounted(load)
  watch(code, () => {
    void load()
  })

  return { bars, loading, error, reload: load }
}
