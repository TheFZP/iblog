package com.iblog.module.stock.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iblog.module.stock.model.IndustryData;
import com.iblog.module.stock.support.StockCode;
import com.iblog.module.stock.support.StockHttpClient;
import com.iblog.module.stock.support.StockHttpClient.StockDataException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 个股行业分析（东方财富 HSF10）。
 *
 * <p><b>口径唯一真源</b>：对应桌面版 {@code src-tauri/src/api/eastmoney.rs} 的
 * {@code fetch_industry_analysis} + {@code parse_industry_analysis}。</p>
 *
 * <h3>与桌面版的差异（有意为之）</h3>
 * <ul>
 *   <li>桌面版用正则从 {@code quote.eastmoney.com/{code}.html} 的行情页里抠行业名，
 *       属于「页面一改版就坏」的抓法。这里改用 HSF10 返回的 {@code gsgm_hypj}（行业平均）
 *       反推所属行业 —— 实测该字段里 {@code CORRE_SECURITY_NAME} 固定是「行业平均」，
 *       <b>并不含行业名</b>，所以行业名仍走行情页，但解析失败时降级为空串而不是报错。</li>
 *   <li>桌面版漏掉了 {@code netProfit} / {@code totalCap} 两个字段的解析，
 *       这里补上，前端才能画出「营收 + 净利 + 市值」三列对比。</li>
 * </ul>
 *
 * <h3>上游怪癖</h3>
 * <ul>
 *   <li>该接口是普通 JSON（不是 JSONP），不需要 strip 回调包裹</li>
 *   <li>{@code scbx} 固定 4 条，{@code TIME_TYPE} 1=近1月 2=近3月 3=近6月 4=今年以来</li>
 *   <li>港股无此数据，直接返回空结构而不是抛错</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IndustryService {

    private final StockHttpClient http;
    private final ObjectMapper objectMapper;

    private static final String URL_ANALYSIS =
            "https://emweb.securities.eastmoney.com/PC_HSF10/IndustryAnalysis/PageAjax?code=%s";

    /** 公司概况：只为取 {@code EM2016} 行业分类，见 {@link #fetchIndustryName}。 */
    private static final String URL_SURVEY =
            "https://emweb.securities.eastmoney.com/PC_HSF10/CompanySurvey/PageAjax?code=%s";

    private static final String REFERER_HSF10 = "https://emweb.securities.eastmoney.com/";

    /**
     * 拉取行业分析。
     *
     * @param rawCode 任意写法的股票代码（600519 / sh600519 / 600519.SH 均可）
     */
    public IndustryData fetchIndustry(String rawCode) {
        String code = StockCode.normalize(rawCode);
        if (code.isEmpty()) {
            throw new StockDataException("股票代码不能为空");
        }
        if (StockCode.isHk(code)) {
            // 港股没有东财 HSF10 行业分析。返回空结构而不是 400 ——
            // 「港股没有这个功能」和「请求出错了」对用户是两件事。
            IndustryData empty = new IndustryData();
            empty.setIndustryName("港股");
            empty.setMarketPerformance(List.of());
            empty.setRevenueRanking(List.of());
            return empty;
        }

        IndustryData data = parseAnalysis(fetchAnalysisJson(code));

        // 行业名单独取，失败不影响整体（桌面版同样降级为空串）
        try {
            String name = fetchIndustryName(code);
            if (name != null && !name.isEmpty()) {
                data.setIndustryName(name);
            }
        } catch (Exception e) {
            log.info("获取行业名称失败，降级为空 code={} err={}", code, e.toString());
        }
        return data;
    }

    private String fetchAnalysisJson(String code) {
        String emCode = StockCode.toEmHsf10Code(code);
        String url = String.format(URL_ANALYSIS, emCode);
        String text = http.getText(url, REFERER_HSF10, java.nio.charset.StandardCharsets.UTF_8);
        if (text == null || text.isBlank()) {
            throw new StockDataException("行业分析返回空内容");
        }
        return text;
    }

    /**
     * 从 F10 公司概况里取行业名。
     *
     * <p><b>为什么不用桌面版那套「抓行情页 HTML 里的板块链接」</b>：实测
     * {@code quote.eastmoney.com/sh600519.html} 返回的 51KB HTML 是 SPA 空壳，
     * 里面<b>一个 {@code bkzj/BK*.html} 链接都没有</b>（行业信息由浏览器里执行的 JS 渲染）。
     * 桌面版那条正则今天必然取不到东西 —— 它靠的是 Tauri WebView 里已经跑完 JS 的页面，
     * 在纯服务端是拿不到的。</p>
     *
     * <p>改用 {@code CompanySurvey/PageAjax} 的 {@code EM2016}，形如
     * {@code 食品饮料-饮料-白酒}（行业-子行业-细分），取最后一级作为行业名。
     * 这是标准分类字段，比从 HTML 里抠文字耐改版得多。</p>
     */
    private String fetchIndustryName(String code) {
        String emCode = StockCode.toEmHsf10Code(code);
        String json = http.getText(String.format(URL_SURVEY, emCode), REFERER_HSF10,
                java.nio.charset.StandardCharsets.UTF_8);
        if (json == null || json.isBlank()) {
            return "";
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode basic = root.get("jbzl");
            if (basic == null || !basic.isArray() || basic.isEmpty()) {
                return "";
            }
            String em2016 = basic.get(0).path("EM2016").asText("");
            if (em2016.isBlank()) {
                return "";
            }
            // "食品饮料-饮料-白酒" → "白酒"
            String[] parts = em2016.split("-");
            return parts[parts.length - 1].trim();
        } catch (Exception e) {
            log.info("解析公司概况失败 code={} err={}", code, e.toString());
            return "";
        }
    }

    /** 解析 HSF10 返回的 JSON。拆成包级可见的纯函数，便于单测。 */
    IndustryData parseAnalysis(String json) {
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (Exception e) {
            throw new StockDataException("行业分析 JSON 解析失败: " + e.getMessage());
        }

        IndustryData data = new IndustryData();
        data.setIndustryName("");

        // ---- 市场表现（个股 vs 沪深300）----
        List<IndustryData.MarketPerformance> perf = new ArrayList<>();
        for (JsonNode item : arrayOf(root, "scbx")) {
            IndustryData.MarketPerformance p = new IndustryData.MarketPerformance();
            p.setTimeType(item.path("TIME_TYPE").asInt(0));
            p.setChangeRate(item.path("CHANGERATE").asDouble(0));
            p.setHs300ChangeRate(item.path("HS300_CHANGERATE").asDouble(0));
            perf.add(p);
        }
        data.setMarketPerformance(perf);

        // ---- 营收排名 ----
        List<IndustryData.RevenueRanking> ranking = new ArrayList<>();
        String reportType = "";
        for (JsonNode item : arrayOf(root, "gsgm_yysr")) {
            String sc = item.path("CORRE_SECURITY_CODE").asText("");
            // 「行业平均」这类伪条目不是真股票，跳过，它单独进 industryAverage
            if (sc.isEmpty() || !sc.chars().allMatch(Character::isDigit)) {
                continue;
            }
            IndustryData.RevenueRanking r = new IndustryData.RevenueRanking();
            r.setStockCode(sc);
            r.setStockName(item.path("CORRE_SECURITY_NAME").asText(""));
            r.setTotalOperateIncome(item.path("TOTAL_OPERATEINCOME").asDouble(0));
            r.setTotalOperateIncomeRank(item.path("TOTAL_OPERATEINCOME_RANK").asInt(0));
            r.setNetProfit(item.path("NETPROFIT").asDouble(0));
            r.setNetProfitRank(item.path("NETPROFIT_RANK").asInt(0));
            r.setTotalCap(item.path("TOTAL_CAP").asDouble(0));
            ranking.add(r);
            if (reportType.isEmpty()) {
                reportType = item.path("REPORT_TYPE").asText("");
            }
        }

        // 个股自己可能不在同行对比列表里（列表只放同行业其他公司），从 gsgm 补一条，
        // 否则前端表格里永远看不到自己那只。
        String selfCode = arrayOf(root, "gsgm").isEmpty() ? ""
                : arrayOf(root, "gsgm").get(0).path("CORRE_SECURITY_CODE").asText("");
        if (!selfCode.isEmpty() && ranking.stream().noneMatch(r -> r.getStockCode().equals(selfCode))) {
            JsonNode self = arrayOf(root, "gsgm").get(0);
            IndustryData.RevenueRanking r = new IndustryData.RevenueRanking();
            r.setStockCode(selfCode);
            r.setStockName(self.path("SECURITY_NAME_ABBR").asText(""));
            r.setTotalOperateIncome(self.path("TOTAL_OPERATEINCOME").asDouble(0));
            r.setTotalOperateIncomeRank(self.path("TOTAL_OPERATEINCOME_RANK").asInt(0));
            r.setNetProfit(self.path("NETPROFIT").asDouble(0));
            r.setNetProfitRank(self.path("NETPROFIT_RANK").asInt(0));
            r.setTotalCap(self.path("TOTAL_CAP").asDouble(0));
            ranking.add(r);
            if (reportType.isEmpty()) {
                reportType = self.path("REPORT_TYPE").asText("");
            }
        }
        ranking.sort(Comparator.comparingInt(IndustryData.RevenueRanking::getTotalOperateIncomeRank));
        data.setRevenueRanking(ranking);
        data.setReportType(reportType);

        // ---- 行业平均 ----
        List<JsonNode> avgNodes = arrayOf(root, "gsgm_hypj");
        if (!avgNodes.isEmpty()) {
            JsonNode avg = avgNodes.get(0);
            IndustryData.IndustryAverage a = new IndustryData.IndustryAverage();
            a.setTotalOperateIncome(avg.path("TOTAL_OPERATEINCOME").asDouble(0));
            a.setNetProfit(avg.path("NETPROFIT").asDouble(0));
            a.setTotalCap(avg.path("TOTAL_CAP").asDouble(0));
            data.setIndustryAverage(a);
        }
        return data;
    }

    private static List<JsonNode> arrayOf(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<JsonNode> list = new ArrayList<>();
        node.forEach(list::add);
        return list;
    }
}
