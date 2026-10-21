package com.iblog.module.paper.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.iblog.config.IblogProperties;
import com.iblog.module.paper.entity.PaperAccount;
import com.iblog.module.paper.entity.PaperNavDaily;
import com.iblog.module.paper.entity.PaperOrder;
import com.iblog.module.paper.entity.PaperPosition;
import com.iblog.module.paper.entity.PaperSignal;
import com.iblog.module.paper.entity.PaperTrade;
import com.iblog.module.paper.mapper.PaperAccountMapper;
import com.iblog.module.paper.mapper.PaperNavDailyMapper;
import com.iblog.module.paper.mapper.PaperOrderMapper;
import com.iblog.module.paper.mapper.PaperPositionMapper;
import com.iblog.module.paper.mapper.PaperSignalMapper;
import com.iblog.module.paper.mapper.PaperTradeMapper;
import com.iblog.module.paper.support.PaperNumbers;
import com.iblog.module.paper.support.TradeCalendar;
import com.iblog.module.paper.vo.PaperAccountVO;
import com.iblog.module.paper.vo.PaperNavVO;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 模拟盘账户：开户、重算、净值快照、重置。
 */
@Service
@RequiredArgsConstructor
public class PaperAccountService {

    private final PaperAccountMapper accountMapper;
    private final PaperPositionMapper positionMapper;
    private final PaperNavDailyMapper navMapper;
    private final PaperOrderMapper orderMapper;
    private final PaperTradeMapper tradeMapper;
    private final PaperSignalMapper signalMapper;
    private final IblogProperties properties;
    private final TradeCalendar calendar;

    /** 取账户，不存在则按配置的初始资金开户。 */
    public PaperAccount ensure(Long userId) {
        PaperAccount account = find(userId);
        if (account != null) {
            return account;
        }
        PaperAccount created = new PaperAccount();
        created.setUserId(userId);
        created.setName("模拟盘");
        BigDecimal initial = PaperNumbers.money(properties.getPaper().getInitialCash());
        created.setInitialCash(initial);
        created.setCash(initial);
        created.setFrozenCash(PaperNumbers.money(BigDecimal.ZERO));
        created.setMarketValue(PaperNumbers.money(BigDecimal.ZERO));
        created.setTotalAsset(initial);
        created.setTotalProfit(PaperNumbers.money(BigDecimal.ZERO));
        created.setTotalProfitPct(BigDecimal.ZERO.setScale(2));
        created.setAutoTrade(0);
        try {
            accountMapper.insert(created);
            return created;
        } catch (DuplicateKeyException ex) {
            // 并发开户：另一个请求已经插入了，直接读回来
            return find(userId);
        }
    }

    public PaperAccount find(Long userId) {
        return accountMapper.selectOne(Wrappers.<PaperAccount>lambdaQuery()
                .eq(PaperAccount::getUserId, userId)
                .last("limit 1"));
    }

    /** 打开/关闭自动交易。真正的开关校验在 Controller（服务端总开关未开时不允许打开）。 */
    public PaperAccount setAutoTrade(Long userId, boolean enabled) {
        PaperAccount account = ensure(userId);
        account.setAutoTrade(enabled ? 1 : 0);
        accountMapper.updateById(account);
        return account;
    }

    /** 用持仓市值重算账户，并落库。 */
    public PaperAccount recompute(PaperAccount account, List<PaperPosition> positions) {
        BigDecimal marketValue = PaperNumbers.money(positions.stream()
                .map(item -> PaperNumbers.nz(item.getMarketValue()))
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        BigDecimal totalAsset = PaperNumbers.money(PaperNumbers.nz(account.getCash())
                .add(PaperNumbers.nz(account.getFrozenCash()))
                .add(marketValue));
        BigDecimal initial = PaperNumbers.nz(account.getInitialCash());
        BigDecimal totalProfit = PaperNumbers.money(totalAsset.subtract(initial));

        account.setMarketValue(marketValue);
        account.setTotalAsset(totalAsset);
        account.setTotalProfit(totalProfit);
        account.setTotalProfitPct(PaperNumbers.pct(totalProfit, initial));
        accountMapper.updateById(account);
        return account;
    }

    /** 今日基准资产：上一交易日收盘总资产；没有快照时退回初始资金。 */
    public BigDecimal todayBase(PaperAccount account) {
        String today = calendar.todayText();
        PaperNavDaily last = navMapper.selectOne(Wrappers.<PaperNavDaily>lambdaQuery()
                .eq(PaperNavDaily::getAccountId, account.getId())
                .lt(PaperNavDaily::getTradeDate, today)
                .orderByDesc(PaperNavDaily::getTradeDate)
                .last("limit 1"));
        return last == null ? PaperNumbers.nz(account.getInitialCash()) : PaperNumbers.nz(last.getTotalAsset());
    }

    public PaperAccountVO toVO(PaperAccount account) {
        BigDecimal base = todayBase(account);
        BigDecimal todayProfit = PaperNumbers.money(PaperNumbers.nz(account.getTotalAsset()).subtract(base));
        long positionCount = positionMapper.selectCount(Wrappers.<PaperPosition>lambdaQuery()
                .eq(PaperPosition::getAccountId, account.getId())
                .gt(PaperPosition::getQuantity, 0));
        return PaperAccountVO.builder()
                .accountId(account.getId())
                .name(account.getName())
                .initialCash(PaperNumbers.money(account.getInitialCash()))
                .cash(PaperNumbers.money(account.getCash()))
                .marketValue(PaperNumbers.money(account.getMarketValue()))
                .totalAsset(PaperNumbers.money(account.getTotalAsset()))
                .totalProfit(PaperNumbers.money(account.getTotalProfit()))
                .totalProfitPct(PaperNumbers.nz(account.getTotalProfitPct()))
                .todayProfit(todayProfit)
                .todayProfitPct(PaperNumbers.pct(todayProfit, base))
                .positionCount((int) positionCount)
                .autoTrade(account.getAutoTrade() == null ? 0 : account.getAutoTrade())
                .tradeDate(latestNavDate(account.getId()))
                .build();
    }

    /** 写入/更新当天的净值快照（重复结算只更新）。 */
    public PaperNavDaily snapshotNav(PaperAccount account) {
        String date = calendar.todayText();
        PaperNavDaily prev = navMapper.selectOne(Wrappers.<PaperNavDaily>lambdaQuery()
                .eq(PaperNavDaily::getAccountId, account.getId())
                .lt(PaperNavDaily::getTradeDate, date)
                .orderByDesc(PaperNavDaily::getTradeDate)
                .last("limit 1"));
        BigDecimal prevAsset = prev == null ? PaperNumbers.nz(account.getInitialCash()) : PaperNumbers.nz(prev.getTotalAsset());
        BigDecimal dayPnl = PaperNumbers.money(PaperNumbers.nz(account.getTotalAsset()).subtract(prevAsset));

        PaperNavDaily nav = navMapper.selectOne(Wrappers.<PaperNavDaily>lambdaQuery()
                .eq(PaperNavDaily::getAccountId, account.getId())
                .eq(PaperNavDaily::getTradeDate, date)
                .last("limit 1"));
        boolean insert = nav == null;
        if (insert) {
            nav = new PaperNavDaily();
            nav.setAccountId(account.getId());
            nav.setTradeDate(date);
        }
        nav.setCash(PaperNumbers.money(account.getCash()));
        nav.setMarketValue(PaperNumbers.money(account.getMarketValue()));
        nav.setTotalAsset(PaperNumbers.money(account.getTotalAsset()));
        nav.setDayPnl(dayPnl);
        nav.setDayPnlPct(PaperNumbers.pct(dayPnl, prevAsset));
        nav.setTotalPnl(PaperNumbers.money(account.getTotalProfit()));
        nav.setTotalPnlPct(PaperNumbers.nz(account.getTotalProfitPct()));
        if (insert) {
            navMapper.insert(nav);
        } else {
            navMapper.updateById(nav);
        }
        return nav;
    }

    public List<PaperNavVO> navList(Long accountId, int days) {
        int limit = Math.min(Math.max(days, 1), 200);
        List<PaperNavDaily> list = navMapper.selectList(Wrappers.<PaperNavDaily>lambdaQuery()
                .eq(PaperNavDaily::getAccountId, accountId)
                .orderByDesc(PaperNavDaily::getTradeDate)
                .last("limit " + limit));
        List<PaperNavDaily> ordered = new ArrayList<>(list);
        Collections.reverse(ordered);
        return ordered.stream()
                .map(item -> PaperNavVO.builder()
                        .date(item.getTradeDate())
                        .cash(PaperNumbers.money(item.getCash()))
                        .marketValue(PaperNumbers.money(item.getMarketValue()))
                        .totalAsset(PaperNumbers.money(item.getTotalAsset()))
                        .dayPnl(PaperNumbers.money(item.getDayPnl()))
                        .dayPnlPct(PaperNumbers.nz(item.getDayPnlPct()))
                        .totalPnl(PaperNumbers.money(item.getTotalPnl()))
                        .totalPnlPct(PaperNumbers.nz(item.getTotalPnlPct()))
                        .build())
                .toList();
    }

    private String latestNavDate(Long accountId) {
        PaperNavDaily last = navMapper.selectOne(Wrappers.<PaperNavDaily>lambdaQuery()
                .eq(PaperNavDaily::getAccountId, accountId)
                .orderByDesc(PaperNavDaily::getTradeDate)
                .last("limit 1"));
        return last == null ? calendar.todayText() : last.getTradeDate();
    }

    /** 重置账户：清空持仓/委托/成交/净值/信号，现金回到初始资金。 */
    @Transactional
    public PaperAccount reset(Long userId) {
        PaperAccount account = ensure(userId);
        Long accountId = account.getId();
        positionMapper.delete(Wrappers.<PaperPosition>lambdaQuery().eq(PaperPosition::getAccountId, accountId));
        orderMapper.delete(Wrappers.<PaperOrder>lambdaQuery().eq(PaperOrder::getAccountId, accountId));
        tradeMapper.delete(Wrappers.<PaperTrade>lambdaQuery().eq(PaperTrade::getAccountId, accountId));
        navMapper.delete(Wrappers.<PaperNavDaily>lambdaQuery().eq(PaperNavDaily::getAccountId, accountId));
        signalMapper.delete(Wrappers.<PaperSignal>lambdaQuery().eq(PaperSignal::getAccountId, accountId));

        BigDecimal initial = PaperNumbers.money(properties.getPaper().getInitialCash());
        account.setInitialCash(initial);
        account.setCash(initial);
        account.setFrozenCash(PaperNumbers.money(BigDecimal.ZERO));
        account.setMarketValue(PaperNumbers.money(BigDecimal.ZERO));
        account.setTotalAsset(initial);
        account.setTotalProfit(PaperNumbers.money(BigDecimal.ZERO));
        account.setTotalProfitPct(BigDecimal.ZERO.setScale(2));
        account.setAutoTrade(0);
        accountMapper.updateById(account);
        return account;
    }
}
