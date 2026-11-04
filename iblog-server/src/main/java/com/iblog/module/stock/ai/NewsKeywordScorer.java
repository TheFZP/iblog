package com.iblog.module.stock.ai;

import com.iblog.module.stock.ai.AiWebSearchService.SearchHit;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 新闻情绪的关键词兜底打分（AI 不可用 / LLM 返回异常时降级用）。
 *
 * <p>为什么需要这一层：新闻因子默认开启，但 DeepSeek Key 可能没配、批量调用可能超时
 * 或回不了 JSON。此时如果直接放弃新闻分，选股在 AI 面板配置前就完全看不到新闻——
 * 而用关键词 + 来源权重 + 时效衰减，即使没有模型也能给每只票一个粗糙但可用的方向。</p>
 *
 * <p>打分量纲与 AI 一致（-100~100）：命中反向强词能盖过一条普通利好，多个正向词累加。
 * 这条路径只保证「方向基本对」，不追求和 AI 一模一样——有 AI 时它不会被调用。</p>
 */
@Service
public class NewsKeywordScorer {

    /** 强利空词：命中直接压到负区间下限附近。 */
    private static final String[] HARDPASS = {
            "立案", "退市", "*ST", "戴帽", "暂停上市", "财务造假", "虚构", "立案调查",
            "证监会立案", "行政处罚", "实控人被", "批捕", "刑事", "暴雷", "爆雷",
            "商誉减值", "业绩变脸", "业绩暴雷", "减持清仓", "清仓式减持", "终止上市"
    };
    /** 明显利好词。 */
    private static final String[] POSITIVE = {
            "中标", "签约", "预中标", "大单", "增持", "回购", "业绩预增", "扭亏", "涨停",
            "重组", "收购", "并购", "获批", "核准", "专利授权", "订单", "涨价", "提价",
            "需求旺盛", "供不应求", "创新高", "业绩增长", "利润增长", "分红", "送转",
            "政策利好", "纳入", "入选", "合作", "战略合作", "人工智能", "AI", "算力"
    };
    /** 明显利空词。 */
    private static final String[] NEGATIVE = {
            "亏损", "预亏", "下滑", "下降", "减持", "质押", "解禁", "商誉", "处罚", "诉讼",
            "被告", "涉诉", "仲裁", "监管函", "警示函", "问询函", "关注函", "滞留", "计提",
            "跌停", "跌超", "闪崩", "抛售", "出货"
    };
    /** 中性财报/公告词：既不当作利好也不当作利空，只贡献「有新闻」的事实。 */
    private static final String[] NEUTRAL_STRONG = {
            "年报", "一季报", "中报", "三季报", "季报", "半年报", "业绩说明会", "股东大会", "限售股上市"
    };

    /** 来源可信度：权重越高，能证实的利好/利空被加权得越高。 */
    private static final String[] SOURCE_HIGH = {
            "财联社", "证券时报", "中证报", "中国证券报", "上海证券报", "证券日报", "人民财讯",
            "交易所", "上交所", "深交所", "北交所", "巨潮资讯", "新浪财经", "东方财富", "同花顺"
    };

    /**
     * 对一组搜索结果打分（每条标题 + 摘要一起判）。返回 Windows 关闭范围外的 -100~100。
     */
    public int scoreAll(List<SearchHit> hits) {
        int score = 0;
        boolean badFound = false;
        for (SearchHit hit : hits) {
            int s = scoreOne(hit);
            if (s <= -60) {
                badFound = true;
            }
            // 取加权平均会抹掉单条重大利空，这里对负面取最大、正面取叠加但有上限
            score = combine(score, s);
        }
        if (badFound) {
            score = Math.min(score, -60);
        }
        return clamp(score, -100, 100);
    }

    private int scoreOne(SearchHit hit) {
        String text = (hit == null || hit.title() == null ? "" : hit.title())
                + "\n" + (hit == null || hit.source() == null ? "" : hit.source())
                + "\n" + (hit == null || hit.snippet() == null ? "" : hit.snippet());
        String lower = text.toLowerCase();

        // 强利空一票否决（命中即进负区）
        for (String w : HARDPASS) {
            if (lower.contains(w.toLowerCase())) {
                return -90;
            }
        }
        // 中性财报不算利好也不加重负面
        boolean neutral = false;
        for (String w : NEUTRAL_STRONG) {
            if (lower.contains(w)) {
                neutral = true;
                break;
            }
        }
        int pos = count(lower, POSITIVE);
        int neg = count(lower, NEGATIVE);
        if (neutral && pos == 0 && neg == 0) {
            return 0;
        }
        int score = (pos * 30) - (neg * 35);
        double weight = sourceWeight(hit);
        return (int) Math.round(score * weight);
    }

    /** 命中来源置信度：官方/主流媒体权重大，自媒体权重小。 */
    private double sourceWeight(SearchHit hit) {
        String source = hit == null || hit.source() == null ? "" : hit.source();
        if (source.isBlank()) {
            return 0.6;
        }
        for (String high : SOURCE_HIGH) {
            if (source.contains(high)) {
                return 1.0;
            }
        }
        if (source.contains("股吧") || source.contains("吧") || source.contains("论坛")
                || source.contains("自媒体") || source.contains("用户")) {
            return 0.4;
        }
        return 0.8;
    }

    private static int count(String text, String[] words) {
        // 简单命中计数：每条新闻中重复出现的词只算一次
        String lower = text.toLowerCase();
        int c = 0;
        for (String w : words) {
            if (lower.contains(w.toLowerCase())) {
                c++;
            }
        }
        return c;
    }

    /** 把一条与累积分合并：正面近似求和（截到上限），负面取更负者。 */
    private static int combine(int acc, int term) {
        if (term >= 0) {
            return clamp(acc + term, -100, 100);
        }
        // 负面取更负：一条硬性利空不该因为一条利好而被稀释
        return Math.min(acc, term);
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}