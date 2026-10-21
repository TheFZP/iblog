package com.iblog.module.paper.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.iblog.module.paper.entity.PaperAccount;
import com.iblog.module.paper.mapper.PaperAccountMapper;
import com.iblog.module.paper.support.TradeCalendar;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 模拟盘后台引擎：T+1 解锁、撮合挂单、自动交易、收盘结算。
 *
 * <p>tick 每 15 秒一次：先兜底解锁昨日买入的持仓（防止服务器错过 15:05 结算），
 * 再撮合限价挂单，最后交给 {@link PaperAutoTrader} 做自动买卖（账户/服务端开关都开时才执行）。
 * 结算固定在交易日 15:05，用结算价刷新持仓并落净值快照。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PaperTradingEngine {

    private final PaperAccountMapper accountMapper;
    private final PaperOrderService orderService;
    private final PaperPositionService positionService;
    private final PaperSettlementService settlementService;
    private final PaperAutoTrader autoTrader;
    private final TradeCalendar calendar;

    @Scheduled(fixedDelayString = "${iblog.paper.tick-ms:15000}")
    public void tick() {
        if (!calendar.allowOrders() && !calendar.isTradingDay(calendar.today())) {
            return;
        }
        List<PaperAccount> accounts = accountMapper.selectList(Wrappers.<PaperAccount>lambdaQuery());
        for (PaperAccount account : accounts) {
            // T+1 兜底：服务器错过 15:05 结算时，次日（含开盘前）把昨日买入的持仓解锁。
            // 有挂单时跳过——挂单已冻结可卖数量，这里再解锁会导致同一批股票被卖两次。
            try {
                if (orderService.countPending(account.getId()) == 0) {
                    positionService.unlockTradable(account.getId(), calendar.todayText());
                }
            } catch (RuntimeException ex) {
                log.warn("模拟盘 T+1 解锁失败 accountId={}", account.getId(), ex);
            }
            if (!calendar.allowOrders()) {
                continue;
            }
            try {
                orderService.processPending(account.getId());
            } catch (RuntimeException ex) {
                log.warn("模拟盘撮合 tick 失败 accountId={}", account.getId(), ex);
            }
            try {
                autoTrader.run(account);
            } catch (RuntimeException ex) {
                log.warn("模拟盘自动交易失败 accountId={}", account.getId(), ex);
            }
        }
    }

    @Scheduled(cron = "0 5 15 * * ?", zone = "Asia/Shanghai")
    public void settleDaily() {
        if (!calendar.isTradingDay(calendar.today())) {
            return;
        }
        List<PaperAccount> accounts = accountMapper.selectList(Wrappers.<PaperAccount>lambdaQuery());
        for (PaperAccount account : accounts) {
            try {
                settlementService.settle(account);
            } catch (RuntimeException ex) {
                log.warn("模拟盘结算失败 accountId={}", account.getId(), ex);
            }
        }
    }
}
