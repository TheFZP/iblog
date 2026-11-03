package com.iblog.module.stock.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 对话会话：一个用户在一只股票上只有一条。
 *
 * <p>全局 AI 助手不绑定个股，用空串 {@code stockCode} 表示，与真实股票共用一张表 ——
 * 两者除了 code 之外没有任何行为差异，拆表只会让查询多一个分支。</p>
 *
 * <p>不加 {@code @TableLogic}：用户点「清空」就是真删，逻辑删除的残行会挡住
 * {@code uk_user_stock} 唯一键重建同一会话。</p>
 */
@Data
@TableName("stock_ai_chat_session")
public class StockAiChatSession {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** 归一化股票代码；空串表示全局对话 */
    private String stockCode;

    /** 股票名称快照，最后问过的名称会覆盖旧值 */
    private String stockName;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
