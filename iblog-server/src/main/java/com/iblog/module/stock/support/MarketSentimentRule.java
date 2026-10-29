package com.iblog.module.stock.support;

/**
 * 市场情绪打分的纯函数（无 Spring 依赖，便于单测）。
 *
 * <p>思路：情绪是「赚钱效应」，不是「指数涨跌」。同样上证涨 0.3%，
 * 上涨 3000 家、涨停 70 家那种日子和上涨 800 家、涨停 20 家的日子，
 * 对打板/短线策略的意义完全不同。所以这里用五个维度加权，
 * 每个维度都换成 0~100 的分项，再按权重合成总分。</p>
 *
 * <p>阈值全部取自 A 股这些年的常态区间（涨停 40~60 家算中性、跌停超过 40 家算差、
 * 成交额 8000 亿~2 万亿算常态），写成常量方便以后按实测调。</p>
 */
public final class MarketSentimentRule {

    // ---------- 维度权重（合计 100） ----------
    /** 涨跌家数（市场宽度）：最直观的赚钱效应 */
    public static final double WEIGHT_BREADTH = 30;
    /** 涨跌停家数对比：短线情绪的强弱 */
    public static final double WEIGHT_LIMIT = 25;
    /** 炸板率：接力资金敢不敢接 */
    public static final double WEIGHT_BROKEN = 15;
    /** 指数涨跌幅：大体方向 */
    public static final double WEIGHT_INDEX = 20;
    /** 两市成交额：增量资金在不在 */
    public static final double WEIGHT_AMOUNT = 10;

    // ---------- 分项阈值 ----------
    /** 上涨占比 20% 以下视为普跌（0 分） */
    public static final double BREADTH_WORST = 0.20;
    /** 上涨占比 80% 以上视为普涨（满分） */
    public static final double BREADTH_BEST = 0.80;
    /** 涨停 15 家以下视为短线冰点（涨停项 0 分） */
    public static final int LIMIT_UP_COLD = 15;
    /** 涨停 80 家以上视为短线高潮（涨停项满分） */
    public static final int LIMIT_UP_HOT = 80;
    /** 跌停 40 家以上视为恐慌（跌停项 0 分） */
    public static final int LIMIT_DOWN_PANIC = 40;
    /** 炸板率 50% 以上视为接力失败（0 分） */
    public static final double BROKEN_WORST = 0.50;
    /** 指数平均涨跌 ±2% 视为区间两端 */
    public static final double INDEX_RANGE_PCT = 2.0;
    /** 成交额 6000 亿以下视为缩量（0 分） */
    public static final double AMOUNT_MIN_YI = 6000;
    /** 成交额 2 万亿以上视为放量（满分） */
    public static final double AMOUNT_MAX_YI = 20000;

    // ---------- 等级阈值 ----------
    public static final double LEVEL_HOT = 75;
    public static final double LEVEL_ACTIVE = 60;
    public static final double LEVEL_NEUTRAL = 40;
    public static final double LEVEL_WEAK = 25;

    private MarketSentimentRule() {
    }

    /** 上涨占比分项：涨 20% 以下 0 分，80% 以上满分，中间线性。 */
    public static double breadthScore(int up, int down) {
        int total = up + down;
        if (total <= 0) {
            return Double.NaN;
        }
        double ratio = (double) up / total;
        return clamp01((ratio - BREADTH_WORST) / (BREADTH_BEST - BREADTH_WORST)) * 100;
    }

    /**
     * 涨跌停分项：涨停越多越好、跌停越少越好，两者按 65% / 35% 合成。
     *
     * <p>为什么跌停权重低于涨停：跌停家数常年个位数到几十家波动幅度大，
     * 而涨停家数是短线资金意愿的直接读数，「一天 80 家涨停」比「没有跌停」
     * 更能说明可以做多。</p>
     */
    public static double limitScore(int limitUp, int limitDown) {
        if (limitUp < 0 || limitDown < 0) {
            return Double.NaN;
        }
        double up = clamp01((limitUp - LIMIT_UP_COLD) / (double) (LIMIT_UP_HOT - LIMIT_UP_COLD));
        double down = clamp01(1 - limitDown / (double) LIMIT_DOWN_PANIC);
        return (up * 0.65 + down * 0.35) * 100;
    }

    /** 炸板率分项：0% 满分，50% 及以上 0 分。样本不足（涨停+炸板为 0）返回 NaN。 */
    public static double brokenScore(int limitUp, int broken) {
        int base = limitUp + broken;
        if (base <= 0) {
            return Double.NaN;
        }
        double rate = (double) broken / base;
        return clamp01(1 - rate / BROKEN_WORST) * 100;
    }

    /** 指数分项：平均涨跌 −2% 为 0 分、+2% 为满分、0 为 50 分。 */
    public static double indexScore(double changePct) {
        if (Double.isNaN(changePct)) {
            return Double.NaN;
        }
        return clamp01((changePct + INDEX_RANGE_PCT) / (INDEX_RANGE_PCT * 2)) * 100;
    }

    /** 成交额分项：6000 亿 0 分、2 万亿满分，线性（对数尺度会让千亿级差异失真，这里保持线性）。 */
    public static double amountScore(double amountYi) {
        if (Double.isNaN(amountYi) || amountYi <= 0) {
            return Double.NaN;
        }
        return clamp01((amountYi - AMOUNT_MIN_YI) / (AMOUNT_MAX_YI - AMOUNT_MIN_YI)) * 100;
    }

    /** 情绪等级中文名，供前端直接显示与日志排查。 */
    public static String level(double score) {
        if (Double.isNaN(score)) {
            return "未知";
        }
        if (score >= LEVEL_HOT) {
            return "亢奋";
        }
        if (score >= LEVEL_ACTIVE) {
            return "活跃";
        }
        if (score >= LEVEL_NEUTRAL) {
            return "中性";
        }
        if (score >= LEVEL_WEAK) {
            return "低迷";
        }
        return "冰点";
    }

    /** 夹到 0~1，NaN 归 0。 */
    public static double clamp01(double value) {
        if (Double.isNaN(value)) {
            return 0;
        }
        return Math.max(0, Math.min(1, value));
    }

    /**
     * 情绪调节后的「当日涨幅上限」。
     *
     * <p>冰点日全市场没人接力，追高就是接盘，所以上限要收紧；
     * 情绪热的时候强势票能走得更远，可以放宽。中间线性过渡。</p>
     *
     * <p>用法：{@code relaxMaxPct=0} 表示用户不要这个自适应，直接返回原上限；
     * 情绪数据缺失（NaN）时同样原样返回——宁可保守，也不要在拿不到数据时擅自放宽。</p>
     */
    public static double effectiveChangeMax(double baseMaxPct, double relaxMaxPct, double score) {
        if (relaxMaxPct <= 0 || Double.isNaN(score)) {
            return baseMaxPct;
        }
        // 50 分以下按「情绪越差分越窄」压缩到原来的 60%，50 分以上线性放大到 relaxMaxPct
        if (score < LEVEL_NEUTRAL) {
            double factor = 0.6 + 0.4 * clamp01(score / LEVEL_NEUTRAL);
            return Math.max(0.2, baseMaxPct * factor);
        }
        double ratio = clamp01((score - LEVEL_NEUTRAL) / (100 - LEVEL_NEUTRAL));
        return Math.max(baseMaxPct, Math.min(relaxMaxPct, baseMaxPct + (relaxMaxPct - baseMaxPct) * ratio));
    }

    /**
     * 热榜名次的权重（0~100，名次越靠前越高）。
     *
     * <p>用「名次 / 池子大小」而不是绝对名次：池子只取 20 只时第 10 名和池子取 100 只时第 10 名
     * 的意义完全不同，归一化后才是同一把尺子。</p>
     */
    public static int hotnessWeight(int rank, int universeSize) {
        if (rank <= 0) {
            return 0;
        }
        int size = universeSize > 0 ? universeSize : rank;
        double ratio = (rank - 1) / (double) Math.max(1, size);
        return (int) Math.round(clamp01(1 - ratio) * 100);
    }
}
