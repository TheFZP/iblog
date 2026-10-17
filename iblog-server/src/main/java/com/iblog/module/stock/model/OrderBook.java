package com.iblog.module.stock.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 五档盘口。
 *
 * <p>A 股行情接口本身已含买卖五档（腾讯字段 [9..28]），无需额外请求；
 * 港股该段恒为空，此时 {@link #available} 为 false。</p>
 */
@Data
@Schema(description = "五档盘口")
public class OrderBook {

    @Schema(description = "买卖五档是否可用（港股通常不可用）")
    private boolean available;

    @Schema(description = "卖五到卖一（价格由高到低）")
    private List<Level> asks;

    @Schema(description = "买一到买五（价格由高到低）")
    private List<Level> bids;

    /** 单档：价格 + 委托量（手） */
    @Data
    @Schema(description = "盘口档位")
    public static class Level {
        @Schema(description = "价格")
        private double price;
        @Schema(description = "委托量（手）")
        private double volume;

        public Level() {
        }

        public Level(double price, double volume) {
            this.price = price;
            this.volume = volume;
        }
    }

    /** 构造不可用状态，避免前端判空 */
    public static OrderBook unavailable() {
        OrderBook b = new OrderBook();
        b.setAvailable(false);
        b.setAsks(List.of());
        b.setBids(List.of());
        return b;
    }
}
