package com.iblog.module.stock.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.iblog.module.stock.entity.StockNewsArticle;
import org.apache.ibatis.annotations.Mapper;

/**
 * 预测用新闻原文存档 Mapper。
 */
@Mapper
public interface StockNewsArticleMapper extends BaseMapper<StockNewsArticle> {
}
