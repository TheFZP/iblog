package com.iblog.module.paper.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.iblog.module.paper.entity.PaperPosition;
import com.iblog.module.paper.mapper.PaperPositionMapper;
import com.iblog.module.stock.ai.AiNewsScoreService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 新闻情绪扫描任务。
 *
 * <p>盘前 9:20 必须扫一次：隔夜公告与早间新闻正是 9:25 盘前预选最需要的输入。
 * 交易时段内约 30 分钟一轮（东财搜索单只 0.3~1s，并发 4 下 20~50 只约 30~90s）。</p>
 *
 * <p>扫描范围 = 热榜前 N + 所有账户的持仓。持仓加进来是为了 Phase 2 的利空预警：
 * 已经买在手里的票可能早就跌出热榜，不额外扫一遍就永远看不到它的新闻。</p>
 *
 * <p>为什么要 {@link ApplicationReadyEvent} 补扫：每天 9:20 的 cron 可能因运维重启错过，
 * 若此时已开盘但新闻缓存为空，重启后首轮选股就没有新闻分。启动补扫能兜住这一情况——
 * {@code scan()} 内部有交易时段与交易日判断，非交易时段启动不会打上游。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NewsScanScheduler {

    private final PaperStrategyService strategyService;
    private final PaperPositionMapper positionMapper;
    private final AiNewsScoreService scoreService;

    @Scheduled(cron = "0 20,50 9,10,11,13,14 * * *", zone = "Asia/Shanghai")
    public void scan() {
        if (!strategyService.anyNewsEnabled()) {
            return;
        }
        scoreService.scan(heldStocks());
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        scan();
    }

    /** 所有账户当前持仓（code → name），去重后交给扫描任务。 */
    private Map<String, String> heldStocks() {
        Map<String, String> held = new LinkedHashMap<>();
        try {
            List<PaperPosition> positions = positionMapper.selectList(
                    Wrappers.<PaperPosition>lambdaQuery().gt(PaperPosition::getQuantity, 0));
            for (PaperPosition position : positions) {
                if (position.getCode() == null || position.getCode().isBlank()) {
                    continue;
                }
                held.putIfAbsent(position.getCode(), position.getName());
            }
        } catch (RuntimeException ex) {
            log.debug("读取持仓失败，本轮新闻扫描只覆盖热榜 err={}", ex.toString());
        }
        return held;
    }
}