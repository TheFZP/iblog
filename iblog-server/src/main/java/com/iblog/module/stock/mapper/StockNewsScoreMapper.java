package com.iblog.module.stock.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.iblog.module.stock.entity.StockNewsScore;
import org.apache.ibatis.annotations.Mapper;

/**
 * 个股新闻情绪分 Mapper。
 */
@Mapper
public interface StockNewsScoreMapper extends BaseMapper<StockNewsScore> {
}