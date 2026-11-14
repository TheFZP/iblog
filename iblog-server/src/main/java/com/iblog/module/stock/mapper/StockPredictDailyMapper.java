package com.iblog.module.stock.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.iblog.module.stock.entity.StockPredictDaily;
import org.apache.ibatis.annotations.Mapper;

/**
 * 次日看涨预测榜单 Mapper。
 */
@Mapper
public interface StockPredictDailyMapper extends BaseMapper<StockPredictDaily> {
}
