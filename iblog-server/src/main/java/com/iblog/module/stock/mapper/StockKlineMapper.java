package com.iblog.module.stock.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.iblog.module.stock.entity.StockKline;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface StockKlineMapper extends BaseMapper<StockKline> {
}
