package com.iblog.module.stock.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.iblog.module.stock.entity.StockPredictDaily;
import com.iblog.module.stock.mapper.StockPredictDailyMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * 次日预测榜单的持久化入口。
 *
 * <p>所有读写集中在这里，事务边界只有 {@link #replaceDaily}：同一交易日重跑时
 * 先删后插，唯一键 {@code (trade_date, code)} 是最后一道防线。</p>
 */
@Repository
@RequiredArgsConstructor
public class NextDayPredictStore {

    private final StockPredictDailyMapper mapper;

    /** 覆盖某交易日榜单（事务内先删后插）。 */
    @Transactional
    public void replaceDaily(LocalDate tradeDate, List<StockPredictDaily> items) {
        mapper.delete(Wrappers.<StockPredictDaily>lambdaQuery()
                .eq(StockPredictDaily::getTradeDate, tradeDate));
        for (StockPredictDaily item : items) {
            mapper.insert(item);
        }
    }

    /** 某交易日榜单，按排名升序。 */
    public List<StockPredictDaily> listByDate(LocalDate tradeDate) {
        return mapper.selectList(Wrappers.<StockPredictDaily>lambdaQuery()
                .eq(StockPredictDaily::getTradeDate, tradeDate)
                .orderByAsc(StockPredictDaily::getRankNo));
    }

    public boolean exists(LocalDate tradeDate) {
        return mapper.selectCount(Wrappers.<StockPredictDaily>lambdaQuery()
                .eq(StockPredictDaily::getTradeDate, tradeDate)) > 0;
    }

    /** 严格早于 {@code date} 的最近一期榜单；没有返回空列表。 */
    public List<StockPredictDaily> latestBefore(LocalDate date) {
        StockPredictDaily latest = mapper.selectOne(Wrappers.<StockPredictDaily>lambdaQuery()
                .lt(StockPredictDaily::getTradeDate, date)
                .orderByDesc(StockPredictDaily::getTradeDate)
                .last("LIMIT 1"));
        if (latest == null) {
            return List.of();
        }
        return listByDate(latest.getTradeDate());
    }

    /** 待回填的预测，较早的排前面。 */
    public List<StockPredictDaily> listPending(int limit) {
        return mapper.selectList(Wrappers.<StockPredictDaily>lambdaQuery()
                .eq(StockPredictDaily::getEvalStatus, "PENDING")
                .orderByAsc(StockPredictDaily::getTradeDate)
                .last("LIMIT " + Math.max(1, limit)));
    }

    /** 已评估样本（概率校准与统计用）。 */
    public List<StockPredictDaily> listEvaluated(LocalDate from, LocalDate to) {
        return mapper.selectList(Wrappers.<StockPredictDaily>lambdaQuery()
                .eq(StockPredictDaily::getEvalStatus, "EVALUATED")
                .ge(StockPredictDaily::getTradeDate, from)
                .le(StockPredictDaily::getTradeDate, to));
    }

    /** 区间内全部榜单（统计明细用，含待评估）。 */
    public List<StockPredictDaily> listBetween(LocalDate from, LocalDate to) {
        return mapper.selectList(Wrappers.<StockPredictDaily>lambdaQuery()
                .ge(StockPredictDaily::getTradeDate, from)
                .le(StockPredictDaily::getTradeDate, to)
                .orderByAsc(StockPredictDaily::getTradeDate)
                .orderByAsc(StockPredictDaily::getRankNo));
    }

    public void update(StockPredictDaily row) {
        mapper.updateById(row);
    }
}
