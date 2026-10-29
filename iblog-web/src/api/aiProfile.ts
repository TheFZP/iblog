import service, { get } from './request'

/**
 * 用户投资画像接口（AI 分析的背景信息，后端键为 `ai_user_profile`）。
 *
 * <p>画像与 AI 工具 {@code save_user_profile} 共用同一行配置，所以用户在页面里
 * 写的、和 AI 自己记的，是同一条数据 —— 页面只需要读写这一份。</p>
 */

export interface AiProfile {
  /** 画像正文（Markdown）；从未保存过时是空串，后端不会返回 null */
  content: string
  /** 最后更新时间，形如 `yyyy-MM-ddTHH:mm:ss`；从未保存过为 null */
  updatedAt: string | null
}

export function fetchAiProfile(): Promise<AiProfile> {
  // request.ts 的响应拦截器在 code === 200 时已经把 R<T> 拆成了 data，这里拿到的就是画像本体
  return get<AiProfile>('/stock/ai/profile')
}

/**
 * 保存画像。空串表示清除（后端对空值走真删）。
 *
 * <p>PUT 只回 {@code R<Void>}，没有新的 updatedAt —— 调用方要展示时间戳只能本地补，
 * 这是接口既定的形状，不是漏读字段。</p>
 */
export async function saveAiProfile(content: string): Promise<void> {
  // request.ts 没有导出 put 便捷函数，直接用默认导出的 axios 实例；拦截器已把 R<T> 拆包，
  // 这里也不需要它的返回值（后端只回 R<Void>），所以直接 await 掉
  await service.put('/stock/ai/profile', { content })
}
