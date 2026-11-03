package com.iblog.module.stock.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.iblog.module.stock.entity.StockAiChatSession;
import org.apache.ibatis.annotations.Mapper;

/**
 * AI 对话会话 Mapper。
 */
@Mapper
public interface StockAiChatSessionMapper extends BaseMapper<StockAiChatSession> {
}
