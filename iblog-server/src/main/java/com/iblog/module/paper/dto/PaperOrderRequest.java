package com.iblog.module.paper.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 手动下单请求。
 *
 * <p>价格只对限价单生效；市价单由服务端按最新价加滑点成交。</p>
 */
@Data
public class PaperOrderRequest {

    @NotBlank(message = "股票代码不能为空")
    private String code;

    /** BUY / SELL */
    @NotBlank(message = "买卖方向不能为空")
    private String side;

    /** MARKET / LIMIT，默认市价 */
    private String type = "MARKET";

    /** 限价单价格（元） */
    private BigDecimal price;

    @NotNull(message = "委托数量不能为空")
    @Min(value = 1, message = "委托数量必须大于 0")
    private Integer quantity;
}
