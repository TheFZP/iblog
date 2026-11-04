package com.iblog.module.paper.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iblog.common.exception.BizException;
import com.iblog.module.paper.dto.PaperStrategyUpdateRequest;
import com.iblog.module.paper.entity.PaperStrategy;
import com.iblog.module.paper.mapper.PaperStrategyMapper;
import com.iblog.module.paper.support.PaperStrategyParams;
import com.iblog.module.paper.support.TradeCalendar;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 模拟盘策略 CRUD。当前只有一条默认的「趋势选股」策略，
 * 但表结构预留了多策略，后面加回测/多策略组合不用改表。
 *
 * <p>一个账户一套规则（paper_strategy 有 uk_account），params 是完整 JSON；
 * update 时会先解析再落库，脏字段/越界值被钳制，避免前端传坏配置。</p>
 */
@Service
@RequiredArgsConstructor
public class PaperStrategyService {

    private final PaperStrategyMapper strategyMapper;
    private final TradeCalendar calendar;
    private final ObjectMapper objectMapper;

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
        strategy.setParams(PaperStrategyParams.defaultJson(objectMapper));
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

    /**
     * 是否存在开启了新闻因子的策略。
     *
     * <p>新闻扫描是全局任务（热榜前 N 只扫一次，所有账户共用），只有所有账户都关掉
     * {@code newsEnabled} 时才值得整轮跳过——否则用户关了开关仍会每 30 分钟消耗 AI 调用。
     * 解析失败的脏配置按「未开启」处理，避免一条坏 JSON 把扫描永久卡住。</p>
     */
    public boolean anyNewsEnabled() {
        try {
            List<PaperStrategy> all = strategyMapper.selectList(Wrappers.<PaperStrategy>lambdaQuery());
            for (PaperStrategy strategy : all) {
                if (PaperStrategyParams.parse(objectMapper, strategy.getParams()).isNewsEnabled()) {
                    return true;
                }
            }
        } catch (RuntimeException ignored) {
            // 读库失败时宁可扫一次，也不要因为一次数据库抖动让新闻缓存长期为空
            return true;
        }
        return false;
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
            PaperStrategyParams normalized = PaperStrategyParams.parse(objectMapper, request.getParams());
            strategy.setParams(normalized.toJson(objectMapper));
        }
        strategyMapper.updateById(strategy);
        return strategy;
    }

    /** 用一段参数 JSON 覆盖账户当前策略（规则模板「一键应用」用），不做其它改动。 */
    @Transactional
    public PaperStrategy replaceParams(Long accountId, String paramsJson) {
        PaperStrategy strategy = ensureDefault(accountId);
        strategy.setParams(PaperStrategyParams.parse(objectMapper, paramsJson).toJson(objectMapper));
        strategyMapper.updateById(strategy);
        return strategy;
    }

    public void touch(PaperStrategy strategy) {
        strategy.setLastRunTime(calendar.now());
        strategyMapper.updateById(strategy);
    }
}
