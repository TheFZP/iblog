package com.iblog.module.stock.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.iblog.module.stock.entity.StockSetting;
import org.apache.ibatis.annotations.Mapper;

/**
 * 行情模块配置 Mapper。
 */
@Mapper
public interface StockSettingMapper extends BaseMapper<StockSetting> {
}
