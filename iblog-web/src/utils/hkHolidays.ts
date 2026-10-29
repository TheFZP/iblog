/**
 * 港股公众假期表（港交所休市日）。
 *
 * <p><b>数据来源</b>：香港特区政府「公众假期」公告（GovHK）
 * https://www.gov.hk/en/about/abouthk/holiday/&lt;年份&gt;.htm
 * 假期名以公告为准，此处按项目用词习惯转写为简体简称。</p>
 *
 * <p><b>为什么要这张表</b>：港股与 A 股假期不同（佛诞、圣诞、复活节等 A 股正常开市），
 * 只看「周一至周五」会把休市日误判为交易日，导致盘外空轮询、以及国庆节照样显示「交易中」。
 * marketTime 上游的时间戳反证只能事后发现「数据没动」，假期表才是先验知识。</p>
 *
 * <p><b>维护</b>：GovHK 通常在当年年中公布翌年假期，届时在下方表内<b>整年完整</b>追加。
 * 未收录的年份由 {@link isHKHoliday} 返回 {@code null}，调用方退回「周一至周五」判定，
 * 功能不中断，只是可能误判（例如把 2028 年国庆节当成交易日）。</p>
 *
 * <p><b>已知限制</b>：不含「恶劣天气（八号风球 / 黑色暴雨）临时停市」——那是交易所临场决定、
 * 当天才公告，无法静态维护。遇到这种日子仍需依赖上游时间戳反证。</p>
 *
 * <p><b>日期一律用 {@code yyyy-MM-dd} 字符串</b>，且取年份用字符串前缀 {@code dateStr.slice(0, 4)}，
 * 不用 {@code new Date(dateStr)}：浏览器把 {@code "2026-10-01"} 当 UTC 零点解析，
 * 东八区转回本地就变成 9 月 30 日，而这个判定要用于「现在是否休市」，错一天就是误报。
 * 需要星期几时用 {@code Date.UTC} 构造 + {@code getUTCDay()} 读取（构造与读取同为 UTC，不受本机时区影响）。</p>
 */

/**
 * 每年假期：`[[日期, 假期名], ...]`，与官方公告逐条对应。
 *
 * <p>保留落在周末的条目（官方公告里本来就有），一是便于与公告逐条核对，
 * 二是将来若要按「补假」规则扩展时有原始信息可依。周末条目对交易日判定无副作用
 * ——{@link isHKTradingDay} 先判周末，再判假期。</p>
 */
export const HK_HOLIDAY_TABLE: Record<number, ReadonlyArray<readonly [string, string]>> = {
  2024: [
    ['2024-01-01', '元旦'],
    ['2024-02-10', '农历年初一'],
    ['2024-02-12', '农历年初三'],
    ['2024-02-13', '农历年初四'],
    ['2024-03-29', '耶稣受难节'],
    ['2024-03-30', '耶稣受难节翌日'],
    ['2024-04-01', '复活节星期一'],
    ['2024-04-04', '清明节'],
    ['2024-05-01', '劳动节'],
    ['2024-05-15', '佛诞'],
    ['2024-06-10', '端午节'],
    ['2024-07-01', '香港特别行政区成立纪念日'],
    ['2024-09-18', '中秋节翌日'],
    ['2024-10-01', '国庆日'],
    ['2024-10-11', '重阳节'],
    ['2024-12-25', '圣诞节'],
    ['2024-12-26', '圣诞节后第一个周日'],
  ],
  2025: [
    ['2025-01-01', '元旦'],
    ['2025-01-29', '农历年初一'],
    ['2025-01-30', '农历年初二'],
    ['2025-01-31', '农历年初三'],
    ['2025-04-04', '清明节'],
    ['2025-04-18', '耶稣受难节'],
    ['2025-04-19', '耶稣受难节翌日'],
    ['2025-04-21', '复活节星期一'],
    ['2025-05-01', '劳动节'],
    ['2025-05-05', '佛诞'],
    ['2025-05-31', '端午节'],
    ['2025-07-01', '香港特别行政区成立纪念日'],
    ['2025-10-01', '国庆日'],
    ['2025-10-07', '中秋节翌日'],
    ['2025-10-29', '重阳节'],
    ['2025-12-25', '圣诞节'],
    ['2025-12-26', '圣诞节后第一个周日'],
  ],
  2026: [
    ['2026-01-01', '元旦'],
    ['2026-02-17', '农历年初一'],
    ['2026-02-18', '农历年初二'],
    ['2026-02-19', '农历年初三'],
    ['2026-04-03', '耶稣受难节'],
    ['2026-04-04', '耶稣受难节翌日'],
    ['2026-04-06', '清明节翌日'],
    ['2026-04-07', '复活节星期一翌日'],
    ['2026-05-01', '劳动节'],
    ['2026-05-25', '佛诞翌日'],
    ['2026-06-19', '端午节'],
    ['2026-07-01', '香港特别行政区成立纪念日'],
    ['2026-09-26', '中秋节翌日'],
    ['2026-10-01', '国庆日'],
    ['2026-10-19', '重阳节翌日'],
    ['2026-12-25', '圣诞节'],
    ['2026-12-26', '圣诞节后第一个周日'],
  ],
  2027: [
    ['2027-01-01', '元旦'],
    ['2027-02-06', '农历年初一'],
    ['2027-02-08', '农历年初三'],
    ['2027-02-09', '农历年初四'],
    ['2027-03-26', '耶稣受难节'],
    ['2027-03-27', '耶稣受难节翌日'],
    ['2027-03-29', '复活节星期一'],
    ['2027-04-05', '清明节'],
    ['2027-05-01', '劳动节'],
    ['2027-05-13', '佛诞'],
    ['2027-06-09', '端午节'],
    ['2027-07-01', '香港特别行政区成立纪念日'],
    ['2027-09-16', '中秋节翌日'],
    ['2027-10-01', '国庆日'],
    ['2027-10-08', '重阳节'],
    ['2027-12-25', '圣诞节'],
    ['2027-12-27', '圣诞节后第一个周日'],
  ],
}

/** 已完整收录的年份（升序）。不硬编码数组，避免表加了年份却忘了同步 */
export const HK_HOLIDAY_YEARS: number[] = Object.keys(HK_HOLIDAY_TABLE)
  .map(Number)
  .sort((a, b) => a - b)

/** 已收录年份的集合，用于 O(1) 判断「这一年我们到底知不知道」 */
const HK_HOLIDAY_YEAR_SET: ReadonlySet<number> = new Set(HK_HOLIDAY_YEARS)

/**
 * 日期 → 假期名。模块加载时构建一次，之后每次调用都是 O(1)。
 *
 * <p>刻意不写成「每次调用遍历数组」：{@code marketTime.getMarketState} 可能被每秒调用，
 * 68 条线性扫描虽然不慢，但没有任何理由在热路径上反复做。</p>
 */
const HK_HOLIDAY_MAP: ReadonlyMap<string, string> = (() => {
  const map = new Map<string, string>()
  for (const year of HK_HOLIDAY_YEARS) {
    for (const [date, name] of HK_HOLIDAY_TABLE[year]) {
      map.set(date, name)
    }
  }
  return map
})()

/** 严格匹配 `yyyy-MM-dd`（补零）。宽松解析会让 `2024-1-1` 这类写法悄悄错位 */
const DATE_PATTERN = /^(\d{4})-(\d{2})-(\d{2})$/

/**
 * 从 `yyyy-MM-dd` 拆出年份与星期。
 *
 * <p>星期用 {@code Date.UTC} 构造再 {@code getUTCDay()} 读：构造与读取都在 UTC，
 * 与本机时区无关。读到的 0 是周日、6 是周六。</p>
 *
 * <p>格式不合法、或 `2026-02-30` 这种不存在的日期，一律返回 {@code null}
 * ——交给调用方当作「无法判定」，而不是硬凑一个错的星期。</p>
 */
function parseDateParts(dateStr: string): { year: number; weekday: number } | null {
  const matched = DATE_PATTERN.exec(dateStr)
  if (!matched) return null

  const year = Number(matched[1])
  const month = Number(matched[2])
  const day = Number(matched[3])
  if (month < 1 || month > 12 || day < 1 || day > 31) return null

  const utc = new Date(Date.UTC(year, month - 1, day))
  // 回读校验：Date.UTC 会把超范围的日期滚到下个月，滚动过就说明这天不存在
  if (utc.getUTCMonth() + 1 !== month || utc.getUTCDate() !== day) return null

  return { year, weekday: utc.getUTCDay() }
}

/**
 * 某日是否为港股休市日（公众假期）。
 *
 * <p><b>三态语义，务必按三态使用</b>：</p>
 * <ul>
 *   <li>{@code true} —— 已收录年份的公众假期；</li>
 *   <li>{@code false} —— 已收录年份的<b>非</b>假期（真的查过了）；</li>
 *   <li>{@code null} —— 该年份没收录 / 参数为空 / 日期格式非法（<b>不知道</b>）。</li>
 * </ul>
 *
 * <p><b>不要用 {@code false} 兼表「未知」</b>：未知年份若返回 false，调用方会认为
 * 「查过了，不是假期」，于是在 2028 年国庆节照样显示「交易中」——比不判还糟，
 * 因为那是自信的错误。三态把「不知道」显式暴露出来，调用方才能决定怎么降级。</p>
 *
 * @param dateStr `yyyy-MM-dd`；null / undefined / 空串均安全
 * @returns 假期 true / 非假期 false / 未知 null
 */
export function isHKHoliday(dateStr: string | null | undefined): boolean | null {
  if (!dateStr) return null

  const parts = parseDateParts(dateStr)
  if (!parts) return null
  if (!HK_HOLIDAY_YEAR_SET.has(parts.year)) return null

  return HK_HOLIDAY_MAP.has(dateStr)
}

/**
 * 假期名。
 *
 * <p>非假期、年份未收录、参数为空一律返回空串——「没有名字」这件事用空串表达就够了，
 * 不必再引入一个 null，否则调用方要同时处理两种空值。</p>
 */
export function getHKHolidayName(dateStr: string | null | undefined): string {
  if (!dateStr) return ''
  return HK_HOLIDAY_MAP.get(dateStr) ?? ''
}

/**
 * 是否为港股交易日：周末与公众假期都返回 false。
 *
 * <p>未收录年份走「周一至周五」兜底（{@link isHKHoliday} 返回 null 时不判假期），
 * 功能不中断但可能把某年国庆节误判为交易日，这是可接受的降级。</p>
 *
 * <p>先判周末再判假期：两者是「或」的关系，且周末判定是纯算术、无需查表。</p>
 */
export function isHKTradingDay(dateStr: string | null | undefined): boolean {
  if (!dateStr) return false

  const parts = parseDateParts(dateStr)
  // 日期非法时不给「交易日」，宁可少报也不要把一个说不清的日子当成开市
  if (!parts) return false

  if (parts.weekday === 0 || parts.weekday === 6) return false

  // null（未知年份）!== true → 放行，即周一至周五兜底
  return isHKHoliday(dateStr) !== true
}
