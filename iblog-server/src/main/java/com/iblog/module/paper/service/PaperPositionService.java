package com.iblog.module.paper.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.iblog.module.paper.entity.PaperPosition;
import com.iblog.module.paper.mapper.PaperPositionMapper;
import com.iblog.module.paper.support.PaperNumbers;
import com.iblog.module.paper.vo.PaperPositionVO;
import com.iblog.module.stock.model.StockQuote;
import com.iblog.module.stock.support.StockCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 模拟盘持仓：查询、行情刷新、T+1 解锁。
 */
@Service
@RequiredArgsConstructor
public class PaperPositionService {

    private final PaperPositionMapper positionMapper;
    private final PaperQuoteService quoteService;

    public List<PaperPosition> list(Long accountId) {
        return positionMapper.selectList(Wrappers.<PaperPosition>lambdaQuery()
                .eq(PaperPosition::getAccountId, accountId)
                .orderByDesc(PaperPosition::getMarketValue)
                .orderByAsc(PaperPosition::getCode));
    }

    public PaperPosition findByCode(Long accountId, String code) {
        return positionMapper.selectOne(Wrappers.<PaperPosition>lambdaQuery()
                .eq(PaperPosition::getAccountId, accountId)
                .eq(PaperPosition::getCode, code)
                .last("limit 1"));
    }

    /** 拉最新行情刷新持仓的现价/市值/浮盈，并落库。 */
    public List<PaperPosition> refreshQuotes(Long accountId) {
        List<PaperPosition> positions = list(accountId);
        if (positions.isEmpty()) {
            return positions;
        }
        Map<String, StockQuote> quotes = quoteService.quotes(
                positions.stream().map(PaperPosition::getCode).toList());
        for (PaperPosition position : positions) {
            StockQuote quote = quotes.get(position.getCode());
            if (quote == null || quote.getPrice() <= 0) {
                continue;
            }
            if (quote.getName() != null && !quote.getName().isBlank()) {
                position.setName(quote.getName());
            }
            position.setLastPrice(PaperNumbers.price(BigDecimal.valueOf(quote.getPrice())));
            applyValuation(position);
            positionMapper.updateById(position);
        }
        return positions;
    }

    /** 按现价重算市值与浮盈（不落库，调用方决定）。 */
    public void applyValuation(PaperPosition position) {
        int quantity = position.getQuantity() == null ? 0 : position.getQuantity();
        BigDecimal lastPrice = PaperNumbers.nz(position.getLastPrice());
        BigDecimal marketValue = PaperNumbers.money(lastPrice.multiply(BigDecimal.valueOf(quantity)));
        BigDecimal cost = PaperNumbers.nz(position.getCostPrice()).multiply(BigDecimal.valueOf(quantity));
        position.setMarketValue(marketValue);
        position.setProfit(PaperNumbers.money(marketValue.subtract(cost)));
        position.setProfitPct(PaperNumbers.pct(position.getProfit(), cost));
    }

    /**
     * T+1 兜底解锁：把买入日早于今天、且仍被锁定的持仓恢复为可卖。
     *
     * <p>正常路径由 15:05 结算调用 {@link #unlockAll(Long)}；但如果服务器在结算时停机，
     * 次日开盘前这里补一次。调用方必须先确认该账户没有待成交委托，
     * 否则挂单冻结的可卖数量会被重复解锁。</p>
     */
    public void unlockTradable(Long accountId, String today) {
        if (today == null || today.isBlank()) {
            return;
        }
        for (PaperPosition position : list(accountId)) {
            int quantity = position.getQuantity() == null ? 0 : position.getQuantity();
            int available = position.getAvailableQuantity() == null ? 0 : position.getAvailableQuantity();
            if (quantity <= 0 || available >= quantity) {
                continue;
            }
            String openDate = position.getOpenDate();
            // 日期格式 yyyy-MM-dd，字典序即时间序；当天买入的持仓不可卖。
            if (openDate == null || openDate.compareTo(today) >= 0) {
                continue;
            }
            position.setAvailableQuantity(quantity);
            positionMapper.updateById(position);
        }
    }

    /** 收盘结算：当天买入的持仓在次日变为可卖。 */
    public void unlockAll(Long accountId) {
        List<PaperPosition> positions = list(accountId);
        for (PaperPosition position : positions) {
            if (position.getAvailableQuantity() == null
                    || !position.getAvailableQuantity().equals(position.getQuantity())) {
                position.setAvailableQuantity(position.getQuantity());
                positionMapper.updateById(position);
            }
        }
    }

    public List<PaperPositionVO> toVOList(List<PaperPosition> positions) {
        return positions.stream().map(PaperPositionService::toVO).toList();
    }

    public static PaperPositionVO toVO(PaperPosition position) {
        String currency = StockCode.isHk(position.getCode()) ? "HK$" : "¥";
        return PaperPositionVO.builder()
                .code(position.getCode())
                .name(position.getName())
                .quantity(position.getQuantity())
                .availableQuantity(position.getAvailableQuantity())
                .costPrice(PaperNumbers.nz(position.getCostPrice()))
                .lastPrice(PaperNumbers.nz(position.getLastPrice()))
                .marketValue(PaperNumbers.money(position.getMarketValue()))
                .profit(PaperNumbers.money(position.getProfit()))
                .profitPct(PaperNumbers.nz(position.getProfitPct()))
                .openDate(position.getOpenDate())
                .currency(currency)
                .build();
    }
}
