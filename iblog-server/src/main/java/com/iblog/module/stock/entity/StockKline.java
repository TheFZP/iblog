package com.iblog.module.stock.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 日 K 线缓存（对应 stock_kline 表）。
 *
 * <p>04 迁移脚本里已经建过这张表，但一直没有实体 —— 模拟盘的选股/回测
 * 需要读 60+ 根日 K，才补上实体与 Mapper 真正用起来。</p>
 */
@Data
@TableName("stock_kline")
public class StockKline {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 归一化代码 */
    private String code;

    /** 周期，目前只用 day */
    private String period;

    /** 交易日：day 用 yyyy-MM-dd */
    private String date;

    private BigDecimal open;

    private BigDecimal high;

    private BigDecimal low;

    private BigDecimal close;

    /** 成交量（手） */
    private BigDecimal volume;

    /** 成交额（万元） */
    private BigDecimal turnover;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
