package com.iblog.module.paper.support;

/**
 * 新闻情绪分的加分/扣分计算（纯函数，便于单测）。
 *
 * <p>新闻分只是选股总分的一个<b>边际调整</b>：利好最多加 {@code max} 分、利空最多扣
 * {@code penalty} 分，绝不会盖过均线/量比/资金流这些主因子。可观测的原始分
 * （-100~100，来自 AI 或关键词兜底）先乘置信度，再线性映射到加分上限。</p>
 *
 * <p>为什么要乘置信度：一条「自媒体的传闻利好」即使原始分打到 90，乘上 0.3 的置信度
 * 也只有 27 分 → 换算成加分上限 15 分的 0.27 → 约 +4 分，影响可控；
 * 而官方公告置信度 0.9 时同样原始分能带来约 +14 分。置信度才是新闻真正拉开差距的地方。</p>
 */
public final class NewsScoreRule {

    /** 当日涨幅达到该值视为「利好已兑现」，利好加分打五折。 */
    private static final double PRICED_IN_CHANGE_PCT = 7.0;
    /** 连板高度达到该值视为「高位」，利好加分打五折。 */
    private static final int PRICED_IN_BOARD_HEIGHT = 2;
    private static final double PRICED_IN_FACTOR = 0.5;

    private NewsScoreRule() {
    }

    /**
     * 把原始新闻分换算成选股加/扣分。
     *
     * @param max     利好加分上限（&gt;=0）
     * @param penalty 利空扣分上限（&gt;=0）
     * @param score   原始新闻分 -100~100（正利好 / 负利空）
     * @param confidence 置信度 0~1（来源越官方越高）
     * @return 本次应加（正数）或扣（负数）的分
     */
    public static int bonus(double max, double penalty, int score, double confidence) {
        double conf = clamp(confidence, 0, 1);
        double effective = clamp(score / 100.0, -1, 1) * conf;
        if (effective >= 0) {
            return (int) Math.round(Math.max(max, 0) * effective);
        }
        return -(int) Math.round(Math.max(penalty, 0) * -effective);
    }

    /**
     * 利好兑现折扣：涨幅过大或已经连板时，利好对次日新增买盘的边际作用下降。
     *
     * <p>只打折正分，利空不 discount —— 高位出利空反而更危险，不能一起减弱。</p>
     *
     * @param bonus     原始加分（负数原样返回）
     * @param changePct 当日涨幅（%）
     * @param boardHeight 昨日连板高度（0 表示未涨停）
     */
    public static int pricedInDiscount(int bonus, double changePct, int boardHeight) {
        if (bonus <= 0) {
            return bonus;
        }
        if (changePct >= PRICED_IN_CHANGE_PCT || boardHeight >= PRICED_IN_BOARD_HEIGHT) {
            return (int) Math.round(bonus * PRICED_IN_FACTOR);
        }
        return bonus;
    }

    /** 置信度不足时不扣分也不加分（用于硬过滤判定：传闻不该把票误杀）。 */
    public static boolean filtered(int sharpScore, double confidence, double minConfidence, double minScore) {
        return confidence >= minConfidence && sharpScore < minScore;
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}