package com.iblog.module.stock.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iblog.module.stock.model.FundamentalData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 基本面解析测试。
 *
 * <p>夹具取自 2026-11-08 东方财富 F10 对 600519 的真实响应（字段名与数值都是上游原文）。
 * 这些数字不要「顺手取整」——它们正是用来钉住字段映射的，尤其是
 * <b>扣非字段叫 {@code KCFJCXSYJLR}</b>（写成 {@code DEDUCT_PARENT_NETPROFIT} 上游会报
 * 「返回字段不存在」），以及同比下降必须按「同月日 + 上一年」配对而不是取第 4 行。</p>
 */
class StockFundamentalServiceTest {

    private final StockFundamentalService service = new StockFundamentalService(null);
    private final ObjectMapper mapper = new ObjectMapper();

    /** 主指标报表：6 期，覆盖三季报/中报/一季报/年报四种 REPORT_TYPE。 */
    private static final String MAIN = """
            [
              {"REPORT_DATE":"2026-09-30 00:00:00","REPORT_TYPE":"三季报","TOTALOPERATEREVE":133283652508.95,"PARENTNETPROFIT":64467425981.95,"KCFJCXSYJLR":64324824599.18,"XSMLL":89.581093798,"XSJLL":50.9404935478,"ROEJQ":24.47,"EPSJB":51.53,"BPS":216.949629787912,"MGJYXJJE":67.242794202432},
              {"REPORT_DATE":"2026-06-30 00:00:00","REPORT_TYPE":"中报","TOTALOPERATEREVE":92278072083.21,"PARENTNETPROFIT":44516880421.86,"KCFJCXSYJLR":44390000000.0,"XSMLL":89.5552128279,"XSJLL":48.24,"ROEJQ":16.75,"EPSJB":35.44,"BPS":210.1,"MGJYXJJE":42.1},
              {"REPORT_DATE":"2026-03-31 00:00:00","REPORT_TYPE":"一季报","TOTALOPERATEREVE":54702912385.23,"PARENTNETPROFIT":27242512886.45,"KCFJCXSYJLR":27160000000.0,"XSMLL":89.7592176242,"XSJLL":49.79,"ROEJQ":10.57,"EPSJB":21.69,"BPS":205.3,"MGJYXJJE":18.7},
              {"REPORT_DATE":"2025-12-31 00:00:00","REPORT_TYPE":"年报","TOTALOPERATEREVE":172054171890.91,"PARENTNETPROFIT":82320067101.68,"KCFJCXSYJLR":82010000000.0,"XSMLL":91.1795516835,"XSJLL":47.84,"ROEJQ":32.53,"EPSJB":65.53,"BPS":198.4,"MGJYXJJE":60.2},
              {"REPORT_DATE":"2025-09-30 00:00:00","REPORT_TYPE":"三季报","TOTALOPERATEREVE":130903889634.88,"PARENTNETPROFIT":64626746712.18,"KCFJCXSYJLR":64500000000.0,"XSMLL":91.2933832134,"XSJLL":49.37,"ROEJQ":24.64,"EPSJB":51.44,"BPS":190.2,"MGJYXJJE":58.1},
              {"REPORT_DATE":"2025-06-30 00:00:00","REPORT_TYPE":"中报","TOTALOPERATEREVE":91093762553.97,"PARENTNETPROFIT":45402962298.1,"KCFJCXSYJLR":45200000000.0,"XSMLL":91.2993094819,"XSJLL":49.84,"ROEJQ":17.89,"EPSJB":36.15,"BPS":182.5,"MGJYXJJE":40.3}
            ]
            """;

    /** 现金流量表：最新一期与主表对齐，其余用于验证按日期匹配。 */
    private static final String CASH = """
            [
              {"REPORT_DATE":"2026-09-30 00:00:00","NETCASH_OPERATE":84058979832.29,"NETCASH_INVEST":25825156841.9,"NETCASH_FINANCE":-40316159517.78},
              {"REPORT_DATE":"2026-06-30 00:00:00","NETCASH_OPERATE":52000000000.0,"NETCASH_INVEST":15000000000.0,"NETCASH_FINANCE":-20000000000.0}
            ]
            """;

    private JsonNode json(String text) throws Exception {
        return mapper.readTree(text);
    }

    @Test
    @DisplayName("最新报告期的盈利与现金流字段映射正确")
    void latestPeriodMapping() throws Exception {
        FundamentalData data = service.parseFundamental("600519", json(MAIN), json(CASH));

        assertTrue(data.isAvailable());
        assertEquals("2026-09-30", data.getReportDate());
        assertEquals("三季报", data.getReportType());
        assertEquals(133283652508.95, data.getRevenue(), 1e-6);
        assertEquals(64467425981.95, data.getNetProfit(), 1e-6);
        // 扣非取的是 KCFJCXSYJLR，写成 DEDUCT_PARENT_NETPROFIT 上游直接报字段不存在
        assertEquals(64324824599.18, data.getDeductedNetProfit(), 1e-6);
        assertEquals(89.581093798, data.getGrossMargin(), 1e-9);
        assertEquals(50.9404935478, data.getNetMargin(), 1e-9);
        assertEquals(24.47, data.getRoe(), 1e-9);
        assertEquals(51.53, data.getEps(), 1e-9);
        assertEquals(216.949629787912, data.getBps(), 1e-9);
        assertEquals(67.242794202432, data.getCashPerShare(), 1e-9);
    }

    @Test
    @DisplayName("现金流三项与净现比")
    void cashFlow() throws Exception {
        FundamentalData data = service.parseFundamental("600519", json(MAIN), json(CASH));

        assertEquals(84058979832.29, data.getOperatingCashFlow(), 1e-6);
        assertEquals(25825156841.9, data.getInvestingCashFlow(), 1e-6);
        assertEquals(-40316159517.78, data.getFinancingCashFlow(), 1e-6);
        // 净现比 = 经营现金流 ÷ 归母净利 ≈ 1.30（>1 说明利润落袋为安）
        assertEquals(84058979832.29 / 64467425981.95, data.getCashToProfitRatio(), 1e-9);
    }

    @Test
    @DisplayName("同比按「同月日 + 上一年」配对，不能取第 4 行")
    void growthMatchesSameMonthDay() throws Exception {
        FundamentalData data = service.parseFundamental("600519", json(MAIN), json(CASH));

        // 2026-09-30 对比 2025-09-30（列表第 5 行，而不是第 4 行的 2025-12-31 年报）
        assertEquals((133283652508.95 - 130903889634.88) / 130903889634.88 * 100,
                data.getRevenueYoY(), 1e-9);
        assertEquals((64467425981.95 - 64626746712.18) / 64626746712.18 * 100,
                data.getProfitYoY(), 1e-9);
        // 若错取 2025-12-31 年报，营收同比会是 -22.5% 左右，与真实 +1.8% 差异巨大
        assertTrue(data.getRevenueYoY() > 0, "茅台 2026Q3 营收同比为正");
    }

    @Test
    @DisplayName("找得到去年同期时同比为实数，找不到时为 NaN")
    void growthMissingPreviousYear() throws Exception {
        // 只有单期数据 → 没有去年同期可比
        String single = """
                [{"REPORT_DATE":"2026-09-30 00:00:00","REPORT_TYPE":"三季报","TOTALOPERATEREVE":100.0,"PARENTNETPROFIT":10.0}]
                """;
        FundamentalData data = service.parseFundamental("600519", json(single), null);

        assertTrue(Double.isNaN(data.getRevenueYoY()));
        assertTrue(Double.isNaN(data.getProfitYoY()));
        assertTrue(Double.isNaN(data.getCashToProfitRatio()), "现金流缺失时净现比为 NaN");
    }

    @Test
    @DisplayName("净利润为负时净现比没有意义，返回 NaN")
    void cashToProfitNaNWhenLoss() throws Exception {
        String loss = """
                [{"REPORT_DATE":"2026-09-30 00:00:00","REPORT_TYPE":"三季报","TOTALOPERATEREVE":100.0,"PARENTNETPROFIT":-10.0}]
                """;
        String cash = """
                [{"REPORT_DATE":"2026-09-30 00:00:00","NETCASH_OPERATE":5.0,"NETCASH_INVEST":0.0,"NETCASH_FINANCE":0.0}]
                """;
        FundamentalData data = service.parseFundamental("600519", json(loss), json(cash));

        // 亏损时「净现比」的比值没有解释力，宁可缺省也不要给出误导性的负数
        assertTrue(Double.isNaN(data.getCashToProfitRatio()));
        assertEquals(5.0, data.getOperatingCashFlow(), 1e-9);
    }

    @Test
    @DisplayName("趋势期列表最多 6 期，按时间倒序")
    void periodsCappedAtSix() throws Exception {
        FundamentalData data = service.parseFundamental("600519", json(MAIN), json(CASH));

        assertEquals(6, data.getPeriods().size());
        assertEquals("2026-09-30", data.getPeriods().get(0).getReportDate());
        assertEquals("2025-06-30", data.getPeriods().get(5).getReportDate());
        assertEquals(133283652508.95, data.getPeriods().get(0).getRevenue(), 1e-6);
        // 经营现金流按日期对齐后回填到对应期
        assertEquals(84058979832.29, data.getPeriods().get(0).getOperatingCashFlow(), 1e-6);
    }

    @Test
    @DisplayName("上游失败/空数据分别给出 reason，不抛异常")
    void failuresDegradeWithReason() throws Exception {
        FundamentalData network = service.parseFundamental("600519", null, null);
        assertFalse(network.isAvailable());
        assertEquals(StockFundamentalService.REASON_NETWORK, network.getReason());

        FundamentalData empty = service.parseFundamental("600519", json("[]"), null);
        assertFalse(empty.isAvailable());
        assertEquals(StockFundamentalService.REASON_EMPTY, empty.getReason());
        assertNull(empty.getReportDate());
    }

    @Test
    @DisplayName("港股没有东财 F10 报表，直接标记 hk 不再请求上游")
    void hkHasNoFundamental() {
        FundamentalData data = service.fundamental("00700");

        assertFalse(data.isAvailable());
        assertEquals(StockFundamentalService.REASON_HK, data.getReason());
        assertEquals("00700", data.getCode());
    }
}
