package com.iblog.module.paper.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.iblog.common.exception.BizException;
import com.iblog.config.IblogProperties;
import com.iblog.module.paper.dto.PaperOrderRequest;
import com.iblog.module.paper.entity.PaperAccount;
import com.iblog.module.paper.entity.PaperOrder;
import com.iblog.module.paper.entity.PaperPosition;
import com.iblog.module.paper.entity.PaperTrade;
import com.iblog.module.paper.mapper.PaperAccountMapper;
import com.iblog.module.paper.mapper.PaperOrderMapper;
import com.iblog.module.paper.mapper.PaperPositionMapper;
import com.iblog.module.paper.mapper.PaperTradeMapper;
import com.iblog.module.paper.support.FeeCalculator;
import com.iblog.module.paper.support.LotRule;
import com.iblog.module.paper.support.PaperConstants;
import com.iblog.module.paper.support.PaperNumbers;
import com.iblog.module.paper.support.PriceLimit;
import com.iblog.module.paper.support.TradeCalendar;
import com.iblog.module.paper.vo.PaperOrderVO;
import com.iblog.module.paper.vo.PaperTradeVO;
import com.iblog.module.stock.model.StockQuote;
import com.iblog.module.stock.support.StockCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模拟盘下单与撮合。
 *
 * <p>成交规则刻意做得「够用但诚实」：</p>
 * <ul>
 *   <li>市价单：按最新价 ± 滑点成交，成交价被涨跌停价截断；涨停不追买、跌停不砸卖。</li>
 *   <li>限价单：能立即成交就立即成交，否则挂 PENDING，冻结资金（买）或可卖数量（卖）；</li>
 *   <li>T+1：当天买入的持仓 availableQuantity 为 0，收盘结算后才等于 quantity。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PaperOrderService {

    private final PaperAccountMapper accountMapper;
    private final PaperOrderMapper orderMapper;
    private final PaperTradeMapper tradeMapper;
    private final PaperPositionMapper positionMapper;
    private final PaperAccountService accountService;
    private final PaperPositionService positionService;
    private final PaperQuoteService quoteService;
    private final FeeCalculator feeCalculator;
    private final TradeCalendar calendar;
    private final IblogProperties properties;

    @Transactional
    public PaperOrderVO place(PaperAccount account, PaperOrderRequest request) {
        return place(account, request, PaperConstants.SOURCE_MANUAL, null);
    }

    /**
     * 下单入口（自动交易也走这里，只是 source=STRATEGY）。
     *
     * @param account    下单账户（多账户时由 Controller/AutoTrader 解析，避免取错户）
     * @param source     MANUAL / STRATEGY
     * @param strategyId 自动交易时记录策略 id，便于回溯
     */
    @Transactional
    public PaperOrderVO place(PaperAccount account, PaperOrderRequest request, String source, Long strategyId) {
        if (account == null) {
            throw new BizException(401, "未登录，请先登录后台");
        }
        if (!calendar.allowOrders()) {
            throw new BizException(400, "当前非交易时段，模拟盘暂不受理委托");
        }
        String code = StockCode.normalize(request.getCode());
        if (code.isEmpty()) {
            throw new BizException(400, "股票代码不能为空");
        }
        if (StockCode.isHk(code)) {
            throw new BizException(400, "模拟盘暂只支持 A 股");
        }
        String side = normalizeSide(request.getSide());
        String type = normalizeType(request.getType());
        int quantity = request.getQuantity() == null ? 0 : request.getQuantity();
        if (quantity <= 0) {
            throw new BizException(400, "委托数量必须大于 0");
        }
        if (!LotRule.isValidQuantity(code, quantity)) {
            throw new BizException(400, LotRule.describe(code));
        }
        if (todayOrderCount(account.getId()) >= properties.getPaper().getMaxOrdersPerDay()) {
            throw new BizException(400, "今日委托笔数已达上限（" + properties.getPaper().getMaxOrdersPerDay() + " 笔）");
        }

        StockQuote quote = quoteService.quote(code);
        if (quote.isSuspended()) {
            throw new BizException(400, "该股票当前停牌或无成交");
        }
        String name = quote.getName();
        BigDecimal current = PaperNumbers.price(BigDecimal.valueOf(quote.getPrice()));
        BigDecimal prevClose = PaperNumbers.price(BigDecimal.valueOf(quote.getPrevClose()));

        PaperOrder order = new PaperOrder();
        order.setAccountId(account.getId());
        order.setCode(code);
        order.setName(name);
        order.setSide(side);
        order.setType(type);
        order.setQuantity(quantity);
        order.setFilledQuantity(0);
        order.setSource(source == null ? PaperConstants.SOURCE_MANUAL : source);
        order.setStrategyId(strategyId);
        order.setStatus(PaperConstants.STATUS_PENDING);

        if (PaperConstants.TYPE_LIMIT.equals(type)) {
            if (request.getPrice() == null || request.getPrice().signum() <= 0) {
                throw new BizException(400, "限价单必须填写委托价");
            }
            BigDecimal limitPrice = PaperNumbers.price(request.getPrice());
            checkLimitPrice(limitPrice, prevClose, code, name);
            order.setPrice(limitPrice);
            boolean buy = PaperConstants.SIDE_BUY.equals(side);
            boolean marketable = buy
                    ? limitPrice.compareTo(current) >= 0
                    : limitPrice.compareTo(current) <= 0;
            if (marketable) {
                fill(account, order, current, quantity);
            } else {
                reserve(account, order);
                orderMapper.insert(order);
            }
        } else {
            BigDecimal fillPrice = marketFillPrice(current, prevClose, code, name, PaperConstants.SIDE_BUY.equals(side));
            fill(account, order, fillPrice, quantity);
        }
        return toVO(order);
    }

    @Transactional
    public PaperOrderVO cancel(PaperAccount account, Long orderId) {
        if (account == null) {
            throw new BizException(401, "未登录，请先登录后台");
        }
        PaperOrder order = orderMapper.selectById(orderId);
        if (order == null || !account.getId().equals(order.getAccountId())) {
            throw new BizException(404, "委托不存在");
        }
        if (!PaperConstants.STATUS_PENDING.equals(order.getStatus())) {
            throw new BizException(400, "只有待成交的委托可以撤销");
        }
        releaseReservation(account, order);
        order.setStatus(PaperConstants.STATUS_CANCELLED);
        orderMapper.updateById(order);
        return toVO(order);
    }

    /** 结算时撤销待成交委托（引擎调用，不需要用户上下文）。 */
    @Transactional
    public void cancelPending(PaperAccount account, PaperOrder order) {
        if (!PaperConstants.STATUS_PENDING.equals(order.getStatus())) {
            return;
        }
        releaseReservation(account, order);
        order.setStatus(PaperConstants.STATUS_CANCELLED);
        orderMapper.updateById(order);
    }

    /** 该账户当前待成交委托数（引擎用于判断能否安全做 T+1 兜底解锁）。 */
    public long countPending(Long accountId) {
        Long count = orderMapper.selectCount(Wrappers.<PaperOrder>lambdaQuery()
                .eq(PaperOrder::getAccountId, accountId)
                .eq(PaperOrder::getStatus, PaperConstants.STATUS_PENDING));
        return count == null ? 0 : count;
    }

    /**
     * 撮合账户下所有挂着的限价单。引擎每 tick 调一次。
     * 单笔失败只记日志，不影响其他委托。
     */
    @Transactional
    public void processPending(Long accountId) {
        List<PaperOrder> pendings = orderMapper.selectList(Wrappers.<PaperOrder>lambdaQuery()
                .eq(PaperOrder::getAccountId, accountId)
                .eq(PaperOrder::getStatus, PaperConstants.STATUS_PENDING)
                .orderByAsc(PaperOrder::getCreateTime));
        for (PaperOrder order : pendings) {
            try {
                PaperAccount account = accountMapper.selectById(accountId);
                if (account == null) {
                    return;
                }
                StockQuote quote = quoteService.quote(order.getCode());
                if (quote.getPrice() <= 0) {
                    continue;
                }
                BigDecimal current = PaperNumbers.price(BigDecimal.valueOf(quote.getPrice()));
                boolean buy = PaperConstants.SIDE_BUY.equals(order.getSide());
                boolean marketable = buy
                        ? order.getPrice().compareTo(current) >= 0
                        : order.getPrice().compareTo(current) <= 0;
                if (!marketable) {
                    continue;
                }
                BigDecimal prevClose = PaperNumbers.price(BigDecimal.valueOf(quote.getPrevClose()));
                if (buy && PriceLimit.atLimitUp(current, prevClose, order.getCode(), quote.getName())) {
                    continue;
                }
                if (!buy && PriceLimit.atLimitDown(current, prevClose, order.getCode(), quote.getName())) {
                    continue;
                }
                releaseReservation(account, order);
                fill(account, order, current, order.getQuantity());
            } catch (RuntimeException ex) {
                log.warn("模拟盘挂单撮合失败 orderId={} code={}", order.getId(), order.getCode(), ex);
            }
        }
    }

    public Map<String, Object> orders(Long accountId, String status, int page, int size) {
        Page<PaperOrder> pageParam = new Page<>(Math.max(page, 1), Math.min(Math.max(size, 1), 100));
        var wrapper = Wrappers.<PaperOrder>lambdaQuery().eq(PaperOrder::getAccountId, accountId);
        if (status != null && !status.isBlank()) {
            wrapper.eq(PaperOrder::getStatus, status.trim().toUpperCase());
        }
        wrapper.orderByDesc(PaperOrder::getId);
        Page<PaperOrder> result = orderMapper.selectPage(pageParam, wrapper);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", result.getTotal());
        data.put("records", result.getRecords().stream().map(PaperOrderService::toVO).toList());
        return data;
    }

    public Map<String, Object> trades(Long accountId, int page, int size) {
        Page<PaperTrade> pageParam = new Page<>(Math.max(page, 1), Math.min(Math.max(size, 1), 100));
        Page<PaperTrade> result = tradeMapper.selectPage(pageParam, Wrappers.<PaperTrade>lambdaQuery()
                .eq(PaperTrade::getAccountId, accountId)
                .orderByDesc(PaperTrade::getId));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", result.getTotal());
        data.put("records", result.getRecords().stream().map(PaperOrderService::toTradeVO).toList());
        return data;
    }

    // ---------- 成交 ----------

    private void fill(PaperAccount account, PaperOrder order, BigDecimal fillPrice, int quantity) {
        LocalDateTime now = calendar.now();
        String tradeDate = calendar.todayText();
        BigDecimal amount = PaperNumbers.money(fillPrice.multiply(BigDecimal.valueOf(quantity)));
        boolean sell = PaperConstants.SIDE_SELL.equals(order.getSide());
        FeeCalculator.Fee fee = feeCalculator.of(amount, sell);
        PaperPosition position = positionService.findByCode(account.getId(), order.getCode());

        if (sell) {
            int available = position == null || position.getAvailableQuantity() == null
                    ? 0 : position.getAvailableQuantity();
            if (available < quantity) {
                throw new BizException(400, "可卖数量不足（T+1，当天买入的持仓次日才能卖出）");
            }
            position.setQuantity(position.getQuantity() - quantity);
            position.setAvailableQuantity(position.getAvailableQuantity() - quantity);
            if (position.getQuantity() <= 0) {
                positionMapper.deleteById(position.getId());
            } else {
                position.setLastPrice(fillPrice);
                positionService.applyValuation(position);
                positionMapper.updateById(position);
            }
            account.setCash(PaperNumbers.money(PaperNumbers.nz(account.getCash()).add(fee.sellIncome(amount))));
        } else {
            BigDecimal buyCost = fee.buyCost(amount);
            if (PaperNumbers.nz(account.getCash()).compareTo(buyCost) < 0) {
                throw new BizException(400, "可用资金不足，本次需要 " + buyCost + " 元");
            }
            account.setCash(PaperNumbers.money(PaperNumbers.nz(account.getCash()).subtract(buyCost)));
            if (position == null) {
                PaperPosition created = new PaperPosition();
                created.setAccountId(account.getId());
                created.setCode(order.getCode());
                created.setName(order.getName());
                created.setQuantity(quantity);
                created.setAvailableQuantity(0);
                created.setCostPrice(PaperNumbers.price(buyCost.divide(BigDecimal.valueOf(quantity), 4, RoundingMode.HALF_UP)));
                created.setLastPrice(fillPrice);
                created.setOpenDate(tradeDate);
                positionService.applyValuation(created);
                positionMapper.insert(created);
            } else {
                int newQuantity = position.getQuantity() + quantity;
                BigDecimal oldCost = PaperNumbers.nz(position.getCostPrice())
                        .multiply(BigDecimal.valueOf(position.getQuantity()));
                BigDecimal newCost = oldCost.add(buyCost);
                position.setQuantity(newQuantity);
                position.setCostPrice(PaperNumbers.price(newCost.divide(BigDecimal.valueOf(newQuantity), 4, RoundingMode.HALF_UP)));
                position.setLastPrice(fillPrice);
                positionService.applyValuation(position);
                positionMapper.updateById(position);
            }
        }

        if (order.getId() == null) {
            orderMapper.insert(order);
        }
        order.setStatus(PaperConstants.STATUS_FILLED);
        order.setFilledQuantity(quantity);
        order.setAvgPrice(fillPrice);
        order.setAmount(amount);
        order.setFilledTime(now);
        orderMapper.updateById(order);
        accountMapper.updateById(account);

        PaperTrade trade = new PaperTrade();
        trade.setAccountId(account.getId());
        trade.setOrderId(order.getId());
        trade.setCode(order.getCode());
        trade.setName(order.getName());
        trade.setSide(order.getSide());
        trade.setPrice(fillPrice);
        trade.setQuantity(quantity);
        trade.setAmount(amount);
        trade.setCommission(fee.commission());
        trade.setStampTax(fee.stampTax());
        trade.setTransferFee(fee.transferFee());
        trade.setTotalFee(fee.total());
        trade.setTradeDate(tradeDate);
        trade.setTradeTime(now);
        tradeMapper.insert(trade);

        accountService.recompute(account, positionService.list(account.getId()));
    }

    // ---------- 冻结/解冻 ----------

    private void reserve(PaperAccount account, PaperOrder order) {
        if (PaperConstants.SIDE_BUY.equals(order.getSide())) {
            BigDecimal estimate = estimateBuyCost(order);
            if (PaperNumbers.nz(account.getCash()).compareTo(estimate) < 0) {
                throw new BizException(400, "可用资金不足，挂单需要冻结 " + estimate + " 元");
            }
            account.setCash(PaperNumbers.money(PaperNumbers.nz(account.getCash()).subtract(estimate)));
            account.setFrozenCash(PaperNumbers.money(PaperNumbers.nz(account.getFrozenCash()).add(estimate)));
        } else {
            PaperPosition position = positionService.findByCode(account.getId(), order.getCode());
            int available = position == null || position.getAvailableQuantity() == null
                    ? 0 : position.getAvailableQuantity();
            if (available < order.getQuantity()) {
                throw new BizException(400, "可卖数量不足（T+1，当天买入的持仓次日才能卖出）");
            }
            position.setAvailableQuantity(available - order.getQuantity());
            positionMapper.updateById(position);
        }
        accountMapper.updateById(account);
    }

    private void releaseReservation(PaperAccount account, PaperOrder order) {
        if (PaperConstants.SIDE_BUY.equals(order.getSide())) {
            BigDecimal estimate = estimateBuyCost(order);
            account.setFrozenCash(PaperNumbers.money(PaperNumbers.nz(account.getFrozenCash()).subtract(estimate)));
            account.setCash(PaperNumbers.money(PaperNumbers.nz(account.getCash()).add(estimate)));
        } else {
            PaperPosition position = positionService.findByCode(account.getId(), order.getCode());
            if (position != null) {
                position.setAvailableQuantity(PaperNumbers.nzInt(position.getAvailableQuantity()) + order.getQuantity());
                positionMapper.updateById(position);
            }
        }
        accountMapper.updateById(account);
    }

    private BigDecimal estimateBuyCost(PaperOrder order) {
        BigDecimal amount = PaperNumbers.money(PaperNumbers.nz(order.getPrice())
                .multiply(BigDecimal.valueOf(order.getQuantity())));
        return feeCalculator.of(amount, false).buyCost(amount);
    }

    // ---------- 规则 ----------

    private BigDecimal marketFillPrice(BigDecimal current, BigDecimal prevClose, String code, String name, boolean buy) {
        if (buy && PriceLimit.atLimitUp(current, prevClose, code, name)) {
            throw new BizException(400, "该股票已涨停，无法买入");
        }
        if (!buy && PriceLimit.atLimitDown(current, prevClose, code, name)) {
            throw new BizException(400, "该股票已跌停，无法卖出");
        }
        BigDecimal slippage = current.multiply(BigDecimal.valueOf(properties.getPaper().getSlippageBps()))
                .divide(BigDecimal.valueOf(10000), 4, RoundingMode.HALF_UP);
        BigDecimal price = buy ? current.add(slippage) : current.subtract(slippage);
        BigDecimal up = PriceLimit.limitUp(prevClose, code, name);
        BigDecimal down = PriceLimit.limitDown(prevClose, code, name);
        if (up != null && price.compareTo(up) > 0) {
            price = up;
        }
        if (down != null && price.compareTo(down) < 0) {
            price = down;
        }
        return PaperNumbers.price(price);
    }

    private void checkLimitPrice(BigDecimal price, BigDecimal prevClose, String code, String name) {
        BigDecimal up = PriceLimit.limitUp(prevClose, code, name);
        BigDecimal down = PriceLimit.limitDown(prevClose, code, name);
        if (up != null && price.compareTo(up) > 0) {
            throw new BizException(400, "委托价高于涨停价 " + up + " 元");
        }
        if (down != null && price.compareTo(down) < 0) {
            throw new BizException(400, "委托价低于跌停价 " + down + " 元");
        }
    }

    private String normalizeSide(String side) {
        String value = side == null ? "" : side.trim().toUpperCase();
        if (PaperConstants.SIDE_BUY.equals(value) || PaperConstants.SIDE_SELL.equals(value)) {
            return value;
        }
        throw new BizException(400, "买卖方向只能是 BUY 或 SELL");
    }

    private String normalizeType(String type) {
        String value = type == null ? "" : type.trim().toUpperCase();
        if (value.isEmpty()) {
            return PaperConstants.TYPE_MARKET;
        }
        if (PaperConstants.TYPE_MARKET.equals(value) || PaperConstants.TYPE_LIMIT.equals(value)) {
            return value;
        }
        throw new BizException(400, "委托类型只能是 MARKET 或 LIMIT");
    }

    private long todayOrderCount(Long accountId) {
        return orderMapper.selectCount(Wrappers.<PaperOrder>lambdaQuery()
                .eq(PaperOrder::getAccountId, accountId)
                .ge(PaperOrder::getCreateTime, calendar.today().atStartOfDay()));
    }

    // ---------- VO ----------

    public static PaperOrderVO toVO(PaperOrder order) {
        return PaperOrderVO.builder()
                .id(order.getId())
                .code(order.getCode())
                .name(order.getName())
                .side(order.getSide())
                .type(order.getType())
                .price(order.getPrice())
                .quantity(order.getQuantity())
                .filledQuantity(order.getFilledQuantity())
                .avgPrice(order.getAvgPrice())
                .amount(PaperNumbers.nz(order.getAmount()))
                .status(order.getStatus())
                .rejectReason(order.getRejectReason())
                .source(order.getSource())
                .createTime(order.getCreateTime())
                .filledTime(order.getFilledTime())
                .build();
    }

    public static PaperTradeVO toTradeVO(PaperTrade trade) {
        return PaperTradeVO.builder()
                .id(trade.getId())
                .code(trade.getCode())
                .name(trade.getName())
                .side(trade.getSide())
                .price(trade.getPrice())
                .quantity(trade.getQuantity())
                .amount(PaperNumbers.money(trade.getAmount()))
                .commission(PaperNumbers.money(trade.getCommission()))
                .stampTax(PaperNumbers.money(trade.getStampTax()))
                .transferFee(PaperNumbers.money(trade.getTransferFee()))
                .totalFee(PaperNumbers.money(trade.getTotalFee()))
                .tradeDate(trade.getTradeDate())
                .tradeTime(trade.getTradeTime())
                .build();
    }
}
