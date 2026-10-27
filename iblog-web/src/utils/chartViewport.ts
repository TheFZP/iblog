/**
 * K 线图视口计算：滚轮缩放后让「最新一根」贴住右边缘。
 *
 * <p>lightweight-charts 的滚轮缩放以鼠标位置为锚点：鼠标偏左时放大，
 * 最新几根会被挤出右侧视口。这里把可见逻辑范围重新算成右端对齐最后一根，
 * 让缩放围绕「最新时间」进行。</p>
 *
 * <p>逻辑索引 i 的 K 线占据 [i-0.5, i+0.5]，所以最后一根（索引 dataLength-1）
 * 的右边缘是 dataLength - 0.5；fitContent 后的范围是 [-0.5, dataLength-0.5]，
 * 跨度为 dataLength。</p>
 */

/** 与 lightweight-charts 的 LogicalRange 结构兼容的最小类型 */
export interface LogicalRangeLike {
  from: number
  to: number
}

/**
 * 计算结果：
 * - `null`：不需要调整（已对齐、数据为空、范围非法）
 * - `'fit'`：已缩到全览，交给 `fitContent()` 统一贴边
 * - `{ from, to }`：需要设置的可见逻辑范围
 */
export type RightLock = LogicalRangeLike | 'fit' | null

/**
 * 计算「最新时间贴右边缘」的可见逻辑范围。
 *
 * @param dataLength 数据根数
 * @param range 当前可见逻辑范围（`timeScale().getVisibleLogicalRange()` 的返回值）
 */
export function latestRightRange(
  dataLength: number,
  range: LogicalRangeLike | null | undefined
): RightLock {
  if (!range || dataLength <= 0) {
    return null
  }
  const span = range.to - range.from
  if (!Number.isFinite(span) || span <= 0) {
    return null
  }
  // 已经缩到全览：左右两端都贴边，交给 fitContent 统一处理
  if (span >= dataLength) {
    return 'fit'
  }
  const rightEdge = dataLength - 0.5
  if (Math.abs(range.to - rightEdge) < 0.01) {
    return null
  }
  return { from: rightEdge - span, to: rightEdge }
}
