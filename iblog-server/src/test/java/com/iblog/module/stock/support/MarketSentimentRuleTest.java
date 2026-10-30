package com.iblog.module.stock.support;

import com.iblog.module.stock.model.MarketSentiment;
import com.iblog.module.stock.service.MarketSentimentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 情绪打分纯函数测试。
 *
 * <p>重点钉住三件事：1）分项缺失不能被当成 0 分；2）冰点/亢奋的边界方向不能反；
 * 3）调节涨幅上限时，拿不到情绪必须原样返回而不是放宽。</p>
 */
class MarketSentimentRuleTest {

    @Test
    @DisplayName("市场宽度：涨多分高，全跌为 0，无样本是 NaN")
    void breadth() {
        assertThat(MarketSentimentRule.breadthScore(4000, 1000)).isEqualTo(100.0);
        assertThat(MarketSentimentRule.breadthScore(1000, 4000)).isEqualTo(0.0);
        assertThat(MarketSentimentRule.breadthScore(0, 0)).isNaN();
        // 20%~80% 区间中点（50% 上涨）应当是 50 分（浮点除法会有尾差，按 0.001 容差比）
        assertThat(MarketSentimentRule.breadthScore(2500, 2500)).isCloseTo(50.0, within(0.001));
    }

    @Test
    @DisplayName("涨跌停：涨停多跌停少分高；跌停超过 40 家时跌停项归零")
    void limit() {
        // 涨停 80 家满分、跌停 0 家满分 → 100
        assertThat(MarketSentimentRule.limitScore(80, 0)).isEqualTo(100.0);
        // 涨停 15 家（冷）、跌停 40 家（恐慌）→ 两个分项都 0
        assertThat(MarketSentimentRule.limitScore(15, 40)).isEqualTo(0.0);
        // 少于 15 家涨停要压到 0，不能出现负数
        assertThat(MarketSentimentRule.limitScore(2, 0)).isGreaterThanOrEqualTo(0.0);
        assertThat(MarketSentimentRule.limitScore(-1, 0)).isNaN();
    }

    @Test
    @DisplayName("炸板率：无涨停无炸板样本不足返回 NaN，50% 炸板为 0 分")
    void broken() {
        assertThat(MarketSentimentRule.brokenScore(0, 0)).isNaN();
        assertThat(MarketSentimentRule.brokenScore(50, 0)).isEqualTo(100.0);
        assertThat(MarketSentimentRule.brokenScore(50, 50)).isEqualTo(0.0);
    }

    @Test
    @DisplayName("指数与成交额：区间两端与 NaN 处理")
    void indexAndAmount() {
        assertThat(MarketSentimentRule.indexScore(0)).isCloseTo(50.0, within(0.001));
        assertThat(MarketSentimentRule.indexScore(2)).isEqualTo(100.0);
        assertThat(MarketSentimentRule.indexScore(-2)).isEqualTo(0.0);
        assertThat(MarketSentimentRule.indexScore(Double.NaN)).isNaN();

        assertThat(MarketSentimentRule.amountScore(6000)).isEqualTo(0.0);
        assertThat(MarketSentimentRule.amountScore(20000)).isEqualTo(100.0);
        assertThat(MarketSentimentRule.amountScore(0)).isNaN();
    }

    @Test
    @DisplayName("等级：分数落在哪个档就说哪个词")
    void level() {
        assertThat(MarketSentimentRule.level(90)).isEqualTo("亢奋");
        assertThat(MarketSentimentRule.level(75)).isEqualTo("亢奋");
        assertThat(MarketSentimentRule.level(65)).isEqualTo("活跃");
        assertThat(MarketSentimentRule.level(50)).isEqualTo("中性");
        assertThat(MarketSentimentRule.level(30)).isEqualTo("低迷");
        assertThat(MarketSentimentRule.level(10)).isEqualTo("冰点");
        assertThat(MarketSentimentRule.level(Double.NaN)).isEqualTo("未知");
    }

    @Test
    @DisplayName("调节涨幅上限：未配置或拿不到情绪时原样返回")
    void effectiveChangeMaxKeepsBase() {
        // 没配自适应
        assertThat(MarketSentimentRule.effectiveChangeMax(7.0, 0, 80)).isEqualTo(7.0);
        // 拿不到情绪：宁可保守，绝不擅自放宽
        assertThat(MarketSentimentRule.effectiveChangeMax(7.0, 12, Double.NaN)).isEqualTo(7.0);
    }

    @Test
    @DisplayName("调节涨幅上限：冰点收紧、亢奋放宽，且不越过配置的上界")
    void effectiveChangeMaxAdjusts() {
        // 情绪 0 分 → 收到原来的 60%
        assertThat(MarketSentimentRule.effectiveChangeMax(10.0, 15, 0)).isEqualTo(6.0);
        // 中性 40 分 → 正好 100%（不收紧也不放宽）
        assertThat(MarketSentimentRule.effectiveChangeMax(10.0, 15, 40)).isEqualTo(10.0);
        // 满分 → 放到配置的上界
        assertThat(MarketSentimentRule.effectiveChangeMax(10.0, 15, 100)).isEqualTo(15.0);
        // 上界小于原值时不能反手收紧（Math.max 兜底）
        assertThat(MarketSentimentRule.effectiveChangeMax(10.0, 8, 100)).isEqualTo(10.0);
    }

    @Test
    @DisplayName("热榜名次权重：第 1 名满分，末位接近 0，名次非法返回 0")
    void hotnessWeight() {
        assertThat(MarketSentimentRule.hotnessWeight(1, 20)).isEqualTo(100);
        // 末位不是 0 分而是 5 分：能进池子说明已经被关注过一轮，只是不如前排
        assertThat(MarketSentimentRule.hotnessWeight(20, 20)).isEqualTo(5);
        assertThat(MarketSentimentRule.hotnessWeight(11, 20)).isEqualTo(50);
        assertThat(MarketSentimentRule.hotnessWeight(0, 20)).isEqualTo(0);
        // 池子大小未知时按自身归一，第 1 名仍应是满分
        assertThat(MarketSentimentRule.hotnessWeight(1, 0)).isEqualTo(100);
    }

    @Test
    @DisplayName("合成打分：缺分项时按剩余权重摊，不会被拉成低分")
    void scoreReweightsMissingDimensions() {
        // 只有涨跌家数可用（其余全 NaN）：50% 上涨 → 50 分左右，不能因为缺项掉到 15 分
        MarketSentimentService.Raw partial = new MarketSentimentService.Raw().up(2500).down(2500);
        partial.limitUp = -1;
        partial.limitDown = -1;
        partial.broken = -1;
        partial.amountYi = 0;
        partial.indexChangePct = Double.NaN;
        double score = MarketSentimentService.build(partial).getScore();
        assertThat(score).isBetween(45.0, 55.0);
    }

    @Test
    @DisplayName("合成打分：冰点日必须显著低于中性日")
    void scoreReflectsColdAndHot() {
        MarketSentimentService.Raw cold = new MarketSentimentService.Raw()
                .up(300).down(4600).limitUp(8).limitDown(60).broken(20)
                .amountYi(5500).indexChangePct(-2.5);
        MarketSentimentService.Raw hot = new MarketSentimentService.Raw()
                .up(4200).down(700).limitUp(90).limitDown(2).broken(5)
                .amountYi(22000).indexChangePct(1.8);
        double coldScore = MarketSentimentService.build(cold).getScore();
        double hotScore = MarketSentimentService.build(hot).getScore();
        assertThat(coldScore).isLessThan(25.0);
        assertThat(hotScore).isGreaterThan(75.0);
        assertThat(MarketSentimentService.build(cold).getLevel()).isEqualTo("冰点");
        assertThat(MarketSentimentService.build(hot).getLevel()).isEqualTo("亢奋");
    }

    @Test
    @DisplayName("炸板率展示值：无样本为 0，有样本按涨停+炸板为分母")
    void brokenRateDisplay() {
        MarketSentimentService.Raw raw = new MarketSentimentService.Raw()
                .up(2500).down(2500).limitUp(60).limitDown(3).broken(20)
                .amountYi(12000).indexChangePct(0.2);
        MarketSentiment vo = MarketSentimentService.build(raw);
        // 20 / (60 + 20) = 25%
        assertThat(vo.getBrokenRate()).isEqualTo(25.0);
        assertThat(vo.isAvailable()).isTrue();
        assertThat(vo.isStale()).isFalse();

        MarketSentimentService.Raw empty = new MarketSentimentService.Raw()
                .up(2500).down(2500).limitUp(0).limitDown(0).broken(0)
                .amountYi(12000).indexChangePct(0.1);
        assertThat(MarketSentimentService.build(empty).getBrokenRate()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("null 快照：available=false 且给出中性 50 分，调用方据此不拦单")
    void buildNull() {
        MarketSentiment vo = MarketSentimentService.build(null);
        assertThat(vo.isAvailable()).isFalse();
        // 不能是 0 分：0 分会被下游当成「情绪冰点」而清仓/停买
        assertThat(vo.getScore()).isEqualTo(50.0);
        assertThat(vo.getLevel()).isEqualTo("中性");
    }

    /**
     * 按真实腾讯返回的字段布局造一行：88 个 {@code ~} 分隔字段，GBK 名省略成 ASCII。
     *
     * <p>用「先造数组再按索引塞值」而不是手写一长串转义字符串：手写时只要少一个
     * {@code ~}，后面所有索引都会错位，而这种错位在断言里表现为「数值差几百」，
     * 很难一眼看出是字段位置错了。</p>
     */
    private static String tencentLine(String varName, String pct, String amountWan) {
        String[] parts = new String[88];
        java.util.Arrays.fill(parts, "0");
        parts[0] = "1";
        parts[1] = "INDEX";
        parts[2] = "000001";
        parts[3] = "3599.02";
        parts[30] = "20261030161400";
        parts[31] = "-52.41";
        parts[32] = pct;
        parts[36] = "497973940";
        parts[37] = amountWan;
        return varName + "=\"" + String.join("~", parts) + "\";";
    }

    @Test
    @DisplayName("腾讯兜底解析：取平均涨跌幅，成交额只累加沪市 + 深市（不含创业板，避免重复）")
    void parseTencentIndex() {
        String text = String.join("\n",
                tencentLine("v_sh000001", "-1.44", "76422719"),
                tencentLine("v_sz399001", "-2.73", "91024083"),
                tencentLine("v_sz399006", "-4.13", "45220938"));
        double[] result = MarketSentimentService.parseTencentIndex(text);
        assertThat(result).isNotNull();
        // 三项涨跌幅 -1.44 / -2.73 / -4.13 的平均
        assertThat(result[0]).isCloseTo(-2.7666, within(0.001));
        // 成交额只算沪 + 深：76422719 + 91024083 万元 = 16744.68 亿，创业板 45220938 万元不计
        assertThat(result[1]).isCloseTo(16744.68, within(0.1));
    }

    @Test
    @DisplayName("腾讯兜底解析：空文本 / 字段不足 / 全非法 一律返回 null，不抛异常")
    void parseTencentIndexInvalid() {
        assertThat(MarketSentimentService.parseTencentIndex(null)).isNull();
        assertThat(MarketSentimentService.parseTencentIndex("")).isNull();
        // 字段数不足 38 的行要能安全跳过
        assertThat(MarketSentimentService.parseTencentIndex("v_sh000001=\"1~上证指数~000001\";")).isNull();
        assertThat(MarketSentimentService.parseTencentIndex("完全没有等号的垃圾文本")).isNull();
    }
}
