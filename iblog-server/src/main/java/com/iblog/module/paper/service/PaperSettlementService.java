package com.iblog.module.paper.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.iblog.module.paper.entity.PaperAccount;
import com.iblog.module.paper.entity.PaperOrder;
import com.iblog.module.paper.entity.PaperPosition;
import com.iblog.module.paper.entity.PaperPredictCandidate;
import com.iblog.module.paper.mapper.PaperAccountMapper;
import com.iblog.module.paper.mapper.PaperOrderMapper;
import com.iblog.module.paper.mapper.PaperPredictCandidateMapper;
import com.iblog.module.paper.support.PaperConstants;
import com.iblog.module.paper.support.TradeCalendar;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 模拟盘收盘结算（15:05）。
 *
 * <p>三件事：撤掉未成交的限价单、把今天买入的持仓解锁为可卖（T+1）、
 * 用收盘价重算账户并写当天净值快照。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PaperSettlementService {

    private final PaperAccountMapper accountMapper;
    private final PaperOrderMapper orderMapper;
    private final PaperAccountService accountService;
    private final PaperPositionService positionService;
    private final PaperOrderService orderService;
    private final TradeCalendar calendar;
    private final PaperPredictCandidateMapper predictCandidateMapper;
    private final PaperPredictStatService predictStatService;

    @Transactional
    public void settle(PaperAccount account) {
        Long accountId = account.getId();
        PaperAccount latest = accountMapper.selectById(accountId);
        if (latest == null) {
            return;
        }
        List<PaperOrder> pendings = orderMapper.selectList(Wrappers.<PaperOrder>lambdaQuery()
                .eq(PaperOrder::getAccountId, accountId)
                .eq(PaperOrder::getStatus, PaperConstants.STATUS_PENDING));
        for (PaperOrder order : pendings) {
            try {
                orderService.cancelPending(latest, order);
            } catch (RuntimeException ex) {
                log.warn("结算撤单失败 orderId={}", order.getId(), ex);
            }
        }

        positionService.unlockAll(accountId);
        List<PaperPosition> positions = positionService.refreshQuotes(accountId);

        PaperAccount fresh = accountMapper.selectById(accountId);
        if (fresh == null) {
            return;
        }
        accountService.recompute(fresh, positions);
        accountService.snapshotNav(fresh);
        // 回填当日尾盘预测候选的收盘价并打印胜率；失败不影响结算
        try {
            backfillPredict(accountId);
        } catch (RuntimeException ex) {
            log.warn("预测胜率回填失败 accountId={} msg={}", accountId, ex.getMessage());
        }
        log.info("模拟盘结算完成 accountId={} date={} totalAsset={}", accountId,
                calendar.todayText(), fresh.getTotalAsset());
    }

    /** 给当天尾盘预测候选回填收盘价，并打印胜率日志。 */
    private void backfillPredict(Long accountId) {
        List<PaperPredictCandidate> candidates = predictCandidateMapper.selectList(
                Wrappers.<PaperPredictCandidate>lambdaQuery()
                        .eq(PaperPredictCandidate::getAccountId, accountId)
                        .eq(PaperPredictCandidate::getTradeDate, calendar.todayText()));
        if (candidates.isEmpty()) {
            return;
        }
        predictStatService.backfillCloseAndLog(accountId, candidates);
        // 只有开启尾盘预测才走到这里：此时所有策略自动买入都是「预测买入」，打印交易胜率
        try {
            log.info(predictStatService.tradeWinRate(accountId));
        } catch (RuntimeException ex) {
            log.warn("预测买入胜率统计失败 accountId={} msg={}", accountId, ex.getMessage());
        }
    }
}
