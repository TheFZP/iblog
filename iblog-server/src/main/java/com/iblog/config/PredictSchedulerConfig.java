package com.iblog.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 次日预测专用调度器。
 *
 * <p>不能挂在默认调度池上：全市场扫描要批量拉行情、补 300 只 K 线、调 LLM，
 * 可能持续几分钟；默认池只有 4 个线程，还要跑模拟盘 15 秒一次的 tick 和每日结算。
 * 一个慢任务就可能把自动交易挤到下一轮。</p>
 */
@Configuration
public class PredictSchedulerConfig {

    @Bean("predictTaskScheduler")
    public ThreadPoolTaskScheduler predictTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("next-day-predict-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        return scheduler;
    }
}
