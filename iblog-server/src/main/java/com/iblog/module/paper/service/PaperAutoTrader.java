package com.iblog.module.paper.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.iblog.common.exception.BizException;
import com.iblog.config.IblogProperties;
import com.iblog.module.paper.dto.PaperOrderRequest;
import com.iblog.module.paper.entity.PaperAccount;
import com.iblog.module.paper.entity.PaperOrder;
import com.iblog.module.paper.entity.PaperPosition;
import com.iblog.module.paper.entity.PaperSignal;
import com.iblog.module.paper.entity.PaperStrategy;
import com.iblog.module.paper.mapper.PaperAccountMapper;
import com.iblog.module.paper.mapper.PaperOrderMapper;
import com.iblog.module.paper.mapper.PaperSignalMapper;
import com.iblog.module.paper.support.LotRule;
import com.iblog.module.paper.support.PaperConstants;
import com.iblog.module.paper.support.PaperNumbers;
import com.iblog.module.paper.support.PaperStrategyParams;
import com.iblog.module.paper.support.PredictFilters;
import com.iblog.module.paper.support.TradeCalendar;
import com.iblog.module.stock.entity.StockPredictDaily;
import com.iblog.module.stock.model.KlineItem;
import com.iblog.module.stock.model.MarketSentiment;
import com.iblog.module.stock.model.StockQuote;
import com.iblog.module.stock.service.MarketSentimentService;
import com.iblog.module.stock.service.NextDayPredictService;
import com.iblog.module.stock.support.StockCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 自动交易执行器：按策略参数做「卖出规则 → 风控熔断 → 买入候选」。
 *
 * <p>刻意把卖出放在买入前面：先释放仓位和资金，再考虑新开仓；
 * 日亏熔断放在卖出之后、买入之前，当天亏损超阈值时只允许减仓不允许加仓。</p>
 *
 * <p>所有下单都复用 {@link PaperOrderService#place(PaperAccount, PaperOrderRequest, String, Long)}，
 * 因此自动交易同样受 T+1、涨跌停、整手、每日笔数上限等约束，不存在「自动交易特权」。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PaperAutoTrader {

    /** 尾盘预测第一轮时间：此前的早期候选不参与预测模式的买入。 */
    private static final LocalTime PREDICT_START = LocalTime.of(14, 30);

    private final PaperAccountMapper accountMapper;
    private final PaperAccountService accountService;
    private final PaperPositionService positionService;
    private final PaperSelectionService selectionService;
    private final PaperStrategyService strategyService;
    private final PaperOrderService orderService;
    private final PaperOrderMapper orderMapper;
    private final PaperSignalMapper signalMapper;
    private final KlineCacheService klineCacheService;
    private final MarketSentimentService sentimentService;
    private final NextDayPredictService nextDayPredictService;
    private final PaperQuoteService quoteService;
    private final IblogProperties properties;
    private final TradeCalendar calendar;
    private final ObjectMapper objectMapper;

    /** 引擎每 tick 对每个账户调用；自动交易关闭时直接返回。 */
    public void run(PaperAccount account) {
        if (account == null || !isAutoTradeOn(account)) {
            return;
        }
        PaperStrategy strategy = strategyService.ensureDefault(account.getId());
        if (strategy.getEnabled() == null || strategy.getEnabled() != 1) {
            return;
        }
        PaperStrategyParams params = PaperStrategyParams.parse(objectMapper, strategy.getParams());

        List<PaperPosition> positions = positionService.refreshQuotes(account.getId());
        account = accountService.recompute(account, positions);
        boolean brake = isDailyLossBrake(account);

        // 情绪只在规则显式开启时去取；关掉的人连一次上游请求都不该多花
        MarketSentiment sentiment = params.isSentimentEnabled() ? sentimentService.current() : null;

        // sell() 返回本 tick 实际卖出成功（卖单已成交）的代码：传给 buy()，避免同一 tick
        // 刚因为止盈/止损清仓、又立刻把这票从候选里买回来（空转手续费 + 仓位被 T+1 锁死）。
        Set<String> justSold = sell(account, positions, strategy, params, sentiment);

        if (brake) {
            log.warn("模拟盘触发日亏熔断 account={} 今日收益={}%，只减仓不加仓", account.getId(), account.getTotalProfitPct());
            return;
        }

        account = accountMapper.selectById(account.getId());
        if (account == null) {
            return;
        }
        account = accountService.recompute(account, positionService.list(account.getId()));
        buy(account, strategy, sentiment, justSold);
    }

    private boolean isAutoTradeOn(PaperAccount account) {
        return properties.getPaper().isEnabled()
                && account.getAutoTrade() != null
                && account.getAutoTrade() == 1;
    }

    // ---------- 卖出 ----------

    private Set<String> sell(PaperAccount account, List<PaperPosition> positions, PaperStrategy strategy,
                             PaperStrategyParams params, MarketSentiment sentiment) {
        // 本 tick 实际卖出成交的代码（成功清仓/减仓的都要：减仓后再买回等于原地加仓，同理应避免）。
        Set<String> soldCodes = new HashSet<>();
        // 情绪冰点清仓：整市场跌停一片时，技术位止盈止损都来不及，先降风险
        String exitReason = sentimentExitReason(params, sentiment);
        // 评分来自最近一次重新选股 / 尾盘预测；缓存为空时不触发，避免服务刚启动就误清仓
        boolean scoreSell = params.isPredictEnabled() || params.isHeldScoreSellEnabled();
        Map<String, PaperSelectionService.HeldScore> heldScores = scoreSell
                ? selectionService.heldScores(account.getId()) : Map.of();
        // 预测分卖出与预测买入共用 14:30 的时间门：早盘用隔夜/早盘评分卖容易被开盘波动带偏，
        // 也会让当天卖出过的票失去 14:30 重新预测后买回的机会。
        boolean predictSellAllowed = !params.isPredictEnabled()
                || !calendar.now().toLocalTime().isBefore(PREDICT_START);
        for (PaperPosition position : positions) {
            int available = position.getAvailableQuantity() == null ? 0 : position.getAvailableQuantity();
            if (available <= 0) {
                continue;
            }
            String reason;
            if (exitReason != null) {
                reason = exitReason;
            } else if (params.isSellAtOpen() && holdingDays(position) >= 1) {
                // 隔夜策略：T+1 可卖后的第一个 tick 直接市价清仓，不看止损止盈/MA10
                reason = "次日开盘卖出";
            } else {
                reason = sellReason(position, params);
                if (reason == null && predictSellAllowed) {
                    // 技术面没触发时，再看策略还看不看得上这只票；止损/止盈等价格规则优先。
                    // 尾盘预测模式用「预测分低于 predictSellMaxScore」；否则用普通持仓评分阈值。
                    int threshold = params.isPredictEnabled()
                            ? params.getPredictSellMaxScore() : params.getHeldScoreSellMin();
                    reason = heldScoreSellReason(heldScores.get(position.getCode()), threshold,
                            params.isPredictEnabled() ? "预测走弱" : "持仓评分过低");
                }
            }
            if (reason == null) {
                continue;
            }
            if (doSell(account, strategy, position, available, reason)) {
                soldCodes.add(position.getCode());
            }
        }
        return soldCodes;
    }

    private boolean doSell(PaperAccount account, PaperStrategy strategy, PaperPosition position, int available, String reason) {
        PaperOrderRequest request = new PaperOrderRequest();
        request.setCode(position.getCode());
        request.setSide(PaperConstants.SIDE_SELL);
        request.setType(PaperConstants.TYPE_MARKET);
        request.setQuantity(available);
        try {
            var filled = orderService.place(account, request, PaperConstants.SOURCE_STRATEGY, strategy.getId());
            recordSignal(account, strategy, position.getCode(), position.getName(), PaperConstants.ACTION_SELL,
                    "自动卖出（" + reason + "）", filled.getAvgPrice());
            return true;
        } catch (BizException ex) {
            log.info("自动卖出被拒 code={} reason={}", position.getCode(), ex.getMessage());
            return false;
        }
    }

    /**
     * 情绪冰点是否该无条件清仓。
     *
     * <p>只在三个条件同时满足时成立：规则开了情绪、配了清仓线、且上游确实给了数据。
     * <b>没有实时数据时绝不返回理由</b>——把「拿不到情绪」当成「情绪为 0」会导致
     * 某个 API 抖动就把所有仓位清光，这是最坏的失败模式。</p>
     */
    private String sentimentExitReason(PaperStrategyParams params, MarketSentiment sentiment) {
        if (sentiment == null || !sentiment.isAvailable()) {
            return null;
        }
        double exitScore = params.getSentimentExitScore();
        if (exitScore <= 0 || sentiment.getScore() > exitScore) {
            return null;
        }
        return "市场情绪冰点 " + sentiment.getScore() + "分（" + sentiment.getLevel() + "）";
    }

    private String sellReason(PaperPosition position, PaperStrategyParams params) {
        BigDecimal lastPrice = position.getLastPrice();
        if (lastPrice == null || lastPrice.signum() <= 0) {
            return null;
        }
        double profitPct = position.getProfitPct() == null ? 0 : position.getProfitPct().doubleValue();
        if (profitPct <= -params.getStopLossPct()) {
            return "止损 " + profitPct + "%";
        }
        if (profitPct >= params.getTakeProfitPct()) {
            return "止盈 " + profitPct + "%";
        }
        if (profitPct > 0) {
            double ma10 = ma10(position.getCode());
            if (!Double.isNaN(ma10) && lastPrice.doubleValue() < ma10) {
                return "跌破 MA10";
            }
        }
        if (holdingDays(position) >= params.getMaxHoldDays()) {
            return "持有超过 " + params.getMaxHoldDays() + " 天";
        }
        return null;
    }

    /**
     * 评分过低是否卖出。
     *
     * <p>评分缺失（还没重新选股 / 服务刚重启）时返回 null，不卖：
     * 把「没有分」当成 0 分会把所有持仓一次清光，这是最坏的失败模式。</p>
     */
    private String heldScoreSellReason(PaperSelectionService.HeldScore score, int threshold, String label) {
        if (score == null || threshold <= 0) {
            return null;
        }
        if (score.score() < threshold) {
            return label + " " + score.score() + " 分（低于 " + threshold + "）";
        }
        return null;
    }

    private double ma10(String code) {
        List<KlineItem> bars = klineCacheService.load(code, 20);
        if (bars.size() < 10) {
            return Double.NaN;
        }
        double sum = 0;
        for (int i = bars.size() - 10; i < bars.size(); i++) {
            sum += bars.get(i).getClose();
        }
        return sum / 10;
    }

    private long holdingDays(PaperPosition position) {
        String openDate = position.getOpenDate();
        if (openDate == null || openDate.isBlank()) {
            return 0;
        }
        try {
            return ChronoUnit.DAYS.between(LocalDate.parse(openDate), calendar.today());
        } catch (RuntimeException ex) {
            return 0;
        }
    }

    // ---------- 买入 ----------

    private void buy(PaperAccount account, PaperStrategy strategy, MarketSentiment sentiment,
                     Set<String> justSold) {
        PaperStrategyParams params = PaperStrategyParams.parse(objectMapper, strategy.getParams());
        // 次日看涨模式接管买入：只按昨天的全市场榜单买，不回退到热榜/尾盘候选
        if (params.isNextDayPredictEnabled()) {
            buyNextDay(account, strategy, params, sentiment, justSold);
            return;
        }
        if (!params.inBuyWindow(calendar.now())) {
            return;
        }
        // 尾盘预测模式只在 14:30 第一轮预测之后买入；之前不买，避免用到早盘的旧候选
        if (params.isPredictEnabled() && calendar.now().toLocalTime().isBefore(PREDICT_START)) {
            return;
        }
        String blockReason = sentimentBuyBlockReason(params, sentiment);
        if (blockReason != null) {
            log.info("模拟盘情绪不足不开新仓 account={} 情绪={}分({}) 门槛={}",
                    account.getId(),
                    sentiment == null ? "-" : sentiment.getScore(),
                    sentiment == null ? "-" : sentiment.getLevel(),
                    params.getSentimentBuyMinScore());
            // 记一条信号，用户在前端能看见「为什么今天没买」，而不是以为引擎坏了。
            // 引擎每 15 秒一个 tick，必须每天只记一次，否则信号表会被同一条刷屏
            recordBlockedOnce(account, strategy, blockReason);
            return;
        }
        List<PaperSignal> candidates = selectionService.recentCandidates(account.getId());
        // 预测模式只买尾盘预测写出的候选；没有预测结果就空仓等待，不回退到早盘选股
        if (candidates.isEmpty() && !params.isPredictEnabled()) {
            candidates = selectionService.refreshIfStale(account.getId());
        }
        if (candidates.isEmpty()) {
            return;
        }
        double maxPositionPct = properties.getPaper().getMaxPositionPct();
        double maxTotalPct = properties.getPaper().getMaxTotalPositionPct();
        // 预测模式下只接受 14:30 之后写入的候选，且分数必须达到买入下限
        LocalDateTime predictCutoff = params.isPredictEnabled()
                ? calendar.today().atTime(PREDICT_START) : null;
        // 当天自动卖出过的标的整天不再买回：不然「评分过低卖出」会在下一个 tick 又买回来，
        // 变成反复买卖空转。手动卖出不在此列（用户手动卖就是想离场，后续想接回由用户自己决定）。
        Set<String> soldToday = todaySoldCodes(account.getId());

        for (PaperSignal candidate : candidates) {
            if (predictCutoff != null) {
                if (candidate.getScore() == null || candidate.getScore() < params.getPredictBuyMinScore()) {
                    continue;
                }
                if (candidate.getCreateTime() == null || candidate.getCreateTime().isBefore(predictCutoff)) {
                    continue;
                }
            }
            // 本 tick 刚卖出（或当天自动卖出过）的标的，不再买回：
            // 否则会陷入「卖出 → 仍入选 → 立刻买回」的空转，白付手续费还让当日买入仓位被 T+1 锁死。
            if (justSold.contains(candidate.getCode()) || soldToday.contains(candidate.getCode())) {
                continue;
            }
            PaperAccount fresh = accountMapper.selectById(account.getId());
            if (fresh == null) {
                return;
            }
            account = fresh;
            List<PaperPosition> positions = positionService.list(account.getId());
            if (holds(positions, candidate.getCode())) {
                continue;
            }
            BigDecimal price = candidate.getPrice();
            if (price == null || price.signum() <= 0) {
                continue;
            }
            BigDecimal totalAsset = PaperNumbers.nz(account.getTotalAsset());
            BigDecimal room = totalAsset.multiply(BigDecimal.valueOf(maxTotalPct))
                    .subtract(PaperNumbers.nz(account.getMarketValue()))
                    .min(PaperNumbers.nz(account.getCash()));
            if (room.signum() <= 0) {
                return;
            }
            // 留 2% 余量给滑点和费用，避免最后一分钱不够导致下单被拒
            BigDecimal budget = totalAsset.multiply(BigDecimal.valueOf(maxPositionPct)).min(room)
                    .multiply(new BigDecimal("0.98"));
            // 各板最小申报单位不同（科创板 200 股起、可 1 股递增），必须按板块取整，
            // 否则小资金账户算出 150 股这种数量会被下单校验直接拒掉
            int quantity = LotRule.roundDown(candidate.getCode(),
                    budget.divide(price, 0, RoundingMode.DOWN).intValue());
            if (quantity <= 0) {
                continue;
            }
            PaperOrderRequest request = new PaperOrderRequest();
            request.setCode(candidate.getCode());
            request.setSide(PaperConstants.SIDE_BUY);
            request.setType(PaperConstants.TYPE_MARKET);
            request.setQuantity(quantity);
            try {
                var filled = orderService.place(account, request, PaperConstants.SOURCE_STRATEGY, strategy.getId());
                recordSignal(account, strategy, candidate.getCode(), candidate.getName(), PaperConstants.ACTION_BUY,
                        "自动买入：" + candidate.getReason(), filled.getAvgPrice());
            } catch (BizException ex) {
                log.info("自动买入被拒 code={} reason={}", candidate.getCode(), ex.getMessage());
            }
        }
    }

    /**
     * 次日看涨买入：昨日 15:30 榜单 → 概率过滤 → 实时行情定价 → 复用常规仓位/风控/下单。
     *
     * <p>与常规买入的区别只有「候选来源」：仓位上限、整手、T+1、涨跌停、每日笔数
     * 全部由 {@link PaperOrderService#place} 兜底，不存在绕过风控的路径。</p>
     */
    private void buyNextDay(PaperAccount account, PaperStrategy strategy, PaperStrategyParams params,
                            MarketSentiment sentiment, Set<String> justSold) {
        if (!params.inNextDayBuyWindow(calendar.now())) {
            return;
        }
        String blockReason = sentimentBuyBlockReason(params, sentiment);
        if (blockReason != null) {
            log.info("模拟盘情绪不足不开新仓（次日看涨） account={} 情绪={}分({}) 门槛={}",
                    account.getId(),
                    sentiment == null ? "-" : sentiment.getScore(),
                    sentiment == null ? "-" : sentiment.getLevel(),
                    params.getSentimentBuyMinScore());
            recordBlockedOnce(account, strategy, blockReason);
            return;
        }
        List<StockPredictDaily> board = nextDayPredictService.latestBefore(calendar.today());
        if (board.isEmpty()) {
            return;
        }
        LocalDate boardDate = board.get(0).getTradeDate();
        if (boardDate == null || boardDate.isBefore(calendar.today().minusDays(7))) {
            log.info("模拟盘次日看涨：最近榜单 {} 已过期，跳过 account={}", boardDate, account.getId());
            return;
        }

        Set<String> skip = new HashSet<>(justSold);
        skip.addAll(todaySoldCodes(account.getId()));
        BuyState state = nextDayBuyState(account.getId());
        skip.addAll(state.attempted());
        int maxBuys = Math.max(1, params.getNextDayMaxBuys());
        int bought = state.bought();
        double threshold = params.getNextDayMinProbability();
        double maxPositionPct = properties.getPaper().getMaxPositionPct();
        double maxTotalPct = properties.getPaper().getMaxTotalPositionPct();

        for (StockPredictDaily row : board) {
            if (bought >= maxBuys) {
                break;
            }
            if (row.getProbability() == null || row.getProbability().doubleValue() < threshold) {
                continue;
            }
            if (row.getBuyable() != null && row.getBuyable() == 0) {
                continue;
            }
            String code = StockCode.normalize(row.getCode());
            if (code.isEmpty() || skip.contains(code)) {
                continue;
            }

            PaperAccount fresh = accountMapper.selectById(account.getId());
            if (fresh == null) {
                return;
            }
            account = fresh;
            List<PaperPosition> positions = positionService.list(account.getId());
            if (holds(positions, code)) {
                continue;
            }

            StockQuote quote;
            try {
                quote = quoteService.quote(code);
            } catch (RuntimeException ex) {
                log.debug("次日看涨：行情失败 code={} err={}", code, ex.getMessage());
                continue;
            }
            if (quote == null || quote.getPrice() <= 0) {
                continue;
            }
            BigDecimal price = BigDecimal.valueOf(quote.getPrice()).setScale(3, RoundingMode.HALF_UP);
            double limitPct = PredictFilters.limitUpPct(code, row.getName());
            double openChangePct = quote.getPrevClose() > 0
                    ? (quote.getPrice() - quote.getPrevClose()) / quote.getPrevClose() * 100 : 0;
            if (openChangePct >= limitPct - 0.15) {
                // 开盘涨停买不进，记一次信号后当天不再重试
                signalNextDay(account, strategy, row, "次日看涨：涨停无法买入", price);
                skip.add(code);
                continue;
            }

            BigDecimal totalAsset = PaperNumbers.nz(account.getTotalAsset());
            BigDecimal room = totalAsset.multiply(BigDecimal.valueOf(maxTotalPct))
                    .subtract(PaperNumbers.nz(account.getMarketValue()))
                    .min(PaperNumbers.nz(account.getCash()));
            if (room.signum() <= 0) {
                signalNextDay(account, strategy, row, "次日看涨：资金或仓位已满", price);
                return;
            }
            BigDecimal budget = totalAsset.multiply(BigDecimal.valueOf(maxPositionPct)).min(room)
                    .multiply(new BigDecimal("0.98"));
            int quantity = LotRule.roundDown(code,
                    budget.divide(price, 0, RoundingMode.DOWN).intValue());
            if (quantity <= 0) {
                signalNextDay(account, strategy, row, "次日看涨：资金不足以买入一手", price);
                skip.add(code);
                continue;
            }

            PaperOrderRequest request = new PaperOrderRequest();
            request.setCode(code);
            request.setSide(PaperConstants.SIDE_BUY);
            request.setType(PaperConstants.TYPE_MARKET);
            request.setQuantity(quantity);
            try {
                var filled = orderService.place(account, request, PaperConstants.SOURCE_STRATEGY, strategy.getId());
                signalNextDay(account, strategy, row, "次日看涨：自动买入", filled.getAvgPrice());
                skip.add(code);
                bought++;
            } catch (BizException ex) {
                log.info("次日看涨买入被拒 code={} reason={}", code, ex.getMessage());
                signalNextDay(account, strategy, row, "次日看涨：下单被拒（" + ex.getMessage() + "）", price);
                skip.add(code);
            }
        }
    }

    /** 次日模式信号：reason 统一以「次日看涨」开头，便于当天去重与前端区分。 */
    private void signalNextDay(PaperAccount account, PaperStrategy strategy, StockPredictDaily row,
                               String prefix, BigDecimal price) {
        double probability = row.getProbability() == null ? 0 : row.getProbability().doubleValue();
        String text = prefix + "（第" + (row.getRankNo() == null ? 0 : row.getRankNo())
                + "名，概率" + String.format("%.1f", probability) + "%）";
        recordSignal(account, strategy, row.getCode(), row.getName(), PaperConstants.ACTION_BUY,
                truncateSignal(text, 255), price);
    }

    /** 当天已尝试（所有「次日看涨」信号）与已成功买入的数量。 */
    private BuyState nextDayBuyState(Long accountId) {
        Set<String> attempted = new HashSet<>();
        int bought = 0;
        try {
            List<PaperSignal> signals = signalMapper.selectList(Wrappers.<PaperSignal>lambdaQuery()
                    .eq(PaperSignal::getAccountId, accountId)
                    .eq(PaperSignal::getAction, PaperConstants.ACTION_BUY)
                    .eq(PaperSignal::getTradeDate, calendar.todayText())
                    .likeRight(PaperSignal::getReason, "次日看涨"));
            for (PaperSignal signal : signals) {
                if (signal.getCode() == null || signal.getCode().isEmpty()) {
                    continue;
                }
                attempted.add(StockCode.normalize(signal.getCode()));
                if (signal.getReason() != null && signal.getReason().startsWith("次日看涨：自动买入")) {
                    bought++;
                }
            }
        } catch (RuntimeException ex) {
            log.warn("查询次日看涨信号失败 account={} err={}", accountId, ex.toString());
        }
        return new BuyState(attempted, bought);
    }

    private record BuyState(Set<String> attempted, int bought) {
    }

    private static String truncateSignal(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }

    private boolean holds(List<PaperPosition> positions, String code) {
        return positions.stream().anyMatch(position ->
                code.equals(position.getCode()) && position.getQuantity() != null && position.getQuantity() > 0);
    }

    /** 当天由自动交易卖出过的代码（source=STRATEGY），用于禁止当天买回。 */
    private Set<String> todaySoldCodes(Long accountId) {
        try {
            List<String> sold = orderMapper.selectList(Wrappers.<PaperOrder>lambdaQuery()
                    .select(PaperOrder::getCode)
                    .eq(PaperOrder::getAccountId, accountId)
                    .eq(PaperOrder::getSide, PaperConstants.SIDE_SELL)
                    .eq(PaperOrder::getSource, PaperConstants.SOURCE_STRATEGY)
                    .ge(PaperOrder::getCreateTime, calendar.today().atStartOfDay())
                    .groupBy(PaperOrder::getCode))
                    .stream().map(PaperOrder::getCode).toList();
            return new HashSet<>(sold);
        } catch (RuntimeException ex) {
            // 查询失败时退回到「只抑制同一 tick」，不影响正常买入
            log.warn("查询今日自动卖出标的失败 account={} err={}", accountId, ex.toString());
            return Set.of();
        }
    }

    /**
     * 情绪不足时的买入拦截原因。
     *
     * <p>与「冰点清仓」相反，这里只要情绪分低于开仓线就返回理由。
     * 同样要求 {@code available}——没数据不能当「情绪为 0」用两次：
     * 那样会一边清仓一边永不买入，等于把策略关掉。</p>
     */
    private String sentimentBuyBlockReason(PaperStrategyParams params, MarketSentiment sentiment) {
        if (sentiment == null || !sentiment.isAvailable()) {
            return null;
        }
        double minScore = params.getSentimentBuyMinScore();
        if (minScore <= 0 || sentiment.getScore() >= minScore) {
            return null;
        }
        return "市场情绪 " + sentiment.getScore() + "分（" + sentiment.getLevel() + "）低于开仓线 " + minScore;
    }

    /**
     * 每天只记一次的「暂停买入」信号。
     *
     * <p>引擎 15 秒一个 tick，直接写会让信号表被同一条刷屏几十遍；
     * 而完全不记，用户又会以为引擎坏了。折中：同一天同一条原因只记一次。</p>
     */
    private void recordBlockedOnce(PaperAccount account, PaperStrategy strategy, String reason) {
        String text = "暂停买入（" + reason + "）";
        String today = calendar.todayText();
        Long exists = signalMapper.selectCount(Wrappers.<PaperSignal>lambdaQuery()
                .eq(PaperSignal::getAccountId, account.getId())
                .eq(PaperSignal::getAction, PaperConstants.ACTION_BUY)
                .eq(PaperSignal::getTradeDate, today)
                .eq(PaperSignal::getReason, text));
        if (exists != null && exists > 0) {
            return;
        }
        recordSignal(account, strategy, "", "大盘", PaperConstants.ACTION_BUY, text, null);
    }

    // ---------- 风控/工具 ----------

    private boolean isDailyLossBrake(PaperAccount account) {
        BigDecimal totalAsset = PaperNumbers.nz(account.getTotalAsset());
        if (totalAsset.signum() <= 0) {
            return false;
        }
        BigDecimal base = accountService.todayBase(account);
        if (base.signum() <= 0) {
            return false;
        }
        double todayPct = totalAsset.subtract(base).doubleValue() / base.doubleValue() * 100;
        return todayPct <= -properties.getPaper().getMaxDailyLossPct() * 100;
    }

    private void recordSignal(PaperAccount account, PaperStrategy strategy, String code, String name,
                              String action, String reason, BigDecimal price) {
        PaperSignal signal = new PaperSignal();
        signal.setAccountId(account.getId());
        signal.setStrategyId(strategy.getId());
        signal.setCode(code);
        signal.setName(name);
        signal.setAction(action);
        signal.setReason(reason);
        signal.setScore(0);
        signal.setPrice(price);
        signal.setTradeDate(calendar.todayText());
        signalMapper.insert(signal);
    }
}
