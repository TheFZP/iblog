package com.iblog.module.paper.service;

import com.iblog.module.stock.ai.AiNewsScoreService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 新闻情绪扫描任务。
 *
 * <p>盘前 9:20 必须扫一次：隔夜公告与早间新闻正是 9:25 盘前预选最需要的输入。
 * 交易时段内约 30 分钟一轮（东财搜索单只 0.3~1s，并发 4 下 20~30 只约 30~90s）。</p>
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
    private final AiNewsScoreService scoreService;

    @Scheduled(cron = "0 20,50 9,10,11,13,14 * * *", zone = "Asia/Shanghai")
    public void scan() {
        if (!strategyService.anyNewsEnabled()) {
            return;
        }
        scoreService.scan();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        scan();
    }
}