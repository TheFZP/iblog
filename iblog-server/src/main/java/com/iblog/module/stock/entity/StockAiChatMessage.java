package com.iblog.module.stock.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 对话消息。
 *
 * <p>{@code tools} / {@code picks} 存 JSON 文本：它们只用于刷新后还原界面，
 * 不参与检索或关联，拆成子表反而多两次查询。</p>
 */
@Data
@TableName("stock_ai_chat_message")
public class StockAiChatMessage {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long sessionId;

    /** user / assistant */
    private String role;

    private String content;

    /** 思考过程，仅 assistant 有 */
    private String reasoning;

    /** 工具轨迹 JSON：[{"name":"...","args":{...}}] */
    private String tools;

    /** 选股卡片 JSON，形状与前端 AiPick 一致 */
    private String picks;

    private LocalDateTime createTime;
}
