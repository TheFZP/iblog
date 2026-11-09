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
    private final PaperSelectionService selectionService;
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

    /**
     * 盘前预选（9:25:10）。
     *
     * <p>为什么不是 9:25:00：集合竞价 9:25 撮合完成，行情接口要几秒才刷出开盘价，
     * 留 10 秒余量避免拿到 0 或昨收。预选只写候选，不下单 —— 真正买入仍等 9:30。</p>
     */
    @Scheduled(cron = "10 25 9 * * ?", zone = "Asia/Shanghai")
    public void preselectBeforeOpen() {
        if (!calendar.isTradingDay(calendar.today())) {
            return;
        }
        for (PaperAccount account : accountMapper.selectList(Wrappers.<PaperAccount>lambdaQuery())) {
            try {
                selectionService.preselectIfEnabled(account.getId());
            } catch (RuntimeException ex) {
                log.warn("模拟盘盘前预选失败 accountId={}", account.getId(), ex);
            }
        }
    }

    /**
     * 开盘复核（9:30:05）。
     *
     * <p>开盘价与实时行情在 9:30 就有了，先复核一轮：没开分时相关硬条件的策略
     * 在这一刻就能拿到完整结果。如果开了分时条件、此时分时还没落定，复核会保留预选结果
     * （{@code keepWhenEmpty}），由下面 9:32 的兜底任务再补一次。</p>
     */
    @Scheduled(cron = "5 30 9 * * ?", zone = "Asia/Shanghai")
    public void refreshAfterOpen() {
        if (!calendar.isTradingDay(calendar.today())) {
            return;
        }
        for (PaperAccount account : accountMapper.selectList(Wrappers.<PaperAccount>lambdaQuery())) {
            try {
                selectionService.refreshAfterOpenIfEnabled(account.getId(), false);
            } catch (RuntimeException ex) {
                log.warn("模拟盘开盘复核失败 accountId={}", account.getId(), ex);
            }
        }
    }

    /**
     * 复核兜底（9:32:00）。
     *
     * <p>只在候选仍是开盘前选出的时候才跑：9:30 复核成功时 createTime 已经更新，这里直接跳过；
     * 9:30 那轮因为分时未就绪而保留了预选结果的账户，会在这一轮拿到完整数据重新选。</p>
     */
    @Scheduled(cron = "0 32 9 * * ?", zone = "Asia/Shanghai")
    public void refreshAfterOpenFallback() {
        if (!calendar.isTradingDay(calendar.today())) {
            return;
        }
        for (PaperAccount account : accountMapper.selectList(Wrappers.<PaperAccount>lambdaQuery())) {
            try {
                selectionService.refreshAfterOpenIfEnabled(account.getId(), true);
            } catch (RuntimeException ex) {
                log.warn("模拟盘开盘复核兜底失败 accountId={}", account.getId(), ex);
            }
        }
    }

    /**
     * 尾盘预测（14:30、14:50）。
     *
     * <p>用当日最新数据重新给热榜和持仓打分：14:30 先排一轮，14:50 用更接近收盘的
     * 数据复核覆盖。开启尾盘预测的账户，自动交易只在这两轮之后按预测分买入。</p>
     */
    @Scheduled(cron = "0 30,50 14 * * ?", zone = "Asia/Shanghai")
    public void refreshPredictions() {
        if (!calendar.isTradingDay(calendar.today())) {
            return;
        }
        for (PaperAccount account : accountMapper.selectList(Wrappers.<PaperAccount>lambdaQuery())) {
            try {
                selectionService.predict(account.getId());
            } catch (RuntimeException ex) {
                log.warn("模拟盘尾盘预测失败 accountId={}", account.getId(), ex);
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
