package com.iblog.module.paper.service;

import com.iblog.common.exception.BizException;
import com.iblog.config.IblogProperties;
import com.iblog.module.paper.dto.PaperOrderRequest;
import com.iblog.module.paper.entity.PaperAccount;
import com.iblog.module.paper.entity.PaperOrder;
import com.iblog.module.paper.entity.PaperPosition;
import com.iblog.module.paper.entity.PaperSignal;
import com.iblog.module.paper.entity.PaperStrategy;
import com.iblog.module.paper.mapper.PaperAccountMapper;
import com.iblog.module.paper.mapper.PaperSignalMapper;
import com.iblog.module.paper.support.PaperConstants;
import com.iblog.module.paper.support.PaperNumbers;
import com.iblog.module.paper.support.PaperStrategyParams;
import com.iblog.module.paper.support.TradeCalendar;
import com.iblog.module.stock.model.KlineItem;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

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

    private final PaperAccountMapper accountMapper;
    private final PaperAccountService accountService;
    private final PaperPositionService positionService;
    private final PaperSelectionService selectionService;
    private final PaperStrategyService strategyService;
    private final PaperOrderService orderService;
    private final PaperSignalMapper signalMapper;
    private final KlineCacheService klineCacheService;
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

        sell(account, positions, strategy, params);

        if (brake) {
            log.warn("模拟盘触发日亏熔断 account={} 今日收益={}%，只减仓不加仓", account.getId(), account.getTotalProfitPct());
            return;
        }

        account = accountMapper.selectById(account.getId());
        if (account == null) {
            return;
        }
        account = accountService.recompute(account, positionService.list(account.getId()));
        buy(account, strategy);
    }

    private boolean isAutoTradeOn(PaperAccount account) {
        return properties.getPaper().isEnabled()
                && account.getAutoTrade() != null
                && account.getAutoTrade() == 1;
    }

    // ---------- 卖出 ----------

    private void sell(PaperAccount account, List<PaperPosition> positions, PaperStrategy strategy, PaperStrategyParams params) {
        for (PaperPosition position : positions) {
            int available = position.getAvailableQuantity() == null ? 0 : position.getAvailableQuantity();
            if (available <= 0) {
                continue;
            }
            String reason = sellReason(position, params);
            if (reason == null) {
                continue;
            }
            PaperOrderRequest request = new PaperOrderRequest();
            request.setCode(position.getCode());
            request.setSide(PaperConstants.SIDE_SELL);
            request.setType(PaperConstants.TYPE_MARKET);
            request.setQuantity(available);
            try {
                var filled = orderService.place(account, request, PaperConstants.SOURCE_STRATEGY, strategy.getId());
                recordSignal(account, strategy, position.getCode(), position.getName(), PaperConstants.ACTION_SELL,
                        "自动卖出（" + reason + "）", filled.getAvgPrice());
            } catch (BizException ex) {
                log.info("自动卖出被拒 code={} reason={}", position.getCode(), ex.getMessage());
            }
        }
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

    private void buy(PaperAccount account, PaperStrategy strategy) {
        List<PaperSignal> candidates = selectionService.recentCandidates(account.getId());
        if (candidates.isEmpty()) {
            candidates = selectionService.refreshIfStale(account.getId());
        }
        if (candidates.isEmpty()) {
            return;
        }
        double maxPositionPct = properties.getPaper().getMaxPositionPct();
        double maxTotalPct = properties.getPaper().getMaxTotalPositionPct();

        for (PaperSignal candidate : candidates) {
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
            int quantity = budget.divide(price, 0, RoundingMode.DOWN).intValue() / 100 * 100;
            if (quantity < 100) {
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

    private boolean holds(List<PaperPosition> positions, String code) {
        return positions.stream().anyMatch(position ->
                code.equals(position.getCode()) && position.getQuantity() != null && position.getQuantity() > 0);
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
