package com.iblog.module.paper.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.iblog.common.exception.BizException;
import com.iblog.config.IblogProperties;
import com.iblog.module.paper.entity.PaperAccount;
import com.iblog.module.paper.entity.PaperNavDaily;
import com.iblog.module.paper.entity.PaperOrder;
import com.iblog.module.paper.entity.PaperPosition;
import com.iblog.module.paper.entity.PaperSignal;
import com.iblog.module.paper.entity.PaperStrategy;
import com.iblog.module.paper.entity.PaperTrade;
import com.iblog.module.paper.mapper.PaperAccountMapper;
import com.iblog.module.paper.mapper.PaperNavDailyMapper;
import com.iblog.module.paper.mapper.PaperOrderMapper;
import com.iblog.module.paper.mapper.PaperPositionMapper;
import com.iblog.module.paper.mapper.PaperSignalMapper;
import com.iblog.module.paper.mapper.PaperStrategyMapper;
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
 * 模拟盘账户：开户、多账户管理、重算、净值快照、重置。
 *
 * <p>账户数量不设上限（想开多少开多少，不再有 max-accounts 配置），
 * 每个账户独立资金/持仓/策略/信号，用于对比不同选股与交易规则。
 * 「主账户」= sort_no 最小的那个，老接口不传 accountId 时落到它上面。</p>
 */
@Service
@RequiredArgsConstructor
public class PaperAccountService {

    /** 初始资金允许区间（元）：下限 1000，上限 1 亿 */
    private static final BigDecimal MIN_INITIAL_CASH = new BigDecimal("1000");
    private static final BigDecimal MAX_INITIAL_CASH = new BigDecimal("100000000");
    private static final int MAX_NAME_LENGTH = 32;

    private final PaperAccountMapper accountMapper;
    private final PaperPositionMapper positionMapper;
    private final PaperNavDailyMapper navMapper;
    private final PaperOrderMapper orderMapper;
    private final PaperTradeMapper tradeMapper;
    private final PaperSignalMapper signalMapper;
    private final PaperStrategyMapper strategyMapper;
    private final IblogProperties properties;
    private final TradeCalendar calendar;

    // ---------- 账户读取 ----------

    /** 取主账户，不存在则按配置的初始资金开第一个户。 */
    public PaperAccount ensure(Long userId) {
        List<PaperAccount> accounts = list(userId);
        if (!accounts.isEmpty()) {
            return accounts.get(0);
        }
        return open(userId, "模拟盘", properties.getPaper().getInitialCash(), 1);
    }

    /** 用户全部模拟盘，按 sort_no、id 升序（第一个是主账户）。 */
    public List<PaperAccount> list(Long userId) {
        return accountMapper.selectList(Wrappers.<PaperAccount>lambdaQuery()
                .eq(PaperAccount::getUserId, userId)
                .orderByAsc(PaperAccount::getSortNo)
                .orderByAsc(PaperAccount::getId));
    }

    /** 取指定账户并校验归属；accountId 为空时返回主账户。 */
    public PaperAccount require(Long userId, Long accountId) {
        if (accountId == null) {
            return ensure(userId);
        }
        PaperAccount account = accountMapper.selectById(accountId);
        if (account == null || !userId.equals(account.getUserId())) {
            throw new BizException(404, "模拟盘账户不存在");
        }
        return account;
    }

    public PaperAccount getById(Long accountId) {
        return accountId == null ? null : accountMapper.selectById(accountId);
    }

    // ---------- 账户管理 ----------

    /** 新建模拟盘：名称留空自动编号，初始资金为空用服务端默认值。账户数量不设上限。 */
    @Transactional
    public PaperAccount create(Long userId, String name, BigDecimal initialCash) {
        List<PaperAccount> accounts = list(userId);
        String defaultName = "模拟盘 " + (accounts.size() + 1);
        String finalName = normalizeName(name, defaultName);
        int sortNo = accounts.stream()
                .mapToInt(item -> item.getSortNo() == null ? 0 : item.getSortNo())
                .max()
                .orElse(0) + 1;
        BigDecimal initial = initialCash == null ? properties.getPaper().getInitialCash() : initialCash;
        return open(userId, finalName, initial, sortNo);
    }

    /** 改名：只改展示名，不影响资金/持仓。 */
    @Transactional
    public PaperAccount rename(Long userId, Long accountId, String name) {
        PaperAccount account = require(userId, accountId);
        account.setName(normalizeName(name, "模拟盘"));
        accountMapper.updateById(account);
        return account;
    }

    /** 删除账户：至少保留一个；该账户的持仓/委托/成交/净值/信号/策略一起清掉。 */
    @Transactional
    public void delete(Long userId, Long accountId) {
        List<PaperAccount> accounts = list(userId);
        if (accounts.size() <= 1) {
            throw new BizException(400, "至少保留一个模拟盘");
        }
        PaperAccount account = require(userId, accountId);
        Long id = account.getId();
        positionMapper.delete(Wrappers.<PaperPosition>lambdaQuery().eq(PaperPosition::getAccountId, id));
        orderMapper.delete(Wrappers.<PaperOrder>lambdaQuery().eq(PaperOrder::getAccountId, id));
        tradeMapper.delete(Wrappers.<PaperTrade>lambdaQuery().eq(PaperTrade::getAccountId, id));
        navMapper.delete(Wrappers.<PaperNavDaily>lambdaQuery().eq(PaperNavDaily::getAccountId, id));
        signalMapper.delete(Wrappers.<PaperSignal>lambdaQuery().eq(PaperSignal::getAccountId, id));
        strategyMapper.delete(Wrappers.<PaperStrategy>lambdaQuery().eq(PaperStrategy::getAccountId, id));
        accountMapper.deleteById(id);
    }

    /** 重置账户：清空流水，现金回到该账户的初始资金，自动交易关闭。 */
    @Transactional
    public PaperAccount reset(Long userId, Long accountId) {
        PaperAccount account = require(userId, accountId);
        Long id = account.getId();
        positionMapper.delete(Wrappers.<PaperPosition>lambdaQuery().eq(PaperPosition::getAccountId, id));
        orderMapper.delete(Wrappers.<PaperOrder>lambdaQuery().eq(PaperOrder::getAccountId, id));
        tradeMapper.delete(Wrappers.<PaperTrade>lambdaQuery().eq(PaperTrade::getAccountId, id));
        navMapper.delete(Wrappers.<PaperNavDaily>lambdaQuery().eq(PaperNavDaily::getAccountId, id));
        signalMapper.delete(Wrappers.<PaperSignal>lambdaQuery().eq(PaperSignal::getAccountId, id));

        BigDecimal initial = PaperNumbers.money(PaperNumbers.nz(account.getInitialCash()));
        if (initial.signum() <= 0) {
            initial = PaperNumbers.money(properties.getPaper().getInitialCash());
        }
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

    /** 打开/关闭自动交易。真正的开关校验在 Controller（服务端总开关未开时不允许打开）。 */
    public PaperAccount setAutoTrade(Long userId, Long accountId, boolean enabled) {
        PaperAccount account = require(userId, accountId);
        account.setAutoTrade(enabled ? 1 : 0);
        accountMapper.updateById(account);
        return account;
    }

    // ---------- 估值/净值 ----------

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
                .sortNo(account.getSortNo() == null ? 0 : account.getSortNo())
                .primary(isPrimary(account))
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

    /** 是否主账户（sort_no 最小的那个）。 */
    public boolean isPrimary(PaperAccount account) {
        if (account == null || account.getUserId() == null) {
            return false;
        }
        List<PaperAccount> accounts = list(account.getUserId());
        return !accounts.isEmpty() && accounts.get(0).getId().equals(account.getId());
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

    // ---------- 内部 ----------

    private PaperAccount open(Long userId, String name, BigDecimal initialCash, int sortNo) {
        BigDecimal initial = PaperNumbers.money(initialCash == null ? properties.getPaper().getInitialCash() : initialCash);
        if (initial.compareTo(MIN_INITIAL_CASH) < 0) {
            throw new BizException(400, "初始资金不能低于 " + MIN_INITIAL_CASH.stripTrailingZeros().toPlainString() + " 元");
        }
        if (initial.compareTo(MAX_INITIAL_CASH) > 0) {
            throw new BizException(400, "初始资金不能超过 1 亿元");
        }
        PaperAccount created = new PaperAccount();
        created.setUserId(userId);
        created.setName(normalizeName(name, "模拟盘"));
        created.setSortNo(sortNo);
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
            // 旧库未跑 06 迁移时仍可能有 uk_user；读回已有账户而不是报错
            List<PaperAccount> accounts = list(userId);
            return accounts.isEmpty() ? created : accounts.get(0);
        }
    }

    private String normalizeName(String name, String fallback) {
        String value = name == null ? "" : name.trim();
        if (value.isEmpty()) {
            value = fallback;
        }
        if (value.length() > MAX_NAME_LENGTH) {
            throw new BizException(400, "账户名称最多 " + MAX_NAME_LENGTH + " 个字");
        }
        return value;
    }

    private String latestNavDate(Long accountId) {
        PaperNavDaily last = navMapper.selectOne(Wrappers.<PaperNavDaily>lambdaQuery()
                .eq(PaperNavDaily::getAccountId, accountId)
                .orderByDesc(PaperNavDaily::getTradeDate)
                .last("limit 1"));
        return last == null ? calendar.todayText() : last.getTradeDate();
    }
}
