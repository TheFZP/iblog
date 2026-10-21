package com.iblog.module.paper.vo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 模拟盘委托。
 */
@Data
@Builder
public class PaperOrderVO {

    private Long id;

    private String code;

    private String name;

    /** BUY / SELL */
    private String side;

    /** MARKET / LIMIT */
    private String type;

    /** 限价单委托价；市价单为 null */
    private BigDecimal price;

    private Integer quantity;

    private Integer filledQuantity;

    /** 成交均价 */
    private BigDecimal avgPrice;

    /** 成交金额（不含费用） */
    private BigDecimal amount;

    /** PENDING / FILLED / CANCELLED / REJECTED */
    private String status;

    private String rejectReason;

    /** MANUAL / STRATEGY */
    private String source;

    private LocalDateTime createTime;

    private LocalDateTime filledTime;
}
