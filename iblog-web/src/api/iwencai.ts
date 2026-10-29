import { post } from './request'
import { ensureIwencaiV, resetIwencaiV } from '@/utils/iwencaiV'

/**
 * 问财自然语言选股代理接口。
 *
 * <p>问财要求 Cookie `v`，而这个值只能由真实浏览器里的 chameleon.js 算出来，
 * 所以链路是「浏览器算 v → 传给我们自己的后端 → 后端带齐请求头转发」。
 * 这里的职责有三件：拿 v、在风控时换 v 重试一次、把重复问题缓存住。</p>
 *
 * <p><b>翻页在本地做</b>：实测问财服务端会忽略 `page` 参数（page=2/3 返回的内容与第
 * 1 页完全一致），只有 `perpage` 会改变返回行数（上限 100）。所以一次请求把整批
 * 行拉回来，页面用前端切片翻页。缓存键因此<b>必须带上 perpage</b>，否则先发起的
 * perpage=50 的结果会被 perpage=100 的请求复用，导致后者以为拿全了实际只有 50 行。</p>
 */

/** 后端返回的问财原始结果（见 IwencaiClient.IwencaiResult）。 */
export interface IwencaiResult {
  /** 列名，与 rows 里每个数组的下标一一对应 */
  columns: string[]
  /** 二维数组：每行是按 columns 顺序排列的单元格值 */
  rows: string[][]
  /** 问财对本次查询的解析结论，可能为空 */
  condition: string
  rowCount: number
}

export interface IwencaiRequest {
  question: string
  page?: number
  perPage?: number
}

/** 会话级缓存：同一问题在 10 分钟内复用结果，避免重复查询触发问财限流。 */
const CACHE_TTL = 10 * 60 * 1000
/** 缓存条数上限，超出丢最旧的（Map 迭代顺序即插入顺序）。 */
const CACHE_MAX = 50
const cache = new Map<string, { data: IwencaiResult; ts: number }>()

/** 归一化问题文本做缓存键：去空白、转小写。 */
function normalizeQuestion(question: string): string {
  return question.replace(/\s+/g, '').toLowerCase()
}

/**
 * 判断错误是否属于「凭证失效 / 被风控」，这类错误换一个新 v 重试一次就能恢复。
 *
 * <p>后端已经把这些情况统一收敛成 429 并给出可辨认的中文文案，所以这里匹配
 * 文案关键词即可；不能去匹配单独的字母 `v`（错误信息里几乎总带这个字母）。</p>
 */
function isCredentialError(message: string): boolean {
  return (
    message.includes('凭证') ||
    message.includes('429') ||
    message.includes('403') ||
    message.includes('限流') ||
    message.includes('频繁')
  )
}

/**
 * 查询问财。
 *
 * @param question 自然语言选股条件
 * @param perPage  一次拉取的行数（后端上限 100）
 * @returns 归一化后的结果；调用方需自行按页切片
 */
export async function queryIwencai(question: string, perPage = 50): Promise<IwencaiResult> {
  const text = question.trim()
  if (!text) {
    throw new Error('请输入选股条件')
  }
  const key = `${normalizeQuestion(text)}|${perPage}`
  const hit = cache.get(key)
  if (hit && Date.now() - hit.ts < CACHE_TTL) {
    return hit.data
  }

  let result: IwencaiResult
  try {
    result = await post<IwencaiResult>('/stock/ai/iwencai', {
      question: text,
      page: 1,
      perPage,
      v: await ensureIwencaiV()
    })
  } catch (err) {
    const message = err instanceof Error ? err.message : String(err)
    if (!isCredentialError(message)) {
      throw err
    }
    // 同一个 v 连续请求几次就会被风控（实测 6 次左右），换一个新的立刻恢复。
    resetIwencaiV()
    await new Promise((resolve) => setTimeout(resolve, 1500))
    let freshV = ''
    try {
      freshV = await ensureIwencaiV()
    } catch {
      // 拿不到新 v 就用空串再试一次，让后端给出更准确的错误文案
      freshV = ''
    }
    result = await post<IwencaiResult>('/stock/ai/iwencai', {
      question: text,
      page: 1,
      perPage,
      v: freshV
    })
  }

  if (cache.size >= CACHE_MAX) {
    const oldest = cache.keys().next().value
    if (oldest != null) {
      cache.delete(oldest)
    }
  }
  cache.set(key, { data: result, ts: Date.now() })
  return result
}

/** 清空会话缓存（用户手动刷新结果时用）。 */
export function clearIwencaiCache(): void {
  cache.clear()
}

/**
 * 从单元格值里解析股票代码。
 *
 * <p>问财的代码列可能是 `301565.SZ`（带市场后缀）也可能是 `301565`，
 * 统一取前 6 位数字；解析不出就返回 null，调用方据此决定该行能不能点击跳转。</p>
 */
export function parseIwencaiCode(value: string | undefined | null): string | null {
  if (!value) {
    return null
  }
  const match = String(value).trim().match(/^(\d{6})(?:\.(SH|SZ|BJ))?$/i)
  return match ? match[1] : null
}

/** 代码列在问财结果里的候选列名（不同问句下叫法不一样）。 */
const CODE_COLUMNS = ['股票代码', '代码', '证券代码']

/** 名称列候选。 */
const NAME_COLUMNS = ['股票简称', '股票名称', '名称', '证券简称']

/** 找某一类列的下标；找不到返回 -1。 */
export function findColumn(columns: string[], candidates: string[]): number {
  for (const candidate of candidates) {
    const index = columns.findIndex((col) => col === candidate)
    if (index >= 0) {
      return index
    }
  }
  // 精确匹配不到时退一步做模糊匹配：问财偶尔会把列名改成「股票代码[20261029]」这种
  for (const candidate of candidates) {
    const index = columns.findIndex((col) => col.includes(candidate))
    if (index >= 0) {
      return index
    }
  }
  return -1
}

/** 从一行里取出股票代码；取不到返回 null。 */
export function rowCode(columns: string[], row: string[]): string | null {
  const index = findColumn(columns, CODE_COLUMNS)
  if (index < 0) {
    return null
  }
  return parseIwencaiCode(row[index])
}

/** 从一行里取出股票名称；取不到返回空串。 */
export function rowName(columns: string[], row: string[]): string {
  const index = findColumn(columns, NAME_COLUMNS)
  return index < 0 ? '' : (row[index] ?? '')
}
