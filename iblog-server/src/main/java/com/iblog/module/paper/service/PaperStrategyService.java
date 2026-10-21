package com.iblog.module.paper.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.iblog.common.exception.BizException;
import com.iblog.module.paper.dto.PaperStrategyUpdateRequest;
import com.iblog.module.paper.entity.PaperStrategy;
import com.iblog.module.paper.mapper.PaperStrategyMapper;
import com.iblog.module.paper.support.TradeCalendar;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 模拟盘策略 CRUD。当前只有一条默认的「趋势选股」策略，
 * 但表结构预留了多策略，后面加回测/多策略组合不用改表。
 */
@Service
@RequiredArgsConstructor
public class PaperStrategyService {

    private final PaperStrategyMapper strategyMapper;
    private final TradeCalendar calendar;

    public PaperStrategy ensureDefault(Long accountId) {
        PaperStrategy existing = strategyMapper.selectOne(Wrappers.<PaperStrategy>lambdaQuery()
                .eq(PaperStrategy::getAccountId, accountId)
                .orderByAsc(PaperStrategy::getId)
                .last("limit 1"));
        if (existing != null) {
            return existing;
        }
        PaperStrategy strategy = new PaperStrategy();
        strategy.setAccountId(accountId);
        strategy.setName("趋势选股");
        strategy.setType("AUTO");
        strategy.setParams("{\"minScore\":60,\"stopLossPct\":8,\"takeProfitPct\":15,\"maxHoldDays\":10}");
        strategy.setEnabled(1);
        strategyMapper.insert(strategy);
        return strategy;
    }

    public List<PaperStrategy> list(Long accountId) {
        ensureDefault(accountId);
        return strategyMapper.selectList(Wrappers.<PaperStrategy>lambdaQuery()
                .eq(PaperStrategy::getAccountId, accountId)
                .orderByAsc(PaperStrategy::getId));
    }

    @Transactional
    public PaperStrategy update(Long accountId, Long strategyId, PaperStrategyUpdateRequest request) {
        PaperStrategy strategy = strategyMapper.selectById(strategyId);
        if (strategy == null || !accountId.equals(strategy.getAccountId())) {
            throw new BizException(404, "策略不存在");
        }
        if (request.getEnabled() != null) {
            strategy.setEnabled(request.getEnabled() ? 1 : 0);
        }
        if (request.getParams() != null) {
            strategy.setParams(request.getParams());
        }
        strategyMapper.updateById(strategy);
        return strategy;
    }

    public void touch(PaperStrategy strategy) {
        strategy.setLastRunTime(calendar.now());
        strategyMapper.updateById(strategy);
    }
}
