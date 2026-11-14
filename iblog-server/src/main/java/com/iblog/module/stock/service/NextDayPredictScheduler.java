package com.iblog.module.stock.service;

import com.iblog.config.IblogProperties;
import com.iblog.module.paper.support.TradeCalendar;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 次日预测定时任务：交易日 15:30 扫描全市场。
 *
 * <p>为什么是 15:30：收盘后行情定型、当日 K 线可修正，且避开 15:05 的模拟盘结算。
 * 任务放在 {@code predictTaskScheduler} 单线程池上，与默认调度池隔离。</p>
 *
 * <p>启动补偿：15:30 之后重启、且当天还没有榜单时补跑一次；否则当天的预测会永久缺失
 * （cron 已经错过了）。</p>
 */
@Slf4j
@Component
public class NextDayPredictScheduler {

    private static final LocalTime RUN_AFTER = LocalTime.of(15, 30);

    private final NextDayPredictService predictService;
    private final IblogProperties properties;
    private final TradeCalendar calendar;
    private final ThreadPoolTaskScheduler scheduler;

    public NextDayPredictScheduler(NextDayPredictService predictService,
                                   IblogProperties properties,
                                   TradeCalendar calendar,
                                   @Qualifier("predictTaskScheduler") ThreadPoolTaskScheduler scheduler) {
        this.predictService = predictService;
        this.properties = properties;
        this.calendar = calendar;
        this.scheduler = scheduler;
    }

    @Scheduled(cron = "0 30 15 * * ?", zone = "Asia/Shanghai", scheduler = "predictTaskScheduler")
    public void daily() {
        if (!properties.getPredict().isEnabled()) {
            return;
        }
        LocalDate today = calendar.today();
        if (!calendar.isTradingDay(today)) {
            return;
        }
        log.info("次日预测：15:30 定时任务触发");
        predictService.runDaily();
    }

    /** 应用启动后补跑当天漏掉的预测（只补生成，不重复已有数据）。 */
    @EventListener(ApplicationReadyEvent.class)
    public void catchUp() {
        if (!properties.getPredict().isEnabled()) {
            return;
        }
        LocalDate today = calendar.today();
        if (!calendar.isTradingDay(today)
                || calendar.now().toLocalTime().isBefore(RUN_AFTER)
                || predictService.hasData(today)) {
            return;
        }
        log.info("次日预测：检测到今天 {} 尚无榜单，启动补偿任务", today);
        scheduler.execute(() -> {
            try {
                predictService.runDaily();
            } catch (RuntimeException ex) {
                log.warn("次日预测补偿任务失败 err={}", ex.toString());
            }
        });
    }
}
