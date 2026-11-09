package com.iblog.module.paper.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.iblog.module.paper.entity.PaperOrder;
import com.iblog.module.paper.entity.PaperPredictCandidate;
import com.iblog.module.paper.entity.PaperSignal;
import com.iblog.module.paper.entity.PaperTrade;
import com.iblog.module.paper.mapper.PaperOrderMapper;
import com.iblog.module.paper.mapper.PaperPredictCandidateMapper;
import com.iblog.module.paper.mapper.PaperTradeMapper;
import com.iblog.module.paper.support.TradeCalendar;
import com.iblog.module.paper.support.PaperConstants;
import com.iblog.module.stock.support.StockCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 尾盘预测胜率统计。
 *
 * <p>两条口径：</p>
 * <ol>
 *   <li><b>预测候选命中率</b>：每天尾盘预测从热榜选出的候选，用「预测时现价 → 当日收盘」的
 *       涨跌幅判断命中（涨 &gt; 0 记为命中）。15:05 结算时回填收盘价并落库，
 *       再按分数分桶统计——分数越高的档命中率越高，说明打分越有区分度。</li>
 *   <li><b>预测买入交易胜率</b>：尾盘预测实际买入、且已卖出的交易，按「卖价 &gt; 买价」算盈利占比，
 *       反应这套预测抓到的票真实赚不赚钱。</li>
 * </ol>
 * 前者衡量「打分靠不靠谱」，后者衡量「按分买最终盈没盈利」。两者独立、可在页面/日志复盘。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PaperPredictStatService {

    private static final BigDecimal BD_100 = BigDecimal.valueOf(100);

    private final PaperPredictCandidateMapper predictCandidateMapper;
    private final PaperTradeMapper tradeMapper;
    private final PaperOrderMapper orderMapper;
    private final PaperQuoteService quoteService;
    private final TradeCalendar calendar;

    /**
     * 尾盘预测跑完后，把候选落库为一轮胜率样本。
     */
    public void recordCandidates(List<PaperSignal> signals) {
        if (signals == null || signals.isEmpty()) {
            return;
        }
        String roundTime = calendar.now().toLocalTime().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
        List<PaperPredictCandidate> rows = new ArrayList<>(signals.size());
        for (PaperSignal signal : signals) {
            if (signal == null || signal.getScore() == null) {
                continue;
            }
            PaperPredictCandidate row = new PaperPredictCandidate();
            row.setAccountId(signal.getAccountId());
            row.setStrategyId(signal.getStrategyId());
            row.setTradeDate(signal.getTradeDate());
            row.setRoundTime(roundTime);
            row.setCode(signal.getCode());
            row.setName(signal.getName());
            row.setScore(signal.getScore());
            row.setReason(signal.getReason());
            row.setPredictPrice(signal.getPrice() == null ? BigDecimal.ZERO : signal.getPrice());
            // close 字段留空，结算时再回填，避免盘中把未定型的收盘价写死
            predictCandidateMapper.insert(row);
            rows.add(row);
        }
        log.info("尾盘预测候选落库 account={} 轮次={} 条数={}", rows.isEmpty() ? "-" : rows.get(0).getAccountId(),
                roundTime, rows.size());
    }

    /**
     * 结算时给当天的预测候选回填收盘价并打印胜率。
     *
     * <p>为什么在结算做：15:05 的收盘价才算定型，盘中回填会把「预测时现价」和「还没走完的
     * 当日价」混在一起，cheat 胜率。结算走完例再统一回填，保证口径干净。</p>
     */
    public void backfillCloseAndLog(Long accountId, List<PaperPredictCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return;
        }
        int filled = 0;
        for (PaperPredictCandidate c : candidates) {
            if (c.getClosePrice() != null) {
                filled++;
                continue;
            }
            String code = StockCode.normalize(c.getCode());
            try {
                double rawPrice = quoteService.quote(code).getPrice();
                if (rawPrice <= 0) {
                    continue;
                }
                BigDecimal price = BigDecimal.valueOf(rawPrice);
                applyClose(c, price);
                predictCandidateMapper.updateById(c);
                filled++;
            } catch (RuntimeException ex) {
                log.warn("预测候选收盘价回填失败 account={} code={} msg={}", accountId, code, ex.getMessage());
            }
        }
        // 命中数按分数档归集，打印分桶胜率，方便看出「分越高是否越准」
        int[] hitCount = new int[5];
        int[] totalCount = new int[5];
        int hit = 0;
        int total = 0;
        for (PaperPredictCandidate c : candidates) {
            if (c.getHit() == null) {
                continue;
            }
            int bucket = bucketOf(c.getScore());
            if (bucket >= 0) {
                hitCount[bucket] += (c.getHit() == 1) ? 1 : 0;
                totalCount[bucket]++;
            }
            if (c.getHit() == 1) {
                hit++;
            }
            total++;
        }
        log.info("尾盘预测胜率(当日尾盘→收盘) account={} date={} 已回填={}/{} 命中={}/{} 胜率={}%",
                accountId, calendar.todayText(), filled, candidates.size(), hit, total,
                total == 0 ? 0 : roundPct(hit, total));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            if (totalCount[i] > 0) {
                sb.append(i).append("档").append(scoreRange(i)).append(":")
                        .append(hitCount[i]).append("/").append(totalCount[i])
                        .append("=").append(roundPct(hitCount[i], totalCount[i])).append("% ");
            }
        }
        if (sb.length() > 0) {
            log.info("尾盘预测胜率分档 account={} {}", accountId, sb.toString().trim());
        }
    }

    private void applyClose(PaperPredictCandidate c, BigDecimal close) {
        BigDecimal predict = c.getPredictPrice();
        if (predict == null || predict.signum() <= 0) {
            c.setClosePrice(close);
            c.setClosePct(BigDecimal.ZERO);
            c.setHit(2);
            return;
        }
        BigDecimal pct = close.subtract(predict)
                .multiply(BD_100)
                .divide(predict, 4, RoundingMode.HALF_UP);
        c.setClosePrice(close);
        c.setClosePct(pct);
        int cmp = pct.signum();
        c.setHit(cmp > 0 ? 1 : (cmp < 0 ? 0 : 2));
    }

    /** 分数分桶：0~2 档覆盖 0~120+。 */
    private int bucketOf(int score) {
        if (score < 40) {
            return 0;
        } else if (score < 70) {
            return 1;
        } else if (score < 90) {
            return 2;
        } else {
            return 3;
        }
    }

    private String scoreRange(int bucket) {
        return switch (bucket) {
            case 0 -> "<40";
            case 1 -> "40-69";
            case 2 -> "70-89";
            default -> "≥90";
        };
    }

    /**
     * 尾盘预测买入的交易胜率：对每笔策略买入，寻找其后同代码的卖出，「卖价 &gt; 买价」记为盈利。
     *
     * <p>用 FIFO 逐笔配对（同一代码先买先卖）。只统计「已彻底平仓」的头寸，
     * 把实际盈利笔数占比上报 —— 这是「按预测买入后最终赚不赚钱」的直接口径。
     * 仅在开启尾盘预测时才打印（此时策略自动买入即是预测买入）。</p>
     */
    public String tradeWinRate(Long accountId) {
        try {
            List<PaperTrade> trades = tradeMapper.selectList(Wrappers.<PaperTrade>lambdaQuery()
                    .eq(PaperTrade::getAccountId, accountId)
                    .orderByAsc(PaperTrade::getTradeTime));
            // 只统计自动交易（策略）产生的成交 —— 尾盘预测模式下的自动买入即是预测买入。
            // trade 不落 source，需经 order 解析；批量取 order.id → source 映射。
            List<Long> orderIds = trades.stream().map(PaperTrade::getOrderId)
                    .filter(java.util.Objects::nonNull).distinct().toList();
            Map<Long, String> orderSource = java.util.Collections.emptyMap();
            if (!orderIds.isEmpty()) {
                orderSource = orderMapper.selectBatchIds(orderIds).stream()
                        .collect(java.util.stream.Collectors.toMap(PaperOrder::getId,
                                PaperOrder::getSource, (a, b) -> a));
            }
            // 买入队列：同一代码按时间排队，卖出一笔消费一笔（FIFO，模拟逐笔平仓）
            Map<String, List<BigDecimal>> buyPrices = new LinkedHashMap<>();
            int wins = 0;
            int closes = 0;
            for (PaperTrade t : trades) {
                String src = orderSource.get(t.getOrderId());
                if (!PaperConstants.SOURCE_STRATEGY.equals(src)) {
                    continue; // 手动单不计入预测胜率
                }
                String code = StockCode.normalize(t.getCode());
                boolean sell = "SELL".equals(t.getSide());
                if (!sell) {
                    buyPrices.computeIfAbsent(code, k -> new ArrayList<>()).add(t.getPrice());
                    continue;
                }
                List<BigDecimal> buys = buyPrices.get(code);
                if (buys == null || buys.isEmpty()) {
                    continue;
                }
                BigDecimal cost = buys.remove(0);
                closes++;
                if (t.getPrice() != null && t.getPrice().compareTo(cost) > 0) {
                    wins++;
                }
            }
            return "预测买入胜率 " + wins + "/" + closes + "=" + (closes == 0 ? 0 : roundPct(wins, closes)) + "%";
        } catch (RuntimeException ex) {
            log.warn("预测买入胜率统计失败 accountId={}", accountId, ex);
            return "预测买入胜率 暂无";
        }
    }

    private long roundPct(int hit, int total) {
        return BigDecimal.valueOf(hit)
                .multiply(BD_100)
                .divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP)
                .longValue();
    }
}