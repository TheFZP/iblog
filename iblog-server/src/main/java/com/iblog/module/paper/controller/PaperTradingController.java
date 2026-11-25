package com.iblog.module.paper.controller;

import com.iblog.common.exception.BizException;
import com.iblog.common.result.R;
import com.iblog.config.IblogProperties;
import com.iblog.module.paper.dto.PaperAccountCreateRequest;
import com.iblog.module.paper.dto.PaperAccountRenameRequest;
import com.iblog.module.paper.dto.PaperOrderRequest;
import com.iblog.module.paper.dto.PaperRuleSaveRequest;
import com.iblog.module.paper.dto.PaperStrategyUpdateRequest;
import com.iblog.module.paper.entity.PaperAccount;
import com.iblog.module.paper.entity.PaperPosition;
import com.iblog.module.paper.entity.PaperSignal;
import com.iblog.module.paper.entity.PaperStrategy;
import com.iblog.module.paper.service.PaperAccountService;
import com.iblog.module.paper.service.PaperOrderService;
import com.iblog.module.paper.service.PaperPositionService;
import com.iblog.module.paper.service.PaperRuleService;
import com.iblog.module.paper.service.PaperSelectionService;
import com.iblog.module.paper.service.PaperStrategyService;
import com.iblog.module.paper.support.TradeCalendar;
import com.iblog.module.paper.vo.PaperAccountVO;
import com.iblog.module.paper.vo.PaperNavVO;
import com.iblog.module.paper.vo.PaperOrderVO;
import com.iblog.module.paper.vo.PaperPositionVO;
import com.iblog.module.paper.vo.PaperRuleVO;
import com.iblog.security.UserContext;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
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
 *
 * <p>多账户：一个用户可开任意多个模拟盘，每个账户独立资金/持仓/策略。
 * 写操作和查询都可以带 {@code ?accountId=}，不传则落到主账户（sort_no 最小）。</p>
 */
@RestController
@RequestMapping("/api/stock/paper")
@RequiredArgsConstructor
public class PaperTradingController {

    private final PaperAccountService accountService;
    private final PaperPositionService positionService;
    private final PaperOrderService orderService;
    private final PaperStrategyService strategyService;
    private final PaperRuleService ruleService;
    private final PaperSelectionService selectionService;
    private final IblogProperties properties;
    private final TradeCalendar calendar;

    // ---------- 账户管理 ----------

    @GetMapping("/accounts")
    public R<List<PaperAccountVO>> accounts() {
        return R.ok(accountService.list(currentUserId()).stream()
                .map(accountService::toVO)
                .toList());
    }

    @PostMapping("/accounts")
    public R<PaperAccountVO> createAccount(@RequestBody PaperAccountCreateRequest request) {
        PaperAccount created = accountService.create(currentUserId(), request.getName(), request.getInitialCash());
        strategyService.ensureDefault(created.getId());
        return R.ok(accountService.toVO(created));
    }

    @PatchMapping("/accounts/{id}")
    public R<PaperAccountVO> renameAccount(@PathVariable Long id,
                                           @Valid @RequestBody PaperAccountRenameRequest request) {
        return R.ok(accountService.toVO(accountService.rename(currentUserId(), id, request.getName())));
    }

    @DeleteMapping("/accounts/{id}")
    public R<Void> deleteAccount(@PathVariable Long id) {
        accountService.delete(currentUserId(), id);
        return R.ok();
    }

    // ---------- 账户/持仓/委托/成交 ----------

    @GetMapping("/account")
    public R<PaperAccountVO> account(@RequestParam(required = false) Long accountId) {
        return R.ok(refreshAccount(requireAccount(accountId)));
    }

    @GetMapping("/positions")
    public R<List<PaperPositionVO>> positions(@RequestParam(required = false) Long accountId) {
        PaperAccount account = requireAccount(accountId);
        List<PaperPosition> positions = positionService.refreshQuotes(account.getId());
        accountService.recompute(account, positions);
        List<PaperPositionVO> list = positionService.toVOList(positions);
        // 附上最近一次重选股时按当前策略算的持仓分（纯展示，未评分则为 null）
        Map<String, PaperSelectionService.HeldScore> scores = selectionService.heldScores(account.getId());
        for (PaperPositionVO vo : list) {
            PaperSelectionService.HeldScore score = scores.get(vo.getCode());
            if (score != null) {
                vo.setScore(score.score());
                vo.setScoreReason(score.reason());
            }
        }
        return R.ok(list);
    }

    @GetMapping("/orders")
    public R<Map<String, Object>> orders(@RequestParam(required = false) Long accountId,
                                         @RequestParam(required = false) String status,
                                         @RequestParam(defaultValue = "1") int page,
                                         @RequestParam(defaultValue = "20") int size) {
        return R.ok(orderService.orders(requireAccount(accountId).getId(), status, page, size));
    }

    @PostMapping("/orders")
    public R<PaperOrderVO> place(@RequestParam(required = false) Long accountId,
                                 @Valid @RequestBody PaperOrderRequest request) {
        return R.ok(orderService.place(requireAccount(accountId), request));
    }

    @DeleteMapping("/orders/{id}")
    public R<PaperOrderVO> cancel(@PathVariable Long id, @RequestParam(required = false) Long accountId) {
        return R.ok(orderService.cancel(requireAccount(accountId), id));
    }

    @GetMapping("/trades")
    public R<Map<String, Object>> trades(@RequestParam(required = false) Long accountId,
                                         @RequestParam(defaultValue = "1") int page,
                                         @RequestParam(defaultValue = "20") int size) {
        return R.ok(orderService.trades(requireAccount(accountId).getId(), page, size));
    }

    @GetMapping("/nav")
    public R<List<PaperNavVO>> nav(@RequestParam(required = false) Long accountId,
                                   @RequestParam(defaultValue = "90") int days) {
        return R.ok(accountService.navList(requireAccount(accountId).getId(), days));
    }

    @PostMapping("/account/reset")
    public R<PaperAccountVO> reset(@RequestParam(required = false) Long accountId) {
        accountService.reset(currentUserId(), accountId);
        return R.ok(refreshAccount(requireAccount(accountId)));
    }

    // ---------- 策略/选股/引擎 ----------

    @GetMapping("/strategies")
    public R<List<PaperStrategy>> strategies(@RequestParam(required = false) Long accountId) {
        return R.ok(strategyService.list(requireAccount(accountId).getId()));
    }

    /** 按当前账户规则给任意一只股票打分（手动评分，不写候选、不下单）。 */
    @GetMapping("/score")
    public R<PaperSelectionService.StockScore> score(@RequestParam(required = false) Long accountId,
                                                     @RequestParam String code,
                                                     @RequestParam(required = false) String name) {
        PaperSelectionService.StockScore result =
                selectionService.scoreStock(requireAccount(accountId).getId(), code, name);
        if (result == null) {
            return R.fail(400, "该股票代码不受支持，或日 K 数据不足（少于 30 根），暂时无法评分");
        }
        return R.ok(result);
    }

    @PutMapping("/strategies/{id}")
    public R<PaperStrategy> updateStrategy(@PathVariable Long id,
                                           @RequestParam(required = false) Long accountId,
                                           @RequestBody PaperStrategyUpdateRequest request) {
        return R.ok(strategyService.update(requireAccount(accountId).getId(), id, request));
    }

    // ---------- 规则模板（每用户多套命名规则，可一键应用到账户） ----------

    @GetMapping("/rules")
    public R<List<PaperRuleVO>> rules(@RequestParam(required = false) Long accountId) {
        Long userId = currentUserId();
        Long resolved = accountService.require(userId, accountId).getId();
        return R.ok(ruleService.list(userId, resolved));
    }

    @PostMapping("/rules")
    public R<PaperRuleVO> createRule(@RequestBody PaperRuleSaveRequest request) {
        return R.ok(ruleService.create(currentUserId(), request));
    }

    @PutMapping("/rules/{id}")
    public R<PaperRuleVO> updateRule(@PathVariable Long id, @RequestBody PaperRuleSaveRequest request) {
        return R.ok(ruleService.update(currentUserId(), id, request));
    }

    @DeleteMapping("/rules/{id}")
    public R<Void> deleteRule(@PathVariable Long id) {
        ruleService.delete(currentUserId(), id);
        return R.ok();
    }

    /** 把模板参数复制进账户当前策略，返回更新后的策略。 */
    @PostMapping("/accounts/{id}/apply-rule/{ruleId}")
    public R<PaperStrategy> applyRule(@PathVariable Long id, @PathVariable Long ruleId) {
        return R.ok(ruleService.apply(currentUserId(), id, ruleId));
    }

    @GetMapping("/selections")
    public R<List<PaperSignal>> selections(@RequestParam(required = false) Long accountId,                                           @RequestParam(required = false) String date) {
        PaperAccount account = requireAccount(accountId);
        return R.ok(date == null || date.isBlank()
                ? selectionService.recentCandidates(account.getId())
                : selectionService.recent(account.getId(), date));
    }

    /** 立即重新选股（页面上的「重新选股」按钮；一轮约几秒，期间会请求上游行情）。 */
    @PostMapping("/selections/refresh")
    public R<List<PaperSignal>> refreshSelections(@RequestParam(required = false) Long accountId) {
        return R.ok(selectionService.refresh(requireAccount(accountId).getId()));
    }

    @GetMapping("/signals")
    public R<List<PaperSignal>> signals(@RequestParam(required = false) Long accountId,
                                        @RequestParam(defaultValue = "50") int limit) {
        return R.ok(selectionService.history(requireAccount(accountId).getId(), limit));
    }

    @GetMapping("/engine/status")
    public R<Map<String, Object>> engineStatus(@RequestParam(required = false) Long accountId) {
        PaperAccount account = refreshAccountEntity(requireAccount(accountId));
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
    public R<PaperAccountVO> autoTrade(@RequestParam(required = false) Long accountId,
                                       @RequestBody Map<String, Boolean> body) {
        Boolean enabled = body.get("enabled");
        if (enabled == null) {
            throw new BizException(400, "缺少 enabled 参数");
        }
        if (enabled && !properties.getPaper().isEnabled()) {
            throw new BizException(400, "服务端未开启模拟盘自动交易（iblog.paper.enabled=false）");
        }
        PaperAccount account = accountService.setAutoTrade(currentUserId(), accountId, enabled);
        return R.ok(refreshAccount(account));
    }

    // ---------- 内部 ----------

    private PaperAccountVO refreshAccount(PaperAccount account) {
        return accountService.toVO(refreshAccountEntity(account));
    }

    private PaperAccount refreshAccountEntity(PaperAccount account) {
        List<PaperPosition> positions = positionService.refreshQuotes(account.getId());
        return accountService.recompute(account, positions);
    }

    private PaperAccount requireAccount(Long accountId) {
        return accountService.require(currentUserId(), accountId);
    }

    private Long currentUserId() {
        Long userId = UserContext.get();
        if (userId == null) {
            throw new BizException(401, "未登录，请先登录后台");
        }
        return userId;
    }
}
