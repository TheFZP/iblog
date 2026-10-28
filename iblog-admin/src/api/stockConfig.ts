import { get, put } from './request'

/**
 * 行情 / AI 配置接口。
 *
 * <p>后端是 `AdminStockConfigController`，白名单键写死在校验里：白名单外的键会被静默忽略，
 * 所以这里提交整份对象是安全的——用户没动过的 Key 会以打码值原样提交回去，
 * 后端看到含 `****` 的值会跳过，不会把真 Key 覆盖成星号。</p>
 */

/** 与后端 WRITABLE_KEYS 一一对应；敏感项只有 AI Key。 */
export interface StockConfig {
  ai_api_key: string
  ai_model: string
  ai_thinking_enabled: string
  ai_reasoning_effort: string
  ai_web_search_enabled: string
  quotes_refresh_ms: string
  kline_refresh_ms: string
}

export function fetchStockConfig(): Promise<StockConfig> {
  return get<StockConfig>('/admin/stock/config')
}

export function saveStockConfig(body: Partial<StockConfig>): Promise<void> {
  return put<void>('/admin/stock/config', body)
}
