package com.iblog.module.stock.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.iblog.module.stock.entity.StockAiChatMessage;
import org.apache.ibatis.annotations.Mapper;

/**
 * AI 对话消息 Mapper。
 */
@Mapper
public interface StockAiChatMessageMapper extends BaseMapper<StockAiChatMessage> {
}
