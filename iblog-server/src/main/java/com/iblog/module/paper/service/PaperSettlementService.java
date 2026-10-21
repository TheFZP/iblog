package com.iblog.module.paper.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.iblog.module.paper.entity.PaperAccount;
import com.iblog.module.paper.entity.PaperOrder;
import com.iblog.module.paper.entity.PaperPosition;
import com.iblog.module.paper.mapper.PaperAccountMapper;
import com.iblog.module.paper.mapper.PaperOrderMapper;
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
        log.info("模拟盘结算完成 accountId={} date={} totalAsset={}", accountId,
                calendar.todayText(), fresh.getTotalAsset());
    }
}
