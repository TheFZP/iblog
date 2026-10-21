package com.iblog.module.paper.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 策略参数（存在 paper_strategy.params 的 JSON 文本）。
 * 解析失败或字段缺失都回落到默认值，避免一条脏配置把自动交易整个停掉。
 */
public record PaperStrategyParams(double minScore, double stopLossPct, double takeProfitPct, int maxHoldDays) {

    public static PaperStrategyParams parse(ObjectMapper mapper, String json) {
        double minScore = 60;
        double stopLossPct = 8;
        double takeProfitPct = 15;
        int maxHoldDays = 10;
        if (json != null && !json.isBlank()) {
            try {
                JsonNode node = mapper.readTree(json);
                minScore = node.path("minScore").asDouble(minScore);
                stopLossPct = node.path("stopLossPct").asDouble(stopLossPct);
                takeProfitPct = node.path("takeProfitPct").asDouble(takeProfitPct);
                maxHoldDays = node.path("maxHoldDays").asInt(maxHoldDays);
            } catch (Exception ignored) {
                // 用默认值
            }
        }
        return new PaperStrategyParams(minScore, stopLossPct, takeProfitPct, maxHoldDays);
    }
}
