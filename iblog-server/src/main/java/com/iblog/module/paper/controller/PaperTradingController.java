package com.iblog.module.paper.controller;

import com.iblog.common.exception.BizException;
import com.iblog.common.result.R;
import com.iblog.config.IblogProperties;
import com.iblog.module.paper.dto.PaperOrderRequest;
import com.iblog.module.paper.dto.PaperStrategyUpdateRequest;
import com.iblog.module.paper.entity.PaperAccount;
import com.iblog.module.paper.entity.PaperPosition;
import com.iblog.module.paper.entity.PaperSignal;
import com.iblog.module.paper.entity.PaperStrategy;
import com.iblog.module.paper.service.PaperAccountService;
import com.iblog.module.paper.service.PaperOrderService;
import com.iblog.module.paper.service.PaperPositionService;
import com.iblog.module.paper.service.PaperSelectionService;
import com.iblog.module.paper.service.PaperStrategyService;
import com.iblog.module.paper.support.TradeCalendar;
import com.iblog.module.paper.vo.PaperAccountVO;
import com.iblog.module.paper.vo.PaperNavVO;
import com.iblog.module.paper.vo.PaperOrderVO;
import com.iblog.module.paper.vo.PaperPositionVO;
import com.iblog.security.UserContext;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模拟盘交易接口。
 *
 * <p>挂在 {@code /api/stock/paper} 下，复用行情模块已有的登录拦截
 * （后台 header 令牌或官网 Cookie 都认），不需要再开一套鉴权。</p>
 */
@RestController
@RequestMapping("/api/stock/paper")
@RequiredArgsConstructor
public class PaperTradingController {

    private final PaperAccountService accountService;
    private final PaperPositionService positionService;
    private final PaperOrderService orderService;
    private final PaperStrategyService strategyService;
    private final PaperSelectionService selectionService;
    private final IblogProperties properties;
    private final TradeCalendar calendar;

    // ---------- P1：账户/持仓/委托/成交 ----------

    @GetMapping("/account")
    public R<PaperAccountVO> account() {
        return R.ok(refreshAccount());
    }

    @GetMapping("/positions")
    public R<List<PaperPositionVO>> positions() {
        PaperAccount account = currentAccount();
        List<PaperPosition> positions = positionService.refreshQuotes(account.getId());
        accountService.recompute(account, positions);
        return R.ok(positionService.toVOList(positions));
    }

    @GetMapping("/orders")
    public R<Map<String, Object>> orders(@RequestParam(required = false) String status,
                                         @RequestParam(defaultValue = "1") int page,
                                         @RequestParam(defaultValue = "20") int size) {
        return R.ok(orderService.orders(currentAccount().getId(), status, page, size));
    }

    @PostMapping("/orders")
    public R<PaperOrderVO> place(@Valid @RequestBody PaperOrderRequest request) {
        return R.ok(orderService.place(currentUserId(), request));
    }

    @DeleteMapping("/orders/{id}")
    public R<PaperOrderVO> cancel(@PathVariable Long id) {
        return R.ok(orderService.cancel(currentUserId(), id));
    }

    @GetMapping("/trades")
    public R<Map<String, Object>> trades(@RequestParam(defaultValue = "1") int page,
                                         @RequestParam(defaultValue = "20") int size) {
        return R.ok(orderService.trades(currentAccount().getId(), page, size));
    }

    @GetMapping("/nav")
    public R<List<PaperNavVO>> nav(@RequestParam(defaultValue = "90") int days) {
        return R.ok(accountService.navList(currentAccount().getId(), days));
    }

    @PostMapping("/account/reset")
    public R<PaperAccountVO> reset() {
        Long userId = currentUserId();
        accountService.reset(userId);
        return R.ok(refreshAccount());
    }

    // ---------- P2：策略/选股/引擎 ----------

    @GetMapping("/strategies")
    public R<List<PaperStrategy>> strategies() {
        return R.ok(strategyService.list(currentAccount().getId()));
    }

    @PutMapping("/strategies/{id}")
    public R<PaperStrategy> updateStrategy(@PathVariable Long id, @RequestBody PaperStrategyUpdateRequest request) {
        return R.ok(strategyService.update(currentAccount().getId(), id, request));
    }

    @GetMapping("/selections")
    public R<List<PaperSignal>> selections(@RequestParam(required = false) String date) {
        PaperAccount account = currentAccount();
        return R.ok(date == null || date.isBlank()
                ? selectionService.recentCandidates(account.getId())
                : selectionService.recent(account.getId(), date));
    }

    /** 立即重新选股（页面上的「重新选股」按钮；一轮约几秒，期间会请求上游行情）。 */
    @PostMapping("/selections/refresh")
    public R<List<PaperSignal>> refreshSelections() {
        return R.ok(selectionService.refresh(currentAccount().getId()));
    }

    @GetMapping("/signals")
    public R<List<PaperSignal>> signals(@RequestParam(defaultValue = "50") int limit) {
        return R.ok(selectionService.history(currentAccount().getId(), limit));
    }

    @GetMapping("/engine/status")
    public R<Map<String, Object>> engineStatus() {
        PaperAccount account = refreshAccountEntity();
        PaperStrategy strategy = strategyService.ensureDefault(account.getId());
        PaperAccountVO vo = accountService.toVO(account);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("enabled", properties.getPaper().isEnabled());
        data.put("autoTrade", account.getAutoTrade() != null && account.getAutoTrade() == 1);
        data.put("strategyEnabled", strategy.getEnabled() != null && strategy.getEnabled() == 1);
        data.put("trading", calendar.isTradingTimeNow());
        data.put("forceTradingHours", properties.getPaper().isForceTradingHours());
        data.put("candidates", selectionService.recentCandidates(account.getId()).size());
        data.put("todayProfit", vo.getTodayProfit());
        data.put("todayProfitPct", vo.getTodayProfitPct());
        data.put("lastRunTime", strategy.getLastRunTime());
        return R.ok(data);
    }

    /** 打开/关闭自动交易（服务端总开关关闭时不允许打开）。 */
    @PostMapping("/account/auto-trade")
    public R<PaperAccountVO> autoTrade(@RequestBody Map<String, Boolean> body) {
        Long userId = currentUserId();
        Boolean enabled = body.get("enabled");
        if (enabled == null) {
            throw new BizException(400, "缺少 enabled 参数");
        }
        if (enabled && !properties.getPaper().isEnabled()) {
            throw new BizException(400, "服务端未开启模拟盘自动交易（iblog.paper.enabled=false）");
        }
        accountService.setAutoTrade(userId, enabled);
        return R.ok(refreshAccount());
    }

    // ---------- 内部 ----------

    private PaperAccountVO refreshAccount() {
        return accountService.toVO(refreshAccountEntity());
    }

    private PaperAccount refreshAccountEntity() {
        PaperAccount account = currentAccount();
        List<PaperPosition> positions = positionService.refreshQuotes(account.getId());
        return accountService.recompute(account, positions);
    }

    private PaperAccount currentAccount() {
        return accountService.ensure(currentUserId());
    }

    private Long currentUserId() {
        Long userId = UserContext.get();
        if (userId == null) {
            throw new BizException(401, "未登录，请先登录后台");
        }
        return userId;
    }
}
