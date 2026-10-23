package com.iblog.module.stock.service;

import com.iblog.module.stock.model.MoneyFlow;
import com.iblog.module.stock.model.MoneyFlowHistoryItem;
import com.iblog.module.stock.support.EmFflowParser;
import com.iblog.module.stock.support.EmMoneyFlowFields;
import com.iblog.module.stock.support.StockCode;
import com.iblog.module.stock.support.StockHttpClient;
import com.iblog.module.stock.support.StockHttpClient.StockDataException;
import com.iblog.module.stock.support.UpstreamBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
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

    /**
     * 单次请求的重试次数。
     *
     * <p>实测该域名连接被 reset 的比例在三成上下（15 次里 6 次失败、8 次里 8 次成功都见过），
     * 且失败通常几百毫秒内就返回 —— 多试几次成本很低。</p>
     */
    private static final int MAX_ATTEMPTS = 5;
    /** 每次重试前固定等一会儿，太快连发更容易继续被拒 */
    private static final long RETRY_BACKOFF_MS = 150L;
    /** 整段重试的时间预算，必须小于前端 axios 的 15s 超时，留足余量给响应序列化 */
    private static final long RETRY_BUDGET_MS = 6000L;
    /** 单次请求超时：上游正常在 250-600ms 返回，给到 3s 已经很宽松 */
    private static final Duration FETCH_TIMEOUT = Duration.ofSeconds(3);

    /**
     * 上游熔断：连续失败 6 次就判定「东财这个出口现在被限流」，冷却 60 秒内直接失败。
     *
     * <p>选股一轮会问几十上百只票，被限流时每只都白等 5 次重试 × 2 个域名，
     * 整轮从几十秒涨到几分钟（线上实测过 3 分钟仍未跑完，前端/nginx 先超时）。
     * 熔断把这些时间省下来：反正这一轮拿不到资金流，不如早点放弃，
     * 让选股靠日 K 打分的项继续跑完。</p>
     *
     * <p>阈值取 6 而不是 1：单只票的 5 次重试里有 2~3 次失败是常态（通道间歇性 reset），
     * 连续 6 次全失败才说明是整体性问题，不是偶发抖动。</p>
     */
    private static final int BREAKER_THRESHOLD = 6;
    private static final long BREAKER_COOLDOWN_MS = 60_000L;

    private final UpstreamBreaker breaker = new UpstreamBreaker(BREAKER_THRESHOLD, BREAKER_COOLDOWN_MS);

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
        FetchResult realtime = safeFetch(String.format(URL_REALTIME, secid));
        if (!realtime.lines.isEmpty()) {
            MoneyFlow mf = parseSnapshot(realtime.lines.get(0));
            if (mf != null) {
                mf.setCurrent(true);
                return mf;
            }
        }

        // 2) 回退：历史接口最近一个交易日
        FetchResult history = safeFetch(String.format(URL_HISTORY, secid, 1));
        if (!history.lines.isEmpty()) {
            MoneyFlow mf = parseSnapshot(history.lines.get(0));
            if (mf != null) {
                // 上游有数据但当日未生成 —— 明确标注，前端据此显示交易日而非"今日"
                mf.setCurrent(false);
                return mf;
            }
        }

        // 3) 都拿不到：区分「网络失败」与「上游确实没有」，前端文案完全不同
        MoneyFlow empty = new MoneyFlow();
        empty.setCurrent(false);
        empty.setUnavailable(true);
        if (realtime.networkError || history.networkError) {
            empty.setReason(MoneyFlow.REASON_NETWORK);
            log.warn("资金流向上游不可达 code={} secid={}", code, secid);
        } else {
            log.info("资金流向无数据 code={} secid={}", code, secid);
        }
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

        FetchResult fetched = safeFetch(String.format(URL_HISTORY, secid, n));
        List<String> lines = fetched.lines;
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
     * 一次上游拉取的结果：解析出的数据行 + 是否因为网络原因彻底失败。
     *
     * <p>之所以要把这两件事分开带出去：{@code lines} 为空有两种截然不同的含义 ——
     * 「请求通了但上游说没有」（该股确实无数据）和「根本没连上」（过会儿再试就好）。
     * 前端对这两种情况的文案完全不同，服务端如果统一成空数组，就等于把信息丢了。</p>
     */
    private record FetchResult(List<String> lines, boolean networkError) {
        static FetchResult ok(List<String> lines) {
            return new FetchResult(lines, false);
        }

        static FetchResult failed() {
            return new FetchResult(new ArrayList<>(), true);
        }
    }

    /**
     * 拉取并解析，网络层面的失败降级为 {@link FetchResult#failed()}。
     *
     * <p>为什么吞异常：资金流是文章页的「附加信息」，上游抖动不应该让整个详情页报错。
     * 真正的失败会在响应里体现为 {@code unavailable=true} + 空数据，前端有对应的空态文案。</p>
     *
     * <p><b>为什么要重试</b>：实测东财 push2 系列的连接会<b>间歇性被重置</b>
     * （{@code HTTP/1.1 header parser received no bytes}），同一秒内换个连接又通了，
     * 15 次里 6 次失败、8 次里 8 次成功都出现过 —— 与请求内容、请求头都无关，就是通道问题。
     * 单次失败直接当成「无数据」的话，页面会显示一个假的「暂无资金流数据」，比报错更误导人。</p>
     *
     * <p><b>为什么有总预算</b>：失败通常在几百毫秒内返回，但偶尔会拖到超时。重试次数 ×
     * 单次超时如果超过前端 axios 的 15 秒，用户看到的就是「加载中」一直转到超时 —— 那比
     * 立刻告诉他「暂时取不到」还糟。所以给整段重试一个硬预算，超了就放弃。</p>
     */
    private FetchResult safeFetch(String url) {
        // 熔断期内直接放弃：这一轮反正拿不到资金流，不要让每只票都白等 5 次重试
        if (breaker.isOpen()) {
            log.debug("资金流向已熔断，跳过 url={}", url);
            return FetchResult.failed();
        }
        long deadline = System.currentTimeMillis() + RETRY_BUDGET_MS;
        RuntimeException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            if (System.currentTimeMillis() >= deadline) {
                log.warn("资金流向重试预算用完 url={} attempts={}", url, attempt - 1);
                break;
            }
            try {
                String text = http.getTextNoKeepAlive(url, REFERER, java.nio.charset.StandardCharsets.UTF_8,
                        FETCH_TIMEOUT);
                breaker.recordSuccess();
                return FetchResult.ok(EmFflowParser.parseKlines(text));
            } catch (Exception e) {
                last = e instanceof RuntimeException re ? re : new StockDataException(e.getMessage());
                log.warn("资金流向请求失败 url={} attempt={}/{} err={}", url, attempt, MAX_ATTEMPTS, e.getMessage());
                if (breaker.recordFailure()) {
                    log.warn("资金流向上游连续失败 {} 次，熔断 {} 秒（期间不再请求东财资金流）",
                            BREAKER_THRESHOLD, BREAKER_COOLDOWN_MS / 1000);
                    return FetchResult.failed();
                }
                if (attempt < MAX_ATTEMPTS) {
                    sleepQuietly(RETRY_BACKOFF_MS);
                }
            }
        }
        log.warn("资金流向请求最终失败 url={} err={}", url, last == null ? "unknown" : last.getMessage());
        return FetchResult.failed();
    }

    /** 东财资金流当前是否处于熔断期（选股等批量调用方可据此提前跳过，省掉整轮询问）。 */
    public boolean isUpstreamDown() {
        return breaker.isOpen();
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
