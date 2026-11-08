package com.iblog.module.stock.service;

import com.iblog.module.stock.model.FundamentalData;
import com.iblog.module.stock.support.StockHttpClient;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 基本面真实上游联调测试。
 *
 * <p>会真的打东方财富 F10 接口，因此<b>上游不可达时用 {@link Assumptions#assumeTrue} 跳过</b>，
 * 而不是判失败 —— 断网、公司网络限制、上游维护都不该让本地构建变红。</p>
 *
 * <p>存在意义：单测里的夹具是我从上游抄下来的，如果上游悄悄改了字段名
 * （历史上就发生过扣非字段从 {@code DEDUCT_PARENT_NETPROFIT} 变成 {@code KCFJCXSYJLR}），
 * 夹具测试仍然全绿但线上功能已经坏了。联调测试是唯一能发现这类漂移的手段。</p>
 */
class StockFundamentalLiveTest {

    private final StockFundamentalService service = new StockFundamentalService(new StockHttpClient());

    @Test
    @DisplayName("真实上游客：贵州茅台应返回可用的盈利与现金流数据")
    void maotaiHasRealFundamental() {
        FundamentalData data = service.fundamental("600519");

        Assumptions.assumeFalse(StockFundamentalService.REASON_NETWORK.equals(data.getReason()),
                "上游不可达，跳过联调校验");

        assertTrue(data.isAvailable(), "茅台基本面应可用，reason=" + data.getReason());
        assertTrue(data.getRevenue() > 0, "营收应为正，实际 " + data.getRevenue());
        assertTrue(data.getNetProfit() > 0, "归母净利应为正，实际 " + data.getNetProfit());
        assertTrue(data.getGrossMargin() > 50, "白酒毛利率应显著偏高，实际 " + data.getGrossMargin());
        assertTrue(data.getPeriods().size() >= 4, "应返回多期趋势，实际 " + data.getPeriods().size());
        // 经营现金流有数据即可，不钉具体金额（每季度都在变）
        assertTrue(Double.isFinite(data.getOperatingCashFlow()), "经营现金流应取到");
        assertTrue(data.getReportDate() != null && data.getReportDate().length() == 10);
    }

    @Test
    @DisplayName("港股明确不支持")
    void hkNotSupported() {
        FundamentalData data = service.fundamental("00700");
        assertEquals(StockFundamentalService.REASON_HK, data.getReason());
    }
}
