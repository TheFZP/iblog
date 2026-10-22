package com.iblog.module.paper.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 模拟盘账户：一个后台用户一个账户。
 */
@Data
@TableName("paper_account")
public class PaperAccount {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 后台用户 id（uk_user 唯一） */
    private Long userId;

    private String name;

    /** 展示排序（主账户=1） */
    private Integer sortNo;

    /** 初始资金 */
    private BigDecimal initialCash;

    /** 可用现金 */
    private BigDecimal cash;

    /** 冻结资金（预留给限价单，当前恒为 0） */
    private BigDecimal frozenCash;

    /** 持仓市值 */
    private BigDecimal marketValue;

    /** 总资产 = 现金 + 市值 */
    private BigDecimal totalAsset;

    /** 累计盈亏 = 总资产 - 初始资金 */
    private BigDecimal totalProfit;

    /** 累计收益率（%） */
    private BigDecimal totalProfitPct;

    /** 自动交易开关：0 关 1 开 */
    private Integer autoTrade;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
