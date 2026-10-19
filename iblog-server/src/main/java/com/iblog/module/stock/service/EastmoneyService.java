package com.iblog.module.stock.service;

import com.iblog.module.stock.model.MoneyFlow;
import com.iblog.module.stock.model.MoneyFlowHistoryItem;
import com.iblog.module.stock.support.EmFflowParser;
import com.iblog.module.stock.support.EmMoneyFlowFields;
import com.iblog.module.stock.support.StockCode;
import com.iblog.module.stock.support.StockHttpClient;
import com.iblog.module.stock.support.StockHttpClient.StockDataException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 东方财富资金流向数据源。
 *
 * <p><b>口径唯一真源</b>：与桌面版 {@code src-tauri/src/api/eastmoney.rs} 的
 * {@code fetch_money_flow} / {@code fetch_money_flow_history} 一一对应。
 * 字段顺序定义集中在 {@link EmMoneyFlowFields}，改下标前请先看那里的注释。</p>
 *
 * <h3>上游怪癖（都是实测踩出来的，别"优化"掉）</h3>
 * <ul>
 *   <li>{@code push2.eastmoney.com} 主域被 WAF 拦（ECONNRESET），
 *       实时接口只能用 {@code push2delay}，历史接口用 {@code push2his}</li>
 *   <li>{@code /fflow/kline/get?klt=1}（日内分时资金流）对本项目出口 IP 恒定 ECONNRESET，
 *       <b>不要尝试</b>；当日快照统一走 {@code daykline?lmt=1}</li>
 *   <li>非交易时段（含集合竞价）当日数据为空数组，必须回退到「最近一个交易日」并标注
 *       {@link MoneyFlow#isCurrent()} = false，不能报错也不能留白</li>
 *   <li>{@code lmt=0}（全量）在部分标的会返回 rc≠0，取历史用显式 lmt</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EastmoneyService {

    private final StockHttpClient http;

    private static final String URL_REALTIME =
            "https://push2delay.eastmoney.com/api/qt/stock/fflow/daykline/get"
                    + "?secid=%s&fields1=f1,f2,f3,f7&fields2=" + EmMoneyFlowFields.FIELDS2
                    + "&klt=101&lmt=1&ut=b2884a393a59ad64002292a3e90d46a5&cb=jQuery";

    private static final String URL_HISTORY =
            "https://push2his.eastmoney.com/api/qt/stock/fflow/daykline/get"
                    + "?secid=%s&fields1=f1,f2,f3,f7&fields2=" + EmMoneyFlowFields.FIELDS2
                    + "&klt=101&lmt=%d&ut=b2884a393a59ad64002292a3e90d46a5&cb=jQuery";

    /** 东财接口的 Referer，缺了会被拒 */
    private static final String REFERER = "https://data.eastmoney.com/";

    /** 历史条数上限：前端只画 30 日，留点余量但别让上游一次吐几千行 */
    public static final int MAX_HISTORY_LIMIT = 120;

    // ==================== 当日快照 ====================

    /**
     * 拉取主力资金当日快照。
     *
     * <p>优先 push2delay 实时接口；返回空（非交易时段/上游未生成）时回退到 push2his 最近一个交易日。
     * 两条路都空则抛 {@link StockDataException}，由 Controller 决定是 400 还是 500。</p>
     */
    public MoneyFlow fetchMoneyFlow(String rawCode) {
        String code = StockCode.normalize(rawCode);
        if (code.isEmpty()) {
            throw new StockDataException("股票代码不能为空");
        }
        String secid = StockCode.toEmSecid(code);

        // 1) 实时（delay）接口
        List<String> lines = safeFetch(String.format(URL_REALTIME, secid));
        if (!lines.isEmpty()) {
            MoneyFlow mf = parseSnapshot(lines.get(0));
            if (mf != null) {
                mf.setCurrent(true);
                return mf;
            }
        }

        // 2) 回退：历史接口最近一个交易日
        lines = safeFetch(String.format(URL_HISTORY, secid, 1));
        if (!lines.isEmpty()) {
            MoneyFlow mf = parseSnapshot(lines.get(0));
            if (mf != null) {
                // 上游有数据但当日未生成 —— 明确标注，前端据此显示交易日而非"今日"
                mf.setCurrent(false);
                return mf;
            }
        }

        // 3) 都拿不到：区分「上游无此数据」与「请求失败」交给上层，这里给出无害的空对象
        log.info("资金流向无数据 code={} secid={}", code, secid);
        MoneyFlow empty = new MoneyFlow();
        empty.setCurrent(false);
        empty.setUnavailable(true);
        return empty;
    }

    // ==================== 历史 ====================

    /**
     * 拉取近 N 个交易日的主力净流入序列。
     *
     * <p>返回<b>按日期升序</b>（旧 → 新），与 lightweight-charts 的时间轴要求一致，
     * 前端不要反转（桌面版踩过 "data must be asc ordered" 崩溃）。</p>
     *
     * @param limit 条数，非法值按 30 处理，超过 {@link #MAX_HISTORY_LIMIT} 截断
     */
    public List<MoneyFlowHistoryItem> fetchMoneyFlowHistory(String rawCode, Integer limit) {
        String code = StockCode.normalize(rawCode);
        if (code.isEmpty()) {
            throw new StockDataException("股票代码不能为空");
        }
        int n = limit == null || limit <= 0 ? 30 : Math.min(limit, MAX_HISTORY_LIMIT);
        String secid = StockCode.toEmSecid(code);

        List<String> lines = safeFetch(String.format(URL_HISTORY, secid, n));
        List<MoneyFlowHistoryItem> items = new ArrayList<>(lines.size());
        for (String line : lines) {
            MoneyFlowHistoryItem it = parseHistoryItem(line);
            if (it != null) {
                items.add(it);
            }
        }
        // 上游本身就是升序，这里再排一次是防御 —— 万一上游改了顺序，
        // 图表会直接崩，一次 O(n log n) 换来的稳定性很划算
        items.sort((a, b) -> a.getDate().compareTo(b.getDate()));
        return items;
    }

    // ==================== 解析（纯函数部分见 EmFflowParser / EmMoneyFlowFields）====================

    /**
     * 解析快照行。字段不全返回 null（由调用方回退）。
     */
    static MoneyFlow parseSnapshot(String line) {
        String[] f = EmFflowParser.splitLine(line);
        if (f.length < EmMoneyFlowFields.MIN_FIELDS) {
            return null;
        }
        // 说明：daykline 接口本身就带占比字段（f57-f61），不需要像桌面版的
        // /fflow/kline/get 实时接口那样用「净流入 / 成交额」自行推算占比
        MoneyFlow mf = new MoneyFlow();
        mf.setDate(EmMoneyFlowFields.at(f, EmMoneyFlowFields.IDX_DATE));
        mf.setMainNetInflow(EmMoneyFlowFields.yuanToWan(EmMoneyFlowFields.num(f, EmMoneyFlowFields.IDX_MAIN_NET)));
        mf.setSmallNet(EmMoneyFlowFields.yuanToWan(EmMoneyFlowFields.num(f, EmMoneyFlowFields.IDX_SMALL_NET)));
        mf.setMediumNet(EmMoneyFlowFields.yuanToWan(EmMoneyFlowFields.num(f, EmMoneyFlowFields.IDX_MEDIUM_NET)));
        mf.setLargeNet(EmMoneyFlowFields.yuanToWan(EmMoneyFlowFields.num(f, EmMoneyFlowFields.IDX_LARGE_NET)));
        mf.setSuperLargeNet(EmMoneyFlowFields.yuanToWan(EmMoneyFlowFields.num(f, EmMoneyFlowFields.IDX_SUPER_NET)));
        mf.setMainNetPct(EmMoneyFlowFields.num(f, EmMoneyFlowFields.IDX_MAIN_PCT));
        mf.setSmallPct(EmMoneyFlowFields.num(f, EmMoneyFlowFields.IDX_SMALL_PCT));
        mf.setMediumPct(EmMoneyFlowFields.num(f, EmMoneyFlowFields.IDX_MEDIUM_PCT));
        mf.setLargePct(EmMoneyFlowFields.num(f, EmMoneyFlowFields.IDX_LARGE_PCT));
        mf.setSuperLargePct(EmMoneyFlowFields.num(f, EmMoneyFlowFields.IDX_SUPER_PCT));
        return mf;
    }

    /**
     * 解析历史行。
     */
    static MoneyFlowHistoryItem parseHistoryItem(String line) {
        String[] f = EmFflowParser.splitLine(line);
        if (f.length < EmMoneyFlowFields.MIN_FIELDS) {
            return null;
        }
        MoneyFlowHistoryItem it = new MoneyFlowHistoryItem();
        it.setDate(EmMoneyFlowFields.at(f, EmMoneyFlowFields.IDX_DATE));
        it.setMainNetInflow(EmMoneyFlowFields.yuanToWan(EmMoneyFlowFields.num(f, EmMoneyFlowFields.IDX_MAIN_NET)));
        it.setSmallNet(EmMoneyFlowFields.yuanToWan(EmMoneyFlowFields.num(f, EmMoneyFlowFields.IDX_SMALL_NET)));
        it.setMediumNet(EmMoneyFlowFields.yuanToWan(EmMoneyFlowFields.num(f, EmMoneyFlowFields.IDX_MEDIUM_NET)));
        it.setLargeNet(EmMoneyFlowFields.yuanToWan(EmMoneyFlowFields.num(f, EmMoneyFlowFields.IDX_LARGE_NET)));
        it.setSuperLargeNet(EmMoneyFlowFields.yuanToWan(EmMoneyFlowFields.num(f, EmMoneyFlowFields.IDX_SUPER_NET)));
        it.setClose(EmMoneyFlowFields.num(f, EmMoneyFlowFields.IDX_CLOSE));
        return it;
    }

    /**
     * 拉取并解析，网络层面的失败降级为空列表。
     *
     * <p>为什么吞异常：资金流是文章页的「附加信息」，上游抖动不应该让整个详情页报错。
     * 真正的失败会在响应里体现为 {@code current=false} + 空数据，前端有对应的空态文案。</p>
     */
    private List<String> safeFetch(String url) {
        try {
            String text = http.getText(url, REFERER, java.nio.charset.StandardCharsets.UTF_8);
            return EmFflowParser.parseKlines(text);
        } catch (Exception e) {
            log.warn("资金流向请求失败 url={} err={}", url, e.getMessage());
            return new ArrayList<>();
        }
    }
}
