package com.iblog.module.stock.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iblog.module.stock.model.IndustryData;
import com.iblog.module.stock.support.StockCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 行业分析解析测试。
 *
 * <p>夹具取自 2026-10-19 东方财富 HSF10 对 600519 的真实响应（已裁剪掉未使用的字段），
 * 数值都是上游原文，不要「顺手改成整十整百」—— 这些数字正是用来钉住字段映射的。</p>
 *
 * <h3>为什么必须钉这几件事</h3>
 * <ul>
 *   <li>上游的 {@code gsgm_yysr} 里混了一条 {@code CORRE_SECURITY_CODE="行业平均"} 的伪记录，
 *       不当成真股票过滤掉，界面上就会出现一只叫「行业平均」的公司</li>
 *   <li>茅台自己（行业营收第 1）并<b>不在</b> {@code gsgm_yysr} 里，只在 {@code gsgm} 里，
 *       不补这一条，「营收排名」表里永远看不到自己</li>
 *   <li>{@code TIME_TYPE} 取值 1..4 对应近1月/近3月/近6月/今年以来，顺序错了整块对比就错位</li>
 * </ul>
 */
class IndustryServiceTest {

    /** 与 Spring 注入的 ObjectMapper 行为一致即可（这里只做 readTree）。 */
    private final IndustryService service = new IndustryService(null, new ObjectMapper());

    /** 真实响应裁剪版：scbx 4 条 + gsgm_yysr 5 条（含「行业平均」伪条目）+ gsgm 1 条 + gsgm_hypj。 */
    private static final String FIXTURE = """
            {
              "scbx": [
                {"CHANGERATE": 3.906176479814, "HS300_CHANGERATE": -7.441439822672, "TIME_TYPE": 1},
                {"CHANGERATE": 5.743814844374, "HS300_CHANGERATE": -7.828503851531, "TIME_TYPE": 2},
                {"CHANGERATE": -3.867487442387, "HS300_CHANGERATE": -12.252530083447, "TIME_TYPE": 3},
                {"CHANGERATE": -1.514398522771, "HS300_CHANGERATE": -9.836081011426, "TIME_TYPE": 4}
              ],
              "gsgm_yysr": [
                {"CORRE_SECURITY_CODE": "000858", "CORRE_SECURITY_NAME": "五粮液",
                 "TOTAL_CAP": 279747488920.35, "TOTAL_OPERATEINCOME": 28416674541.77,
                 "NETPROFIT": 8996132822.3, "REPORT_TYPE": "2026年中报",
                 "TOTAL_OPERATEINCOME_RANK": 2, "NETPROFIT_RANK": 2},
                {"CORRE_SECURITY_CODE": "600809", "CORRE_SECURITY_NAME": "山西汾酒",
                 "TOTAL_CAP": 142369824707.4, "TOTAL_OPERATEINCOME": 21044387808.34,
                 "NETPROFIT": 6469492200.8, "REPORT_TYPE": "2026年中报",
                 "TOTAL_OPERATEINCOME_RANK": 3, "NETPROFIT_RANK": 3},
                {"CORRE_SECURITY_CODE": "002304", "CORRE_SECURITY_NAME": "洋河股份",
                 "TOTAL_CAP": 57169590558.3, "TOTAL_OPERATEINCOME": 10540431576.04,
                 "NETPROFIT": 2595427873.08, "REPORT_TYPE": "2026年中报",
                 "TOTAL_OPERATEINCOME_RANK": 4, "NETPROFIT_RANK": 5},
                {"CORRE_SECURITY_CODE": "000568", "CORRE_SECURITY_NAME": "泸州老窖",
                 "TOTAL_CAP": 105888591248.22, "TOTAL_OPERATEINCOME": 10472224575.78,
                 "NETPROFIT": 4376950333.58, "REPORT_TYPE": "2026年中报",
                 "TOTAL_OPERATEINCOME_RANK": 5, "NETPROFIT_RANK": 4},
                {"CORRE_SECURITY_CODE": "行业平均", "CORRE_SECURITY_NAME": "行业平均",
                 "TOTAL_CAP": 123674559792.547, "TOTAL_OPERATEINCOME": 10401269846.826,
                 "NETPROFIT": 3858824351.907, "REPORT_TYPE": "2026年中报",
                 "TOTAL_OPERATEINCOME_RANK": null, "NETPROFIT_RANK": null}
              ],
              "gsgm": [
                {"CORRE_SECURITY_CODE": "600519", "CORRE_SECURITY_NAME": "贵州茅台",
                 "SECURITY_NAME_ABBR": "贵州茅台", "TOTAL_CAP": 1661358447729,
                 "TOTAL_OPERATEINCOME": 92278072083.21, "NETPROFIT": 46033330566.78,
                 "REPORT_TYPE": "2026年中报",
                 "TOTAL_OPERATEINCOME_RANK": 1, "NETPROFIT_RANK": 1}
              ],
              "gsgm_hypj": [
                {"TOTAL_CAP": 123674559792.547, "TOTAL_OPERATEINCOME": 10401269846.826,
                 "NETPROFIT": 3858824351.907, "REPORT_TYPE": "2026年中报"}
              ]
            }
            """;

    @Test
    @DisplayName("市场表现按 TIME_TYPE 映射，保留真实小数")
    void marketPerformance() {
        IndustryData data = service.parseAnalysis(FIXTURE);

        assertEquals(4, data.getMarketPerformance().size());
        assertEquals(1, data.getMarketPerformance().get(0).getTimeType());
        assertEquals(3.906176479814, data.getMarketPerformance().get(0).getChangeRate(), 1e-9);
        assertEquals(-7.441439822672, data.getMarketPerformance().get(0).getHs300ChangeRate(), 1e-9);
        assertEquals(4, data.getMarketPerformance().get(3).getTimeType());
        assertEquals(-1.514398522771, data.getMarketPerformance().get(3).getChangeRate(), 1e-9);
    }

    @Test
    @DisplayName("「行业平均」伪条目不会被当成一只股票")
    void filtersPseudoCompany() {
        IndustryData data = service.parseAnalysis(FIXTURE);

        assertTrue(data.getRevenueRanking().stream()
                        .noneMatch(r -> "行业平均".equals(r.getStockName())),
                "gsgm_yysr 里的「行业平均」混在真股票中，必须过滤");
        assertTrue(data.getRevenueRanking().stream()
                .allMatch(r -> r.getStockCode().chars().allMatch(Character::isDigit)));
    }

    @Test
    @DisplayName("个股本身不在同行列表时从 gsgm 补进来，并排在第 1")
    void appendsSelfWhenMissing() {
        IndustryData data = service.parseAnalysis(FIXTURE);

        // 4 家同行 + 补进来的茅台
        assertEquals(5, data.getRevenueRanking().size());
        IndustryData.RevenueRanking first = data.getRevenueRanking().get(0);
        assertEquals("600519", first.getStockCode());
        assertEquals("贵州茅台", first.getStockName());
        assertEquals(1, first.getTotalOperateIncomeRank());
        assertEquals(92278072083.21, first.getTotalOperateIncome(), 1e-6);
        assertEquals(46033330566.78, first.getNetProfit(), 1e-6);
        assertEquals(1661358447729d, first.getTotalCap(), 1e-6);
    }

    @Test
    @DisplayName("排名按 TOTAL_OPERATEINCOME_RANK 升序")
    void sortedByRank() {
        IndustryData data = service.parseAnalysis(FIXTURE);

        int prev = 0;
        for (IndustryData.RevenueRanking r : data.getRevenueRanking()) {
            assertTrue(r.getTotalOperateIncomeRank() > prev,
                    "排名必须严格递增，实际 " + r.getStockName() + "=" + r.getTotalOperateIncomeRank());
            prev = r.getTotalOperateIncomeRank();
        }
    }

    @Test
    @DisplayName("行业平均与报表期取到真实值")
    void industryAverage() {
        IndustryData data = service.parseAnalysis(FIXTURE);

        assertEquals("2026年中报", data.getReportType());
        assertEquals(10401269846.826, data.getIndustryAverage().getTotalOperateIncome(), 1e-6);
        assertEquals(3858824351.907, data.getIndustryAverage().getNetProfit(), 1e-6);
        assertEquals(123674559792.547, data.getIndustryAverage().getTotalCap(), 1e-3);
    }

    @Test
    @DisplayName("字段缺失时给空列表而不是 null，前端不必到处判空")
    void missingFieldsDegradeToEmpty() {
        IndustryData data = service.parseAnalysis("{}");

        assertTrue(data.getMarketPerformance().isEmpty());
        assertTrue(data.getRevenueRanking().isEmpty());
        assertEquals("", data.getIndustryName());
    }

    @Test
    @DisplayName("HSF10 代码是「市场大写+代码」，不是 secid")
    void hsf10CodeMapping() {
        // 这两个是不同接口的两套格式，混用会直接 400 —— 曾经写错过
        assertEquals("SH600519", StockCode.toEmHsf10Code("600519"));
        assertEquals("SZ000002", StockCode.toEmHsf10Code("000002.SZ"));
        assertEquals("SZ300750", StockCode.toEmHsf10Code("sz300750"));
        assertEquals("BJ430047", StockCode.toEmHsf10Code("430047"));

        assertEquals("1.600519", StockCode.toEmSecid("600519"));
        assertEquals("0.000002", StockCode.toEmSecid("000002.SZ"));
    }
}
