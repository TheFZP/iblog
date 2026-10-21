package com.iblog.module.paper.dto;

import lombok.Data;

/**
 * 更新策略开关或参数（params 是 JSON 文本，原样保存）。
 */
@Data
public class PaperStrategyUpdateRequest {

    private Boolean enabled;

    /** 形如 {"minScore":60,"stopLossPct":8,"takeProfitPct":15,"maxHoldDays":10} */
    private String params;
}
